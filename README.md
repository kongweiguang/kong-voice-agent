# Kong Voice Agent

Kong Voice Agent 是一个面向生产场景设计的 Java Voice Agent 后端框架，基于 Java 21、Spring Boot 3 和 WebSocket 实现，支持文本与 PCM 音频输入，内置 Session 与 turnId 隔离、VAD、流式 ASR、LLM、TTS、打断流程和 React 前端联调界面。

项目围绕可替换能力接口设计，业务侧可以自定义接入 ASR、LLM、TTS、VAD 等真实服务。`kong-voice-agent-core` 是面向多个系统复用的框架层，负责稳定协议、状态机、运行态和扩展接口；`kong-voice-agent-app` 是应用层示例，负责启动、认证、默认集成和联调入口。

## 当前能力

- 登录接口：`POST http://localhost:9877/api/auth/login`，默认固定账号为 `demo` / `demo123456`
- WebSocket 端点：`ws://localhost:9877/ws/agent?token=<login-token>`，连接前必须先登录获取 token
- 文本输入：直接发送 JSON 文本消息进入 Agent 回复链路
- 音频输入：默认发送 16kHz / mono / 16-bit PCM little-endian 二进制帧，格式可通过配置调整
- Session 隔离：每个 WebSocket 连接拥有独立 session，并可通过业务 `sessionId` 找回当前 WebSocket 连接
- Turn 隔离：所有异步结果通过雪花 ID 字符串 `turnId` 防止旧回复污染新对话
- 通用 ID：`IdUtils` 支持不可预测 session id 和趋势递增的雪花 ID
- VAD：优先加载 `models/silero_vad.onnx`，模型不可用时自动回退到 RMS fallback
- ASR / LLM / TTS：应用模块默认提供 DashScope Qwen-ASR / Qwen-TTS 与应用侧 LLM 实现，可通过 Spring Bean 替换
- 打断：支持客户端主动 `interrupt`，也支持 Agent 播报中用户重新说话触发打断
- React UI：`ui/` 使用 React 19、TypeScript 5.9、Vite 7、React Router 7、Shadcn UI / Radix UI、Tailwind CSS 4、Lucide React 和 pnpm，提供豆包风格的产品化聊天界面、轻量会话侧栏、移动端覆盖式侧栏、底部固定输入、麦克风 PCM 输入、发送/打断一体主按钮、连接、TTS 自动播放和文字区播报动效；每个前端会话拥有独立 WebSocket，多个会话连接可同时存在，切换会话不会断开其他在线会话；会话列表和消息快照会写入浏览器 `localStorage`

## 环境要求

| 工具 | 要求 |
| --- | --- |
| JDK | 21 或更高版本 |
| Maven | 3.9.0 或更高版本 |
| Node.js | 22 或更高版本 |
| pnpm | 10 或更高版本 |
| 浏览器 | Chrome、Edge 或其他支持 WebSocket 与麦克风权限的现代浏览器 |

确认本机环境：

```bash
java -version
mvn -version
node -v
pnpm -v
```

如果 Maven 构建提示 Java 或 Maven 版本不满足要求，请先升级对应工具。根工程已通过 Maven Enforcer 固化构建基线，避免低版本环境产生隐蔽问题。

应用模块引入 AgentScope 相关 BOM 时，会在模块内重新导入项目统一的 Spring Boot BOM，确保 Spring Boot、Spring Framework、Jackson 和 JUnit 等平台依赖仍跟随根工程的 `spring-boot.version`。如果启动期出现 `Could not find class [org.springframework.boot.thread.Threading]`，优先检查 `spring-boot` 与 `spring-boot-autoconfigure` 是否被业务 BOM 管理成了不同主版本。

## 3 分钟快速开始

### 1. 构建项目

在仓库根目录执行：

```bash
mvn clean package
```

只构建应用模块及其依赖时可以执行：

```bash
mvn -pl kong-voice-agent-app -am package
```

### 2. 启动后端

```bash
java -jar kong-voice-agent-app/target/kong-voice-agent-app-0.1.jar
```

启动成功后，默认监听端口为 `9877`，先用固定账号登录：

