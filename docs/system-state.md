# System State

最后更新：2026-04-22

这份文档用于跨多轮推进时保存当前目标、硬约束、已达成结论、待决问题和下一步计划。后续每次推进优先更新这里，而不是只依赖会话上下文。

原待办文档已拆解合并到 `docs/features.md`，后续不再维护单独的待办文件。

## 当前目标

构建一个 Java 21 + Spring Boot 3 的 voice agent 后端，支持 WebSocket 音频输入、文本输入、流式 ASR、TurnManager、EOU endpointing、LLM、TTS 和 interruption。

当前版本的路线是先保证本地可运行闭环，再按需替换或扩展真实服务。所有设计都要围绕 `turnId` 隔离，避免旧 turn 污染新 turn。

工程当前采用 Maven 3.9+ 多模块结构：`kong-voice-agent-core` 是面向多个系统复用的框架层，负责公共协议、运行态和扩展点；`kong-voice-agent-app` 是应用层示例，包含 Spring Boot 启动类、默认集成和业务装配。业务侧通过注册同类型 Bean 覆盖默认 ASR、VAD、EOU、LLM、TTS，通过 `VoicePipelineHook` 挂接自定义业务逻辑。项目面向 GitHub 开源维护，构建和代码风格优先服务长期可维护性、健壮性和扩展性。

仓库根目录新增 `ui/` React 前端工程，使用 pnpm 管理依赖，技术栈为 React 19、TypeScript 5.9、Vite 7、React Router 7、Shadcn UI / Radix UI、Tailwind CSS 4 和 Lucide React。当前界面采用豆包风格的产品化 AI 对话布局，左侧轻量会话栏承载新对话、历史摘要、连接状态和账号入口，右侧聊天区包含欢迎态、示例问题和底部固定输入框，支持日间/夜间主题、固定账号登录、WebSocket 连接、麦克风 PCM 输入、停止录音自动 `audio_end`、发送/打断一体主按钮、TTS 自动播放和助手文字区播报动效。前端约定一个对话对应一条 WebSocket 连接和一个后端 session，多个会话连接可同时存在；点击“新对话”会为新会话建立新连接，切换会话不会关闭其他在线连接；会话列表、消息快照、`sessionId` 和最近 `turnId` 会保存到浏览器 `localStorage`。

当前 WebSocket 已加入登录前置流程：客户端先调用 `POST /api/auth/login` 使用固定账号换取 token，再连接 `/ws/agent?token=<login-token>`。token 只保存在当前服务进程内存中，服务重启后全部失效。

## 本轮已完成

