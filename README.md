# Kong Voice Agent

Kong Voice Agent 是一个面向生产场景设计的 Java Voice Agent 后端框架，基于 Java 21、Spring Boot 3 和 WebSocket 实现，支持文本与 PCM 音频输入，内置 Session 与 turnId 隔离、VAD、流式 ASR、LLM、TTS、打断流程和前端联调台。

项目围绕可替换能力接口设计，业务侧可以自定义接入 ASR、LLM、TTS、VAD 等真实服务；当前版本同时提供可独立运行的 mock 闭环，便于快速验证协议、状态机和前后端联调流程。

## 当前能力

- WebSocket 端点：`ws://localhost:9877/ws/agent`
- 文本输入：直接发送 JSON 文本消息进入 Agent 回复链路
- 音频输入：默认发送 16kHz / mono / 16-bit PCM little-endian 二进制帧，格式可通过配置调整
- Session 隔离：每个 WebSocket 连接拥有独立 session
- Turn 隔离：所有异步结果通过 `turnId` 防止旧回复污染新对话
- VAD：优先加载 `models/silero_vad.onnx`，模型不可用时自动回退到 RMS fallback
- ASR / LLM / TTS：应用模块默认提供 mock 实现，可通过 Spring Bean 替换
- 打断：支持客户端主动 `interrupt`，也支持 Agent 播报中用户重新说话触发打断
- 前端联调台：`kong-voice-agent-app/frontend-demo.html`

## 环境要求

| 工具 | 要求 |
| --- | --- |
| JDK | 21 或更高版本 |
| Maven | 3.9.0 或更高版本 |
| 浏览器 | Chrome、Edge 或其他支持 WebSocket 与麦克风权限的现代浏览器 |

确认本机环境：

```bash
java -version
mvn -version
```

如果 Maven 构建提示 Java 或 Maven 版本不满足要求，请先升级对应工具。根工程已通过 Maven Enforcer 固化构建基线，避免低版本环境产生隐蔽问题。

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

启动成功后，默认监听端口为 `9877`，WebSocket 地址为：

```text
ws://localhost:9877/ws/agent
```

### 3. 打开前端联调台

直接用浏览器打开：

```text
kong-voice-agent-app/frontend-demo.html
```

页面里的默认地址已经是 `ws://localhost:9877/ws/agent`。点击连接后，可以先发送文本：

```text
你好，介绍一下你自己
```

正常情况下，事件区会陆续看到：

```text
state_changed
asr_final
agent_thinking
agent_text_chunk
tts_audio_chunk
```

文本链路跑通后，再尝试麦克风输入、`ping` 心跳和 `interrupt` 打断。

如果浏览器因为本地文件访问限制影响麦克风权限，可以在仓库根目录启动一个静态服务：

```bash
python -m http.server 5173
```

然后访问：

```text
http://localhost:5173/kong-voice-agent-app/frontend-demo.html
```

## 最小 WebSocket 示例

前端或脚本可以直接发送 JSON 文本消息验证链路：

```js
const socket = new WebSocket("ws://localhost:9877/ws/agent");

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

## 音频输入说明

服务端默认按以下格式处理 WebSocket 二进制音频帧，实际值来自 `kong-voice-agent.audio` 配置：

- 采样率：`16000Hz`
- 声道：`mono`
- 位深与字节序：`16-bit PCM little-endian`
- 推荐分片：`20ms`，约 `640 bytes`

前端采集麦克风时，需要把浏览器 `AudioContext` 的 Float32 音频重采样到 16kHz，并转换为 PCM16 little-endian 后再发送。仓库内的 `kong-voice-agent-app/frontend-demo.html` 已包含这条验证路径，可以作为前端接入参考。

如果修改 `sample-rate`、`channels` 或 `upload-chunk-ms`，前端采集和重采样逻辑也要同步调整；当前服务端不会在 WebSocket 中做音频格式协商。

## 项目结构

```text
.
├── docs/
│   ├── features.md                 # 功能清单、实现状态和验收点
│   ├── frontend-integration.md     # 前端接入和联调说明
│   ├── protocol.md                 # WebSocket 协议、事件和示例
│   └── system-state.md             # 当前目标、约束和后续计划
├── kong-voice-agent-core/          # 可复用语音流水线、协议模型、扩展接口
├── kong-voice-agent-app/           # Spring Boot 启动入口、配置、mock 能力、联调台
├── models/                         # 外置模型目录，默认查找 silero_vad.onnx
├── pom.xml                         # Maven 多模块根工程
└── README.md
```

两个模块的职责：

- `kong-voice-agent-core`：承载 Session、TurnManager、VAD、WebSocket 处理器、协议模型、ASR / LLM / TTS 抽象和 `VoicePipelineHook`。
- `kong-voice-agent-app`：承载 Spring Boot 应用入口、`/ws/agent` 注册、运行配置和默认 mock ASR / LLM / TTS。

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
  audio:
    sample-rate: 16000
    channels: 1
    sample-format: s16le
    upload-chunk-ms: 20
  vad:
    model-path: ${KONG_VOICE_AGENT_VAD_MODEL_PATH:file:models/silero_vad.onnx}
    speech-threshold: 0.6
    fallback-enabled: true
```

