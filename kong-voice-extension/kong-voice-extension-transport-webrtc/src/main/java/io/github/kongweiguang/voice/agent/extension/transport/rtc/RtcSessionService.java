package io.github.kongweiguang.voice.agent.extension.transport.rtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.kongweiguang.voice.agent.model.AgentEvent;
import io.github.kongweiguang.voice.agent.model.EventType;
import io.github.kongweiguang.voice.agent.model.payload.RtcIceCandidatePayload;
import io.github.kongweiguang.voice.agent.playback.PlaybackDispatcher;
import io.github.kongweiguang.voice.agent.service.VoicePipelineService;
import io.github.kongweiguang.voice.agent.session.SessionManager;
import io.github.kongweiguang.voice.agent.session.SessionState;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 管理 WebRTC PeerConnection 生命周期、音频桥接和 signaling 操作。
 *
 * @author kongweiguang
 */
@Service
@ConditionalOnProperty(prefix = "kong-voice-agent.rtc", name = "enabled", havingValue = "true")
public class RtcSessionService {
    /**
     * 共享会话注册表。
     */
    private final SessionManager sessionManager;

    /**
     * 核心语音流水线。
     */
    private final VoicePipelineService voicePipelineService;

    /**
     * WebSocket 控制面事件发送器。
     */
    private final PlaybackDispatcher playbackDispatcher;

    /**
     * WebRTC 配置。
     */
    private final RtcProperties rtcProperties;

    /**
     * 当前活跃 RTC 会话。
     */
    private final Map<String, RtcPeerConnectionContext> rtcContexts = new ConcurrentHashMap<>();

    /**
     * 服务器端无头音频模块。
     */
    private final HeadlessAudioDeviceModule audioDeviceModule = new HeadlessAudioDeviceModule();

    /**
     * WebRTC 工厂。
     */
    private final PeerConnectionFactory peerConnectionFactory;

    /**
     * 创建 RTC 会话服务。
     */
    public RtcSessionService(SessionManager sessionManager,
                             VoicePipelineService voicePipelineService,
                             PlaybackDispatcher playbackDispatcher,
                             RtcProperties rtcProperties) {
        this.sessionManager = sessionManager;
        this.voicePipelineService = voicePipelineService;
        this.playbackDispatcher = playbackDispatcher;
        this.rtcProperties = rtcProperties;
        this.audioDeviceModule.initPlayout();
        this.audioDeviceModule.startPlayout();
        this.audioDeviceModule.initRecording();
        this.audioDeviceModule.startRecording();
        this.peerConnectionFactory = new PeerConnectionFactory(audioDeviceModule);
    }

    /**
     * 为当前控制面会话启用 RTC 媒体链路；如果该会话之前已存在 RTC，会先替换旧上下文。
     */
    public RtcSessionDescriptor openSession(SessionState sessionState) {
        closeSession(sessionState.sessionId(), "replaced_by_new_session");
        SessionState sharedSessionState = sessionManager.attachRtc(sessionState.sessionId());
        RtcPeerConnectionContext context = createContext(sharedSessionState);
        rtcContexts.put(sharedSessionState.sessionId(), context);
        context.stateReporter().sessionOpened();
        return new RtcSessionDescriptor(sharedSessionState.sessionId(), buildIceServerUrls());
    }

    /**
     * 处理浏览器 offer，并返回 answer。
     */
    public RTCSessionDescription handleOffer(String sessionId, RTCSessionDescription offer) {
        RtcPeerConnectionContext context = requireContext(sessionId);
        setRemoteDescription(context.peerConnection(), offer);
        context.remoteDescriptionApplied(true);
        context.flushPendingRemoteIceCandidates();
        RTCSessionDescription answer = createAnswer(context.peerConnection());
        setLocalDescription(context.peerConnection(), answer);
        return answer;
    }

    /**
     * 接收浏览器 trickle ICE candidate。
     */
    public void addRemoteIceCandidate(String sessionId, RTCIceCandidate candidate) {
        RtcPeerConnectionContext context = requireContext(sessionId);
        if (Boolean.TRUE.equals(context.remoteDescriptionApplied())) {
            context.peerConnection().addIceCandidate(candidate);
            return;
        }
        context.pendingRemoteIceCandidates().add(candidate);
    }

    /**
     * 主动关闭 RTC 会话并释放资源。
     */
    public void closeSession(String sessionId) {
        closeSession(sessionId, "rtc_close");
    }

    /**
     * 按指定原因关闭 RTC 会话并释放资源。
     */
    private void closeSession(String sessionId, String reason) {
        RtcPeerConnectionContext context = rtcContexts.remove(sessionId);
        if (context != null) {
            context.stateReporter().closed(reason);
            context.close();
        }
        sessionManager.get(sessionId).ifPresent(sessionState -> sessionState.audioEgressAdapter(null));
        sessionManager.detachRtc(sessionId);
    }

