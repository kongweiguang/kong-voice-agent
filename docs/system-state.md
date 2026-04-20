# System State

最后更新：2026-04-20

这份文档用于跨多轮推进时保存当前目标、硬约束、已达成结论、待决问题和下一步计划。后续每次推进优先更新这里，而不是只依赖会话上下文。

原待办文档已拆解合并到 `docs/features.md`，后续不再维护单独的待办文件。

## 当前目标

构建一个 Java 21 + Spring Boot 3 的 voice agent 后端，支持 WebSocket 音频输入、文本输入、流式 ASR、TurnManager、endpointing、LLM、TTS 和 interruption。

当前版本的路线是先完成 mock 闭环，再替换成真实服务。所有设计都要围绕 `turnId` 隔离，避免旧 turn 污染新 turn。

工程当前采用 Maven 3.9+ 多模块结构：`kong-voice-agent-core` 是公共可复用语音能力模块，`kong-voice-agent-app` 是包含 Spring Boot 启动类、默认 mock 能力和业务装配的应用模块。业务侧通过注册同类型 Bean 覆盖默认 ASR、VAD、LLM、TTS，通过 `VoicePipelineHook` 挂接自定义业务逻辑。项目面向 GitHub 开源维护，构建和代码风格优先服务长期可维护性、健壮性和扩展性。

## 本轮已完成

- 完成 Spring Boot 3 + Java 21 Maven 工程骨架
- 实现 WebSocket 接入层，路径为 `/ws/agent`
- 实现 session 生命周期、`turnId` 自增和旧 turn 失效机制
- 实现 PCM 工具、ring buffer、pre-roll buffer
- 实现 Silero VAD ONNX 加载入口和无模型 RMS fallback
- 实现 mock Streaming ASR、TurnManager、EndpointingPolicy
- 实现 mock LLM、TextChunker、mock TTS
- 新增 WebSocket `text` 上行消息，支持直接发送文本对话
- 实现 PlaybackDispatcher 和 InterruptionManager
- 补齐 README、协议文档、功能记录和本状态文档
- 将原待办文档的阶段任务拆解合并到 `docs/features.md`
- 删除原待办文档，后续开发文档只依赖当前文档集
- 为主源码和测试源码补充职责、边界和关键流程注释
- 将现有主源码和测试源码中的英文注释统一调整为中文表述
- 新增单元测试，覆盖 PCM、session、endpointing、turn commit 边界和旧 turn 拦截
- `mvn test` 已通过
- 新增前端对接文档 `docs/frontend-integration.md`
- 新增静态联调界面 `kong-voice-agent-app/frontend-demo.html`，支持连接 WebSocket、文本输入、麦克风 PCM、心跳、打断和事件日志
- 拆分 Maven 多模块：根工程聚合 `kong-voice-agent-core` 与 `kong-voice-agent-app`
- `kong-voice-agent-core` 模块承载通用语音流水线、协议模型、可替换接口和 hook
- `kong-voice-agent-app` 模块承载 Spring Boot 启动入口、运行配置、虚拟线程执行器、WebSocket 端点注册和默认 mock 能力
- 将音频处理和 Agent 下游任务执行器切换为 JDK 21 虚拟线程，并将虚拟线程路径上的 `synchronized` 锁替换为 `ReentrantLock`
- 同一 session 的音频处理在进入 VAD / ASR / TurnManager 前通过 `ReentrantLock` 串行化，避免虚拟线程并发打乱音频顺序
- 新增 `StreamingAsrAdapterFactory`，支持每个 session 创建独立 ASR 实例
- 默认 VAD 由 core 自动装配；默认 mock ASR、LLM、TTS 由 app 通过 `@ConditionalOnMissingBean` 装配，业务模块可声明同类型 Bean 覆盖
- 将 ASR、LLM、TTS 的 mock 实现从 core 移至 app，core 测试改用测试专用 stub
- 新增 `VoicePipelineHook`，支持观察音频、文本、turn commit、LLM、TTS 和 interruption 节点
- 引入 Lombok，当前用于简化构造器注入、日志字段和 `SessionState` 的 fluent 访问器
- 为现有主源码和测试源码的类级 Javadoc 统一补充 `@author kongweiguang`
- 将下行 `AgentEvent.payload` 从 `Map<String, Object>` 改为实现 `AgentEventPayload` 的实体类，接口不使用 sealed 限制，JSON 协议外壳保持不变
- 新增 Maven Enforcer 构建护栏，固化 Java 21、Maven 3.9+ 和非 SNAPSHOT 依赖基线
- 将 WebSocket 入站 `WsMessage.payload` 调整为 `JsonNode`，并在协议模型中集中校验 `payload.text`
- 补充 `AGENTS.md` 开源维护、长期可维护性优先和符号级中文注释要求
- 将 WebSocket JSON 文本消息改为 `WsTextMessageHandlerRegistry` 策略分发，内置 type 保持稳定，业务方可新增但不能覆盖内置 type
- 将 `kong-voice-agent.audio` 绑定到 `AudioFormatSpec`，新建 session 时使用配置化音频格式创建 PCM 缓冲和每会话 ASR 适配器

## 关键约束