- 完成 Spring Boot 3 + Java 21 Maven 工程骨架
- 实现 WebSocket 接入层，路径为 `/ws/agent`
- 新增 `POST /api/auth/login` 固定账号登录接口，默认账号为 `demo` / `demo123456`
- WebSocket 握手改为读取 `/ws/agent?token=<login-token>` 中的 token，缺失或无效时返回 401
- 新增进程内 token 管理，登录 token 仅当前 JVM 有效，服务重启后全部失效
- 实现 session 生命周期、基于 WebSocket id 的会话主索引、基于业务 `sessionId` 的查询索引、雪花 ID 字符串 `turnId` 和旧 turn 失效机制
- 实现 PCM 工具、ring buffer、pre-roll buffer
- 实现 Silero VAD ONNX 加载入口和无模型 RMS fallback
- 实现 mock Streaming ASR、TurnManager、EndpointingPolicy
- 实现默认 LLM 和 TTS 编排
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
- 已移除旧 HTML 静态联调页，当前统一使用 `ui/` React 界面做前端联调
- 拆分 Maven 多模块：根工程聚合 `kong-voice-agent-core` 与 `kong-voice-agent-app`
- `kong-voice-agent-core` 模块承载通用语音流水线、协议模型、可替换接口和 hook
- `kong-voice-agent-app` 模块承载 Spring Boot 启动入口、运行配置、虚拟线程执行器、WebSocket 端点注册和默认集成能力
- 将音频处理和 Agent 下游任务执行器切换为 JDK 21 虚拟线程，并将虚拟线程路径上的 `synchronized` 锁替换为 `ReentrantLock`
- 同一 session 的音频处理在进入 VAD / ASR / TurnManager 前通过 `ReentrantLock` 串行化，避免虚拟线程并发打乱音频顺序
- 新增 `StreamingAsrAdapterFactory`，支持每个 session 创建独立 ASR 实例
- 默认 VAD 由 core 自动装配；默认 ASR、LLM、TTS 由 app 通过 `@ConditionalOnMissingBean` 装配，业务模块可声明同类型 Bean 覆盖
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
- 新增可扩展 EOU 能力：`EouDetector` 可通过 Spring Bean 覆盖；默认提供 LiveKit MultilingualModel 风格 ONNX detector，并通过 `EouHistoryProvider` 按会话读取外部历史，模型缺失时可 fallback 到原静音端点行为
- TurnManager endpointing 已接入 EOU：静音候选后先做语义结束判断，`EOU=false` 继续等待，超过最大静音窗口兜底提交，仍保持 turn committed 之后才启动 LLM/TTS
- 新增 `kong-voice-agent.onnx` 配置和 `OnnxSessionOptionsFactory`，默认 VAD 与默认 EOU 可统一切换 CUDA GPU provider，并支持 CUDA 不可用时回退 CPU session
- 将 LiveKit EOU 真实模型测试调整为显式开启的模型集成测试，默认 `mvn test` 不再加载外置大模型资产
- 新增严格 CUDA EOU 并发测试和 `scripts/run-eou-cuda-test.ps1`，通过 `fallbackToCpu=false` 验证真实 CUDA provider，不允许误走 CPU 回退
- `IdUtils` 新增 64 位趋势递增雪花 ID 生成能力，支持系统属性或环境变量配置节点号，并补充工具类测试
- `turnId` 已改为使用 `IdUtils.snowflakeIdStr()` 生成的字符串，前端和服务端只按相等性判断当前 turn
- `SessionManager` 保持使用 Spring WebSocket id 作为主索引，并提供通过业务 `sessionId` 查询 `SessionState` 与当前 `WebSocketSession` 的入口，便于 WebSocket 回调之外的业务流程复用连接。
- 修复 AgentScope BOM 覆盖 Spring Boot 自动配置版本导致的启动失败，应用模块现在先导入项目统一 Spring Boot BOM，再导入 AgentScope BOM
- 新增 `VoiceAgentApplicationTest` 完整上下文启动烟测，防止后续依赖版本漂移再次导致自动配置类解析失败
- 默认 ASR 切换为 DashScope Qwen-ASR：提交 turn 时将累计 PCM 包装成 WAV Data URL 后调用 OpenAI 兼容 `/chat/completions`，失败时直接报错，不回退假转写
- 默认 TTS 切换为 DashScope Qwen-TTS：调用 DashScope multimodal generation 接口，优先读取 `output.audio.data`，也支持下载 `output.audio.url`，失败时直接报错，不回退假音频
- 新增 DashScope Qwen-ASR / Qwen-TTS integration 适配器测试，覆盖请求体、鉴权头、base64 音频解析和 API Key 缺失边界
- DashScope Qwen-TTS 适配器已按 turnId 缓冲 LLM 片段，累计到句子边界或最后一个 chunk 后再合成；默认开启 DashScope SSE 流式 TTS，逐块读取音频并下发，关闭流式后仍按句非流式合成，避免短音频片段导致播放断续
- React UI 已对齐当前协议：按 `turnId` 过滤过期文本与音频，消费 `tts_audio_chunk.payload.audioBase64` 并在打断、切换 turn 或新建对话时清空旧播放队列
- 新增根目录 `ui/` 前端工程，采用 React 19、TypeScript 5.9、Vite 7、React Router 7、Shadcn UI / Radix UI、Tailwind CSS 4、Lucide React 和 pnpm
- `ui/` 已实现豆包风格产品化聊天界面、轻量会话侧栏、移动端覆盖式侧栏、首屏欢迎态、底部固定输入、麦克风 PCM 输入、停止录音自动 `audio_end`、发送/打断一体主按钮、日间/夜间主题切换、固定账号登录、WebSocket 连接、Agent 文本 chunk 聚合、TTS 播放队列、播报动效、多前端会话 WebSocket 并存和 `localStorage` 会话快照
- `ui/` 已通过 `pnpm lint` 和 `pnpm build` 验证，并生成 `pnpm-lock.yaml`
- `audio_end` 已改为通过音频处理执行器异步提交 ASR final，并在提交前显式下发 `USER_TURN_COMMITTED`；ASR 提交失败会转换为 `error(code=asr_failed)`
- 新增 `AgentResponseOrchestrator` 承接 turn commit 后的 LLM/TTS 编排、错误转换和播报状态收口，`VoicePipelineService` 保持音频、ASR 和 turn 状态机门面职责
- 新增 `TurnCancellationCoordinator` 以及 ASR/TTS `cancelTurn` 扩展点，打断或切换 turn 时释放旧 turn 的 PCM、待合成文本等缓存；空闲 `interrupt` 现在是 no-op，不创建新 turn
- `SessionState` 的 invalid turn 记录改为有上限的最近失效记录，避免长连接多轮对话无限增长

