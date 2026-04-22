# Protocol

这份文档定义 WebSocket 协议的消息形态、方向、示例和约束。当前版本以 mock 闭环为优先，但协议本身要保持对真实 ASR / LLM / TTS 的可替换性。

## 基本约定

- 传输层：WebSocket
- 登录接口：`POST /api/auth/login`
- WebSocket 连接地址：`/ws/agent?token=<login-token>`
- 音频：二进制 PCM frame
- 控制、文本输入与状态：JSON 文本消息
- 下行消息都会携带 `sessionId` 和 `turnId`；尚未创建用户 turn 时 `turnId` 为 `null`，创建后为服务端雪花 ID 字符串
- 上行控制消息当前最小字段是字符串 `type`，服务端以 WebSocket 连接定位 session
- 服务端内置支持 `ping`、`interrupt`、`audio_end` 和 `text`，业务方可以通过 Spring Bean 新增自定义 type
- 过期消息要按 `turnId` 做字符串相等性判断后丢弃，不要依赖数值自增或大小比较
- 本版本不支持 preemptive，`turn committed` 之前不启动 LLM
- WebSocket 握手阶段必须携带登录得到的 `token` query 参数；缺失或无效时服务端返回 401，不创建 session

## 登录协议

当前版本提供应用侧固定账号登录，用于本地 mock 闭环和前端联调。默认账号来自 `kong-voice-agent.auth.fixed-user`：

| 配置项 | 默认值 | 环境变量 |
| --- | --- | --- |
| `account-id` | `demo-user` | `KONG_VOICE_AGENT_AUTH_FIXED_USER_ACCOUNT_ID` |
| `username` | `demo` | `KONG_VOICE_AGENT_AUTH_FIXED_USER_USERNAME` |
| `password` | `demo123456` | `KONG_VOICE_AGENT_AUTH_FIXED_USER_PASSWORD` |

公开部署时必须覆盖默认密码，避免使用示例账号暴露服务。

### `POST /api/auth/login`

请求体：

```json
{
  "username": "demo",
  "password": "demo123456"
}
```

成功响应：

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

认证失败返回 HTTP 401：

```json
{
  "code": "unauthorized",
  "message": "账号或密码错误"
}
```

`token` 是服务端生成的 UUID 字符串，只保存在当前 JVM 进程内存中。服务重启后旧 token 全部失效；当前版本不提供持久化、跨实例共享、主动撤销、过期刷新或 Bearer Header WebSocket 鉴权。

## 上行协议

服务端对 JSON 文本消息采用策略注册表分发：先解析统一外壳，再按 `type`
查找对应的 `WsTextMessageHandler`。内置 type 保持稳定，业务自定义策略只能新增
type，不能覆盖内置 type；重复注册同一个 type 会在启动阶段失败，避免协议行为不确定。

所有 WebSocket 上行消息都必须在登录后发送。客户端应先调用 `POST /api/auth/login`，再用返回的 `token` 连接：

