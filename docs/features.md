# Features

这份文档是后续开发的主功能清单。后续推进只维护 `README.md`、`docs/system-state.md`、`docs/features.md` 和 `docs/protocol.md`。

## 功能总览

| 功能域 | 状态 | 当前实现 | 后续关注 |
| --- | --- | --- | --- |
| 工程骨架 | 已实现 | Spring Boot 3、Java 21、Maven 3.9+ 多模块、Maven Enforcer、JDK 虚拟线程执行器、基础日志、可打包 app jar | 按需补 CI / profile |
| WebSocket 接入 | 已实现 | `/ws/agent` 支持策略注册表驱动的 JSON 控制消息、文本输入、自定义 type 和二进制 PCM | 增加端到端 WebSocket 自动化测试 |
| Session 与 turnId | 已实现 | 每连接独立 session，维护 current turn 和旧 turn 失效集合 | 丰富并发与断连测试 |
| 音频基础设施 | 已实现 | PCM s16le 转换、RMS、ring buffer、pre-roll、时长换算 | 增加异常格式校验策略 |
| VAD | 已实现 | Silero ONNX 加载入口，默认从项目 `models/` 读取，模型缺失时 RMS fallback | 放入真实模型并确认输入签名 |
| Streaming ASR | 已实现 | 每 session ASR 工厂，app 默认 mock adapter 支持 partial / final | 接入真实流式 ASR |
| TurnManager / endpointing | 已实现 | 根据 VAD、ASR、时间信息推进状态并 commit turn | 扩展真实噪声场景测试 |
| Audio ingress 编排 | 已实现 | WebSocket 音频异步进入 VAD + ASR + TurnManager | 增加背压、队列和延迟指标 |
| LLM 编排 | 已实现 | turn committed + final transcript 后才调用可覆盖 LLM Bean，app 默认 mock | 接入真实 LLM，支持配置切换 |
| TextChunker / TTS | 已实现 | 文本切句后进入可覆盖 TTS Bean，app 默认 mock，返回 base64 chunk | 接入真实 TTS 与音频格式协商 |
| Playback / interruption | 已实现 | 播报中用户开口或 interrupt 会停止旧 turn | 增加完整集成测试 |
| Hook 扩展 | 已实现 | `VoicePipelineHook` 支持观察音频、文本、commit、LLM、TTS、打断节点 | 按业务需要增加可改写上下文的 hook |
| 协议文档 | 已实现 | 上下行消息和 JSON 示例记录在 `docs/protocol.md`，后端下行 `AgentEvent.payload` 已改为事件专属实体类 | 与前端联调后冻结字段 |
| 前端联调文档与界面 | 已实现 | `docs/frontend-integration.md` 记录接入方式，`kong-voice-agent-app/frontend-demo.html` 提供静态调试台 | 根据真实前端联调反馈调整 UI 与字段说明 |
| README / 状态文档 | 已实现 | 入口说明、系统状态、协议、功能清单齐备 | 每次功能推进同步更新 |
| 代码注释 | 已实现 | 主源码和测试源码已补充类级、符号级、关键方法和核心约束注释 | 后续新增代码同步补注释 |
| preemptive | 不支持 | 本版本明确禁止 turn commit 前启动 LLM/TTS | 除非另立版本，否则不实现 |

## 已实现功能细目

### 1. 工程骨架

- Java 21 + Spring Boot 3 + Maven 3.9+ 多模块。
- 根工程通过 Maven Enforcer 固化 Java 21、Maven 3.9+ 和非 SNAPSHOT 依赖基线，避免开源用户在不兼容环境下得到隐晦构建失败。
- 异步音频处理和 Agent 下游任务使用 JDK 21 虚拟线程执行器；共享状态串行化优先使用 `ReentrantLock`。
- 根工程 `kong-voice-agent-parent` 通过 `dependencyManagement` 统一管理 Spring Boot、ONNX Runtime 和内部模块版本，并聚合 `kong-voice-agent-core` 和 `kong-voice-agent-app` 两个子模块。
- `kong-voice-agent-core` 模块承载通用语音流水线、协议模型、可替换接口和 hook。
- `kong-voice-agent-app` 模块承载 Spring Boot 启动入口、运行配置、虚拟线程执行器、默认 mock 能力和业务装配。
- 依赖包含 WebSocket、Jackson、ONNX Runtime Java、Lombok、Spring Boot Test。
- 应用入口为 `kong-voice-agent-app` 模块的 `VoiceAgentApplication`。
- 配置文件为 `kong-voice-agent-app/src/main/resources/application.yml`。
- `mvn test` 和 `mvn clean package` 已通过。
- 应用可启动，默认端口为 `9877`。

