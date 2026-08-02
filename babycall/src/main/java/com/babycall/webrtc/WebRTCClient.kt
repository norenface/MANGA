package com.babycall.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
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
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
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
 * The baby role additionally supports sharing its screen (whatever game or
 * app is on screen) as a second video track alongside the camera, sent over
 * the same connections -- see [grantScreenCapturePermission] and
 * [startScreenShare]. Because a MediaProjection consent token can only be
 * redeemed once, the resulting capture is created a single time and left
 * running for as long as this client instance lives, rather than being
 * stopped and restarted with each call.
 *
 * For a viewer's one-shot call screen, create one instance and call [close]
 * when leaving so the camera/mic are released immediately. For the baby
 * role, one instance is instead kept alive for the whole listening service's
 * lifetime (see [com.babycall.call.BabyCallManager]) so the screen-share
 * grant and video track survive across separate viewers connecting and
 * disconnecting.
 */
class WebRTCClient(
    private val context: Context,
    private val turnServers: List<PeerConnection.IceServer> = emptyList()
) {
    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy { buildFactory() }
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val cameraRenderers = mutableMapOf<String, SurfaceViewRenderer>()
    private val screenRenderers = mutableMapOf<String, SurfaceViewRenderer>()
    private val screenSenders = mutableMapOf<String, RtpSender>()

    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null

    private var screenCapturePermission: Intent? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenVideoSource: VideoSource? = null
    private var localScreenVideoTrack: VideoTrack? = null

    var onIceCandidate: ((peerId: String, candidate: IceCandidate) -> Unit)? = null
    var onConnectionFailed: ((peerId: String) -> Unit)? = null
    var onScreenShareStopped: (() -> Unit)? = null

    /** Fired the moment a remote participant's shared-screen track starts arriving (viewer role only). */
    var onScreenTrackReceived: ((peerId: String) -> Unit)? = null

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

    /** Pass null for the baby role, which never previews its own camera. */
    fun startLocalVideo(localRenderer: SurfaceViewRenderer?) {
        this.localRenderer = localRenderer
        localRenderer?.init(eglBase.eglBaseContext, null)
        localRenderer?.setMirror(true)

        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        localVideoSource = source
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("${CAMERA_TRACK_PREFIX}${System.currentTimeMillis()}", source)
        localRenderer?.let { videoTrack.addSink(it) }
        localVideoTrack = videoTrack

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_${System.currentTimeMillis()}", audioSource)
    }

    /** Stops and releases the camera/mic (e.g. when the last viewer leaves) without
     *  disposing the factory/EGL context, so [startLocalVideo] can be called again
     *  later to resume -- used by the baby role, which keeps one client alive for
     *  its whole listening lifetime rather than one per call. */
    fun stopLocalMedia() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoSource?.dispose()
        localVideoSource = null
        localVideoTrack = null
        localAudioTrack = null
        localRenderer?.release()
        localRenderer = null
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
     * non-null [cameraRenderer] to display their incoming camera video (used
     * by a viewer showing the baby's camera, or the baby's own small bubble
     * showing the first connected viewer); pass null to receive but not
     * render it. [screenRenderer] is only meaningful for a viewer's
     * connection to the baby -- pass non-null there to display the baby's
     * shared screen; every other case should leave it null.
     */
    fun createPeerConnection(peerId: String, cameraRenderer: SurfaceViewRenderer?, screenRenderer: SurfaceViewRenderer? = null) {
        cameraRenderer?.init(eglBase.eglBaseContext, null)
        if (cameraRenderer != null) cameraRenderers[peerId] = cameraRenderer
        screenRenderer?.init(eglBase.eglBaseContext, null)
        if (screenRenderer != null) screenRenderers[peerId] = screenRenderer

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
                    if (track.id().startsWith(SCREEN_TRACK_PREFIX)) {
                        screenRenderers[peerId]?.let { track.addSink(it) }
                        onScreenTrackReceived?.invoke(peerId)
                    } else {
                        cameraRenderers[peerId]?.let { track.addSink(it) }
                    }
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
        localScreenVideoTrack?.let { screenSenders[peerId] = pc.addTrack(it, listOf("babycall_screen_stream")) }
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
        cameraRenderers.remove(peerId)?.release()
        screenRenderers.remove(peerId)?.release()
        screenSenders.remove(peerId)
    }

    fun activePeerCount(): Int = peerConnections.size

    /** Stores the one-time MediaProjection consent so [startScreenShare] can redeem it later. */
    fun grantScreenCapturePermission(resultData: Intent) {
        screenCapturePermission = resultData
    }

    fun hasScreenCapturePermission(): Boolean = screenCapturePermission != null

    fun isScreenSharing(): Boolean = localScreenVideoTrack != null

    fun isLocalMediaActive(): Boolean = localVideoTrack != null

    /**
     * Starts sharing the device screen as a second video track, attached to
     * every currently-open (and every future) peer connection. The
     * MediaProjection consent token this redeems can only be used once, so
     * this must only ever be called a single time per granted permission --
     * screen sharing is meant to run continuously for as long as this
     * client instance lives, not be stopped and restarted per call.
     */
    fun startScreenShare(width: Int, height: Int): Boolean {
        if (localScreenVideoTrack != null) return true
        val resultData = screenCapturePermission ?: return false
        return try {
            val capturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    screenCapturePermission = null
                    onScreenShareStopped?.invoke()
                }
            })
            val source = peerConnectionFactory.createVideoSource(true)
            val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
            capturer.startCapture(width, height, SCREEN_SHARE_FPS)

            val track = peerConnectionFactory.createVideoTrack("${SCREEN_TRACK_PREFIX}${System.currentTimeMillis()}", source)
            screenCapturer = capturer
            screenVideoSource = source
            localScreenVideoTrack = track

            peerConnections.forEach { (peerId, pc) ->
                screenSenders[peerId] = pc.addTrack(track, listOf("babycall_screen_stream"))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        peerConnections.values.forEach {
            it.close()
            it.dispose()
        }
        peerConnections.clear()
        cameraRenderers.values.forEach { it.release() }
        cameraRenderers.clear()
        screenRenderers.values.forEach { it.release() }
        screenRenderers.clear()
        screenSenders.clear()

        stopLocalMedia()

        try {
            screenCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        screenCapturer?.dispose()
        screenCapturer = null
        screenVideoSource?.dispose()
        screenVideoSource = null
        localScreenVideoTrack = null
        screenCapturePermission = null

        peerConnectionFactory.dispose()
        eglBase.release()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val CAMERA_TRACK_PREFIX = "cam_"
        private const val SCREEN_TRACK_PREFIX = "screen_"
        private const val SCREEN_SHARE_FPS = 8
    }
}
