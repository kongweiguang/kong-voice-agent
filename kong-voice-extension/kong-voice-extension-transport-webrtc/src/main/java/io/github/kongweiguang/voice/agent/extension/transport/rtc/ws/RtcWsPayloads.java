package io.github.kongweiguang.voice.agent.extension.transport.rtc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.voice.agent.ws.WsMessage;

/**
 * 集中解析 WebRTC WebSocket signaling 的 payload，避免字段读取散落在各个 handler 中。
 *
 * @author kongweiguang
 */
public final class RtcWsPayloads {
    private RtcWsPayloads() {
    }

    /**
     * 读取 `rtc_offer` 的请求体。
     */
    public static RtcOfferRequest offer(WsMessage message, String currentSessionId) {
        JsonNode payload = requiredPayload(message);
        return new RtcOfferRequest(
                resolveSessionId(payload, currentSessionId),
                requiredText(payload, "type"),
                requiredText(payload, "sdp")
        );
    }

    /**
     * 读取 `rtc_ice_candidate` 的请求体。
     */
    public static RtcIceCandidateRequest iceCandidate(WsMessage message, String currentSessionId) {
        JsonNode payload = requiredPayload(message);
        return new RtcIceCandidateRequest(
                resolveSessionId(payload, currentSessionId),
                optionalText(payload, "sdpMid"),
                optionalInteger(payload, "sdpMLineIndex"),
                requiredText(payload, "candidate")
        );
    }

    /**
     * 读取 `rtc_close` 的请求体；允许省略 sessionId，默认关闭当前控制面会话绑定的 RTC。
     */
    public static RtcCloseRequest close(WsMessage message, String currentSessionId) {
        JsonNode payload = message.payload();
        return new RtcCloseRequest(resolveSessionId(payload, currentSessionId));
    }

    /**
     * 归一化并校验 signaling 里的 sessionId。
     */
    private static String resolveSessionId(JsonNode payload, String currentSessionId) {
        String sessionId = optionalText(payload, "sessionId");
        String normalized = sessionId == null || sessionId.isBlank() ? currentSessionId : sessionId.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("payload.sessionId is required");
        }
        if (currentSessionId != null && !currentSessionId.isBlank() && !currentSessionId.equals(normalized)) {
            throw new IllegalArgumentException("payload.sessionId must match current control session");
        }
        return normalized;
    }

    /**
     * 读取必填字符串字段。
     */
    private static String requiredText(JsonNode payload, String fieldName) {
        JsonNode field = payload == null ? null : payload.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalArgumentException("payload." + fieldName + " is required");
        }
        return field.asText().trim();
    }

    /**
     * 读取可选字符串字段。
     */
    private static String optionalText(JsonNode payload, String fieldName) {
        JsonNode field = payload == null ? null : payload.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        if (!field.isTextual()) {
            throw new IllegalArgumentException("payload." + fieldName + " must be a string");
        }
        return field.asText();
    }

    /**
     * 读取可选整数。
     */
    private static Integer optionalInteger(JsonNode payload, String fieldName) {
        JsonNode field = payload == null ? null : payload.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        if (!field.canConvertToInt()) {
            throw new IllegalArgumentException("payload." + fieldName + " must be an integer");
        }
        return field.intValue();
    }

    /**
     * signaling 请求要求显式 payload。
     */
    private static JsonNode requiredPayload(WsMessage message) {
        if (message.payload() == null || message.payload().isNull()) {
            throw new IllegalArgumentException("payload is required");
        }
        return message.payload();
    }

    /**
     * `rtc_offer` 请求。
     *
     * @param sessionId 当前要协商的业务会话 id
     * @param type      SDP 类型
     * @param sdp       SDP 文本
     * @author kongweiguang
     */
    public record RtcOfferRequest(String sessionId,
                                  String type,
                                  String sdp) {
    }

    /**
     * `rtc_ice_candidate` 请求。
     *
     * @param sessionId      当前要补交 candidate 的业务会话 id
     * @param sdpMid         candidate 对应媒体段
     * @param sdpMLineIndex  candidate 对应 m-line 下标
     * @param candidate      candidate SDP 文本
     * @author kongweiguang
     */
    public record RtcIceCandidateRequest(String sessionId,
                                         String sdpMid,
                                         Integer sdpMLineIndex,
                                         String candidate) {
    }

    /**
     * `rtc_close` 请求。
     *
     * @param sessionId 要关闭的 RTC 会话 id
     * @author kongweiguang
     */
    public record RtcCloseRequest(String sessionId) {
    }
}