## 关键约束

- 默认支持 16kHz / mono / 16-bit PCM little-endian，运行时音频参数从 `kong-voice-agent.audio` 读取
- WebSocket 连接前必须先登录，登录接口为 `POST /api/auth/login`
- 当前固定账号默认值为 `accountId=demo-user`、`username=demo`、`password=demo123456`，公开部署必须通过环境变量覆盖默认密码
- 固定账号环境变量为 `KONG_VOICE_AGENT_AUTH_FIXED_USER_ACCOUNT_ID`、`KONG_VOICE_AGENT_AUTH_FIXED_USER_USERNAME` 和 `KONG_VOICE_AGENT_AUTH_FIXED_USER_PASSWORD`
- WebSocket 地址为 `/ws/agent?token=<login-token>`，token 必须来自当前进程内存中的有效登录记录
- 当前 token 不持久化、不跨实例共享、不提供刷新或撤销接口；服务重启、token 缺失或 token 无效时客户端必须重新登录
- 当前 WebSocket 协议不做音频格式协商，客户端发送的 PCM 必须与服务端音频配置一致
- 当前 TTS 下行协议不携带音频格式元数据；默认 DashScope Qwen-TTS 返回音频字节，由 React UI 优先用浏览器解码播放，后续如需动态格式需要扩展 payload
- 使用 WebSocket 持续上传音频
- 允许通过 WebSocket JSON 文本消息直接提交用户文本
- 允许 ASR / LLM / TTS 先走应用层示例实现或业务自定义实现
- turn commit 之前不能调用 LLM
- turn commit 之前不能启动 TTS
- 本版本不支持 preemptive
- 所有异步回调都必须校验 `turnId`
- interruption 需要能立即打断旧 turn 的播报
- `audio_end` 可能触发远端 ASR 调用，必须离开 WebSocket 文本消息线程并复用同一 session 的音频处理锁
- LLM/TTS 流式适配器必须在接口方法返回前完成回调或同步抛错；异步 SDK 需要在适配器内部等待完成
- 被打断或被新 turn 取代的旧 turn 必须调用 ASR/TTS `cancelTurn` 释放 turn 级缓存
- 新增或修改代码时需要同步维护有用注释，尤其是异步、状态机和协议边界
- 方法、属性、常量、枚举值、record 组件、配置 Bean 和公开扩展点都需要中文注释；简单 getter/setter 可由字段注释和类级说明覆盖
- 测试要尽量完整、覆盖全面，新增或修改功能时优先补齐协议解析、异常输入、状态机边界、异步 `turnId` 隔离、打断流程、配置默认值和公开扩展点测试
- 测试需要按领域分类，使用包名、`@Tag` 和 `@DisplayName` 标注 `protocol`、`pipeline`、`audio`、`session`、`turn` 等类别，方便查看和筛选
- 项目作为 GitHub 开源项目维护，不提交本地 IDE、临时调试、机器私有路径、明文密钥和只适合个人环境的默认值
- 方案选择优先考虑中长期可维护性、健壮性和扩展性，不为了短期通过牺牲清晰分层、协议兼容、错误边界和测试覆盖
- 设计开发优先采用成熟设计模式表达稳定变化点；当前基线包括 Strategy / Adapter、Factory、Observer / Hook、Facade 和 State Machine
- 设计模式使用必须服务于解耦、替换、测试或扩展，不能为了形式牺牲代码直观性
- 公共能力优先放入 `kong-voice-agent-core` 模块；具体业务启动、配置和定制实现放入 `kong-voice-agent-app` 模块或后续业务模块
- `kong-voice-agent-app` 可引入业务侧 BOM，但 Spring Boot、Spring Framework、Jackson、JUnit 等平台依赖必须优先跟随根工程 `spring-boot.version`，不能被业务 BOM 覆盖成不兼容主版本
- WebSocket 上行 JSON 文本消息通过 `WsTextMessageHandler` 扩展自定义 type；内置 `ping`、`interrupt`、`audio_end`、`text` 不允许被业务策略覆盖
- WebSocket 握手阶段的 token 鉴权是 session 创建前置条件，不允许未认证连接进入语音流水线
- app 默认 ASR / TTS 直接通过 `kong-voice-agent.asr.dashscope.*` 和 `kong-voice-agent.tts.dashscope.*` 对接 DashScope；API Key 缺失、外部服务异常或返回空内容应明确暴露，不能静默回退假数据
- hook 用于业务观察和扩展关键节点，不能绕过 `turnId` 校验和 turn commit 边界
- Lombok 仅用于消除样板代码；涉及复杂初始化、参数归一化、异步边界和状态机规则的代码仍需显式保留并补充中文注释
- Lombok 生成构造器时通过根目录 `lombok.config` 复制 Spring `@Qualifier`，避免多 Executor Bean 的构造器注入丢失限定信息
- 下行事件构造必须使用 `AgentEventPayload` 的具体实体类，业务方可自定义实现，避免新增事件时重新引入松散 `Map` payload
- 当前运行基线为 JDK 21，异步任务可以使用虚拟线程；虚拟线程涉及的共享状态锁优先使用 `ReentrantLock`，避免新增 `synchronized`
- 雪花 ID 节点号范围为 0 到 1023；生产多实例部署需要显式配置 `kong.voice-agent.snowflake.node-id` 或 `KONG_VOICE_AGENT_SNOWFLAKE_NODE_ID`，避免实例间节点号碰撞
- 模型文件统一放在项目根目录 `models/`；本地开发默认读取 `models/silero_vad.onnx`
- 打包部署后通过 compose 将项目 `./models/` 映射到容器 `/kong/models/`，容器内模型路径由 `KONG_VOICE_AGENT_VAD_MODEL_PATH=file:/kong/models/silero_vad.onnx` 指定
- EOU 默认模型文件同样使用外置模型目录，默认读取 `models/livekit-turn-detector/model_quantized.onnx` 和 `models/livekit-turn-detector/tokenizer.json`，不提交大模型文件到 Git
- ONNX Runtime 默认使用 CPU；启用 `kong-voice-agent.onnx.gpu-enabled=true` 时需要使用 `onnxruntime_gpu` artifact 构建并准备匹配 CUDA 环境，`fallback-to-cpu=true` 时 CUDA provider 不可用会回退 CPU
- 依赖外置模型文件的测试不进入默认测试路径，需要通过 `-Dkong.voice-agent.model-tests=true` 显式开启
- 严格 CUDA 验证需要额外设置 `-Dkong.voice-agent.cuda-tests=true`，并确保 CUDA 12.x 和 cuDNN 9.x DLL 在 Windows `PATH` 中

