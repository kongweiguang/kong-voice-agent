# 前端对接说明

这份文档面向 Web 前端联调，说明如何连接 voice agent 后端、发送文本或音频、处理下行事件，以及如何使用 `kong-voice-agent-app/frontend-demo.html` 做手工验证。

## 接入信息

| 项 | 值 |
| --- | --- |
| WebSocket 地址 | `ws://localhost:9877/ws/agent` |
| JSON 消息方向 | 客户端到服务端、服务端到客户端 |
| 音频消息方向 | 客户端以 WebSocket 二进制帧发送 PCM |
| 音频格式 | 16kHz / mono / 16-bit PCM little-endian |
| 推荐音频分片 | 20ms，约 640 bytes |
| 文本入口 | `{"type":"text","payload":{"text":"..."}}` |

服务端以每个 WebSocket 连接创建一个独立 session。前端无需在上行消息里传 `sessionId`，但必须在下行消息里按 `sessionId` 和 `turnId` 维护显示、播放和过期消息丢弃逻辑。JSON 上行消息按字符串 `type` 进入服务端策略注册表，内置支持 `ping`、`interrupt`、`audio_end` 和 `text`；业务后端可以新增自定义 type，但不能覆盖这些内置 type。

## 快速验证

1. 启动后端：

```bash
mvn -pl kong-voice-agent-app -am package
java -jar kong-voice-agent-app/target/kong-voice-agent-app-0.1.jar
```

2. 打开 `kong-voice-agent-app/frontend-demo.html`。
3. 使用默认地址 `ws://localhost:9877/ws/agent` 点击连接。
4. 发送文本 `你好，介绍一下你自己`。
5. 观察事件区依次出现 `state_changed`、`asr_final`、`agent_thinking`、`agent_text_chunk`、`tts_audio_chunk`。

如果浏览器阻止麦克风权限，请用本地静态服务打开页面，例如在仓库根目录执行：

```bash
python -m http.server 5173
```

然后访问 `http://localhost:5173/kong-voice-agent-app/frontend-demo.html`。文本联调通常直接打开 HTML 文件也能工作。

## 上行消息

### 文本对话

文本输入会直接创建一个已提交的用户 turn，跳过 VAD/ASR partial 阶段，随后进入 LLM/TTS。

```json
{
  "type": "text",
  "payload": {
    "text": "你好，介绍一下你自己"
  }
}
```

### 心跳

```json
{
  "type": "ping",
  "payload": {
    "ts": 1776657600000
  }
}
```

服务端返回 `pong`：

```json
{
  "type": "pong",
  "sessionId": "sess_001",
  "turnId": 0,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "ok": true
  }
}
```

### 主动打断

```json
{
  "type": "interrupt",
  "payload": {
    "reason": "client_interrupt"
  }
}
```

当前实现会以服务端固定原因 `client_interrupt` 处理打断。前端可保留 `payload.reason` 作为本地日志字段，但不要依赖服务端逐字回传该值。

### 音频结束

```json
{
  "type": "audio_end",
  "payload": {}
}
```

音频流结束后，服务端会尝试提交当前音频 turn 并输出 final transcript。

### PCM 二进制帧

麦克风输入需要转换为 16kHz 单声道 PCM16 little-endian，并通过 WebSocket 二进制消息发送。

```js
socket.send(pcmArrayBuffer);
```

前端实现建议：

- 使用 `navigator.mediaDevices.getUserMedia({ audio: true })` 获取麦克风。
- 使用 `AudioContext` 读取 Float32 PCM。
- 将输入采样率重采样到 16000Hz。
- 将 Float32 范围 `[-1, 1]` 裁剪并写入 `Int16Array`。
- 每 20ms 左右发送一个二进制分片。

## 下行消息

所有 JSON 下行消息都使用统一外壳：

```json
{
  "type": "agent_text_chunk",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {}
}
```

前端应按 `type` 分发事件，并按 `turnId` 处理 UI 与播放队列。