验收点：

- `mvn clean package` 成功。
- `java -jar kong-voice-agent-app/target/kong-voice-agent-app-0.1.jar` 可启动。
- README 写明启动和验证方式。

### 2. WebSocket 接入

- WebSocket 路径：`/ws/agent`。
- JSON 文本消息入口由 `WsTextMessageHandlerRegistry` 按字符串 `type` 分发，内置支持：`ping`、`interrupt`、`audio_end`、`text`。
- 业务模块可以注册 `WsTextMessageHandler` Bean 新增自定义 type；内置 type 不允许覆盖，重复 type 在启动阶段失败。
- 二进制消息入口：接收 PCM 音频块。
- `text` 会直接创建已提交的用户 turn，跳过 VAD/ASR，复用 LLM/TTS 下游链路。
- 连接建立时创建 session。
- 连接断开时销毁 session 并清理状态。
- 下行事件统一通过 `PlaybackDispatcher` 发送 JSON。
- 下行事件的 `AgentEvent.payload` 在 Java 内部使用实体类表达，统一实现可扩展的 `AgentEventPayload` 接口，避免事件构造处依赖松散 `Map`。
- 上行 `WsMessage.type` 使用字符串保留未知业务 type，`payload` 使用 `JsonNode` 并由对应策略解析。

验收点：

- WebSocket 可以连接。
- `ping` 可返回 `pong`。
- `text` 可触发 `asr_final(source=text)`、`agent_thinking`、`agent_text_chunk` 和 `tts_audio_chunk`。
- 二进制 PCM 能进入音频处理流程。
- 自定义上行 JSON type 可通过策略 Bean 扩展，且不会覆盖内置协议行为。
- 断连能触发 session 清理。

### 3. Session 与 turnId

- 每个 WebSocket 连接拥有独立 `SessionState`。
- session 维护 `currentTurnId`、生命周期状态、ASR/LLM/TTS 活跃 turn、音频缓冲、转写文本、打断标记等。
- 新 turn 通过 `nextTurnId()` 自增。
- 旧 turn 通过 invalid turn 集合失效。
- 下游异步结果发出前必须调用 `isCurrentTurn(turnId)` 校验。

验收点：

- 多连接 session 互不影响。
- turnId 可自增。
- 旧 session 可销毁。
- interruption 后旧 turn 结果会被丢弃。

### 4. 音频基础设施

- 固定输入格式：16kHz / mono / 16-bit PCM little-endian。
- 默认上传块：20ms。
- `PcmUtils` 支持 byte[] 到 short[]、RMS、毫秒换算、拼接、裁剪。
- `CircularByteBuffer` 支持保留最新 PCM 数据。
- `PreRollBuffer` 保留最近约 400ms 音频。

验收点：

- ring buffer 可写可读，并在超容量时保留最新数据。
- pre-roll 能保留最近 300 到 500ms 音频。
- PCM little-endian 转换正确。
- RMS 结果可用于 mock VAD。

### 5. VAD

- `VadEngine` 定义统一接口。
- `SileroVadEngine` 默认尝试加载项目根目录 `models/silero_vad.onnx`。
- 打包部署后通过 compose 将项目 `./models/` 映射到容器 `/kong/models/`，并用 `VOICE_AGENT_VAD_MODEL_PATH=file:/kong/models/silero_vad.onnx` 指定运行时模型路径。
- 所有模型文件统一放在 `models/` 目录，后续新增模型也复用该目录映射。
- 模型存在时尝试通过 ONNX Runtime 推理。
- 模型缺失或推理失败时回退到 RMS fallback。
- `VadDecision` 输出 `speechProbability` 和 `speech` flag。

验收点：

