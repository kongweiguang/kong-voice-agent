package io.github.kongweiguang.voice.agent.app.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.github.kongweiguang.v1.json.Json;
import io.github.kongweiguang.voice.agent.app.dto.ChatEvent;
import io.github.kongweiguang.voice.agent.app.util.MsgUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理应用侧 Agent 和对话会话。
 *
 * <p>这里使用 AgentScope 的 InMemorySession 保存 Agent 状态。每次请求创建新的 Agent 实例，
 * 再从 session 恢复历史；上层 voice pipeline 通过 turnId 控制旧输出失效。</p>
 *
 * @author kongweiguang
 */
@Service
public class AgentService {
    /**
     * OpenAI 兼容模型 API Key。
     */
    @Value("${ai.model.api-key:xxx}")
    private String apiKey;

    /**
     * OpenAI 兼容模型服务地址。
     */
    @Value("${ai.model.base-url:http://124.74.245.74:34033/v1}")
    private String baseUrl;

    /**
     * OpenAI 兼容模型名称。
     */
    @Value("${ai.model.model-name:Qwen3-Omni-30B-A3B-Instruct}")
    private String modelName;

    /**
     * AgentScope 会话存储，用于在同一个 voice session 内保留对话记忆。
     */
    private final Session session = new InMemorySession();

    /**
     * 正在运行的 Agent 缓存，key 为 sessionId，请求结束后清理。
     */
    private final ConcurrentHashMap<String, ReActAgent> runningAgents = new ConcurrentHashMap<>();


    /**
     * 创建新的 Agent 实例，并按 voice sessionId 恢复历史状态。
     */
    private ReActAgent createAgent(String sessionId) {
        ReActAgent agent = ReActAgent.builder()
                .name("对话助手")
                .sysPrompt("""
                        # Role: Voice Assistant
                        你是一个亲切、自然且高效的语音助手。你的目标是通过语音与用户交流，提供像真人对话一样的流畅体验。
                        
                        ## Core Principles (核心输出准则)
                        1. **口语化表达**：使用自然的人类语言，多用语气词（如“嗯”、“哦”、“好的”），避免书面化的解释。
                        2. **短句优先**：尽量缩短句子长度，每一段话只表达一个核心观点。长句在 TTS 播报时会显得机械且难以听懂。
                        3. **禁用特殊符号**：严禁使用 Markdown 格式（如加粗 **、斜体 *、标题 #）、列表符号（如 1.、- ）或表情符号。这些符号会导致 TTS 停顿异常或读出怪异内容。
                        4. **数字文字化**：将数字和符号转化为纯文字。例如：将 "25°C" 转化为 "二十五摄氏度"，将 "3.5折" 转化为 "三点五折"。
                        5. **拒绝长列表**：如果有多个选项，不要一次性全部列出。先说最重要的两三个，然后询问用户是否需要听剩下的。
                        6. **主动引导**：回复结尾尽量抛出一个简洁的问题，引导用户继续对话。
                        
                        ## Constraints (禁忌事项)
                        - 绝对不要输出代码块、表格或复杂的结构化数据。
                        - 严禁使用视觉导向的词汇，如“如下所示”、“请参考下图”。
                        - 避免过度道歉，保持对话的简洁明快。
                        
                        ## Output Example (输出示例)
                        - 反例："根据您的定位，今日天气如下：1. 晴朗；2. 25-30度；3. 紫外线强。"
                        - 正例："今天天气挺不错的，晴空万里。气温大约在二十五到三十度之间。出门的话，记得涂点防晒霜哦。你打算今天出去走走吗？"
                        """)
                .model(getModel())
                .memory(new InMemoryMemory())
                .build();
        agent.loadIfExists(session, sessionId);
        return agent;
    }

    private OpenAIChatModel getModel() {
        // 使用 OpenAI 兼容模型接口，便于替换本地网关、Ollama 代理或云厂商兼容端点。
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .httpTransport(OkHttpTransport.builder().build())
                .formatter(new OpenAIChatFormatter())
                .generateOptions(
                        GenerateOptions.builder().additionalBodyParam("chat_template_kwargs", Map.of("enable_thinking", true)).additionalBodyParam("enable_thinking", false).build()
                )
                .build();
    }

    /**
     * 处理一条已提交的用户输入，并输出可被 LLM 编排器消费的事件流。
     */
    public Flux<ChatEvent> chat(String sessionId, String message) {
        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        // voice pipeline 只在 ASR final 或文本 committed 后调用这里，因此 message 已是本轮最终用户文本。
        Msg userMsg = Msg.builder()
                .name("User")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(message).build())
                .build();
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .includeReasoningResult(false)
                        .build();
        return agent.stream(userMsg, streamOptions)
                // AgentScope 的模型事件统一压成 TEXT 事件，后续由 OllamaLlmOrchestrator 转为 LlmChunk。
                .map(event -> ChatEvent.text(MsgUtils.getTextContent(event.getMessage()), true, rawResponse(event)))
                .concatWith(Flux.just(ChatEvent.complete()))
                .doFinally(signal -> {
                    // 流结束后保存 Agent 记忆，再移除运行态，避免长期占用模型上下文对象。
                    runningAgents.remove(sessionId);
                    agent.saveTo(session, sessionId);

                })
                // 下游统一接收 ERROR 和 COMPLETE，避免模型异常让响应流悬挂。
                .onErrorResume(error -> Flux.just(ChatEvent.error(error.getMessage()), ChatEvent.complete()));
    }

    /**
     * 尽量保留底层模型事件原貌；序列化失败时退回对象字符串，避免影响主回复链路。
     */
    private String rawResponse(Object event) {
        try {
            return Json.str(event);
        } catch (Exception ex) {
            return String.valueOf(event);
        }
    }

}
