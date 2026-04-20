# Protocol

这份文档定义 WebSocket 协议的消息形态、方向、示例和约束。当前版本以 mock 闭环为优先，但协议本身要保持对真实 ASR / LLM / TTS 的可替换性。

## 基本约定

- 传输层：WebSocket
- 音频：二进制 PCM frame
- 控制、文本输入与状态：JSON 文本消息
- 下行消息都会携带 `sessionId` 和 `turnId`
- 上行控制消息当前最小字段是字符串 `type`，服务端以 WebSocket 连接定位 session
- 服务端内置支持 `ping`、`interrupt`、`audio_end` 和 `text`，业务方可以通过 Spring Bean 新增自定义 type
- 过期消息要按 `turnId` 丢弃
- 本版本不支持 preemptive，`turn committed` 之前不启动 LLM

## 上行协议

服务端对 JSON 文本消息采用策略注册表分发：先解析统一外壳，再按 `type`
查找对应的 `WsTextMessageHandler`。内置 type 保持稳定，业务自定义策略只能新增
type，不能覆盖内置 type；重复注册同一个 type 会在启动阶段失败，避免协议行为不确定。

### 1. `audio_chunk`

客户端发送二进制 PCM 数据。

- 默认格式：16kHz / mono / 16-bit PCM little-endian
- 默认建议每包 20ms
- 实际音频格式由服务端 `kong-voice-agent.audio` 配置决定，客户端必须发送与服务端配置一致的 PCM；当前协议不包含格式协商字段

示例：

```text
[binary pcm bytes]
```

### 2. `interrupt`

用户主动中断当前播报。

```json
{
  "type": "interrupt",
  "payload": {
    "reason": "user_speaking_again"
  }
}
```

### 3. `audio_end`

通知当前音频流结束。

```json
{
  "type": "audio_end",
  "payload": {}
}
```

### 4. `ping`

```json
{
  "type": "ping",
  "payload": {
    "ts": 1713580800000
  }
}
```

服务端会返回 `pong`，用于前端确认连接仍然可用。

### 5. `text`

客户端直接提交用户文本。服务端会为该文本创建新的 committed turn，跳过 VAD/ASR，直接进入 LLM/TTS。若 Agent 正在播报，文本输入会先打断旧 turn。

```json
{
  "type": "text",
  "payload": {
    "text": "你好，介绍一下你自己"
  }
}
```

### 6. 业务自定义 type

业务模块可以声明 `WsTextMessageHandler` Bean 扩展新的上行 JSON 文本消息类型。
自定义消息仍使用统一外壳，`payload` 在后端模型中以 `JsonNode` 保留，由业务策略自行解析和校验。
未知 type 会返回 `error`，错误码为 `bad_message`。

```json
{
  "type": "custom_event",
  "payload": {
    "value": "业务自定义内容"
  }
}
```

扩展示意：

```java
@Component
public class CustomEventWsTextMessageHandler implements WsTextMessageHandler {
    @Override
    public String type() {
        return "custom_event";
    }

    @Override
    public void handle(WsTextMessageContext context) {
        // 在这里解析 context.message().payload() 并执行业务逻辑。
    }
}
```

## 下行协议

### 1. `state_changed`

```json
{
  "type": "state_changed",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "state": "USER_SPEAKING",
    "reason": "speech"
  }
}
```

### 2. `asr_partial`

```json
{
  "type": "asr_partial",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "text": "mock partial 320ms"
  }
}
```

### 3. `asr_final`

音频输入时表示 ASR 最终结果；文本输入时表示已提交的用户文本，`payload.source` 为 `text`。

```json
{
  "type": "asr_final",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "text": "mock final transcript for turn 12 (900ms)",
    "source": "audio"
  }
}
```

### 4. `agent_thinking`

```json
{
  "type": "agent_thinking",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "transcript": "mock final transcript for turn 12 (900ms)"
  }
}
```

### 5. `agent_text_chunk`

