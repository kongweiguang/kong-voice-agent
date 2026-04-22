# Architecture

这份文档说明当前 Kong Voice Agent 框架的模块分层，以及一次文本或音频输入从客户端进入系统、经过后端流水线、再流回客户端的完整路径。

## 模块分层

```mermaid
flowchart TB
    UI["ui/<br/>React 前端联调界面<br/>登录、WebSocket、文本、麦克风 PCM、TTS 播放"]

    App["kong-voice-agent-app<br/>Spring Boot 应用模块"]
    AppAuth["认证与启动配置<br/>AuthController / AuthTokenService<br/>WebSocketConfig / AuthHandshakeInterceptor"]
    AppIntegration["应用侧默认集成<br/>DashScope Qwen-ASR<br/>应用侧 LLM<br/>DashScope Qwen-TTS"]

    Core["kong-voice-agent-core<br/>可复用语音代理核心模块"]
    WsLayer["WebSocket 适配层<br/>AgentWebSocketHandler<br/>WsTextMessageHandlerRegistry<br/>ping / interrupt / audio_end / text"]
    Pipeline["流水线门面<br/>VoicePipelineService<br/>音频、文本、commit、LLM、TTS、打断编排"]
    Session["会话与 turn 状态<br/>SessionManager / SessionState<br/>turnId 隔离 / invalid turn 集合"]
    Turn["状态机与端点<br/>TurnManager / EndpointingPolicy<br/>VAD + ASR + EOU 推动提交"]
    Extension["可替换扩展点<br/>StreamingAsrAdapterFactory<br/>VadEngine / EouDetector<br/>LlmOrchestrator / TtsOrchestrator<br/>VoicePipelineHook"]
    Dispatch["下行发送<br/>PlaybackDispatcher<br/>AgentEvent + payload 实体"]

    Models["models/<br/>外置 ONNX 模型目录<br/>Silero VAD / LiveKit EOU"]
    Docs["docs/<br/>协议、功能、前端联调、系统状态、架构"]

    UI -->|"HTTP 登录 / WebSocket"| App
    App --> AppAuth
    App --> AppIntegration
    App --> Core
    Core --> WsLayer
    Core --> Pipeline
    Core --> Session
    Core --> Turn
    Core --> Extension
    Core --> Dispatch
    Extension --> Models
    Docs -.-> UI
    Docs -.-> App
    Docs -.-> Core
```

当前分层的核心原则是：`kong-voice-agent-core` 保存稳定协议、状态机、运行态和扩展接口；`kong-voice-agent-app` 负责启动、认证、端点注册和默认服务集成；`ui/` 只作为联调和产品化前端参考，不承载后端 session 状态。

## 数据流总览