    /**
     * 关闭所有活跃 RTC 会话。
     */
    @PreDestroy
    public void shutdown() {
        rtcContexts.keySet().stream().toList().forEach(this::closeSession);
        audioDeviceModule.stopRecording();
        audioDeviceModule.stopPlayout();
        audioDeviceModule.dispose();
        peerConnectionFactory.dispose();
    }

    /**
     * 创建当前会话的 PeerConnection 及音频桥接资源。
     */
    private RtcPeerConnectionContext createContext(SessionState sessionState) {
        CustomAudioSource outboundAudioSource = new CustomAudioSource();
        AudioTrack outboundAudioTrack = peerConnectionFactory.createAudioTrack("agent-audio-" + sessionState.sessionId(), outboundAudioSource);
        RtcAudioEgressAdapter audioEgressAdapter = new RtcAudioEgressAdapter(sessionState.sessionId(), outboundAudioSource);
        RtcRuntimeStateReporter stateReporter = new RtcRuntimeStateReporter(sessionState, playbackDispatcher);
        sessionState.audioEgressAdapter(audioEgressAdapter);

        RTCPeerConnection peerConnection = peerConnectionFactory.createPeerConnection(buildRtcConfiguration(),
                new SessionPeerConnectionObserver(sessionState, stateReporter));
        peerConnection.addTrack(outboundAudioTrack, List.of("agent-stream"));
        return new RtcPeerConnectionContext(sessionState, peerConnection, outboundAudioSource, outboundAudioTrack, audioEgressAdapter, stateReporter);
    }

    /**
     * 构造 PeerConnection 配置。
     */
    private RTCConfiguration buildRtcConfiguration() {
        RTCConfiguration configuration = new RTCConfiguration();
        for (String url : buildIceServerUrls()) {
            RTCIceServer iceServer = new RTCIceServer();
            iceServer.urls.add(url);
            configuration.iceServers.add(iceServer);
        }
        return configuration;
    }

    /**
     * 返回当前建链使用的 ICE server URL。
     */
    private List<String> buildIceServerUrls() {
        return rtcProperties.iceServers();
    }