```bash
curl -X POST http://localhost:9877/api/auth/login -H "Content-Type: application/json" -d '{"username":"demo","password":"demo123456"}'
```

登录成功会返回本次登录的 `token`，例如：

```json
{
  "token": "00000000-0000-0000-0000-000000000000",
  "tokenType": "Bearer",
  "user": {
    "accountId": "demo-user",
    "username": "demo"
  }
}
```

WebSocket 地址需要把登录得到的 token 放到 query 参数中：

```text
ws://localhost:9877/ws/agent?token=<login-token>
```

### 3. 启动 React UI

新 UI 位于仓库根目录的 `ui/`，使用 pnpm 管理依赖：

```bash
cd ui
pnpm install
pnpm dev
```

默认访问：

```text
http://localhost:5173/
```

页面左侧是轻量会话栏，包含新对话、历史摘要、连接状态和账号入口；右侧是产品化聊天区，首屏会展示欢迎态和示例问题，底部固定输入框使用同一个主按钮负责发送和打断：空闲时显示发送，发送后立即切换为打断。使用默认账号 `demo` / `demo123456` 登录后，在左侧连接状态区点击连接，页面会通过 `ws://localhost:9877/ws/agent?token=<login-token>` 为当前会话建立 WebSocket；点击“新对话”会创建新的前端会话并为它打开新的后端 session，不会关闭其他仍在线的会话连接。React UI 会把会话列表、当前选中会话、消息快照、`sessionId` 和最近 `turnId` 写入浏览器 `localStorage`，刷新页面后可回看本地历史；切换会话会恢复该会话的本地记录，并在连接仍在线时继续复用原 WebSocket。随后可直接发送文本，也可以点击输入框左侧麦克风按钮开始采集，页面会把浏览器音频重采样为 16kHz 单声道 PCM16 并以二进制帧发送，停止录音时自动发送 `audio_end`；收到回复后可查看流式 Agent 文本、自动播放 TTS 音频、在助手文字区查看播报动效，或切换日间/夜间主题。

React UI 默认读取：

```text
VITE_AGENT_HTTP_BASE=http://localhost:9877
VITE_AGENT_WS_BASE=ws://localhost:9877
```

如需修改后端地址，可复制 `ui/.env.example` 为 `ui/.env.local` 后调整。

## 最小 WebSocket 示例

前端或脚本需要先登录，再用返回的 token 建立 WebSocket 连接并发送 JSON 文本消息验证链路：

```js
const loginResponse = await fetch("http://localhost:9877/api/auth/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username: "demo", password: "demo123456" })
});
const login = await loginResponse.json();
const socket = new WebSocket(`ws://localhost:9877/ws/agent?token=${encodeURIComponent(login.token)}`);

socket.addEventListener("open", () => {
  socket.send(JSON.stringify({
    type: "text",
    payload: {
      text: "你好，介绍一下你自己"
    }
  }));
});

