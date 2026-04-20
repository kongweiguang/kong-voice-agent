package io.github.kongweiguang.voice.agent.ws.handler;

import io.github.kongweiguang.voice.agent.ws.WsTextMessageContext;

/**
 * WebSocket JSON 文本消息处理策略。业务方可以声明该接口的 Spring Bean
 * 新增自定义 type，但不能覆盖内置协议 type。
 *
 * @author kongweiguang
 */
public interface WsTextMessageHandler {
    /**
     * 当前策略负责处理的上行消息 type。
     */
    String type();

    /**
     * 处理已解析的上行文本消息。
     */
    void handle(WsTextMessageContext context);
}