| 类型 | 用途 | 前端建议 |
| --- | --- | --- |
| `state_changed` | 状态切换 | 更新会话状态、调试状态条 |
| `asr_partial` | 流式中间转写 | 只展示临时字幕，不触发业务提交 |
| `asr_final` | 最终用户输入 | 固化用户气泡或转写结果 |
| `agent_thinking` | Agent 开始生成 | 展示思考中状态 |
| `agent_text_chunk` | Agent 文本片段 | 按 `seq` 追加到当前 Agent 气泡 |
| `tts_audio_chunk` | Agent 音频片段 | 按 `seq` 播放或排队；mock 音频仅用于链路验证 |
| `playback_stop` | 停止旧播报 | 清空当前播放队列 |
| `turn_interrupted` | 旧 turn 被打断 | 标记旧 turn 失效 |
| `error` | 协议或运行错误 | 展示错误并写入日志 |
| `pong` | 心跳响应 | 更新连接延迟或在线状态 |

## turnId 处理规则

`turnId` 是前端处理并发与打断的关键字段。

1. 前端收到更大的 `turnId` 后，应把旧 turn 的临时字幕、思考中状态和待播放音频视为过期。
2. 对于 `agent_text_chunk` 和 `tts_audio_chunk`，只追加或播放当前有效 `turnId` 的内容。
3. 收到 `playback_stop` 或 `turn_interrupted` 时，应立即停止旧音频并清理队列。
4. `asr_partial` 只能展示为临时状态，不能当作最终用户输入提交给 UI 业务层。

## 推荐前端状态模型

```ts
type AgentRuntimeState =
  | "DISCONNECTED"
  | "CONNECTED"
  | "USER_SPEAKING"
  | "USER_TURN_COMMITTED"
  | "AGENT_THINKING"
  | "AGENT_SPEAKING"
  | "INTERRUPTED";

interface AgentEventEnvelope<T = Record<string, unknown>> {
  type: string;
  sessionId: string;
  turnId: number;
  timestamp: string;
  payload: T;
}
```

前端不需要完全复刻服务端状态机，但建议至少维护：

- `connected`：WebSocket 是否连接。
- `sessionId`：最近一次下行事件携带的 session。
- `currentTurnId`：当前有效 turn。
- `partialText`：正在变化的 ASR 临时文本。
- `finalUserText`：已提交用户文本。
- `agentText`：当前 Agent 文本聚合。
- `audioQueue`：当前 turn 的 TTS 音频队列。

## JavaScript 最小示例

```js
const socket = new WebSocket("ws://localhost:9877/ws/agent");

socket.addEventListener("open", () => {
  socket.send(JSON.stringify({ type: "ping", payload: { ts: Date.now() } }));
});

socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  switch (message.type) {
    case "asr_partial":
      renderPartial(message.payload.text);
      break;
    case "asr_final":
      renderUserText(message.payload.text);
      break;
    case "agent_text_chunk":
      appendAgentText(message.turnId, message.payload.text);
      break;
    case "playback_stop":
    case "turn_interrupted":
      stopPlayback(message.turnId);
      break;
    default:
      logEvent(message);
  }
});

function sendText(text) {
  socket.send(JSON.stringify({ type: "text", payload: { text } }));
}
```

## 错误处理

常见错误：

- `bad_message`：JSON 格式错误、缺少 `type`、缺少 `payload.text`。
- WebSocket 断开：前端应停止采集麦克风并禁用发送按钮。
- 音频格式错误：服务端当前按固定 PCM 格式处理，前端必须完成重采样和 PCM16 转换。

建议前端把最近 100 条事件保留在调试日志里，联调时按时间、`type`、`turnId` 和 `payload` 排查。

## 联调验收清单

- [ ] WebSocket 可以连接并收到初始 `state_changed(IDLE)`。
- [ ] `ping` 能收到 `pong`。
- [ ] 文本输入能收到 `asr_final(source=text)`。
- [ ] Agent 文本能按 chunk 聚合显示。
- [ ] `interrupt` 能触发旧 turn 停止。
- [ ] 麦克风输入能产生 `asr_partial`。
- [ ] `audio_end` 后能产生 `asr_final(source=audio)`。
- [ ] 收到新 `turnId` 后旧音频不会继续播放。
