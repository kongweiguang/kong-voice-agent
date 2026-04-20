package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 上行文本消息策略注册表，根据 type 将消息分发给对应策略。
 *
 * <p>注册表在启动阶段 fail fast，避免重复 type 或覆盖内置 type 后导致
 * 协议行为不确定。</p>
 *
 * @author kongweiguang
 */
@Component
public class WsTextMessageHandlerRegistry {
    /**
     * type 到处理策略的不可变映射。
     */
    private final Map<String, WsTextMessageHandler> handlers;

    /**
     * 收集 Spring 容器中的所有上行消息处理策略。
     */
    public WsTextMessageHandlerRegistry(Collection<WsTextMessageHandler> handlers) {
        Map<String, WsTextMessageHandler> indexedHandlers = new HashMap<>();
        for (WsTextMessageHandler handler : handlers) {
            String type = normalizeType(handler.type());
            WsTextMessageHandler previous = indexedHandlers.putIfAbsent(type, handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate ws message type: " + type);
            }
        }
        this.handlers = Map.copyOf(indexedHandlers);
    }

    /**
     * 根据消息 type 查找策略并执行。未知 type 会作为协议错误抛出。
     */
    public void handle(WsTextMessageContext context) {
        String type = normalizeType(context.message().type());
        WsTextMessageHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("unsupported type: " + type);
        }
        handler.handle(context);
    }

    /**
     * 统一校验 type，避免空白 type 进入注册表或运行期分发。
     */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        return type.trim();
    }
}