socket.addEventListener("message", (event) => {
  console.log(JSON.parse(event.data));
});
```

常用上行消息：

```json
{
  "type": "ping",
  "payload": {
    "ts": 1776657600000
  }
}
```

```json
{
  "type": "interrupt",
  "payload": {
    "reason": "client_interrupt"
  }
}
```

```json
{
  "type": "audio_end",
  "payload": {}
}
```

更多字段和事件示例见 [docs/protocol.md](docs/protocol.md)。

当前 `agent_text_chunk` 除 `text`、`seq`、`isLast` 外，还会透传 `payload.rawResponse`，用于前端联调时查看底层 LLM 的原始响应内容。

## 音频输入说明

服务端默认按以下格式处理 WebSocket 二进制音频帧，实际值来自 `kong-voice-agent.audio` 配置：

- 采样率：`16000Hz`
- 声道：`mono`
- 位深与字节序：`16-bit PCM little-endian`
- 推荐分片：`20ms`，约 `640 bytes`

前端采集麦克风时，需要把浏览器 `AudioContext` 的 Float32 音频重采样到 16kHz，并转换为 PCM16 little-endian 后再发送。仓库内的 `ui/` React 界面已包含这条验证路径，可以作为前端接入参考。

React UI 也会消费 `tts_audio_chunk.payload.audioBase64` 并播放 TTS 音频。当前应用默认调用阿里云 DashScope Qwen-TTS，并把返回的音频字节作为 `tts_audio_chunk.payload.audioBase64` 下发；页面会优先尝试浏览器解码，如果后端替换为裸 PCM，页面才会按 PCM16 little-endian 和播放区采样率兜底播放。

如果修改 `sample-rate`、`channels` 或 `upload-chunk-ms`，前端采集和重采样逻辑也要同步调整；当前服务端不会在 WebSocket 中做音频格式协商。

## 项目结构

```text
.
├── docs/
│   ├── architecture.md             # 模块分层和端到端数据流
│   ├── features.md                 # 功能清单、实现状态和验收点
│   ├── frontend-integration.md     # 前端接入和联调说明
│   ├── protocol.md                 # WebSocket 协议、事件和示例
│   └── system-state.md             # 当前目标、约束和后续计划
├── kong-voice-agent-core/          # 可复用语音流水线、协议模型、扩展接口
├── kong-voice-agent-app/           # Spring Boot 启动入口、配置和应用侧集成
├── ui/                             # React 19 + Vite 7 + Tailwind CSS 4 对话界面
├── models/                         # 外置模型目录，默认查找 silero_vad.onnx
├── pom.xml                         # Maven 多模块根工程
└── README.md
```

两个模块的职责：

- `kong-voice-agent-core`：框架层，承载 Session、TurnManager、VAD、EOU、WebSocket 处理器、协议模型、ASR / LLM / TTS 抽象和 `VoicePipelineHook`，设计目标是被多个系统复用。
- `kong-voice-agent-app`：应用层示例，承载 Spring Boot 应用入口、`/ws/agent` 注册、运行配置和默认服务集成，用来演示如何接入 core。

## 配置入口

默认配置位于：

```text
kong-voice-agent-app/src/main/resources/application.yml
```

关键默认值：

```yaml
server:
  port: 9877

kong-voice-agent:
  auth:
    fixed-user:
      account-id: ${KONG_VOICE_AGENT_AUTH_FIXED_USER_ACCOUNT_ID:demo-user}
      username: ${KONG_VOICE_AGENT_AUTH_FIXED_USER_USERNAME:demo}
      password: ${KONG_VOICE_AGENT_AUTH_FIXED_USER_PASSWORD:demo123456}
  onnx:
    gpu-enabled: ${KONG_VOICE_AGENT_ONNX_GPU_ENABLED:true}
    gpu-device-id: ${KONG_VOICE_AGENT_ONNX_GPU_DEVICE_ID:0}
    fallback-to-cpu: ${KONG_VOICE_AGENT_ONNX_FALLBACK_TO_CPU:false}
  audio:
    sample-rate: 16000
    channels: 1
    sample-format: s16le
    upload-chunk-ms: 20
  asr:
    dashscope:
      api-key: ${KONG_VOICE_AGENT_DASHSCOPE_API_KEY:${DASHSCOPE_API_KEY:}}
      base-url: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      chat-completions-path: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_CHAT_COMPLETIONS_PATH:/chat/completions}
      model: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_MODEL:qwen3-asr-flash}
      language: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_LANGUAGE:zh}
      enable-itn: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_ENABLE_ITN:true}
      timeout-ms: ${KONG_VOICE_AGENT_ASR_DASHSCOPE_TIMEOUT_MS:15000}
  tts:
    dashscope:
      api-key: ${KONG_VOICE_AGENT_DASHSCOPE_API_KEY:${DASHSCOPE_API_KEY:}}
      base-url: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com}
      generation-path: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_GENERATION_PATH:/api/v1/services/aigc/multimodal-generation/generation}
      model: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_MODEL:qwen3-tts-flash}
      voice: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_VOICE:Cherry}
      language-type: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_LANGUAGE_TYPE:Chinese}
      streaming-enabled: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_STREAMING_ENABLED:true}
      timeout-ms: ${KONG_VOICE_AGENT_TTS_DASHSCOPE_TIMEOUT_MS:30000}
  vad:
    model-path: ${KONG_VOICE_AGENT_VAD_MODEL_PATH:file:models/silero_vad.onnx}
    speech-threshold: 0.6
    fallback-enabled: true
  eou:
    enabled: ${KONG_VOICE_AGENT_EOU_ENABLED:true}
    provider: livekit-multilingual
    model-path: ${KONG_VOICE_AGENT_EOU_MODEL_PATH:file:models/livekit-turn-detector/model_quantized.onnx}
    tokenizer-path: ${KONG_VOICE_AGENT_EOU_TOKENIZER_PATH:file:models/livekit-turn-detector/tokenizer.json}
    fallback-enabled: true
    default-threshold: 0.5
    min-silence-ms: 500
    max-silence-ms: 1600
    inference-timeout-ms: 300
    language: zh
