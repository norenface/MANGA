package com.babycall.webrtc

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Thin wrapper around org.webrtc's PeerConnection APIs. One local camera/mic
 * capture is shared (a single MediaStreamTrack can be attached to any number
 * of PeerConnections in WebRTC), but each remote participant gets its own
 * independently-negotiated [PeerConnection] keyed by [peerId] — this is what
 * lets the baby device stay connected to several viewers at once (a parent,
 * a grandparent, an uncle, ...) without them interfering with each other:
 * closing one peer's connection never touches the others.
 *
 * One instance per call screen; call [close] when leaving the screen so the
 * camera/mic are released immediately.
 */
class WebRTCClient(
    private val context: Context,
    private val turnServers: List<PeerConnection.IceServer> = emptyList()
) {
    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy { buildFactory() }
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val remoteRenderers = mutableMapOf<String, SurfaceViewRenderer>()

    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null

    var onIceCandidate: ((peerId: String, candidate: IceCandidate) -> Unit)? = null
    var onConnectionFailed: ((peerId: String) -> Unit)? = null

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startLocalVideo(localRenderer: SurfaceViewRenderer) {
        this.localRenderer = localRenderer
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)

        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        localVideoSource = source
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("video_${System.currentTimeMillis()}", source)
        videoTrack.addSink(localRenderer)
        localVideoTrack = videoTrack

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_${System.currentTimeMillis()}", audioSource)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            enumerator.createCapturer(it, null)?.let { capturer -> return capturer }
        }
        deviceNames.firstOrNull()?.let {
            enumerator.createCapturer(it, null)?.let { capturer -> return capturer }
        }
        return null
    }

    /**
     * Creates a new, independent PeerConnection to one participant. Pass a
     * non-null [remoteRenderer] to display their incoming video (used by a
     * viewer showing the baby's camera); pass null to receive but not render
     * it (used by the baby, which doesn't display each viewer's video).
     */
    fun createPeerConnection(peerId: String, remoteRenderer: SurfaceViewRenderer?) {
        remoteRenderer?.init(eglBase.eglBaseContext, null)
        if (remoteRenderer != null) remoteRenderers[peerId] = remoteRenderer

        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        iceServers.addAll(turnServers)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(peerId, candidate)
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    remoteRenderers[peerId]?.let { track.addSink(it) }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    onConnectionFailed?.invoke(peerId)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: return

        peerConnections[peerId] = pc
        localVideoTrack?.let { pc.addTrack(it, listOf("babycall_stream")) }
        localAudioTrack?.let { pc.addTrack(it, listOf("babycall_stream")) }
    }

    fun createOffer(peerId: String, onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnections[peerId] ?: return
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun createAnswer(peerId: String, onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnections[peerId] ?: return
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun setRemoteDescription(peerId: String, sdp: SessionDescription) {
        peerConnections[peerId]?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addIceCandidate(peerId: String, candidate: IceCandidate) {
        peerConnections[peerId]?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    /** Ends and releases just one participant's connection; the others (and the shared camera/mic) keep running. */
    fun closePeerConnection(peerId: String) {
        peerConnections.remove(peerId)?.let {
            it.close()
            it.dispose()
        }
        remoteRenderers.remove(peerId)?.release()
    }

    fun activePeerCount(): Int = peerConnections.size

    fun close() {
        peerConnections.values.forEach {
            it.close()
            it.dispose()
        }
        peerConnections.clear()
        remoteRenderers.values.forEach { it.release() }
        remoteRenderers.clear()

        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        videoCapturer?.dispose()
        localVideoSource?.dispose()
        localRenderer?.release()
        peerConnectionFactory.dispose()
        eglBase.release()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
