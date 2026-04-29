import { AgentEventEnvelope, sendWsJson } from "@/api/agentClient";

/** WebRTC 传输模式的会话创建响应。 */
export interface RtcSessionResponse {
  /** 当前控制面 WebSocket 绑定的后端 sessionId。 */
  sessionId: string;
  /** 浏览器建链使用的 ICE server 列表。 */
  iceServers: Array<{ urls: string[] }>;
}

/** 浏览器提交给后端的 SDP。 */
export interface RtcSessionDescriptionPayload {
  /** SDP 类型，当前常见值为 OFFER 或 ANSWER。 */
  type: string;
  /** SDP 文本。 */
  sdp: string;
}

/** 服务端返回给浏览器的 SDP answer。 */
export interface RtcAnswerResponse {
  /** SDP 类型，服务端当前返回 ANSWER。 */
  type: string;
  /** SDP 文本。 */
  sdp: string;
}

/** 浏览器 trickle ICE candidate 请求体。 */
export interface RtcIceCandidatePayload {
  /** candidate 对应的媒体段标识。 */
  sdpMid: string | null;
  /** candidate 对应的 m-line 下标。 */
  sdpMLineIndex: number | null;
  /** candidate SDP 字符串。 */
  candidate: string;
}

/** 通过 WebSocket signaling 为当前会话启动 RTC。 */
export async function createRtcSession(socket: WebSocket): Promise<RtcSessionResponse> {
  return awaitRtcEvent<RtcSessionResponse>(socket, "rtc_session_ready", () => {
    sendWsJson(socket, "rtc_start");
  }, "创建 RTC 会话失败");
}

/** 向后端提交 offer，并等待 `rtc_answer` 事件。 */
export async function submitRtcOffer(
  socket: WebSocket,
  sessionId: string,
  offer: RtcSessionDescriptionPayload,
): Promise<RtcAnswerResponse> {
  return awaitRtcEvent<RtcAnswerResponse>(socket, "rtc_answer", () => {
    sendWsJson(socket, "rtc_offer", {
      sessionId,
      type: offer.type,
      sdp: offer.sdp,
    });
  }, "提交 RTC offer 失败", sessionId);
}

/** 向后端提交浏览器 trickle ICE candidate。 */
export function submitRtcCandidate(socket: WebSocket, sessionId: string, candidate: RtcIceCandidatePayload) {
  sendWsJson(socket, "rtc_ice_candidate", {
    sessionId,
    sdpMid: candidate.sdpMid,
    sdpMLineIndex: candidate.sdpMLineIndex,
    candidate: candidate.candidate,
  });
}

/** 关闭 RTC 会话。 */
export function closeRtcSession(socket: WebSocket, sessionId: string) {
  sendWsJson(socket, "rtc_close", { sessionId });
}

/** 等待某个 RTC signaling 事件返回。 */
function awaitRtcEvent<T>(
  socket: WebSocket,
  eventType: string,
  trigger: () => void,
  fallbackMessage: string,
  sessionId?: string,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      cleanup();
      reject(new Error(`${fallbackMessage}：等待 WebSocket signaling 超时`));
    }, 15_000);

    const onMessage = (messageEvent: MessageEvent) => {
      if (typeof messageEvent.data !== "string") {
        return;
      }
      try {
        const event = JSON.parse(messageEvent.data) as AgentEventEnvelope;
        if (event.type === "error") {
          cleanup();
          reject(new Error(readPayloadText(event, "message") || fallbackMessage));
          return;
        }
        if (event.type !== eventType) {
          return;
        }
        if (sessionId && event.sessionId && event.sessionId !== sessionId) {
          return;
        }
        cleanup();
        resolve((event.payload ?? {}) as T);
      } catch {
        // 交给页面侧通用消息处理即可，这里只等待匹配的 signaling 事件。
      }
    };

    const onCloseOrError = () => {
      cleanup();
      reject(new Error(`${fallbackMessage}：WebSocket 已断开`));
    };

    const cleanup = () => {
      window.clearTimeout(timeout);
      socket.removeEventListener("message", onMessage);
      socket.removeEventListener("close", onCloseOrError);
      socket.removeEventListener("error", onCloseOrError);
    };

    socket.addEventListener("message", onMessage);
    socket.addEventListener("close", onCloseOrError);
    socket.addEventListener("error", onCloseOrError);
    trigger();
  });
}

/** 读取 RTC signaling 错误消息。 */
function readPayloadText(event: AgentEventEnvelope, fieldName: string) {
  const value = event.payload?.[fieldName];
  return typeof value === "string" ? value : null;
}