```

`kong-voice-agent.auth.fixed-user` 是当前应用侧固定账号配置，默认只用于本地示例部署和前端联调。公开部署时必须通过环境变量覆盖默认密码：

```bash
KONG_VOICE_AGENT_AUTH_FIXED_USER_ACCOUNT_ID=your-account-id
KONG_VOICE_AGENT_AUTH_FIXED_USER_USERNAME=your-username
KONG_VOICE_AGENT_AUTH_FIXED_USER_PASSWORD=your-strong-password
```

登录成功签发的 token 只保存在当前服务进程内存中；应用重启后旧 token 全部失效，当前版本也不提供跨实例共享、过期时间刷新或撤销接口。

`kong-voice-agent.audio` 会绑定到运行时 `AudioFormatSpec`，用于每个新 session 的 PCM 缓冲区、pre-roll 缓冲区和每会话 ASR 适配器创建。

`IdUtils.snowflakeId()` 可生成 64 位趋势递增雪花 ID，`IdUtils.snowflakeIdStr()` 会返回对应的字符串形式；用户 turnId 使用字符串形式下发，调用方只应按相等性判断当前 turn。多实例部署时建议为每个实例显式分配 0 到 1023 之间的节点号：

```bash
KONG_VOICE_AGENT_SNOWFLAKE_NODE_ID=1
```

也可以通过 JVM 系统属性指定：

```bash
java -Dkong.voice-agent.snowflake.node-id=1 -jar kong-voice-agent-app/target/kong-voice-agent-app-0.1.jar
```

未配置节点号时，当前进程会随机选择一个节点号，适合本地单机验证；生产多实例应显式配置，避免实例间节点号碰撞。

`kong-voice-agent.onnx` 会统一影响默认 VAD 和默认 EOU 的 ONNX Runtime session。默认使用 CPU；需要尝试 CUDA GPU 时，运行前设置：

```bash
KONG_VOICE_AGENT_ONNX_GPU_ENABLED=true
KONG_VOICE_AGENT_ONNX_GPU_DEVICE_ID=0
KONG_VOICE_AGENT_ONNX_FALLBACK_TO_CPU=true
```

GPU 模式需要使用 ONNX Runtime GPU 原生包构建，并准备匹配的 CUDA 运行环境：

```bash
mvn clean package -Donnxruntime.artifactId=onnxruntime_gpu
```

如果 `fallback-to-cpu=true`，CUDA provider 不可用时会回退到 CPU session；如果设置为 `false`，GPU 初始化失败会按 VAD / EOU 自身的 `fallback-enabled` 策略处理。

如果需要指定 VAD 模型路径，可以设置环境变量：

```bash
KONG_VOICE_AGENT_VAD_MODEL_PATH=file:/absolute/path/to/silero_vad.onnx
```

没有模型文件时，项目仍可启动并使用 RMS fallback，适合先完成本地链路验证。

EOU 用于在 VAD 发现静音候选后判断“用户这句话是否已经说完”。默认 provider 是 LiveKit MultilingualModel 风格的本地 ONNX 实现，模型和 tokenizer 需要放在 `models/livekit-turn-detector/`；如果文件缺失且 `fallback-enabled=true`，系统会回到原静音端点行为。需要指定模型时可以设置：

```bash
KONG_VOICE_AGENT_EOU_MODEL_PATH=file:/absolute/path/to/model_quantized.onnx
KONG_VOICE_AGENT_EOU_TOKENIZER_PATH=file:/absolute/path/to/tokenizer.json
```

## DashScope ASR / TTS 联调

应用模块默认直接对接阿里云 DashScope：

- ASR：`qwen3-asr-flash`，在 `audio_end` 或端点提交时通过音频处理执行器异步提交，把累计 PCM 包装成 WAV，再以 Base64 Data URL 调用 OpenAI 兼容 `/chat/completions`。
- TTS：`qwen3-tts-flash`，按 turnId 累计 LLM 文本到句子边界或最后一个 chunk，默认通过 DashScope SSE 流式 multimodal generation 接口读取音频分片，并把每个分片作为 `tts_audio_chunk.payload.audioBase64` 下发。若关闭流式模式，则同样按句累计后一次性下发该句音频。

启动前需要配置 DashScope API Key：

```bash
export DASHSCOPE_API_KEY=sk-xxxx
```

Windows PowerShell：

```powershell
$env:DASHSCOPE_API_KEY="sk-xxxx"
```

也可以使用项目专用环境变量覆盖：

```bash
export KONG_VOICE_AGENT_DASHSCOPE_API_KEY=sk-xxxx
```

外部服务不可用、鉴权失败或返回空音频时会明确失败，不回退到假转写或假音频；ASR 提交失败会通过 WebSocket 下发 `error(code=asr_failed)`，TTS 下游失败会下发 `error(code=tts_failed)`，当前连接不会因为远端回调异常而中断。默认 DashScope TTS 已内置句子级文本积攒，未到句末的非末尾 LLM 片段不会立即合成，避免播放出现过短音频片段导致的不连贯。

常用配置项：

| 配置项 | 默认值 | 环境变量 |
| --- | --- | --- |
| `kong-voice-agent.asr.dashscope.api-key` | 空 | `KONG_VOICE_AGENT_DASHSCOPE_API_KEY` 或 `DASHSCOPE_API_KEY` |
| `kong-voice-agent.asr.dashscope.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_BASE_URL` |
| `kong-voice-agent.asr.dashscope.chat-completions-path` | `/chat/completions` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_CHAT_COMPLETIONS_PATH` |
| `kong-voice-agent.asr.dashscope.model` | `qwen3-asr-flash` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_MODEL` |
| `kong-voice-agent.asr.dashscope.language` | `zh` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_LANGUAGE` |
| `kong-voice-agent.asr.dashscope.enable-itn` | `true` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_ENABLE_ITN` |
| `kong-voice-agent.asr.dashscope.timeout-ms` | `15000` | `KONG_VOICE_AGENT_ASR_DASHSCOPE_TIMEOUT_MS` |
| `kong-voice-agent.tts.dashscope.api-key` | 空 | `KONG_VOICE_AGENT_DASHSCOPE_API_KEY` 或 `DASHSCOPE_API_KEY` |
| `kong-voice-agent.tts.dashscope.base-url` | `https://dashscope.aliyuncs.com` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_BASE_URL` |
| `kong-voice-agent.tts.dashscope.generation-path` | `/api/v1/services/aigc/multimodal-generation/generation` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_GENERATION_PATH` |
| `kong-voice-agent.tts.dashscope.model` | `qwen3-tts-flash` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_MODEL` |
| `kong-voice-agent.tts.dashscope.voice` | `Cherry` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_VOICE` |
| `kong-voice-agent.tts.dashscope.language-type` | `Chinese` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_LANGUAGE_TYPE` |
| `kong-voice-agent.tts.dashscope.streaming-enabled` | `true` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_STREAMING_ENABLED` |
| `kong-voice-agent.tts.dashscope.timeout-ms` | `30000` | `KONG_VOICE_AGENT_TTS_DASHSCOPE_TIMEOUT_MS` |

## 替换真实服务

当前 app 模块的 ASR 默认对接 DashScope Qwen-ASR，TTS 默认对接 DashScope Qwen-TTS，LLM 使用应用侧默认实现。后续接入其他生产服务时，优先通过 Spring Bean 覆盖这些接口：

- `StreamingAsrAdapterFactory`：为每个 session 创建独立的流式 ASR 实例
- `EouDetector`：替换整体 EOU 判断逻辑，可接入自研模型、云端 EOU 服务或规则引擎
- `EouHistoryProvider`：为默认 LiveKit EOU 实现按会话查询最近对话历史；core 不主动记录业务对话内容
- `LlmOrchestrator`：接入真实大模型流式生成
- `TtsOrchestrator`：接入真实 TTS 流式合成
- `VoicePipelineHook`：观察音频、文本、turn commit、LLM、TTS 和打断节点，适合做日志、审计、埋点和业务上下文记录

`LlmOrchestrator.stream` 和 `TtsOrchestrator.synthesizeStreaming` 的回调需要在方法返回前完成，或通过同步异常暴露失败；如果底层 SDK 是异步订阅模型，适配器应在接口方法内部等待完成。`StreamingAsrAdapter.cancelTurn` 和 `TtsOrchestrator.cancelTurn` 用于释放被打断 turn 的 PCM、待合成文本或远端流状态，长连接场景下建议真实实现显式清理资源。

应用默认 LLM 提示会要求模型使用用户输入语言回答；用户用中文提问时，应返回自然中文。若替换模型后仍出现中文问题英文回答，优先检查模型能力、对话模板和 `ai.model.model-name` 指向的实际模型。

WebSocket JSON 上行消息也可以通过注册 `WsTextMessageHandler` Bean 扩展新的业务 `type`。内置 `ping`、`interrupt`、`audio_end`、`text` 不允许覆盖，以保证公开协议稳定。

EOU 扩展示例：

```java
@Bean
public EouDetector customEouDetector() {
    return context -> new EouPrediction(true, 1.0, 0.5, "custom_rule", false);
}