```mermaid
flowchart TD
    Client["前端 / 客户端"]

    Client -->|"POST /api/auth/login"| AuthController["AuthController"]
    AuthController --> AuthTokenService["AuthTokenService<br/>签发进程内 token"]
    AuthTokenService -->|"token"| Client

    Client -->|"WebSocket /ws/agent?token=..."| AuthHandshake["AuthHandshakeInterceptor<br/>握手 token 校验"]
    AuthHandshake -->|无效| Unauthorized["401<br/>不创建 session"]
    AuthHandshake -->|有效| WsHandler["AgentWebSocketHandler"]

    WsHandler -->|"afterConnectionEstablished"| SessionManager["SessionManager.create"]
    SessionManager --> SessionState["SessionState<br/>sessionId / currentTurnId<br/>ASR / TurnManager / 缓冲区"]
    WsHandler --> Dispatcher["PlaybackDispatcher"]
    Dispatcher -->|"state_changed(IDLE)"| Client

    Client -->|"JSON 文本帧"| WsHandler
    Client -->|"Binary PCM 帧"| WsHandler

    WsHandler -->|"TextMessage"| Registry["WsTextMessageHandlerRegistry"]
    Registry -->|"ping"| Ping["pong"]
    Ping --> Dispatcher
    Registry -->|"interrupt"| Interrupt["VoicePipelineService.interrupt"]
    Registry -->|"audio_end"| AudioEnd["VoicePipelineService.commitAudioEnd"]
    Registry -->|"text"| TextInput["VoicePipelineService.acceptText"]
    Registry -->|"非法 JSON / 未知 type / 非法 payload"| BadMessage["error(bad_message)"]
    BadMessage --> Dispatcher

    WsHandler -->|"BinaryMessage PCM"| AudioInput["VoicePipelineService.acceptAudio"]

    TextInput -->|"必要时先打断旧播报"| Interruption1["InterruptionManager<br/>invalidate old turn"]
    Interruption1 -->|"playback_stop / turn_interrupted"| Dispatcher
    TextInput -->|"nextTurnId<br/>USER_TURN_COMMITTED"| TextCommitted["asr_final(source=text)"]
    TextCommitted --> Dispatcher
    TextCommitted --> LlmStart["startLlmAfterCommit"]

    AudioInput -->|"写入 ring buffer / pre-roll"| AudioBuffers["CircularByteBuffer<br/>PreRollBuffer"]
    AudioInput -->|"audioTaskExecutor 虚拟线程"| AudioProcess["processAudioLocked<br/>同 session 串行"]
    AudioProcess --> Vad["VadEngine<br/>Silero ONNX 或 RMS fallback"]
    Vad -->|"Agent 播报中检测到说话"| BargeIn["barge_in"]
    BargeIn --> Interruption2["InterruptionManager<br/>旧 turn 失效，新 turn 创建"]
    Interruption2 -->|"playback_stop / turn_interrupted / USER_PRE_SPEECH"| Dispatcher

    Vad -->|"说话帧"| AsrAccept["StreamingAsrAdapter.acceptAudio"]
    Vad -->|"静音且无 turn"| DropSilence["丢弃纯静音"]
    AsrAccept -->|"可选 partial"| AsrPartial["asr_partial"]
    AsrPartial --> Dispatcher

    AsrAccept --> MaybeEou["maybePredictEou<br/>静音候选阶段"]
    MaybeEou --> Eou["EouDetector<br/>finished / waiting / fallback"]
    Eou --> TurnManager["TurnManager.onAudio"]
    Vad --> TurnManager

    TurnManager -->|"状态迁移"| StateEvents["state_changed<br/>USER_PRE_SPEECH / USER_SPEAKING / USER_ENDPOINTING"]
    StateEvents --> Dispatcher
    TurnManager -->|"endpointReached"| AudioCommitted["USER_TURN_COMMITTED"]
    AudioCommitted --> AsrCommit["StreamingAsrAdapter.commitTurn"]
    AudioEnd --> AsrCommit
    AsrCommit -->|"final transcript"| AsrFinal["asr_final(source=audio)"]
    AsrFinal --> Dispatcher
    AsrFinal --> LlmStart

    LlmStart -->|"校验 fin 且 turnId 当前有效"| Thinking["agent_thinking"]
    Thinking --> Dispatcher
    LlmStart -->|"agentTaskExecutor 虚拟线程"| Llm["LlmOrchestrator.stream"]
    Llm -->|"LlmChunk"| LlmTurnCheck{"isCurrentTurn(turnId)?"}
    LlmTurnCheck -->|否| DropLlm["丢弃旧 turn LLM 输出"]
    LlmTurnCheck -->|是| AgentText["agent_text_chunk"]
    AgentText --> Dispatcher
    AgentText --> Tts["TtsOrchestrator.synthesizeStreaming"]

    Tts -->|"TtsChunk"| TtsTurnCheck{"isCurrentTurn(turnId)?"}
    TtsTurnCheck -->|否| DropTts["丢弃旧 turn TTS 输出"]
    TtsTurnCheck -->|是| TtsAudio["tts_audio_chunk<br/>audioBase64"]
    TtsAudio --> Dispatcher
    Dispatcher -->|"JSON 下行事件"| Client

    Llm -->|"异常"| LlmError["error(llm_failed)"]
    Tts -->|"异常"| TtsError["error(tts_failed)"]
    LlmError --> Dispatcher
    TtsError --> Dispatcher
```