- 项目启动时能尝试加载 ONNX 模型。
- 无模型文件时系统仍可启动并进入 RMS fallback。
- 任意 PCM 窗口可得到 VAD 决策。

后续改进：

- 补入真实 `models/silero_vad.onnx`。
- 根据真实模型输入/输出签名固定推理代码。
- 增加带真实 PCM fixture 的 VAD 测试。

### 6. Streaming ASR

- `StreamingAsrAdapter` 定义流式 ASR 接口。
- `StreamingAsrAdapterFactory` 负责为每个 session 创建独立 ASR 实例，业务模块可通过同类型 Bean 覆盖默认工厂。
- `kong-voice-agent-app` 模块的 `MockStreamingAsrAdapter` 按音频时长生成 partial transcript。
- turn commit 后生成 final transcript。
- 接口保留真实 ASR 替换空间。

验收点：

- 音频进入后能持续返回 `asr_partial`。
- turn commit 或 `audio_end` 后能返回 `asr_final`。
- 真实 ASR 可通过 adapter 替换，不需要改 WebSocket 层。

### 7. TurnManager 与 Endpointing

- 状态集合：`IDLE`、`USER_PRE_SPEECH`、`USER_SPEAKING`、`USER_ENDPOINTING`、`USER_TURN_COMMITTED`、`AGENT_THINKING`、`AGENT_SPEAKING`、`INTERRUPTED`。
- endpointing 默认参数：
  - `speechThreshold = 0.6`
  - `silenceThreshold = 0.35`
  - `preRollMs = 400`
  - `endSilenceMs = 600`
  - `minSpeechMs = 200`
  - `maxTurnMs = 15000`
- TurnManager 接收 VAD、ASR、音频时间信息并推进状态。
- 只有 `USER_TURN_COMMITTED` 后才允许触发 LLM。

验收点：

- 能判断开始说话。
- 能判断结束说话。
- 能触发 turn committed。
- committed 前不会调用 LLM。
- 状态流转已有单元测试覆盖。

### 8. Audio Ingress 编排

- WebSocket 二进制音频包写入 ring buffer 和 pre-roll buffer。
- 音频处理通过虚拟线程版 `audioTaskExecutor` 异步执行，避免阻塞 WebSocket IO 线程。
- 同一 session 的音频块进入 VAD / ASR / TurnManager 前会串行化，保证状态机按音频顺序推进。
- 每个音频块进入 VAD 和 ASR。
- 状态变化下发 `state_changed`。
- ASR partial 下发 `asr_partial`。
- ASR final 下发 `asr_final`。

验收点：

- WebSocket IO 线程不承担耗时 VAD/ASR/turn 处理。
- 同一连接内音频处理保持顺序，避免异步任务并发打乱 ASR 累计和 endpointing 状态。
- 能看到 partial transcript。
- turn commit 后能看到 final transcript。
- 状态切换可通过下行事件观察。

### 9. LLM 编排

- `LlmOrchestrator` 定义统一接口。
- 业务模块可以声明自己的 `LlmOrchestrator` Bean 覆盖 app 默认 mock。
- `kong-voice-agent-app` 模块的 `MockLlmOrchestrator` 基于 final transcript 生成 mock 回复块。
- `VoicePipelineService` 只在 turn committed 且 final transcript 就绪后启动 LLM。
- 每个 LLM chunk 下发前校验 `turnId`。
- 下发事件：`agent_thinking`、`agent_text_chunk`。

验收点：

- partial transcript 阶段不会启动 LLM。
- turn commit 后才进入 LLM。
- 前端可收到 thinking 和文本 chunk。
- 旧 turn 的 LLM 结果不会污染当前 turn。

### 10. TextChunker 与 TTS

- `TextChunker` 按句号、逗号、问号、感叹号和长度阈值切句。
- `TtsOrchestrator` 定义 TTS 接口。
- 业务模块可以声明自己的 `TtsOrchestrator` Bean 覆盖 app 默认 mock。
- `kong-voice-agent-app` 模块的 `MockTtsOrchestrator` 输出模拟音频 chunk。
- 每个 `TtsChunk` 带 `turnId`、`seq`、`isLast`、`audio`、`text`。
- 下发事件：`tts_audio_chunk`，音频以 base64 放在 payload 中。