@Bean
public EouHistoryProvider eouHistoryProvider(ChatHistoryService chatHistoryService) {
    return (sessionId, maxTurns) -> chatHistoryService.recentTurns(sessionId, maxTurns);
}
```

## 测试

执行全部测试：

```bash
mvn test
```

默认测试不加载真实大模型文件。需要验证 LiveKit EOU ONNX 模型资产时，先放好 `models/livekit-turn-detector/model_quantized.onnx` 和 `models/livekit-turn-detector/tokenizer.json`，再显式开启模型集成测试：

```bash
mvn -pl kong-voice-agent-core "-Dkong.voice-agent.model-tests=true" "-Dtest=MultilingualEouDetectorTest" test
```

需要严格确认 EOU 真实模型使用 CUDA 而不是 CPU 回退时，先安装 CUDA 12.x Toolkit 和 cuDNN 9.x，并确保 `cudart64_12.dll`、`cublas64_12.dll`、`cublasLt64_12.dll`、`cudnn64_9.dll` 能从 `PATH` 找到，然后执行：

```powershell
.\scripts\run-eou-cuda-test.ps1
```

如果 CUDA 或 cuDNN 的 `bin` 目录尚未加入系统 `PATH`，可以只对当前 PowerShell 进程指定：

```powershell
.\scripts\run-eou-cuda-test.ps1 -CudaBin "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.9\bin" -CudnnBin "C:\path\to\cudnn\bin"
```

这个脚本会使用 `onnxruntime_gpu`，并启用 `fallbackToCpu=false` 的严格 CUDA 测试；只要 CUDA provider 加载失败，测试就会失败，不会悄悄走 CPU。

当前测试重点覆盖：

- PCM 工具和音频缓冲
- Session 生命周期
- TurnManager 状态边界
- EOU 自动装配、prompt 构造和 endpointing 边界
- turn committed 后才进入 LLM
- interruption 后旧 turn 结果被丢弃
- audio_end 异步 ASR 提交、ASR 失败错误事件、空闲 interrupt no-op 和 turn 取消资源释放
- WebSocket 消息解析和处理器注册边界

后续新增协议、状态机、异步 turnId 隔离、打断流程或公开扩展点时，需要同步补充测试。

## 常见问题

### 启动时提示找不到 `models/silero_vad.onnx`

这是可接受的。当前配置允许模型缺失时回退到 RMS fallback；ASR 和 TTS 默认依赖 DashScope，需要先配置 `DASHSCOPE_API_KEY` 或 `KONG_VOICE_AGENT_DASHSCOPE_API_KEY`。

### 收到 `DashScope API Key 未配置`

先确认运行应用的同一个终端或进程环境里已经设置 `DASHSCOPE_API_KEY`，也可以设置 `KONG_VOICE_AGENT_DASHSCOPE_API_KEY`。如果使用 IDE 启动，需要把环境变量加到 Run Configuration 中。

### 收到 `DashScope Qwen-TTS 返回了空音频`

先确认 API Key 有效、模型名称和音色名称可用，并检查网络能访问 `https://dashscope.aliyuncs.com`。如果接口返回成功但没有 `output.audio.data` 或 `output.audio.url`，后端会下发 `error(code=tts_failed)`，前端应展示错误并停止当前 turn 的播放队列。