```json
{
  "type": "agent_text_chunk",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "seq": 0,
    "text": "我已收到你的语音内容：",
    "isLast": false
  }
}
```

### 6. `tts_audio_chunk`

```json
{
  "type": "tts_audio_chunk",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "seq": 0,
    "isLast": false,
    "text": "我已收到你的语音内容：",
    "audioBase64": "TU9DS19QQ006..."
  }
}
```

### 7. `playback_stop`

```json
{
  "type": "playback_stop",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "reason": "interrupted"
  }
}
```

### 8. `turn_interrupted`

```json
{
  "type": "turn_interrupted",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "reason": "barge_in"
  }
}
```

### 9. `error`

```json
{
  "type": "error",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "code": "bad_message",
    "message": "Invalid JSON"
  }
}
```

### 10. `pong`

```json
{
  "type": "pong",
  "sessionId": "sess_001",
  "turnId": 12,
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "ok": true
  }
}
```

## 消息模型建议

建议所有 JSON 消息都遵循统一外壳：

```json
{
  "type": "state_changed",
  "sessionId": "sess_001",
  "turnId": 12,
  "payload": {},
  "timestamp": "2026-04-20T02:30:00Z"
}
```

如果某条消息字段较少，可以直接平铺；如果后续需要扩展，`payload` 是更稳妥的兼容做法。

服务端 Java 下行模型中，`AgentEvent.payload` 使用事件专属实体类承载，例如
`StateChangedPayload`、`AsrFinalPayload`、`AgentTextChunkPayload`、`TtsAudioChunkPayload`
和 `ErrorPayload`。这些实体统一实现普通接口 `AgentEventPayload`，接口不使用 sealed
限制，业务方可以自定义 payload 实现类。事件外壳的必填字段仍由 `AgentEvent`
统一定义，该实现约束只影响后端构造事件的类型安全，序列化后的 JSON 字段形态仍保持本协议示例中的 `payload` 对象。

服务端 Java 上行模型中，`WsMessage.type` 使用字符串，`WsMessage.payload` 使用
`JsonNode`，以便自定义 type 在协议边界被策略注册表处理，而不是在 JSON 反序列化阶段失败。

## 运行时规则

1. `partial transcript` 只能推进状态，不能触发 LLM。
2. `turn committed` 是 LLM 启动边界。
3. TTS 只能消费当前有效 `turnId`。
4. 用户重新开口时，旧 turn 必须立即失效。
5. mock 模式下返回的数据结构必须与真实模式一致，避免切换成本过高。
6. `text` 上行消息直接创建 committed turn，不经过 ASR partial 阶段，但仍必须等到该提交边界后才能启动 LLM/TTS。

## Mock 验证流程

1. 发送 `audio_chunk`
2. 观察 `state_changed` 到 `USER_SPEAKING`
3. 观察连续 `asr_partial`
4. 观察 `asr_final`
5. 观察 `agent_thinking`
6. 观察 `agent_text_chunk`
7. 观察 `tts_audio_chunk`
8. 在 agent speaking 时发送第二轮 `audio_chunk` 或 `interrupt`
9. 观察 `playback_stop` 和 `turn_interrupted`

## 文本验证流程

1. 发送 `text`
2. 观察 `state_changed` 到 `USER_TURN_COMMITTED`
3. 观察 `asr_final`，其中 `payload.source` 为 `text`
4. 观察 `agent_thinking`
5. 观察 `agent_text_chunk`
6. 观察 `tts_audio_chunk`

## 前端联调界面

面向前端接入的字段说明、状态建议和调试步骤维护在 `docs/frontend-integration.md`。`kong-voice-agent-app/frontend-demo.html` 提供一个静态调试界面，可直接连接 `/ws/agent`，发送文本、心跳、打断和麦克风 PCM。

## 不支持项

本版本不支持 `preemptive`，因此协议里不会定义“在 turn commit 之前提前生成回答”的消息语义。所有生成动作都必须等到 turn commit 之后再发生。