- 默认支持 16kHz / mono / 16-bit PCM little-endian，运行时音频参数从 `kong-voice-agent.audio` 读取
- 当前 WebSocket 协议不做音频格式协商，客户端发送的 PCM 必须与服务端音频配置一致
- 使用 WebSocket 持续上传音频
- 允许通过 WebSocket JSON 文本消息直接提交用户文本
- 允许 ASR / LLM / TTS 先走 mock
- turn commit 之前不能调用 LLM
- turn commit 之前不能启动 TTS
- 本版本不支持 preemptive
- 所有异步回调都必须校验 `turnId`
- interruption 需要能立即打断旧 turn 的播报
- 新增或修改代码时需要同步维护有用注释，尤其是异步、状态机和协议边界
- 方法、属性、常量、枚举值、record 组件、配置 Bean 和公开扩展点都需要中文注释；简单 getter/setter 可由字段注释和类级说明覆盖
- 测试要尽量完整、覆盖全面，新增或修改功能时优先补齐协议解析、异常输入、状态机边界、异步 `turnId` 隔离、打断流程、配置默认值和公开扩展点测试
- 测试需要按领域分类，使用包名、`@Tag` 和 `@DisplayName` 标注 `protocol`、`pipeline`、`audio`、`session`、`turn` 等类别，方便查看和筛选
- 项目作为 GitHub 开源项目维护，不提交本地 IDE、临时调试、机器私有路径、明文密钥和只适合个人环境的默认值
- 方案选择优先考虑中长期可维护性、健壮性和扩展性，不为了短期通过牺牲清晰分层、协议兼容、错误边界和测试覆盖
- 设计开发优先采用成熟设计模式表达稳定变化点；当前基线包括 Strategy / Adapter、Factory、Observer / Hook、Facade 和 State Machine
- 设计模式使用必须服务于解耦、替换、测试或扩展，不能为了形式牺牲代码直观性
- 公共能力优先放入 `kong-voice-agent-core` 模块；具体业务启动、配置和定制实现放入 `kong-voice-agent-app` 模块或后续业务模块
- WebSocket 上行 JSON 文本消息通过 `WsTextMessageHandler` 扩展自定义 type；内置 `ping`、`interrupt`、`audio_end`、`text` 不允许被业务策略覆盖
- ASR、LLM、TTS 的 app 默认 mock 实现只能作为 fallback，业务定制应通过 Spring Bean 覆盖接口；core 不承载这些 mock 实现
- hook 用于业务观察和扩展关键节点，不能绕过 `turnId` 校验和 turn commit 边界
- Lombok 仅用于消除样板代码；涉及复杂初始化、参数归一化、异步边界和状态机规则的代码仍需显式保留并补充中文注释
- Lombok 生成构造器时通过根目录 `lombok.config` 复制 Spring `@Qualifier`，避免多 Executor Bean 的构造器注入丢失限定信息
- 下行事件构造必须使用 `AgentEventPayload` 的具体实体类，业务方可自定义实现，避免新增事件时重新引入松散 `Map` payload
- 当前运行基线为 JDK 21，异步任务可以使用虚拟线程；虚拟线程涉及的共享状态锁优先使用 `ReentrantLock`，避免新增 `synchronized`
- 模型文件统一放在项目根目录 `models/`；本地开发默认读取 `models/silero_vad.onnx`
- 打包部署后通过 compose 将项目 `./models/` 映射到容器 `/kong/models/`，容器内模型路径由 `KONG_VOICE_AGENT_VAD_MODEL_PATH=file:/kong/models/silero_vad.onnx` 指定

## 已达成结论

1. 这不是一个 preemptive 版本，`partial transcript` 不触发 LLM。
2. 最小可运行闭环应该优先用 mock 跑通，避免一开始就被外部服务依赖卡住。
3. 业务状态必须显式区分 session 和 turn，不能把共享状态散落在 handler 里。
4. 协议需要把 `turnId` 作为一等字段，便于隔离和丢弃过期结果。
5. 模型不再放入 `src/main/resources` 随 jar 打包，统一作为外置文件由 `models/` 目录和 compose volume 提供。
6. 文档本身要作为长期记忆的一部分，持续维护到 `docs/` 下。
7. `docs/features.md` 是后续功能推进的主清单，不再使用单独的待办文件。
8. 文本输入属于已提交用户 turn，跳过 VAD/ASR partial 阶段，但仍不能绕过 turn commit 后才能启动 LLM/TTS 的边界。
9. 多模块拆分后，`kong-voice-agent-core` 不包含 Spring Boot 启动类；启动入口、`application.yml` 和端点注册在 `kong-voice-agent-app` 模块。
10. 每个 session 的 ASR 通过 `StreamingAsrAdapterFactory` 创建，避免业务 ASR 实现共享会话状态。
11. WebSocket JSON 文本消息按字符串 `type` 进入策略注册表，避免自定义 type 被 enum 反序列化提前拒绝；内置 type 不允许覆盖以保持公开协议稳定。

## 待决问题

- 真实 ASR 服务最终接哪一家
- 真实 LLM 服务最终接哪一家
- 真实 TTS 服务最终接哪一家
- Silero VAD 模型文件需要补入 `models/silero_vad.onnx`
- 是否需要在 Bean 覆盖之外，为 mock / real 服务增加显式配置开关
- WebSocket 下行协议当前采用统一 `payload` 外壳，前端是否需要平铺字段

## 下一步计划

1. 用真实 WebSocket 客户端脚本做端到端手工验证
2. 增加更贴近真实流式场景的集成测试
3. 接入真实 Silero ONNX 输入张量逻辑，替换当前 RMS fallback 推理路径
4. 在 `kong-voice-agent-app` 模块增加真实 ASR、LLM、TTS 的业务 Bean 实现
5. 根据前端联调结果调整协议字段是否平铺

## 当前文档基线

- `README.md`：项目入口和运行说明
- `docs/features.md`：功能清单和状态
- `docs/protocol.md`：消息协议和样例
- `docs/frontend-integration.md`：前端对接和联调说明
