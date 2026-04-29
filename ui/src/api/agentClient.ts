/** 登录成功后后端返回的用户身份信息。 */
export interface LoginUser {
  /** 当前账号标识，默认演示账号为 123456。 */
  accountId: string;
  /** 当前登录用户名。 */
  username: string;
}

/** 登录成功后前端需要保存的最小凭据。 */
export interface LoginResponse {
  /** WebSocket 握手 query 参数使用的进程内 token。 */
  token: string;
  /** token 类型，当前后端固定返回 Bearer。 */
  tokenType: string;
  /** 登录用户信息。 */
  user: LoginUser;
}

/** 后端下行事件统一外壳，payload 按 type 再细分。 */
export interface AgentEventEnvelope<T = Record<string, unknown>> {
  /** 事件类型，例如 agent_text_chunk、asr_final 或 error。 */
  type: string;
  /** 后端 session id，连接建立后由服务端生成。 */
  sessionId?: string;
  /** 当前 turn id；尚未创建用户 turn 时可能为空。 */
  turnId?: string | null;
  /** 服务端事件时间戳。 */
  timestamp?: string;
  /** 事件载荷，具体字段取决于事件类型。 */
  payload?: T;
}

/** 前端登录请求参数。 */
export interface LoginRequest {
  /** 登录用户名。 */
  username: string;
  /** 登录密码。 */
  password: string;
}

/** HTTP 基础地址，开发环境默认直连本地后端。 */
export const AGENT_HTTP_BASE = import.meta.env.VITE_AGENT_HTTP_BASE ?? "http://localhost:9877";

/** WebSocket 基础地址，开发环境默认直连本地后端。 */
export const AGENT_WS_BASE = import.meta.env.VITE_AGENT_WS_BASE ?? "ws://localhost:9877";

/** 使用固定账号换取后端进程内 token。 */
export async function loginAgent(request: LoginRequest): Promise<LoginResponse> {
  const response = await fetch(`${AGENT_HTTP_BASE}/api/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `登录失败：HTTP ${response.status}`);
  }

  return response.json() as Promise<LoginResponse>;
}

/** 根据 token 构造受鉴权保护的 Agent WebSocket 地址。 */
export function buildAgentWsUrl(token: string, sessionId?: string | null) {
  const query = new URLSearchParams({ token });
  if (sessionId) {
    query.set("sessionId", sessionId);
  }
  return `${AGENT_WS_BASE}/ws/agent?${query.toString()}`;
}

/** 发送统一外壳的 WebSocket JSON 消息。 */
export function sendWsJson(socket: WebSocket, type: string, payload: Record<string, unknown> = {}) {
  socket.send(JSON.stringify({ type, payload }));
}