### 连接不上 WebSocket

请确认后端已经启动，并检查端口是否为 `9877`：

```text
ws://localhost:9877/ws/agent?token=<login-token>
```

如果 WebSocket 握手返回 401，请先调用 `POST /api/auth/login` 获取 token，并确认 URL 中的 `token` query 参数没有丢失或被截断。服务重启后内存 token 会失效，需要重新登录。如果修改过 `server.port`，React UI 的登录地址和 WebSocket 地址都需要同步修改。

### React UI 连接到了错误的后端地址

`ui/` 默认使用 `VITE_AGENT_HTTP_BASE=http://localhost:9877` 和 `VITE_AGENT_WS_BASE=ws://localhost:9877`。如果后端端口、域名或代理路径发生变化，请在 `ui/.env.local` 中覆盖这两个变量，然后重新执行 `pnpm dev`。

### 麦克风没有事件或没有 ASR final

先用文本输入验证后端链路，再检查浏览器是否授予麦克风权限。音频必须转换为服务端 `kong-voice-agent.audio` 配置的格式；默认是 16kHz / mono / PCM16 little-endian。如果采样率或格式不对，服务端无法按预期处理。

### 为什么 partial transcript 不会触发 LLM

当前版本明确不支持 preemptive。只有 turn committed 后才会启动 LLM 和 TTS，这样可以保证协议边界稳定，并避免用户还没说完时提前生成错误回复。

