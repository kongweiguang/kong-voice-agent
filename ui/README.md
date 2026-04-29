# Kong Voice Agent UI

`ui/` 是 Kong Voice Agent 的 React 对话界面，使用 pnpm 管理依赖。

## 技术栈

| 类别 | 技术 | 版本 |
| --- | --- | --- |
| 框架 | React + TypeScript | React 19、TS 5.9 |
| 构建 | Vite | 7.x |
| 路由 | React Router | v7 |
| UI 组件库 | Shadcn UI 风格组件 + Radix UI | 最新兼容版本 |
| 样式 | Tailwind CSS | v4 |
| 图标 | Lucide React | 最新 |
| 状态管理 | React useState | 无全局状态库 |
| HTTP 客户端 | 原生 fetch 封装 | 无额外客户端库 |

## 启动

```bash
pnpm install
pnpm dev
```

默认访问：

```text
http://localhost:5173/
```

## 配置

默认连接本地后端：

```text
VITE_AGENT_HTTP_BASE=http://localhost:9877
VITE_AGENT_WS_BASE=ws://localhost:9877
```

需要修改时，复制 `.env.example` 为 `.env.local` 后调整。

## 验证

```bash
pnpm lint
pnpm build
```

当前界面采用产品化聊天布局，支持轻量会话侧栏、移动端覆盖式侧栏、首屏欢迎态、底部固定输入、固定账号登录、WebSocket 连接、麦克风 PCM 输入、停止录音自动 `audio_end`、发送/打断一体主按钮、TTS 自动播放和助手文字区播报动效。每个前端会话拥有独立 WebSocket；点击“新对话”会为新会话建立新的后端 session，切换会话不会断开其他在线 WebSocket。顶部切换 `WS PCM / WebRTC` 时，会同步更新当前会话的传输模式，关闭旧音频运行态，并在当前会话已经在线时按新模式自动重连。会话列表、当前选中会话、消息快照、`sessionId` 和最近 `turnId` 会保存到浏览器 `localStorage`，刷新页面后可以回看本地历史。