```text
ws://localhost:9877/ws/agent?token=<login-token>
```

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
  "turnId": "739251562187575296",
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "state": "USER_SPEAKING",
    "reason": "speech"
  }
}
```

`payload.reason` 除了 `speech`、`speech_started`、`silence_candidate`、`speech_resumed` 等状态机原因外，还可能出现 EOU 相关值：

- `eou_detected`：语义 EOU 判断当前用户话语已经结束。
- `eou_waiting`：语义 EOU 判断用户可能还会继续说，服务端继续等待。
- `eou_max_silence_fallback`：EOU 持续未确认结束，但静音超过最大等待窗口，服务端兜底提交。
- `eou_unavailable_fallback`：EOU 模型不可用，服务端按静音策略兜底提交。

### 2. `asr_partial`

```json
{
  "type": "asr_partial",
  "sessionId": "sess_001",
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "text": "你好，介绍一下你自己",
    "source": "audio"
  }
}
```

### 4. `agent_thinking`

```json
{
  "type": "agent_thinking",
  "sessionId": "sess_001",
  "turnId": "739251562187575296",
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "transcript": "你好，介绍一下你自己"
  }
}
```

### 5. `agent_text_chunk`

```json
{
  "type": "agent_text_chunk",
  "sessionId": "sess_001",
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
  "timestamp": "2026-04-20T02:30:00Z",
  "payload": {
    "seq": 0,
    "isLast": false,
    "text": "我已收到你的语音内容：",
    "audioBase64": "TU9DS19QQ006..."
  }
}
```

`audioBase64` 当前只承载 TTS 服务返回的原始字节，不额外携带格式元数据。应用默认调用 DashScope Qwen-TTS，并透传返回音频字节，React UI 会优先尝试浏览器解码播放；如果业务把 TTS 返回格式改成裸 PCM，前端可再按自身约定 fallback。跨 turn 播放仍必须按 `turnId` 相等性过滤。

### 7. `playback_stop`

```json
{
  "type": "playback_stop",
  "sessionId": "sess_001",
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
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
  "turnId": "739251562187575296",
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

1. `partial transcript` 只能推进状态，不能触发 LLM；当前默认 DashScope Qwen-ASR 同步适配器不生成假 partial。
2. 音频输入下，VAD 发现静音候选后可以通过 EOU 判断是否提交；EOU 只影响提交时机，不改变消息结构。
3. `turn committed` 是 LLM 启动边界。
4. TTS 只能消费当前有效 `turnId`，服务端通过字符串相等性隔离当前 turn。
5. 用户重新开口时，旧 turn 必须立即失效。
6. 当前 app 默认直接对接 DashScope Qwen-ASR 和 Qwen-TTS，API Key 缺失、服务不可用或返回空音频时明确失败，不回退到假转写或假音频。
7. `text` 上行消息直接创建 committed turn，不经过 ASR partial 阶段，但仍必须等到该提交边界后才能启动 LLM/TTS。
8. WebSocket token 只在当前进程内存中校验；服务重启、token 缺失或 token 无效时，客户端必须重新登录后再连接。

LLM/TTS 位于异步下游回调中，运行期失败必须转换为下行 `error` 事件，不能让异常继续冒泡到 Reactor 订阅线程。当前 TTS 合成失败使用 `code=tts_failed`，LLM 启动或同步调用失败使用 `code=llm_failed`；客户端收到这类错误后应结束当前 turn 的思考或播放状态，并等待用户重新输入。

LLM 文本可以继续按 token 或短片段流式下发 `agent_text_chunk`。当前流水线会把每个非空 LLM 文本片段提交给 TTS；默认 DashScope Qwen-TTS 适配器会按 turnId 累计文本，遇到句子边界或最后一个 chunk 后再启动合成。`kong-voice-agent.tts.dashscope.streaming-enabled=true` 时，适配器通过 DashScope SSE 流式接口逐块读取音频并立即下发多个 `tts_audio_chunk`；关闭后同样按句累计，但每句只下发一个非流式音频块。

## DashScope 验证流程

默认 ASR / TTS 使用阿里云 DashScope。启动应用前先配置：

```bash
export DASHSCOPE_API_KEY=sk-xxxx
```

Windows PowerShell：

```powershell
$env:DASHSCOPE_API_KEY="sk-xxxx"
```

默认配置会访问 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` 和 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`。如果需要切换模型、端点或音色，修改 `kong-voice-agent.asr.dashscope.*` 和 `kong-voice-agent.tts.dashscope.*`。

1. 调用 `POST /api/auth/login` 获取 token
2. 连接 `/ws/agent?token=<login-token>`
3. 发送 `audio_chunk`
4. 观察 `state_changed` 到 `USER_SPEAKING`
5. 发送 `audio_end` 或等待端点提交
6. 观察 `asr_final`
7. 观察 `agent_thinking`
8. 观察 `agent_text_chunk`
9. 观察 `tts_audio_chunk`
10. 在 agent speaking 时发送第二轮 `audio_chunk` 或 `interrupt`
11. 观察 `playback_stop` 和 `turn_interrupted`

## 文本验证流程

1. 调用 `POST /api/auth/login` 获取 token
2. 连接 `/ws/agent?token=<login-token>`
3. 发送 `text`
4. 观察 `state_changed` 到 `USER_TURN_COMMITTED`
5. 观察 `asr_final`，其中 `payload.source` 为 `text`
6. 观察 `agent_thinking`
7. 观察 `agent_text_chunk`
8. 观察 `tts_audio_chunk`

## 前端联调界面

面向前端接入的字段说明、状态建议和调试步骤维护在 `docs/frontend-integration.md`。仓库根目录的 `ui/` React 界面提供当前前端联调入口，流程是先登录，再连接 `/ws/agent?token=<login-token>`，随后发送文本、心跳、打断和麦克风 PCM。一个前端对话对应一条 WebSocket 连接和一个后端 session，点击“新对话”应关闭旧连接并建立新连接；React UI 会把会话列表和消息快照保存到浏览器 `localStorage`，但这只是本地回看记录，不代表后端 session 可跨连接复用。

## 不支持项

本版本不支持 `preemptive`，因此协议里不会定义“在 turn commit 之前提前生成回答”的消息语义。所有生成动作都必须等到 turn commit 之后再发生。