## 已达成结论

1. 这不是一个 preemptive 版本，`partial transcript` 不触发 LLM。
2. 最小可运行闭环应该优先用应用层示例实现或简化实现跑通，避免一开始就被外部服务依赖卡住。
3. 业务状态必须显式区分 session 和 turn，不能把共享状态散落在 handler 里。
4. 协议需要把 `turnId` 作为一等字段，便于隔离和丢弃过期结果。
5. 模型不再放入 `src/main/resources` 随 jar 打包，统一作为外置文件由 `models/` 目录和 compose volume 提供。
6. 文档本身要作为长期记忆的一部分，持续维护到 `docs/` 下。
7. `docs/features.md` 是后续功能推进的主清单，不再使用单独的待办文件。
8. 文本输入属于已提交用户 turn，跳过 VAD/ASR partial 阶段，但仍不能绕过 turn commit 后才能启动 LLM/TTS 的边界。
9. 多模块拆分后，`kong-voice-agent-core` 不包含 Spring Boot 启动类；启动入口、`application.yml` 和端点注册在 `kong-voice-agent-app` 模块。
10. 每个 session 的 ASR 通过 `StreamingAsrAdapterFactory` 创建，避免业务 ASR 实现共享会话状态。
11. WebSocket JSON 文本消息按字符串 `type` 进入策略注册表，避免自定义 type 被 enum 反序列化提前拒绝；内置 type 不允许覆盖以保持公开协议稳定。
12. EOU 是可替换能力，不与默认 LiveKit MultilingualModel 风格实现绑定；业务可以替换整个 `EouDetector`，默认 LiveKit 实现只额外需要可选的 `EouHistoryProvider`。
13. EOU 只影响音频 turn commit 时机，不改变 WebSocket 消息结构，不引入 preemptive LLM。
14. 默认 VAD 和默认 EOU 的 ONNX 执行设备通过统一配置控制，避免每个模型实现各自定义 GPU 开关。
15. 当前认证只解决本地联调和单实例示例部署的最小登录态，不等同于完整生产账号体系；后续真实部署需要补充持久化、过期、撤销和跨实例共享策略。

