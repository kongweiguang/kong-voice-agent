/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.kongweiguang.voice.agent.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 应用侧 Agent 输出事件，OllamaLlmOrchestrator 会把它转换成核心流水线的 LlmChunk。
 *
 * @author kongweiguang
 */
@Data
public class ChatEvent {

    /**
     * 事件类型：TEXT、TOOL_USE、TOOL_RESULT、TOOL_CONFIRM、ERROR、COMPLETE。
     */
    private String type;

    /**
     * TEXT 事件携带的增量文本。
     */
    private String content;

    /**
     * TOOL_USE 或 TOOL_RESULT 事件携带的工具名称。
     */
    private String toolName;

    /**
     * TOOL_USE 或 TOOL_RESULT 事件携带的工具调用 ID。
     */
    private String toolId;

    /**
     * TOOL_USE 事件携带的工具入参。
     */
    private Map<String, Object> toolInput;

    /**
     * TOOL_RESULT 事件携带的工具结果。
     */
    private String toolResult;

    /**
     * TOOL_CONFIRM 事件携带的待确认工具调用列表。
     */
    private List<PendingToolCall> pendingToolCalls;

    /**
     * ERROR 事件携带的错误信息。
     */
    private String error;

    /**
     * 底层 AgentScope 或模型供应商返回的原始响应内容。
     */
    private String rawResponse;

    /**
     * 是否为增量文本内容。
     */
    private Boolean incremental;

    /**
     * 创建一段可进入 LLM/TTS 下游的文本事件。
     */
    public static ChatEvent text(String content, Boolean incremental) {
        return text(content, incremental, content);
    }

    /**
     * 创建一段可进入 LLM/TTS 下游的文本事件，并保留底层原始响应。
     */
    public static ChatEvent text(String content, Boolean incremental, String rawResponse) {
        ChatEvent event = new ChatEvent();
        event.type = "TEXT";
        event.content = content;
        event.incremental = incremental;
        event.rawResponse = rawResponse;
        return event;
    }

    /**
     * 创建工具调用事件。
     */
    public static ChatEvent toolUse(String toolId, String toolName, Map<String, Object> input) {
        ChatEvent event = new ChatEvent();
        event.type = "TOOL_USE";
        event.toolId = toolId;
        event.toolName = toolName;
        event.toolInput = input;
        return event;
    }

    /**
     * 创建工具结果事件。
     */
    public static ChatEvent toolResult(String toolId, String toolName, String result) {
        ChatEvent event = new ChatEvent();
        event.type = "TOOL_RESULT";
        event.toolId = toolId;
        event.toolName = toolName;
        event.toolResult = result;
        return event;
    }

    /**
     * 创建等待用户确认工具调用的事件。
     */
    public static ChatEvent toolConfirm(List<PendingToolCall> pendingToolCalls) {
        ChatEvent event = new ChatEvent();
        event.type = "TOOL_CONFIRM";
        event.pendingToolCalls = pendingToolCalls;
        return event;
    }

    /**
     * 创建模型或 Agent 执行失败事件。
     */
    public static ChatEvent error(String error) {
        ChatEvent event = new ChatEvent();
        event.type = "ERROR";
        event.error = error;
        event.rawResponse = error;
        return event;
    }

    /**
     * 创建本轮模型输出结束事件。
     */
    public static ChatEvent complete() {
        ChatEvent event = new ChatEvent();
        event.type = "COMPLETE";
        return event;
    }

    /**
     * 创建应用侧中断事件。
     */
    public static ChatEvent interrupted(String message) {
        ChatEvent event = new ChatEvent();
        event.type = "INTERRUPTED";
        event.content = message;
        return event;
    }

    /**
     * 等待用户确认的工具调用信息。
     *
     * @author kongweiguang
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PendingToolCall {
        /**
         * 工具调用 ID。
         */
        private String id;

        /**
         * 工具名称。
         */
        private String name;

        /**
         * 工具入参。
         */
        private Map<String, Object> input;

        /**
         * 是否为高风险工具调用，需要用户显式确认。
         */
        private Boolean dangerous;

    }
}