### 启动时报 `Could not find class [org.springframework.boot.thread.Threading]`

这是 Spring Boot 依赖版本不一致的典型表现。本项目要求 `spring-boot`、`spring-boot-autoconfigure` 和 Spring Framework 版本由根工程 `spring-boot.version` 统一管理；如果新增第三方 BOM，需要确认它没有把 Spring Boot 自动配置包提升到另一个主版本。可以执行下面命令检查：

```bash
mvn -pl kong-voice-agent-app dependency:tree -Dincludes=org.springframework.boot
```

正常情况下，`spring-boot` 和 `spring-boot-autoconfigure` 应该同为 `3.4.8`。

## 文档索引

- [docs/architecture.md](docs/architecture.md)：模块分层、数据流、文本/音频路径和打断流程图
- [docs/features.md](docs/features.md)：功能清单、当前实现状态和验收点
- [docs/protocol.md](docs/protocol.md)：WebSocket 上下行协议和 JSON 示例
- [docs/frontend-integration.md](docs/frontend-integration.md)：前端接入、麦克风 PCM 和联调说明
- [docs/system-state.md](docs/system-state.md)：跨轮开发状态、硬约束、待决问题和下一步计划

建议新手阅读顺序：

1. 先按本 README 跑通文本闭环
2. 再启动 `ui/` 的 React 对话界面验证登录、连接和文本对话
3. 然后阅读 `docs/architecture.md` 理解整体数据流
4. 再阅读 `docs/protocol.md` 理解消息字段
5. 最后阅读 `docs/features.md` 和 `docs/system-state.md` 理解系统边界与后续方向