验收点：

- agent 文本能被合理切句。
- mock TTS 能输出可验证音频块。
- 客户端能持续收到 `tts_audio_chunk`。
- 旧 turn 音频不会发送。

### 11. Playback 与 Interruption

- `PlaybackDispatcher` 负责 WebSocket 下行发送。
- `InterruptionManager` 负责打断流程。
- 当 Agent 正在 speaking 且检测到新用户说话时：
  - 旧 `turnId` 失效。
  - `agentSpeaking` 置为 false。
  - 下发 `playback_stop`。
  - 下发 `turn_interrupted`。
  - 分配新 `turnId`。
  - 状态切到 `USER_PRE_SPEECH`。
- 客户端也可发送 `interrupt` 主动打断。

验收点：

- Agent 播报时用户重新说话可以立即打断。
- 旧 turn 的 TTS chunk 不再发送。
- 新 turn 正常开始。
- interruption 旧 turn 拦截已有单元测试覆盖。

### 12. Hook 扩展

- `VoicePipelineHook` 位于 `kong-voice-agent-core` 模块。
- 业务模块可以注册一个或多个 hook Bean。
- 当前 hook 节点包括 `onAudioReceived`、`onTextReceived`、`onTurnCommitted`、`beforeLlm`、`onLlmChunk`、`onTtsChunk` 和 `onInterrupted`。
- hook 默认方法为空，业务只需要覆盖自己关心的节点。
- hook 适合做审计、日志、埋点、业务上下文记录和打断观察，不需要改动公共流水线。

验收点：

- 文本闭环测试覆盖 hook 的提交、LLM 和 TTS 节点。
- 未注册 hook 时流水线保持原行为。

### 13. 协议

上行：

- 二进制 PCM `audio_chunk`
- 文本 `interrupt`
- 文本 `audio_end`
- 文本 `ping`
- 文本 `text`
- 文本自定义 type，通过 `WsTextMessageHandler` Bean 扩展

下行：

- `state_changed`
- `asr_partial`
- `asr_final`
- `agent_thinking`
- `agent_text_chunk`
- `tts_audio_chunk`
- `playback_stop`
- `turn_interrupted`
- `error`
- `pong`

协议细节和 JSON 示例维护在 `docs/protocol.md`。服务端上行文本消息按策略注册表处理，未知业务 type 可先进入策略分发边界；下行事件仍保持统一 `payload` JSON 外壳，但 Java 内部已用可由业务自定义实现的 `AgentEventPayload` 及各事件 payload 实体约束字段。

### 14. 文档与跨轮状态

- `README.md`：项目入口、启动、验证和约束。
- `docs/system-state.md`：当前目标、关键约束、结论、待决问题、下一步计划。
- `docs/features.md`：主功能清单和验收记录。
- `docs/protocol.md`：WebSocket 协议。
- `docs/frontend-integration.md`：前端 WebSocket、文本、音频和事件处理对接说明。
- `kong-voice-agent-app/frontend-demo.html`：静态前端联调界面，支持文本、麦克风 PCM、心跳、打断和事件日志。

后续修改规则：

- 新增或修改功能时，同步更新本文件对应功能域。
- 改变协议时，同步更新 `docs/protocol.md`。
- 改变前端接入方式、联调界面或用户可见行为时，同步更新 `docs/frontend-integration.md`。
- 改变目标、约束、待决问题或下一步计划时，同步更新 `docs/system-state.md`。
- 不再使用单独的待办文件。

### 15. 代码注释

- 主源码已补充模块职责、接口边界、字段、常量、枚举值、record 组件、配置 Bean 和关键方法注释。
- 异步处理、`turnId` 校验、turn commit 边界、interruption 等核心约束已在代码中标注。
- 测试源码已补充场景说明，方便从测试失败定位被保护的行为。
- 现有源码和测试源码注释已统一调整为中文表述，保留必要协议名、类名和缩写。

验收点：

- 所有 `kong-voice-agent-core/src/main/java`、`kong-voice-agent-core/src/test/java` 和 `kong-voice-agent-app/src/main/java` 文件至少包含职责或场景注释。
- 方法、属性、常量、枚举值、record 组件、配置 Bean 和公开扩展点需要有中文说明；简单 getter/setter 可由字段注释和类级说明覆盖。
- 注释不替代清晰命名，不为显而易见的语句增加噪音。
- `mvn test` 通过。