## 文本输入路径

1. 客户端发送 `{"type":"text","payload":{"text":"..."}}`。
2. `AgentWebSocketHandler` 解析为 `WsMessage`，交给 `WsTextMessageHandlerRegistry`。
3. `TextWsTextMessageHandler` 调用 `VoicePipelineService.acceptText`。
4. 如果 Agent 正在播报，先通过 `InterruptionManager` 失效旧 `turnId`，并下发 `playback_stop` 与 `turn_interrupted`。
5. 服务端创建新的 `turnId`，状态进入 `USER_TURN_COMMITTED`。
6. 文本输入被包装为 `asr_final(source=text)`，然后进入 `startLlmAfterCommit`。
7. LLM 输出 `agent_text_chunk`，每个非空文本片段继续提交给 TTS。
8. TTS 输出 `tts_audio_chunk(audioBase64)`，客户端按 `turnId` 和 `seq` 播放。

文本路径会跳过 VAD、ASR partial 和 EOU，但不会绕过 `turn committed` 边界。

## 音频输入路径

1. 客户端通过 WebSocket 二进制帧发送 PCM，默认格式为 16kHz / mono / 16-bit PCM little-endian。
2. `AgentWebSocketHandler` 复制二进制载荷，调用 `VoicePipelineService.acceptAudio`。
3. 流水线先写入 `CircularByteBuffer` 和 `PreRollBuffer`，再把耗时处理提交到 `audioTaskExecutor`。
4. 同一个 session 内，`processAudioLocked` 用锁保证 VAD、ASR、TurnManager 按音频帧顺序推进。
5. `VadEngine` 判断是否有人声；如果 Agent 正在播报且检测到用户重新开口，会触发 `barge_in` 打断。
6. 有效音频进入每 session 独立的 `StreamingAsrAdapter.acceptAudio`，真实流式 ASR 可以返回 `asr_partial`。
7. 静音候选阶段，`maybePredictEou` 会在满足最小静音窗口且存在 partial 文本时调用 `EouDetector`。
8. `TurnManager` 结合 VAD、ASR、EOU 和时间窗口产出状态迁移或提交事件。
9. 达到端点后，服务端调用 `StreamingAsrAdapter.commitTurn` 得到最终文本，并下发 `asr_final(source=audio)`。
10. 音频 final 进入同一条 LLM/TTS 下游链路。

## 打断与 turnId 隔离

打断有两种入口：客户端主动发送 `interrupt`，或者 Agent 播报中 VAD 检测到用户重新说话。两者最终都走 `InterruptionManager`：

1. 将旧 `turnId` 放入 invalid turn 集合。
2. 将 `agentSpeaking` 置为 `false`，状态切到 `INTERRUPTED`。
3. 下发 `playback_stop(oldTurnId)` 和 `turn_interrupted(oldTurnId)`。
4. 创建新的 `turnId`，状态切到 `USER_PRE_SPEECH`。

所有 ASR、LLM、TTS 异步结果发布前都必须通过 `SessionState.isCurrentTurn(turnId)` 校验。校验失败的旧结果会被直接丢弃，不再污染当前 turn 的文本、音频或状态。

## 下行事件

后端统一通过 `PlaybackDispatcher` 下发 `AgentEvent`。每条下行 JSON 都带有 `sessionId`、`turnId`、`timestamp` 和事件专属 `payload`。

常见下行事件包括：

- `state_changed`：状态迁移。
- `asr_partial`：流式 ASR 中间结果，只用于展示。
- `asr_final`：最终用户输入，音频为 `source=audio`，文本输入为 `source=text`。
- `agent_thinking`：LLM 开始处理。
- `agent_text_chunk`：Agent 文本片段。
- `tts_audio_chunk`：TTS 音频片段，音频在 `payload.audioBase64`。
- `playback_stop`：停止旧播报。
- `turn_interrupted`：旧 turn 被打断。
- `error`：协议或异步下游错误。
- `pong`：心跳响应。
