package io.github.kongweiguang.voice.agent.model.payload;

import io.github.kongweiguang.voice.agent.model.AgentEvent;

/**
 * 下行事件 payload 的统一规范接口，避免事件构造处继续使用松散 Map。
 * 事件外壳的必填字段由 {@link AgentEvent} 统一定义；业务方可以自行实现该接口扩展 payload 字段。
 *
 * @author kongweiguang
 */
public interface AgentEventPayload {
}
