package io.github.kongweiguang.voice.agent.ws;

import io.github.kongweiguang.voice.agent.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 WebSocket 入站消息模型在协议边界集中完成字段校验。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("WebSocket 入站消息")
class WsMessageTest {
    @Test
    @DisplayName("从 JsonNode 读取文本 payload")
    void readsTextPayloadFromJsonNode() {
        WsMessage message = JsonUtils.read("""
                {"type":"text","payload":{"text":"你好"}}
                """, WsMessage.class);

        assertThat(message.type()).isEqualTo(WsMessageType.text.name());
        assertThat(message.textPayload()).isEqualTo("你好");
    }

    @Test
    @DisplayName("未知 type 保留为字符串")
    void keepsUnknownTypeAsString() {
        WsMessage message = JsonUtils.read("""
                {"type":"custom_event","payload":{"value":1}}
                """, WsMessage.class);

        assertThat(message.type()).isEqualTo("custom_event");
        assertThat(message.payload().get("value").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒绝非字符串 text 字段")
    void rejectsNonTextPayload() {
        WsMessage message = JsonUtils.read("""
                {"type":"text","payload":{"text":123}}
                """, WsMessage.class);

        assertThatThrownBy(message::textPayload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.text is required");
    }

    @Test
    @DisplayName("拒绝缺少 text 字段")
    void rejectsMissingTextPayload() {
        WsMessage message = JsonUtils.read("""
                {"type":"text","payload":{}}
                """, WsMessage.class);

        assertThatThrownBy(message::textPayload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.text is required");
    }

    @Test
    @DisplayName("拒绝缺少 payload")
    void rejectsMissingPayload() {
        WsMessage message = JsonUtils.read("""
                {"type":"text"}
                """, WsMessage.class);

        assertThatThrownBy(message::textPayload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload.text is required");
    }
}
