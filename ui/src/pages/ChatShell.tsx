import { FormEvent, KeyboardEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Bot,
  CheckCircle2,
  ChevronRight,
  History,
  Loader2,
  LogIn,
  LogOut,
  Menu,
  MessageSquarePlus,
  Mic,
  MicOff,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Plug,
  RefreshCw,
  Send,
  Sparkles,
  Square,
  Sun,
  UserRound,
  Volume2,
  Wifi,
  WifiOff,
  X,
} from "lucide-react";
import {
  AgentEventEnvelope,
  buildAgentWsUrl,
  loginAgent,
  LoginResponse,
  sendWsJson,
} from "@/api/agentClient";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

/** 对话消息角色。 */
type MessageRole = "user" | "assistant" | "system";

/** 对话消息模型，用于右侧消息流渲染。 */
interface ChatMessage {
  /** 前端生成的稳定消息 id。 */
  id: string;
  /** 消息归属角色。 */
  role: MessageRole;
  /** 消息文本内容。 */
  content: string;
  /** 后端 turn id，系统消息允许为空。 */
  turnId?: string | null;
  /** 消息是否仍在流式生成。 */
  streaming?: boolean;
  /** TTS 音频是否正在播放这条助手消息。 */
  speaking?: boolean;
  /** 消息创建时间。 */
  createdAt: Date;
}

/** 历史会话条目，用于左侧列表展示。 */
interface ConversationSummary {
  /** 历史项唯一标识。 */
  id: string;
  /** 历史项标题。 */
  title: string;
  /** 最近一条消息摘要。 */
  preview: string;
  /** 最近更新时间文案。 */
  updatedAt: string;
  /** 是否为当前选中会话。 */
  active?: boolean;
}

/** 浏览器本地持久化的前端会话记录。 */
interface ConversationRecord extends ConversationSummary {
  /** 后端返回的 sessionId，用于回看时确认这条记录对应的历史连接。 */
  sessionId?: string | null;
  /** 当前会话最近一次活跃 turnId。 */
  currentTurnId?: string | null;
  /** 该会话的本地消息快照。 */
  messages: ChatMessage[];
  /** 会话创建时间戳。 */
  createdAtMs: number;
  /** 会话最近更新时间戳。 */
  updatedAtMs: number;
}

/** WebSocket 连接状态。 */
type ConnectionState = "disconnected" | "connecting" | "connected";

/** TTS 播放队列项，按同一 turn 内的 seq 顺序播放。 */
interface TtsPlaybackItem {
  /** 音频所属 turn id。 */
  turnId: string;
  /** 同一 turn 内的 TTS 序号。 */
  seq: number;
  /** 后端返回的 base64 音频字节。 */
  audioBase64: string;
}

/** 登录表单初始值。 */
const DEFAULT_LOGIN_FORM = {
  username: "demo",
  password: "demo123456",
};

/** 首屏建议问题，帮助用户快速开始本地联调。 */
const STARTER_PROMPTS = [
  "你好，介绍一下你自己",
  "现在的连接状态正常吗？",
  "请用一句话说明 voice agent 的流程",
];

/** React UI 上传给后端的默认麦克风采样率，需要与服务端默认音频配置一致。 */
const MICROPHONE_TARGET_SAMPLE_RATE = 16000;

/** 本地会话列表持久化 key。 */
const CONVERSATION_STORAGE_KEY = "kong-voice-agent.conversations.v1";

/** 当前选中会话持久化 key。 */
const ACTIVE_CONVERSATION_STORAGE_KEY = "kong-voice-agent.active-conversation.v1";