## 待决问题

- 真实 ASR 默认已接 DashScope Qwen-ASR，后续如需实时 partial 可继续替换为流式 ASR
- 真实 LLM 服务最终接哪一家
- 真实 TTS 默认已接 DashScope Qwen-TTS，后续可按音频格式和延迟需求优化
- Silero VAD 模型文件需要补入 `models/silero_vad.onnx`
- LiveKit turn detector 模型和 tokenizer 需要按需补入 `models/livekit-turn-detector/`
- 是否需要在 Bean 覆盖之外，为示例 / 生产服务增加显式配置开关
- WebSocket 下行协议当前采用统一 `payload` 外壳，前端是否需要平铺字段
- 固定账号认证后续是否替换为真实用户体系，以及 token 过期、撤销和多实例共享策略如何设计

## 下一步计划

1. 用真实 WebSocket 客户端脚本做端到端手工验证
2. 增加更贴近真实流式场景的集成测试
3. 补充登录接口、固定账号环境变量覆盖和 WebSocket token 鉴权失败路径测试
4. 接入真实 Silero ONNX 输入张量逻辑，替换当前 RMS fallback 推理路径
5. 补入 LiveKit turn detector 真实模型资产并用真实 ASR partial 验证 EOU 效果
6. 在 `kong-voice-agent-app` 模块增加真实 ASR、LLM、TTS 的业务 Bean 实现
7. 根据前端联调结果调整协议字段是否平铺
8. 为 `ui/` React 前端补充服务端历史会话持久化、跨设备同步和端到端 UI 验证

## 当前文档基线

- `README.md`：项目入口和运行说明
- `docs/architecture.md`：模块分层、端到端数据流、文本/音频路径和打断流程
- `docs/features.md`：功能清单和状态
- `docs/protocol.md`：消息协议和样例
- `docs/frontend-integration.md`：前端对接和联调说明
- `ui/`：React 对话界面和 pnpm 前端工程