`kong-voice-agent.audio` 会绑定到运行时 `AudioFormatSpec`，用于每个新 session 的 PCM 缓冲区、pre-roll 缓冲区和每会话 ASR 适配器创建。

如果需要指定 VAD 模型路径，可以设置环境变量：

```bash
KONG_VOICE_AGENT_VAD_MODEL_PATH=file:/absolute/path/to/silero_vad.onnx
```

没有模型文件时，项目仍可启动并使用 RMS fallback，适合新手先完成 mock 闭环验证。

## 替换真实服务

当前 app 模块的 ASR、LLM、TTS 都是 mock 实现，主要用于本地验证协议和状态机。后续接入真实服务时，优先通过 Spring Bean 覆盖这些接口：

- `StreamingAsrAdapterFactory`：为每个 session 创建独立的流式 ASR 实例
- `LlmOrchestrator`：接入真实大模型流式生成
- `TtsOrchestrator`：接入真实 TTS 流式合成
- `VoicePipelineHook`：观察音频、文本、turn commit、LLM、TTS 和打断节点，适合做日志、审计、埋点和业务上下文记录

WebSocket JSON 上行消息也可以通过注册 `WsTextMessageHandler` Bean 扩展新的业务 `type`。内置 `ping`、`interrupt`、`audio_end`、`text` 不允许覆盖，以保证公开协议稳定。

## 测试

执行全部测试：

```bash
mvn test
```

当前测试重点覆盖：

- PCM 工具和音频缓冲
- Session 生命周期
- TurnManager 状态边界
- turn committed 后才进入 LLM
- interruption 后旧 turn 结果被丢弃
- WebSocket 消息解析和处理器注册边界

后续新增协议、状态机、异步 turnId 隔离、打断流程或公开扩展点时，需要同步补充测试。

## 常见问题

### 启动时提示找不到 `models/silero_vad.onnx`

这是可接受的。当前配置允许模型缺失时回退到 RMS fallback，仍然可以跑通文本、mock ASR、mock LLM 和 mock TTS 链路。

### 连接不上 WebSocket

请确认后端已经启动，并检查端口是否为 `9877`：

```text
ws://localhost:9877/ws/agent
```

如果修改过 `server.port`，前端联调台里的 WebSocket 地址也需要同步修改。

### 麦克风没有事件或没有 ASR final

先用文本输入验证后端链路，再检查浏览器是否授予麦克风权限。音频必须转换为服务端 `kong-voice-agent.audio` 配置的格式；默认是 16kHz / mono / PCM16 little-endian。如果采样率或格式不对，服务端无法按预期处理。

### 为什么 partial transcript 不会触发 LLM

当前版本明确不支持 preemptive。只有 turn committed 后才会启动 LLM 和 TTS，这样可以保证协议边界稳定，并避免用户还没说完时提前生成错误回复。

## 文档索引

- [docs/features.md](docs/features.md)：功能清单、当前实现状态和验收点
- [docs/protocol.md](docs/protocol.md)：WebSocket 上下行协议和 JSON 示例
- [docs/frontend-integration.md](docs/frontend-integration.md)：前端接入、麦克风 PCM 和联调说明
- [docs/system-state.md](docs/system-state.md)：跨轮开发状态、硬约束、待决问题和下一步计划

建议新手阅读顺序：

1. 先按本 README 跑通文本闭环
2. 再用 `frontend-demo.html` 验证麦克风和打断
3. 然后阅读 `docs/protocol.md` 理解消息字段
4. 最后阅读 `docs/features.md` 和 `docs/system-state.md` 理解系统边界与后续方向