/** 主对话工作台，负责登录、连接、发送文本和渲染产品化 AI 对话界面。 */
export function ChatShell() {
  const initialConversations = useMemo(() => loadStoredConversations(), []);
  const initialActiveConversation = useMemo(
    () => resolveInitialActiveConversation(initialConversations),
    [initialConversations],
  );
  const [theme, setTheme] = useState<"light" | "dark">("light");
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginForm, setLoginForm] = useState(DEFAULT_LOGIN_FORM);
  const [login, setLogin] = useState<LoginResponse | null>(null);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [loginLoading, setLoginLoading] = useState(false);
  const [connectionState, setConnectionState] = useState<ConnectionState>("disconnected");
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [conversations, setConversations] = useState<ConversationRecord[]>(initialConversations);
  const [activeConversationId, setActiveConversationId] = useState(initialActiveConversation.id);
  const [sessionId, setSessionId] = useState<string | null>(initialActiveConversation.sessionId ?? null);
  const [currentTurnId, setCurrentTurnId] = useState<string | null>(initialActiveConversation.currentTurnId ?? null);
  const [playbackStatus, setPlaybackStatus] = useState("等待 TTS");
  const [turnActive, setTurnActive] = useState(false);
  const [recording, setRecording] = useState(false);
  const [audioInputStatus, setAudioInputStatus] = useState("麦克风未开启");
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>(initialActiveConversation.messages);
  const socketRef = useRef<WebSocket | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const currentAudioSourceRef = useRef<AudioBufferSourceNode | null>(null);
  const microphoneContextRef = useRef<AudioContext | null>(null);
  const microphoneStreamRef = useRef<MediaStream | null>(null);
  const microphoneSourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const microphoneProcessorRef = useRef<ScriptProcessorNode | null>(null);
  const microphoneMuteRef = useRef<GainNode | null>(null);
  const recordingRef = useRef(false);
  const playbackQueueRef = useRef<TtsPlaybackItem[]>([]);
  const playingAudioRef = useRef(false);
  const currentTurnIdRef = useRef<string | null>(null);
  const invalidTurnIdsRef = useRef(new Set<string>());

  /** 当前是否已经建立可发送消息的 WebSocket 连接。 */
  const connected = connectionState === "connected";

  /** 左侧会话列表，全部来自浏览器 localStorage 和当前运行态。 */
  const history = useMemo(
    () =>
      conversations
        .slice()
        .sort((left, right) => right.updatedAtMs - left.updatedAtMs)
        .map((conversation) => ({
          id: conversation.id,
          title: conversation.title,
          preview: conversation.preview,
          updatedAt: formatConversationTime(conversation.updatedAtMs),
          active: conversation.id === activeConversationId,
        })),
    [activeConversationId, conversations],
  );

  /** 连接状态对应的状态栏文案。 */
  const connectionLabel = useMemo(() => {
    if (connectionState === "connected") {
      return "已连接";
    }
    if (connectionState === "connecting") {
      return "连接中";
    }
    return "未连接";
  }, [connectionState]);

  /** 当前是否还停留在产品化欢迎空状态。 */
  const emptyConversation = messages.length === 0;

  /** 停止麦克风采集，必要时通知后端提交当前音频 turn。 */
  const stopMicrophone = useCallback((sendAudioEnd: boolean) => {
    recordingRef.current = false;
    setRecording(false);

    if (microphoneProcessorRef.current) {
      microphoneProcessorRef.current.onaudioprocess = null;
      microphoneProcessorRef.current.disconnect();
    }
    microphoneMuteRef.current?.disconnect();
    microphoneSourceRef.current?.disconnect();
    microphoneStreamRef.current?.getTracks().forEach((track) => track.stop());
    microphoneContextRef.current?.close().catch(() => {
      // 浏览器可能已经关闭音频上下文，清理阶段忽略即可。
    });

    microphoneProcessorRef.current = null;
    microphoneMuteRef.current = null;
    microphoneSourceRef.current = null;
    microphoneStreamRef.current = null;
    microphoneContextRef.current = null;
    setAudioInputStatus("麦克风未开启");

    if (sendAudioEnd && socketRef.current?.readyState === WebSocket.OPEN) {
      sendWsJson(socketRef.current, "audio_end", {});
      setTurnActive(true);
      setMessages((current) => [...current, systemMessage("麦克风已停止，已发送 audio_end。")]);
    }
  }, []);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
  }, [theme]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages]);

  useEffect(() => {
    currentTurnIdRef.current = currentTurnId;
  }, [currentTurnId]);

  useEffect(() => {
    persistConversations(conversations);
  }, [conversations]);

  useEffect(() => {
    persistActiveConversationId(activeConversationId);
  }, [activeConversationId]);

  useEffect(() => {
    const syncTimer = window.setTimeout(() => {
      setConversations((current) =>
        current.map((conversation) =>
          conversation.id === activeConversationId
            ? {
                ...conversation,
                sessionId,
                currentTurnId,
                messages: sanitizeMessagesForStorage(messages),
                preview: buildConversationPreview(messages, conversation.preview),
                updatedAtMs: Date.now(),
              }
            : conversation,
        ),
      );
    }, 0);
    return () => window.clearTimeout(syncTimer);
  }, [activeConversationId, currentTurnId, messages, sessionId]);

  useEffect(() => {
    recordingRef.current = recording;
  }, [recording]);

  useEffect(() => {
    return () => {
      stopMicrophone(false);
      socketRef.current?.close();
      playbackQueueRef.current = [];
      if (currentAudioSourceRef.current) {
        try {
          currentAudioSourceRef.current.stop();
        } catch {
          // 页面卸载时音频节点可能已经结束，忽略即可。
        }
      }
      currentAudioSourceRef.current = null;
      playingAudioRef.current = false;
    };
  }, [stopMicrophone]);

  /** 切换浅色和深色主题。 */
  function toggleTheme() {
    setTheme((current) => (current === "light" ? "dark" : "light"));
  }

  /** 更新登录表单字段。 */
  function updateLoginField(field: keyof typeof DEFAULT_LOGIN_FORM, value: string) {
    setLoginForm((current) => ({ ...current, [field]: value }));
  }

  /** 提交登录表单并保存后端返回的 token。 */
  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoginLoading(true);
    setLoginError(null);

    try {
      const result = await loginAgent(loginForm);
      setLogin(result);
      setLoginOpen(false);
      setMessages((current) => [
        ...current,
        systemMessage(`已登录为 ${result.user.username}，可以连接后端 WebSocket。`),
      ]);
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : "登录失败");
    } finally {
      setLoginLoading(false);
    }
  }

  /** 注销当前用户并关闭已建立的 WebSocket 连接。 */
  function handleLogout() {
    socketRef.current?.close();
    socketRef.current = null;
    setLogin(null);
    setConnectionState("disconnected");
    setSessionId(null);
    setCurrentTurnId(null);
    setTurnActive(false);
    stopMicrophone(false);
    stopPlayback("已退出登录。");
    setMessages((current) => [...current, systemMessage("已退出登录。")]);
  }

  /** 重置当前连接对应的前端运行态，避免旧 session 的 turn 和音频继续污染新连接。 */
  function resetConversationRuntime() {
    setSessionId(null);
    setCurrentTurnId(null);
    currentTurnIdRef.current = null;
    invalidTurnIdsRef.current.clear();
    setTurnActive(false);
    stopMicrophone(false);
    stopPlayback("等待 TTS");
  }

  /** 使用指定 token 打开一条新的 Agent WebSocket，服务端会为这条连接创建独立 session。 */
  function openAgentSocket(token: string) {
    socketRef.current?.close();
    resetConversationRuntime();
    setConnectionState("connecting");
    setConnectionError(null);

    const socket = new WebSocket(buildAgentWsUrl(token));
    socketRef.current = socket;

    socket.addEventListener("open", () => {
      if (socketRef.current !== socket) {
        return;
      }
      setConnectionState("connected");
      sendWsJson(socket, "ping", { ts: Date.now() });
      setPlaybackStatus("等待 TTS");
      setMessages((current) => [...current, systemMessage("WebSocket 已连接。")]);
    });

    socket.addEventListener("message", (event) => {
      if (socketRef.current !== socket) {
        return;
      }
      if (typeof event.data !== "string") {
        return;
      }
      try {
        handleAgentEvent(JSON.parse(event.data) as AgentEventEnvelope);
      } catch {
        setMessages((current) => [...current, systemMessage("收到无法解析的服务端消息。")]);
      }
    });

    socket.addEventListener("close", () => {
      if (socketRef.current !== socket) {
        return;
      }
      setConnectionState("disconnected");
      socketRef.current = null;
      setSessionId(null);
      setCurrentTurnId(null);
      setTurnActive(false);
      stopMicrophone(false);
      stopPlayback("连接已断开。");
    });

    socket.addEventListener("error", () => {
      if (socketRef.current !== socket) {
        return;
      }
      setConnectionError("WebSocket 连接失败，请确认后端已启动且 token 有效。");
      setConnectionState("disconnected");
    });
  }

  /** 建立后端 Agent WebSocket 连接；每次新建连接都会让后端创建新的 session。 */
  function connectSocket() {
    if (!login?.token || connectionState === "connecting") {
      return;
    }

    openAgentSocket(login.token);
  }

  /** 主动断开 WebSocket 连接。 */
  function disconnectSocket() {
    socketRef.current?.close();
    socketRef.current = null;
    setConnectionState("disconnected");
    setSessionId(null);
    setCurrentTurnId(null);
    setTurnActive(false);
    stopMicrophone(false);
    stopPlayback("连接已断开。");
    setMessages((current) => [...current, systemMessage("WebSocket 已断开。")]);
  }

  /** 新建会话时关闭旧连接并重新连接，确保一个前端会话只对应一个 WebSocket session。 */
  function startNewConversation() {
    const token = login?.token;
    const conversation = createConversationRecord();
    socketRef.current?.close();
    socketRef.current = null;
    setMessages([]);
    setActiveConversationId(conversation.id);
    setConversations((current) => [conversation, ...current.map((item) => ({ ...item, active: false }))]);
    setInput("");
    resetConversationRuntime();
    setConnectionState("disconnected");
    setConnectionError(null);
    setMobileSidebarOpen(false);

    if (token) {
      openAgentSocket(token);
    }
  }

  /** 切换到浏览器本地保存的历史会话，只恢复本地消息，不复用已关闭的后端连接。 */
  function selectConversation(conversationId: string) {
    const conversation = conversations.find((item) => item.id === conversationId);
    if (!conversation || conversation.id === activeConversationId) {
      setMobileSidebarOpen(false);
      return;
    }

    socketRef.current?.close();
    socketRef.current = null;
    setActiveConversationId(conversation.id);
    setMessages(conversation.messages);
    setInput("");
    setSessionId(conversation.sessionId ?? null);
    setCurrentTurnId(conversation.currentTurnId ?? null);
    setTurnActive(false);
    stopMicrophone(false);
    stopPlayback("已切换到本地历史会话。");
    setConnectionState("disconnected");
    setConnectionError(null);
    setMobileSidebarOpen(false);
  }

  /** 处理后端下行事件并映射为聊天消息。 */
  function handleAgentEvent(event: AgentEventEnvelope) {
    if (event.sessionId) {
      setSessionId(event.sessionId);
      updateCurrentSessionTitle(event.sessionId);
    }
    if (event.turnId) {
      setCurrentTurnId(event.turnId);
    }

    switch (event.type) {
      case "asr_final":
        upsertUserMessage(event);
        break;
      case "agent_thinking":
        setTurnActive(true);
        upsertAssistantMessage(event, "正在思考...", true);
        break;
      case "agent_text_chunk":
        appendAssistantChunk(event);
        break;
      case "tts_audio_chunk":
        setTurnActive(true);
        enqueueTtsAudio(event);
        if (event.payload?.["isLast"] === true) {
          setMessages((current) => markStreamingDone(current));
        }
        break;
      case "turn_interrupted":
      case "playback_stop":
        if (event.turnId) {
          invalidTurnIdsRef.current.add(event.turnId);
        }
        setTurnActive(false);
        stopPlayback("当前播报已停止。");
        setMessages((current) => markStreamingDone(current));
        break;
      case "error":
        setTurnActive(false);
        stopPlayback("后端返回错误。");
        setMessages((current) => [
          ...markStreamingDone(current),
          systemMessage(readPayloadText(event, "message") || "后端返回错误。"),
        ]);
        break;
      default:
        break;
    }
  }

  /** 将 asr_final 事件固化为用户消息。 */
  function upsertUserMessage(event: AgentEventEnvelope) {
    const text = readPayloadText(event, "text");
    if (!text) {
      return;
    }
    setMessages((current) => {
      if (current.some((message) => message.role === "user" && message.turnId === event.turnId)) {
        return current;
      }
      return [
        ...current,
        {
          id: crypto.randomUUID(),
          role: "user",
          content: text,
          turnId: event.turnId,
          createdAt: new Date(),
        },
      ];
    });
    updateCurrentHistory(text);
  }

  /** 创建或更新当前 turn 的助手消息。 */
  function upsertAssistantMessage(event: AgentEventEnvelope, content: string, streaming: boolean) {
    setMessages((current) => {
      const index = current.findIndex((message) => message.role === "assistant" && message.turnId === event.turnId);
      if (index >= 0) {
        return current.map((message, messageIndex) =>
          messageIndex === index ? { ...message, content, streaming } : message,
        );
      }
      return [
        ...current,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content,
          turnId: event.turnId,
          streaming,
          createdAt: new Date(),
        },
      ];
    });
  }

  /** 把 agent_text_chunk 追加到当前助手气泡。 */
  function appendAssistantChunk(event: AgentEventEnvelope) {
    const text = readPayloadText(event, "text");
    if (!text) {
      return;
    }
    setMessages((current) => {
      const index = current.findIndex((message) => message.role === "assistant" && message.turnId === event.turnId);
      if (index >= 0) {
        return current.map((message, messageIndex) =>
          messageIndex === index
            ? {
                ...message,
                content: message.content === "正在思考..." ? text : `${message.content}${text}`,
                streaming: !event.payload?.["isLast"],
              }
            : message,
        );
      }
      return [
        ...current,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: text,
          turnId: event.turnId,
          streaming: !event.payload?.["isLast"],
          createdAt: new Date(),
        },
      ];
    });
  }

  /** 收到 TTS 音频后入队并启动播放，同时驱动助手文字区的播放动效。 */
  function enqueueTtsAudio(event: AgentEventEnvelope) {
    const turnId = event.turnId;
    const audioBase64 = readPayloadText(event, "audioBase64");
    if (!turnId || !audioBase64) {
      return;
    }
    if (currentTurnIdRef.current && turnId !== currentTurnIdRef.current) {
      return;
    }
    if (invalidTurnIdsRef.current.has(turnId)) {
      return;
    }

    playbackQueueRef.current.push({
      turnId,
      seq: readPayloadNumber(event, "seq", playbackQueueRef.current.length),
      audioBase64,
    });
    playbackQueueRef.current.sort((left, right) => left.seq - right.seq);
    setPlaybackStatus(`待播放 ${playbackQueueRef.current.length} 段`);
    drainPlaybackQueue().catch((error: unknown) => {
      setPlaybackStatus(error instanceof Error ? `播放失败：${error.message}` : "播放失败");
      clearSpeakingState();
    });
  }

  /** 顺序消费 TTS 队列，避免同一 turn 内的音频片段重叠播放。 */
  async function drainPlaybackQueue() {
    if (playingAudioRef.current) {
      return;
    }
    playingAudioRef.current = true;
    while (playbackQueueRef.current.length > 0) {
      const item = playbackQueueRef.current.shift();
      if (!item || item.turnId !== currentTurnIdRef.current || invalidTurnIdsRef.current.has(item.turnId)) {
        continue;
      }
      setAssistantSpeaking(item.turnId, true);
      setPlaybackStatus(`正在播放 seq=${item.seq}`);
      await playTtsAudio(item.audioBase64);
    }
    playingAudioRef.current = false;
    currentAudioSourceRef.current = null;
    clearSpeakingState();
    setMessages((current) => markStreamingDone(current));
    setPlaybackStatus("等待 TTS");
    setTurnActive(false);
  }

  /** 播放 TTS 音频，优先走浏览器解码，失败时按 PCM16 little-endian 兜底。 */
  async function playTtsAudio(audioBase64: string) {
    const context = await ensureAudioContext();
    const bytes = base64ToBytes(audioBase64);
    try {
      const decoded = await context.decodeAudioData(bytes.buffer.slice(0));
      await playAudioBuffer(decoded);
    } catch {
      await playPcm16(bytes);
    }
  }

  /** 确保 AudioContext 可用，并在浏览器需要用户手势时尝试恢复。 */
  async function ensureAudioContext() {
    audioContextRef.current = audioContextRef.current || new AudioContext();
    if (audioContextRef.current.state === "suspended") {
      await audioContextRef.current.resume();
    }
    return audioContextRef.current;
  }

  /** 播放浏览器已解码的音频缓冲。 */
  async function playAudioBuffer(buffer: AudioBuffer) {
    const context = await ensureAudioContext();
    await new Promise<void>((resolve) => {
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      currentAudioSourceRef.current = source;
      source.onended = () => resolve();
      source.start();
    });
  }

  /** 当 TTS 返回裸 PCM 时，按默认 24kHz PCM16 单声道进行兜底播放。 */
  async function playPcm16(bytes: Uint8Array) {
    const context = await ensureAudioContext();
    const sampleRate = 24000;
    const sampleCount = Math.floor(bytes.length / 2);
    const audioBuffer = context.createBuffer(1, sampleCount, sampleRate);
    const channel = audioBuffer.getChannelData(0);
    for (let index = 0; index < sampleCount; index += 1) {
      const low = bytes[index * 2];
      const high = bytes[index * 2 + 1];
      const sample = (high << 8) | low;
      const signed = sample >= 0x8000 ? sample - 0x10000 : sample;
      channel[index] = Math.max(-1, Math.min(1, signed / 0x8000));
    }
    await playAudioBuffer(audioBuffer);
  }

  /** 停止当前 TTS 播放并清空队列。 */
  function stopPlayback(reason: string) {
    playbackQueueRef.current = [];
    if (currentAudioSourceRef.current) {
      try {
        currentAudioSourceRef.current.stop();
      } catch {
        // 音频节点可能已经自然结束，停止失败不影响状态清理。
      }
    }
    currentAudioSourceRef.current = null;
    playingAudioRef.current = false;
    clearSpeakingState();
    setPlaybackStatus(reason);
  }

  /** 标记指定 turn 的助手消息正在播放 TTS。 */
  function setAssistantSpeaking(turnId: string, speaking: boolean) {
    setMessages((current) =>
      current.map((message) =>
        message.role === "assistant" && message.turnId === turnId ? { ...message, speaking } : message,
      ),
    );
  }

  /** 清除所有助手消息的 TTS 播放动效。 */
  function clearSpeakingState() {
    setMessages((current) => current.map((message) => (message.speaking ? { ...message, speaking: false } : message)));
  }

  /** 发送用户输入的文本消息。 */
  function sendTextMessage(textOverride?: string) {
    const text = (textOverride ?? input).trim();
    if (!text || !socketRef.current || !connected) {
      return;
    }

    setInput("");
    setTurnActive(true);
    sendWsJson(socketRef.current, "text", { text });
  }

  /** 发送打断消息，让后端停止旧 turn。 */
  function interruptTurn() {
    if (!socketRef.current || !connected) {
      return;
    }
    sendWsJson(socketRef.current, "interrupt", { reason: "client_interrupt" });
    if (currentTurnId) {
      invalidTurnIdsRef.current.add(currentTurnId);
    }
    setTurnActive(false);
    stopPlayback("已发送打断。");
    setMessages((current) => [...markStreamingDone(current), systemMessage("已发送打断请求。")]);
  }

  /** 切换麦克风采集状态。 */
  async function toggleMicrophone() {
    if (recording) {
      stopMicrophone(true);
      return;
    }
    await startMicrophone();
  }

  /** 打开麦克风并把浏览器音频重采样为后端默认 PCM16 二进制帧。 */
  async function startMicrophone() {
    if (!socketRef.current || !connected || recordingRef.current) {
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setAudioInputStatus("当前浏览器不支持麦克风采集");
      setMessages((current) => [...current, systemMessage("当前浏览器不支持麦克风采集。")]);
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
      const context = new AudioContext();
      const source = context.createMediaStreamSource(stream);
      const processor = context.createScriptProcessor(4096, 1, 1);
      const mute = context.createGain();
      mute.gain.value = 0;

      processor.onaudioprocess = (event) => {
        const socket = socketRef.current;
        if (!recordingRef.current || !socket || socket.readyState !== WebSocket.OPEN) {
          return;
        }
        const channel = event.inputBuffer.getChannelData(0);
        const pcm = floatToPcm16(channel, context.sampleRate, MICROPHONE_TARGET_SAMPLE_RATE);
        if (pcm.byteLength > 0) {
          socket.send(pcm);
        }
      };

      source.connect(processor);
      processor.connect(mute);
      mute.connect(context.destination);

      microphoneStreamRef.current = stream;
      microphoneContextRef.current = context;
      microphoneSourceRef.current = source;
      microphoneProcessorRef.current = processor;
      microphoneMuteRef.current = mute;
      recordingRef.current = true;
      setRecording(true);
      setAudioInputStatus("正在发送 16kHz PCM");
      setMessages((current) => [...current, systemMessage("麦克风已开启，正在发送 PCM 音频。")]);
    } catch (error) {
      setRecording(false);
      recordingRef.current = false;
      const message = error instanceof Error ? error.message : "麦克风开启失败";
      setAudioInputStatus(`麦克风开启失败：${message}`);
      setMessages((current) => [...current, systemMessage(`麦克风开启失败：${message}`)]);
      stopMicrophone(false);
    }
  }

  /** 处理输入框快捷键，Enter 发送，Shift+Enter 换行。 */
  function handleInputKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendTextMessage();
    }
  }

  /** 更新左侧当前会话摘要。 */
  function updateCurrentHistory(preview: string) {
    setConversations((current) =>
      current.map((item) =>
        item.id === activeConversationId
          ? {
              ...item,
              preview,
              updatedAtMs: Date.now(),
            }
          : item,
      ),
    );
  }

  /** 根据后端下行 sessionId 更新当前会话标题，帮助确认新对话已经对应新连接。 */
  function updateCurrentSessionTitle(nextSessionId: string) {
    setConversations((current) =>
      current.map((item) =>
        item.id === activeConversationId
          ? {
              ...item,
              title: `session ${nextSessionId}`,
              sessionId: nextSessionId,
              updatedAtMs: Date.now(),
            }
          : item,
      ),
    );
  }

  return (
    <main className="relative flex h-screen overflow-hidden bg-background text-foreground">
      {mobileSidebarOpen ? (
        <button
          type="button"
          className="fixed inset-0 z-30 bg-foreground/24 backdrop-blur-[1px] lg:hidden"
          aria-label="关闭侧边栏遮罩"
          onClick={() => setMobileSidebarOpen(false)}
        />
      ) : null}

      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-40 flex w-[min(86vw,320px)] flex-col border-r bg-sidebar px-3 py-4 transition-transform duration-200 lg:static lg:z-10 lg:w-72 lg:translate-x-0",
          mobileSidebarOpen ? "translate-x-0" : "-translate-x-full",
          !sidebarOpen && "lg:w-[76px]",
        )}
      >
        <SidebarContent
          compact={!sidebarOpen}
          history={history}
          login={login}
          loginOpen={loginOpen}
          loginError={loginError}
          loginForm={loginForm}
          loginLoading={loginLoading}
          connectionState={connectionState}
          connectionLabel={connectionLabel}
          connected={connected}
          onCloseMobile={() => setMobileSidebarOpen(false)}
          onToggleDesktop={() => setSidebarOpen((current) => !current)}
          onNewConversation={startNewConversation}
          onSelectConversation={selectConversation}
          onOpenChange={setLoginOpen}
          onLogin={handleLogin}
          onLogout={handleLogout}
          onUpdateField={updateLoginField}
          onConnect={connectSocket}
          onDisconnect={disconnectSocket}
        />
      </aside>

      <section className="relative flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between border-b bg-background/92 px-3 backdrop-blur-xl md:px-6">
          <div className="flex min-w-0 items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="lg:hidden"
              title="打开侧边栏"
              onClick={() => setMobileSidebarOpen(true)}
            >
              <Menu className="size-5" />
            </Button>
            <div className="min-w-0">
              <h1 className="truncate text-sm font-semibold md:text-base">本地语音助手</h1>
              <p className="truncate text-xs text-muted-foreground">
                {sessionId ? `session ${sessionId}` : "登录后连接本地 Agent"}
                {currentTurnId ? ` · turn ${currentTurnId}` : ""}
              </p>
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-1.5">
            <ConnectionBadge state={connectionState} label={connectionLabel} />
            <Button type="button" variant="ghost" size="icon" title="切换主题" onClick={toggleTheme}>
              {theme === "dark" ? <Sun className="size-5" /> : <Moon className="size-5" />}
            </Button>
          </div>
        </header>

        {connectionError ? (
          <div className="border-b bg-destructive/10 px-4 py-2 text-sm text-destructive md:px-6">{connectionError}</div>
        ) : null}

        <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 pb-40 pt-6 md:px-8 md:pb-44">
          <div className="mx-auto flex min-h-full max-w-3xl flex-col">
            {emptyConversation ? (
              <EmptyConversation connected={connected} onUsePrompt={sendTextMessage} />
            ) : (
              <div className="flex flex-col gap-6">
                {messages.map((message) => (
                  <MessageBubble key={message.id} message={message} />
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-background via-background/96 to-transparent px-4 pb-4 pt-10 md:px-8">
          <div className="pointer-events-auto mx-auto max-w-3xl">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2 px-1 text-xs text-muted-foreground">
              <span className="inline-flex min-w-0 items-center gap-1.5">
                {recording ? <Mic className="size-3.5 text-primary" /> : <Volume2 className="size-3.5 text-primary" />}
                <span className="truncate">{recording ? audioInputStatus : playbackStatus}</span>
              </span>
              <span className="inline-flex items-center gap-1.5">
                {connected ? <CheckCircle2 className="size-3.5 text-primary" /> : <WifiOff className="size-3.5" />}
                {connected ? "WebSocket text · TTS 自动播放" : "当前连接不可用"}
              </span>
            </div>
            <div className="flex items-end gap-2 rounded-[1.35rem] border bg-input-shell p-2 shadow-[0_18px_45px_hsl(var(--foreground)/0.10)]">
              <Button
                type="button"
                variant={recording ? "destructive" : "ghost"}
                size="icon"
                className="mb-1 shrink-0"
                title={recording ? "停止录音并提交音频" : "开始麦克风输入"}
                disabled={!connected}
                onClick={() => {
                  toggleMicrophone().catch((error: unknown) => {
                    const message = error instanceof Error ? error.message : "麦克风开启失败";
                    setAudioInputStatus(`麦克风开启失败：${message}`);
                  });
                }}
              >
                {recording ? <MicOff className="size-4" /> : <Mic className="size-4" />}
              </Button>
              <Textarea
                value={input}
                className="min-h-20 flex-1 border-0 bg-transparent px-3 py-3 shadow-none focus-visible:ring-0"
                placeholder={connected ? "给 Kong Voice Agent 发送消息" : "请先登录并连接后端"}
                disabled={!connected}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={handleInputKeyDown}
              />
              <Button
                type="button"
                size="icon"
                className="mb-1 shrink-0"
                title={turnActive ? "打断当前回复" : "发送消息"}
                disabled={!connected || (!turnActive && !input.trim())}
                onClick={() => {
                  if (turnActive) {
                    interruptTurn();
                    return;
                  }
                  sendTextMessage();
                }}
              >
                {turnActive ? <Square className="size-4" /> : <Send className="size-4" />}
              </Button>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}

/** 侧边栏属性集合。 */
interface SidebarContentProps extends LoginPanelProps {
  /** 是否已经建立连接。 */
  connected: boolean;
  /** 当前历史会话。 */
  history: ConversationSummary[];
  /** WebSocket 状态。 */
  connectionState: ConnectionState;
  /** WebSocket 状态文案。 */
  connectionLabel: string;
  /** 关闭移动端侧边栏。 */
  onCloseMobile: () => void;
  /** 折叠或展开桌面端侧边栏。 */
  onToggleDesktop: () => void;
  /** 新建会话并重建 WebSocket session。 */
  onNewConversation: () => void;
  /** 切换到浏览器本地保存的会话记录。 */
  onSelectConversation: (conversationId: string) => void;
  /** 建立 WebSocket 连接。 */
  onConnect: () => void;
  /** 断开 WebSocket 连接。 */
  onDisconnect: () => void;
}

/** 左侧产品化会话栏，承载品牌、新会话、历史、连接和账号入口。 */
function SidebarContent({
  compact,
  history,
  login,
  loginOpen,
  loginError,
  loginForm,
  loginLoading,
  connectionState,
  connectionLabel,
  connected,
  onCloseMobile,
  onToggleDesktop,
  onNewConversation,
  onSelectConversation,
  onOpenChange,
  onLogin,
  onLogout,
  onUpdateField,
  onConnect,
  onDisconnect,
}: SidebarContentProps) {
  return (
    <>
      <div className="mb-4 flex items-center justify-between gap-2">
        {compact ? null : (
          <div className="flex min-w-0 items-center gap-2">
            <div className="flex size-9 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Sparkles className="size-5" />
            </div>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">Kong Voice Agent</p>
              <p className="truncate text-xs text-muted-foreground">AI Chat</p>
            </div>
          </div>
        )}
        <div className="flex items-center gap-1">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="hidden lg:inline-flex"
            title={compact ? "展开侧边栏" : "收起侧边栏"}
            onClick={onToggleDesktop}
          >
            {compact ? <PanelLeftOpen className="size-5" /> : <PanelLeftClose className="size-5" />}
          </Button>
          <Button type="button" variant="ghost" size="icon" className="lg:hidden" title="关闭侧边栏" onClick={onCloseMobile}>
            <X className="size-5" />
          </Button>
        </div>
      </div>

      <Button
        type="button"
        variant="secondary"
        className={cn("mb-3 justify-start rounded-xl", compact && "justify-center px-0")}
        title="新建会话并重连"
        onClick={onNewConversation}
      >
        <MessageSquarePlus className="size-4" />
        {compact ? null : "新对话"}
      </Button>

      <div className="flex-1 overflow-y-auto py-2">
        {compact ? null : <p className="mb-2 px-2 text-xs font-medium text-muted-foreground">最近对话</p>}
        <div className="space-y-1">
          {history.map((item) => (
            <button
              key={item.id}
              type="button"
              className={cn(
                "group flex w-full items-center gap-2 rounded-xl px-2.5 py-2.5 text-left transition-colors hover:bg-secondary",
                item.active ? "bg-secondary" : "bg-transparent",
                compact && "justify-center px-2",
              )}
              title={item.title}
              onClick={() => onSelectConversation(item.id)}
            >
              <History className="size-4 shrink-0 text-muted-foreground" />
              {compact ? null : (
                <>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{item.title}</span>
                    <span className="mt-0.5 block truncate text-xs text-muted-foreground">{item.preview}</span>
                  </span>
                  <span className="shrink-0 text-xs text-muted-foreground">{item.updatedAt}</span>
                </>
              )}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-2 border-t pt-3">
        <div className={cn("rounded-xl bg-background/70 p-2", compact && "flex justify-center")}>
          {compact ? (
            <ConnectionStateIcon state={connectionState} />
          ) : (
            <div className="flex items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2">
                <ConnectionStateIcon state={connectionState} />
                <div className="min-w-0">
                  <p className="truncate text-xs font-medium">{connectionLabel}</p>
                  <p className="truncate text-xs text-muted-foreground">{login ? "token 已就绪" : "请先登录"}</p>
                </div>
              </div>
              {connected ? (
                <Button type="button" variant="ghost" size="icon" title="断开连接" onClick={onDisconnect}>
                  <WifiOff className="size-4" />
                </Button>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title="连接后端"
                  disabled={!login || connectionState === "connecting"}
                  onClick={onConnect}
                >
                  {connectionState === "connecting" ? <Loader2 className="size-4 animate-spin" /> : <Plug className="size-4" />}
                </Button>
              )}
            </div>
          )}
        </div>

        <LoginPanel
          compact={compact}
          login={login}
          loginOpen={loginOpen}
          loginError={loginError}
          loginForm={loginForm}
          loginLoading={loginLoading}
          onOpenChange={onOpenChange}
          onLogin={onLogin}
          onLogout={onLogout}
          onUpdateField={onUpdateField}
        />
      </div>
    </>
  );
}

/** 登录区属性集合。 */
interface LoginPanelProps {
  /** 是否使用收起态布局。 */
  compact: boolean;
  /** 当前登录信息。 */
  login: LoginResponse | null;
  /** 登录弹窗是否打开。 */
  loginOpen: boolean;
  /** 登录失败提示。 */
  loginError: string | null;
  /** 登录表单值。 */
  loginForm: typeof DEFAULT_LOGIN_FORM;
  /** 登录请求是否进行中。 */
  loginLoading: boolean;
  /** 更新弹窗打开状态。 */
  onOpenChange: (open: boolean) => void;
  /** 提交登录表单。 */
  onLogin: (event: FormEvent<HTMLFormElement>) => void;
  /** 注销当前用户。 */
  onLogout: () => void;
  /** 更新登录表单字段。 */
  onUpdateField: (field: keyof typeof DEFAULT_LOGIN_FORM, value: string) => void;
}

/** 左下角登录组件，符合聊天产品账号入口的低干扰位置。 */
function LoginPanel({
  compact,
  login,
  loginOpen,
  loginError,
  loginForm,
  loginLoading,
  onOpenChange,
  onLogin,
  onLogout,
  onUpdateField,
}: LoginPanelProps) {
  if (login) {
    return (
      <div className={cn("flex items-center gap-3 rounded-xl bg-background/70 p-2", compact && "justify-center")}>
        <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-secondary">
          <UserRound className="size-5" />
        </div>
        {compact ? null : (
          <>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{login.user.username}</p>
              <p className="truncate text-xs text-muted-foreground">{login.user.accountId}</p>
            </div>
            <Button type="button" variant="ghost" size="icon" title="退出登录" onClick={onLogout}>
              <LogOut className="size-5" />
            </Button>
          </>
        )}
      </div>
    );
  }

  return (
    <Dialog open={loginOpen} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button type="button" className={cn("w-full rounded-xl", compact && "px-0")} size={compact ? "icon" : "default"}>
          <LogIn className="size-4" />
          {compact ? null : "登录"}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogTitle>登录到本地后端</DialogTitle>
        <DialogDescription>使用固定账号获取 WebSocket token。</DialogDescription>
        <form className="space-y-4" onSubmit={onLogin}>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="username">
              用户名
            </label>
            <Input
              id="username"
              value={loginForm.username}
              autoComplete="username"
              onChange={(event) => onUpdateField("username", event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="password">
              密码
            </label>
            <Input
              id="password"
              type="password"
              value={loginForm.password}
              autoComplete="current-password"
              onChange={(event) => onUpdateField("password", event.target.value)}
            />
          </div>
          {loginError ? <p className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">{loginError}</p> : null}
          <Button type="submit" className="w-full" disabled={loginLoading}>
            {loginLoading ? <Loader2 className="size-4 animate-spin" /> : <LogIn className="size-4" />}
            登录
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** 连接状态标签属性。 */
interface ConnectionBadgeProps {
  /** WebSocket 状态。 */
  state: ConnectionState;
  /** 状态展示文案。 */
  label: string;
}

/** 顶部连接状态标签。 */
function ConnectionBadge({ state, label }: ConnectionBadgeProps) {
  return (
    <span
      className={cn(
        "hidden items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium sm:inline-flex",
        state === "connected" && "bg-primary/12 text-primary",
        state === "connecting" && "bg-accent/25 text-accent-foreground",
        state === "disconnected" && "bg-secondary text-muted-foreground",
      )}
    >
      <ConnectionStateIcon state={state} />
      {label}
    </span>
  );
}

/** 连接状态图标属性。 */
interface ConnectionStateIconProps {
  /** WebSocket 状态。 */
  state: ConnectionState;
}

/** 根据 WebSocket 状态渲染一致的状态图标。 */
function ConnectionStateIcon({ state }: ConnectionStateIconProps) {
  if (state === "connected") {
    return <Wifi className="size-3.5 text-primary" />;
  }
  if (state === "connecting") {
    return <RefreshCw className="size-3.5 animate-spin text-accent-foreground" />;
  }
  return <WifiOff className="size-3.5 text-muted-foreground" />;
}

/** 空会话属性。 */
interface EmptyConversationProps {
  /** 是否已经连接到后端。 */
  connected: boolean;
  /** 点击建议问题时发送文本。 */
  onUsePrompt: (prompt: string) => void;
}

/** 首屏欢迎态，用更产品化的方式提示用户开始对话。 */
function EmptyConversation({ connected, onUsePrompt }: EmptyConversationProps) {
  return (
    <div className="mx-auto flex min-h-[58vh] w-full max-w-2xl flex-col items-center justify-center text-center">
      <div className="mb-5 flex size-14 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-[0_14px_36px_hsl(var(--primary)/0.20)]">
        <Bot className="size-7" />
      </div>
      <h2 className="text-2xl font-semibold tracking-normal md:text-3xl">今天想和本地 Agent 聊什么？</h2>
      <p className="mt-3 max-w-xl text-sm leading-6 text-muted-foreground">
        登录并连接后，可以直接发送文本消息，页面会按 turnId 聚合回复并自动播放 TTS。
      </p>
      <div className="mt-7 grid w-full gap-2 sm:grid-cols-3">
        {STARTER_PROMPTS.map((prompt) => (
          <button
            key={prompt}
            type="button"
            disabled={!connected}
            className="group rounded-2xl border bg-card px-4 py-3 text-left text-sm transition-colors hover:border-primary/45 hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-55"
            onClick={() => onUsePrompt(prompt)}
          >
            <span className="line-clamp-2 min-h-10">{prompt}</span>
            <span className="mt-3 flex items-center text-xs text-muted-foreground">
              发送
              <ChevronRight className="ml-1 size-3.5 transition-transform group-hover:translate-x-0.5" />
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

/** 消息气泡属性。 */
interface MessageBubbleProps {
  /** 需要渲染的一条聊天消息。 */
  message: ChatMessage;
}

/** 聊天消息气泡组件，区分用户、助手和系统消息。 */
function MessageBubble({ message }: MessageBubbleProps) {
  if (message.role === "system") {
    return (
      <div className="mx-auto max-w-[88%] rounded-full bg-secondary/70 px-3 py-1.5 text-center text-xs text-muted-foreground">
        {message.content}
      </div>
    );
  }

  const isUser = message.role === "user";

  return (
    <div className={cn("flex gap-3", isUser ? "justify-end" : "justify-start")}>
      {!isUser ? (
        <div className="mt-1 flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/12 text-primary">
          <Bot className="size-4.5" />
        </div>
      ) : null}
      <div
        className={cn(
          "relative max-w-[min(720px,84%)] overflow-hidden px-4 py-3 text-sm leading-7",
          isUser
            ? "rounded-2xl bg-primary text-primary-foreground"
            : "rounded-2xl border bg-card text-card-foreground shadow-[0_8px_24px_hsl(var(--foreground)/0.05)]",
          message.speaking && "speaking-message border-primary/35 bg-primary/8",
        )}
      >
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
        {message.speaking ? <VoiceWave /> : null}
        {message.streaming ? (
          <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            生成中
          </div>
        ) : null}
      </div>
      {isUser ? (
        <div className="mt-1 flex size-8 shrink-0 items-center justify-center rounded-lg bg-secondary">
          <UserRound className="size-4.5" />
        </div>
      ) : null}
    </div>
  );
}

/** TTS 播放时显示在助手文字区域下方的声波动效。 */
function VoiceWave() {
  return (
    <div className="mt-3 flex items-center gap-1.5 text-primary" aria-label="正在播放 TTS">
      {[0, 1, 2, 3, 4].map((index) => (
        <span
          key={index}
          className="voice-bar h-4 w-1 rounded-full bg-primary/80"
          style={{ animationDelay: `${index * 90}ms` }}
        />
      ))}
      <span className="ml-2 text-xs text-muted-foreground">正在播报</span>
    </div>
  );
}

/** 构造系统提示消息。 */
function systemMessage(content: string): ChatMessage {
  return {
    id: crypto.randomUUID(),
    role: "system",
    content,
    createdAt: new Date(),
  };
}

/** 创建一条新的前端会话记录，真正的后端 session 会在 WebSocket 连接成功后回填。 */
function createConversationRecord(): ConversationRecord {
  const now = Date.now();
  return {
    id: crypto.randomUUID(),
    title: "新会话",
    preview: "等待连接后端 session",
    updatedAt: "现在",
    active: true,
    sessionId: null,
    currentTurnId: null,
    messages: [],
    createdAtMs: now,
    updatedAtMs: now,
  };
}

/** 从 localStorage 恢复会话列表，数据损坏时自动创建一条空会话。 */
function loadStoredConversations(): ConversationRecord[] {
  try {
    const raw = window.localStorage.getItem(CONVERSATION_STORAGE_KEY);
    if (!raw) {
      return [createConversationRecord()];
    }
    const parsed = JSON.parse(raw) as Partial<ConversationRecord>[];
    const conversations = parsed
      .map((item) => normalizeConversationRecord(item))
      .filter((item): item is ConversationRecord => Boolean(item));
    return conversations.length > 0 ? conversations : [createConversationRecord()];
  } catch {
    return [createConversationRecord()];
  }
}

/** 找到上次选中的会话；如果记录不存在，则默认使用最新会话。 */
function resolveInitialActiveConversation(conversations: ConversationRecord[]): ConversationRecord {
  const storedActiveId = window.localStorage.getItem(ACTIVE_CONVERSATION_STORAGE_KEY);
  return (
    conversations.find((conversation) => conversation.id === storedActiveId) ??
    conversations.slice().sort((left, right) => right.updatedAtMs - left.updatedAtMs)[0] ??
    createConversationRecord()
  );
}

/** 标准化 localStorage 中的会话记录，兼容 Date 被序列化为字符串的情况。 */
function normalizeConversationRecord(item: Partial<ConversationRecord> | null | undefined): ConversationRecord | null {
  if (!item || typeof item.id !== "string") {
    return null;
  }
  const now = Date.now();
  const messages = Array.isArray(item.messages)
    ? item.messages.map(normalizeChatMessage).filter((message): message is ChatMessage => Boolean(message))
    : [];
  return {
    id: item.id,
    title: typeof item.title === "string" && item.title ? item.title : "历史会话",
    preview: typeof item.preview === "string" && item.preview ? item.preview : "本地保存的会话",
    updatedAt: "刚刚",
    active: false,
    sessionId: typeof item.sessionId === "string" ? item.sessionId : null,
    currentTurnId: typeof item.currentTurnId === "string" ? item.currentTurnId : null,
    messages,
    createdAtMs: typeof item.createdAtMs === "number" ? item.createdAtMs : now,
    updatedAtMs: typeof item.updatedAtMs === "number" ? item.updatedAtMs : now,
  };
}

/** 标准化本地消息记录，清理不应跨页面刷新的流式与播放状态。 */
function normalizeChatMessage(message: Partial<ChatMessage> | null | undefined): ChatMessage | null {
  if (!message || typeof message.id !== "string" || typeof message.content !== "string") {
    return null;
  }
  const role = message.role === "assistant" || message.role === "system" || message.role === "user" ? message.role : "system";
  return {
    id: message.id,
    role,
    content: message.content,
    turnId: typeof message.turnId === "string" ? message.turnId : null,
    streaming: false,
    speaking: false,
    createdAt: message.createdAt ? new Date(message.createdAt) : new Date(),
  };
}

/** 写入会话列表到 localStorage，保存前清理临时播放状态。 */
function persistConversations(conversations: ConversationRecord[]) {
  try {
    window.localStorage.setItem(
      CONVERSATION_STORAGE_KEY,
      JSON.stringify(
        conversations.map((conversation) => ({
          ...conversation,
          active: false,
          messages: sanitizeMessagesForStorage(conversation.messages),
        })),
      ),
    );
  } catch {
    // localStorage 可能被隐私模式或浏览器策略禁用，失败时保留当前内存态即可。
  }
}

/** 记录当前选中的会话，刷新页面后继续展示同一条本地记录。 */
function persistActiveConversationId(conversationId: string) {
  try {
    window.localStorage.setItem(ACTIVE_CONVERSATION_STORAGE_KEY, conversationId);
  } catch {
    // localStorage 不可写不影响当前页面继续联调。
  }
}

/** 保存消息时去掉只属于当前播放过程的临时状态。 */
function sanitizeMessagesForStorage(messages: ChatMessage[]) {
  return messages.map((message) => ({
    ...message,
    streaming: false,
    speaking: false,
  }));
}

/** 根据消息内容生成左侧会话摘要，优先显示最近一条用户或助手消息。 */
function buildConversationPreview(messages: ChatMessage[], fallback: string) {
  const latest = messages
    .slice()
    .reverse()
    .find((message) => message.role !== "system" && message.content.trim());
  return latest ? latest.content.trim().slice(0, 48) : fallback;
}

/** 将会话更新时间格式化为短文案。 */
function formatConversationTime(updatedAtMs: number) {
  const diffMs = Date.now() - updatedAtMs;
  if (diffMs < 60_000) {
    return "刚刚";
  }
  if (diffMs < 3_600_000) {
    return `${Math.floor(diffMs / 60_000)} 分钟前`;
  }
  if (diffMs < 86_400_000) {
    return `${Math.floor(diffMs / 3_600_000)} 小时前`;
  }
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit" }).format(new Date(updatedAtMs));
}

/** 从 payload 中读取字符串字段。 */
function readPayloadText(event: AgentEventEnvelope, field: string) {
  const value = event.payload?.[field];
  return typeof value === "string" ? value : "";
}

/** 从 payload 中读取数字字段，不存在时使用默认值。 */
function readPayloadNumber(event: AgentEventEnvelope, field: string, defaultValue: number) {
  const value = event.payload?.[field];
  return typeof value === "number" ? value : defaultValue;
}

/** 将 base64 字符串转换为字节数组。 */
function base64ToBytes(value: string) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

/** 将浏览器 Float32 音频重采样并转换为 PCM16 little-endian。 */
function floatToPcm16(input: Float32Array, inputRate: number, targetRate: number) {
  const ratio = inputRate / targetRate;
  const outputLength = Math.floor(input.length / ratio);
  const buffer = new ArrayBuffer(outputLength * 2);
  const view = new DataView(buffer);

  for (let index = 0; index < outputLength; index += 1) {
    const sourceIndex = Math.floor(index * ratio);
    const sample = Math.max(-1, Math.min(1, input[sourceIndex] || 0));
    const pcm = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
    view.setInt16(index * 2, pcm, true);
  }

  return buffer;
}

/** 结束所有正在流式生成的消息。 */
function markStreamingDone(messages: ChatMessage[]) {
  return messages.map((message) => (message.streaming ? { ...message, streaming: false } : message));
}