## 质量要求

### 代码要求

- 保持清晰分层。
- 按 GitHub 开源项目标准维护代码、配置、文档和示例，避免提交本地 IDE、临时调试、机器私有路径和明文密钥。
- 优先选择中长期可维护、健壮、可扩展的方案，不为了短期通过验证破坏分层、协议兼容、错误边界和测试覆盖。
- 优先使用成熟设计模式表达稳定变化点：Strategy / Adapter 用于 VAD、LLM、TTS 等可替换供应商；Factory 用于每会话 ASR；Observer / Hook 用于业务扩展；Facade 用于流水线编排；State Machine 用于 turn 生命周期。
- 设计模式必须服务于解耦、替换、测试或扩展，避免为了形式新增无实际收益的抽象层。
- 避免在 WebSocket IO 线程做耗时工作。
- 虚拟线程路径上的共享状态优先使用 `ReentrantLock`，避免新增 `synchronized`。
- 保证 `turnId` 校验覆盖所有异步路径。
- 保证 session 生命周期清晰。
- 避免把 VAD / ASR / LLM / TTS 硬耦合在一个类中。
- 核心类只添加必要注释，优先用清晰命名表达意图。
- 新增或修改代码时同步维护类级、接口边界和关键流程注释。
- 可使用 Lombok 简化构造器、日志字段和状态对象访问器，但复杂初始化、参数归一化和关键边界逻辑仍保留显式代码。

### 测试要求

至少覆盖：

- session 生命周期。
- PCM 工具类。
- endpointing 状态流转。
- turn commit 后才进入 LLM。
- interruption 时旧 turn 被丢弃。
- WebSocket 端到端事件顺序。
- 协议解析和异常输入。
- 配置默认值与构建基线。
- 公开扩展点的默认行为和覆盖行为。
- 测试类需要按领域使用包名、`@Tag` 和 `@DisplayName` 分类，方便 IDE、Maven 和 CI 查看。

当前已覆盖：

- `CircularByteBufferTest`
- `PcmUtilsTest`
- `SessionManagerTest`
- `TurnManagerTest`
- `VoicePipelinePolicyTest`
- `VoicePipelineTextInputTest`
- `WsMessageTest`

待补测试：

- WebSocket 连接、ping/pong、binary PCM 入口。
- 完整 audio -> ASR -> commit -> LLM -> TTS 事件顺序。
- 完整 text -> commit -> LLM -> TTS WebSocket 端到端事件顺序。
- Agent speaking 中 barge-in 的集成测试。
- 真实 Silero 模型 fixture 测试。

## 明确禁止

- 不实现 preemptive LLM。
- 不允许在 partial transcript 阶段启动 LLM。
- 不允许在 turn committed 前启动 TTS。
- 不允许旧 turn 的异步结果继续影响当前 turn。
- 不允许阻塞 WebSocket 收包线程。

## 最终验收

### 闭环验收

- [x] 客户端上传 PCM 后，后端可实时输出 partial transcript。
- [x] 用户说完一句后，后端可正确识别 turn committed。
- [x] final transcript 送入 LLM。
- [x] 客户端直接发送文本后，后端可创建 committed turn 并进入 LLM/TTS。
- [x] LLM 文本送入 TTS。
- [x] TTS 音频流回传客户端播放。
- [x] Agent 播报中用户重新说话，系统可成功打断旧播报。

### 额外验收

- [x] README 完整。
- [x] mock 模式可独立运行。
- [x] 目录结构清晰。
- [x] 后续替换真实 ASR / LLM / TTS 的改动面尽量小。

## 下一批建议任务

1. 增加 WebSocket 端到端测试客户端，自动验证事件顺序。
2. 补入真实 `models/silero_vad.onnx`，并用真实模型签名固定推理逻辑。
3. 增加真实 ASR、LLM、TTS 的业务模块 Bean 实现。
4. 为真实服务接入定义 adapter 配置、超时、重试和错误事件。
5. 根据前端联调结果决定下行字段继续使用 `payload` 外壳还是平铺。