    /**
     * 等待创建 answer 完成。
     */
    private RTCSessionDescription createAnswer(RTCPeerConnection peerConnection) {
        CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
        peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                future.complete(description);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException("创建 RTC answer 失败: " + error));
            }
        });
        return awaitFuture(future);
    }

    /**
     * 等待设置本地 SDP 完成。
     */
    private void setLocalDescription(RTCPeerConnection peerConnection, RTCSessionDescription description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException("设置本地 SDP 失败: " + error));
            }
        });
        awaitFuture(future);
    }

    /**
     * 等待设置远端 SDP 完成。
     */
    private void setRemoteDescription(RTCPeerConnection peerConnection, RTCSessionDescription description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException("设置远端 SDP 失败: " + error));
            }
        });
        awaitFuture(future);
    }

    /**
     * 统一等待 signaling Future 结果。
     */
    private <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get(rtcProperties.signalingTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("RTC signaling 超时或失败", ex);
        }
    }

    /**
     * 获取当前 sessionId 对应的 RTC 上下文。
     */
    private RtcPeerConnectionContext requireContext(String sessionId) {
        RtcPeerConnectionContext context = rtcContexts.get(sessionId);
        if (context == null) {
            throw new IllegalArgumentException("RTC 会话不存在: " + sessionId);
        }
        return context;
    }

    /**
     * 当前会话的 PeerConnection 观察器。
     */
    private class SessionPeerConnectionObserver implements PeerConnectionObserver {
        /**
         * 当前语音会话状态。
         */
        private final SessionState sessionState;

        /**
         * 当前 RTC 运行态的状态上报器。
         */
        private final RtcRuntimeStateReporter stateReporter;

        /**
         * 创建当前会话观察器。
         */
        private SessionPeerConnectionObserver(SessionState sessionState, RtcRuntimeStateReporter stateReporter) {
            this.sessionState = sessionState;
            this.stateReporter = stateReporter;
        }

        /**
         * 服务端生成 candidate 后，通过现有 WebSocket 控制面通知浏览器。
         */
        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            playbackDispatcher.send(
                    sessionState,
                    AgentEvent.of(
                            EventType.rtc_ice_candidate,
                            sessionState.sessionId(),
                            sessionState.currentTurnId(),
                            new RtcIceCandidatePayload(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                    )
            );
        }

        /**
         * 建链失败或关闭时清理整个 RTC 会话。
         */
        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            stateReporter.peerConnectionState(state);
            if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                closeSession(sessionState.sessionId(), "peer_connection_" + state.name().toLowerCase());
            }
        }

        /**
         * ICE 失败同样需要清理上下文，避免资源泄漏。
         */
        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            stateReporter.iceConnectionState(state);
            if (state == RTCIceConnectionState.FAILED || state == RTCIceConnectionState.CLOSED) {
                closeSession(sessionState.sessionId(), "ice_" + state.name().toLowerCase());
            }
        }

        /**
         * 收到远端音轨后，把音频桥接到现有语音流水线。
         */
        @Override
        public void onTrack(RTCRtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (!(track instanceof AudioTrack audioTrack)) {
                return;
            }
            RtcPeerConnectionContext context = requireContext(sessionState.sessionId());
            context.bindInboundTrack(audioTrack);
            context.stateReporter().trackBound();
        }
    }

    /**
     * RTC 会话运行态。
     */
    private final class RtcPeerConnectionContext {
        /**
         * 当前语音会话状态。
         */
        private final SessionState sessionState;

        /**
         * 当前 PeerConnection。
         */
        private final RTCPeerConnection peerConnection;

        /**
         * 当前会话的自定义下行音频源。
         */
        private final CustomAudioSource outboundAudioSource;

        /**
         * 当前会话的下行音轨。
         */
        private final AudioTrack outboundAudioTrack;

        /**
         * 当前会话的 RTC 音频下行适配器。
         */
        private final RtcAudioEgressAdapter audioEgressAdapter;

        /**
         * 当前 RTC 会话的状态上报器。
         */
        private final RtcRuntimeStateReporter stateReporter;

        /**
         * 远端 SDP 是否已经设置完成。
         */
        private volatile Boolean remoteDescriptionApplied = false;

        /**
         * 在 remote description 就绪前暂存的 trickle candidate。
         */
        private final List<RTCIceCandidate> pendingRemoteIceCandidates = new ArrayList<>();

        /**
         * 当前绑定的入站音频轨。
         */
        private volatile AudioTrack inboundAudioTrack;

        /**
         * 当前绑定的入站音频桥接器。
         */
        private volatile RtcAudioIngressBridge audioIngressBridge;

        /**
         * 创建 RTC 会话上下文。
         */
        private RtcPeerConnectionContext(SessionState sessionState,
                                         RTCPeerConnection peerConnection,
                                         CustomAudioSource outboundAudioSource,
                                         AudioTrack outboundAudioTrack,
                                         RtcAudioEgressAdapter audioEgressAdapter,
                                         RtcRuntimeStateReporter stateReporter) {
            this.sessionState = sessionState;
            this.peerConnection = peerConnection;
            this.outboundAudioSource = outboundAudioSource;
            this.outboundAudioTrack = outboundAudioTrack;
            this.audioEgressAdapter = audioEgressAdapter;
            this.stateReporter = stateReporter;
        }

        /**
         * 把新收到的远端音轨绑定到流水线桥接器。
         */
        private synchronized void bindInboundTrack(AudioTrack audioTrack) {
            if (inboundAudioTrack != null && audioIngressBridge != null) {
                inboundAudioTrack.removeSink(audioIngressBridge);
                audioIngressBridge.close();
            }
            inboundAudioTrack = audioTrack;
            audioIngressBridge = new RtcAudioIngressBridge(sessionState, voicePipelineService, stateReporter::mediaFlowing);
            inboundAudioTrack.addSink(audioIngressBridge);
        }

        /**
         * remote description 设置完成后，批量补交之前缓存的 candidate。
         */
        private synchronized void flushPendingRemoteIceCandidates() {
            for (RTCIceCandidate candidate : pendingRemoteIceCandidates) {
                peerConnection.addIceCandidate(candidate);
            }
            pendingRemoteIceCandidates.clear();
        }

        /**
         * 释放 PeerConnection 与桥接资源。
         */
        private synchronized void close() {
            if (inboundAudioTrack != null && audioIngressBridge != null) {
                inboundAudioTrack.removeSink(audioIngressBridge);
                audioIngressBridge.close();
            }
            sessionState.audioEgressAdapter(null);
            audioEgressAdapter.close();
            peerConnection.close();
            outboundAudioTrack.dispose();
            outboundAudioSource.dispose();
        }

        private RTCPeerConnection peerConnection() {
            return peerConnection;
        }

        private List<RTCIceCandidate> pendingRemoteIceCandidates() {
            return pendingRemoteIceCandidates;
        }

        private Boolean remoteDescriptionApplied() {
            return remoteDescriptionApplied;
        }

        private RtcRuntimeStateReporter stateReporter() {
            return stateReporter;
        }

        private void remoteDescriptionApplied(Boolean remoteDescriptionApplied) {
            this.remoteDescriptionApplied = remoteDescriptionApplied;
        }
    }

    /**
     * RTC 会话创建结果。
     *
     * @param sessionId  服务端会话标识
     * @param iceServers 当前建链使用的 ICE server 列表
     * @author kongweiguang
     */
    public record RtcSessionDescriptor(String sessionId,
                                       List<String> iceServers) {
    }
}
