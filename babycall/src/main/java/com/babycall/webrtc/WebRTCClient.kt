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
 * Thin wrapper around org.webrtc's PeerConnection APIs. One instance per call;
 * call [close] when the call ends so the camera/mic are released immediately
 * (important on a baby's device, which should not keep recording between calls).
 */
class WebRTCClient(
    private val context: Context,
    private val turnServers: List<PeerConnection.IceServer> = emptyList()
) {
    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy { buildFactory() }
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onConnectionFailed: (() -> Unit)? = null

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

    fun initPeerConnection(remoteRenderer: SurfaceViewRenderer) {
        this.remoteRenderer = remoteRenderer
        remoteRenderer.init(eglBase.eglBaseContext, null)

        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        iceServers.addAll(turnServers)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    track.addSink(remoteRenderer)
                    onRemoteVideoTrack?.invoke(track)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    onConnectionFailed?.invoke()
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
        })

        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("babycall_stream")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("babycall_stream")) }
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        videoCapturer?.dispose()
        localVideoSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        localRenderer?.release()
        remoteRenderer?.release()
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
