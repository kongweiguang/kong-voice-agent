package io.github.kongweiguang.voice.agent.ws;

import io.github.kongweiguang.voice.agent.support.TestSessionStates;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandler;
import io.github.kongweiguang.voice.agent.ws.handler.WsTextMessageHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 WebSocket 文本消息策略注册和分发规则。
 *
 * @author kongweiguang
 */
@Tag("protocol")
@DisplayName("WebSocket 文本消息策略注册表")
class WsTextMessageHandlerRegistryTest {
    @Test
    @DisplayName("按 type 命中已注册策略")
    void dispatchesByType() {
        RecordingHandler handler = new RecordingHandler(WsMessageType.ping.name());
        WsTextMessageHandlerRegistry registry = new WsTextMessageHandlerRegistry(List.of(handler));
        WsTextMessageContext context = new WsTextMessageContext(null, TestSessionStates.create("s1"),
                new WsMessage(WsMessageType.ping.name(), null));

        registry.handle(context);

        assertThat(handler.handledTypes).containsExactly(WsMessageType.ping.name());
    }

    @Test
    @DisplayName("未知 type 抛出明确协议错误")
    void rejectsUnknownType() {
        WsTextMessageHandlerRegistry registry = new WsTextMessageHandlerRegistry(List.of());
        WsTextMessageContext context = new WsTextMessageContext(null, TestSessionStates.create("s1"),
                new WsMessage("custom_event", null));

        assertThatThrownBy(() -> registry.handle(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported type: custom_event");
    }

    @Test
    @DisplayName("重复 type 在注册阶段失败")
    void rejectsDuplicateType() {
        WsTextMessageHandler first = new RecordingHandler("dup");
        WsTextMessageHandler second = new RecordingHandler("dup");

        assertThatThrownBy(() -> new WsTextMessageHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate ws message type: dup");
    }

    @Test
    @DisplayName("自定义新增 type 可以注册并处理")
    void allowsCustomType() {
        RecordingHandler customHandler = new RecordingHandler("custom_event");
        WsTextMessageHandlerRegistry registry = new WsTextMessageHandlerRegistry(List.of(customHandler));
        WsTextMessageContext context = new WsTextMessageContext(null, TestSessionStates.create("s1"),
                new WsMessage("custom_event", null));

        registry.handle(context);

        assertThat(customHandler.handledTypes).containsExactly("custom_event");
    }

    @Test
    @DisplayName("业务策略不能覆盖内置 type")
    void rejectsOverridingBuiltInType() {
        WsTextMessageHandler builtIn = new RecordingHandler(WsMessageType.text.name());
        WsTextMessageHandler customOverride = new RecordingHandler(WsMessageType.text.name());

        assertThatThrownBy(() -> new WsTextMessageHandlerRegistry(List.of(builtIn, customOverride)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate ws message type: text");
    }

    /**
     * 记录被分发的 type，用于验证注册表选择了正确策略。
     */
    private static final class RecordingHandler implements WsTextMessageHandler {
        private final String type;
        private final List<String> handledTypes = new ArrayList<>();

        private RecordingHandler(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void handle(WsTextMessageContext context) {
            handledTypes.add(context.message().type());
        }
    }
}
