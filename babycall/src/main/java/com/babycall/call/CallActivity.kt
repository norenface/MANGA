package com.babycall.call

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityCallBinding
import com.babycall.model.CallState
import com.babycall.security.PinDialog
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.SignalingFactory
import com.babycall.webrtc.SignalingRepository
import com.babycall.webrtc.SignalingRoomRepository
import com.babycall.webrtc.WebRTCClient
import kotlinx.coroutines.launch

/**
 * Shared call screen for both roles, working over either transport (cloud
 * Firebase or local Wi-Fi socket — see [SignalingFactory]).
 *
 * Baby role, cloud mode: several viewers (the family's creator and anyone
 * who joined later with the invite code) can be connected at once, each
 * with their own independent WebRTC connection (see [SignalingRoomRepository]);
 * one leaving never disconnects the others. Baby role, local mode: limited
 * to a single viewer, unchanged from the original design.
 *
 * Baby role (either mode): no end-call button is shown at all. Ending a
 * call requires holding a small, unlabeled dot in the corner for 3
 * uninterrupted seconds and then entering the family PIN — something a baby
 * cannot do by chance — and disconnects everyone at once. The screen is
 * pinned (Lock Task) so the back/recents/home gestures cannot exit either.
 *
 * Viewer role (parent, or an invited relative): normal call controls, no
 * restrictions. Calling when someone else is already connected simply joins
 * the same ongoing call instead of failing.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var prefs: Prefs
    private lateinit var role: String
    private lateinit var familyId: String
    private lateinit var myDeviceId: String
    private lateinit var webRTCClient: WebRTCClient

    /** peerId -> that peer's signaling channel. One entry for a viewer or a local-mode baby; one per connected viewer for a cloud-mode baby. */
    private val sessions = mutableMapOf<String, CallSignaling>()
    private var mySessionKey: String? = null
    private var callEnded = false
    private var offerPendingManualAnswer = false
    private var pendingManualAnswerPeerId: String? = null

    private var roomRepo: SignalingRoomRepository? = null

    private var holdRunnable: Runnable? = null
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        role = prefs.role ?: "parent"
        familyId = prefs.familyId ?: run { finish(); return }
        myDeviceId = prefs.deviceId

        webRTCClient = WebRTCClient(this)
        webRTCClient.onIceCandidate = { peerId, candidate ->
            lifecycleScope.launch { runCatching { sessions[peerId]?.sendIceCandidate(myDeviceId, candidate) } }
        }
        webRTCClient.onConnectionFailed = { peerId ->
            runOnUiThread { endSession(peerId) }
        }

        webRTCClient.startLocalVideo(binding.localRenderer)

        if (role == "parent") {
            setupParentUi()
        } else {
            setupBabyUi()
            engageBabySafety()
        }

        lifecycleScope.launch {
            if (!prefs.isLocalMode) runCatching { AuthGate.ensureSignedIn() }

            if (role == "parent") {
                val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID)
                if (calleeId != null) wireOutgoing(calleeId)
            } else if (prefs.isLocalMode) {
                // Local mode already knows who's calling (CallListenerService
                // learned it synchronously before launching this screen).
                val callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
                wireIncomingSingle(callerId)
            } else {
                startBabyRoom()
            }
        }
    }

    // ---- Viewer (parent / invited relative) — single session, either mode ----

    private fun wireOutgoing(calleeId: String) {
        mySessionKey = calleeId
        val signaling = SignalingFactory.create(this, prefs)
        sessions[calleeId] = signaling

        webRTCClient.createPeerConnection(calleeId, binding.remoteRenderer)

        lifecycleScope.launch {
            runCatching {
                signaling.startCall(myDeviceId, calleeId)
                webRTCClient.createOffer(calleeId) { sdp ->
                    lifecycleScope.launch { runCatching { signaling.sendOffer(sdp) } }
                }
            }.onFailure { e ->
                runOnUiThread {
                    binding.tvStatus.text = e.message ?: getString(R.string.error_generic)
                }
            }
        }
        signaling.observeAnswer { sdp -> webRTCClient.setRemoteDescription(calleeId, sdp) }
        signaling.observeIceCandidates(calleeId) { webRTCClient.addIceCandidate(calleeId, it) }
        signaling.observeCallInfo { info ->
            if (info.state == CallState.CONNECTED) {
                runOnUiThread { binding.tvStatus.visibility = android.view.View.GONE }
            }
            if (info.state == CallState.ENDED) {
                runOnUiThread { finishCall() }
            }
        }
    }

    // ---- Baby, local mode — single session (unchanged design) ----

    private fun wireIncomingSingle(callerId: String) {
        mySessionKey = callerId
        val signaling = SignalingFactory.create(this, prefs)
        sessions[callerId] = signaling

        webRTCClient.createPeerConnection(callerId, remoteRenderer = null)

        signaling.observeOffer { sdp ->
            webRTCClient.setRemoteDescription(callerId, sdp)
            if (prefs.autoAnswer) {
                createAndSendAnswer(callerId)
            } else {
                offerPendingManualAnswer = true
                pendingManualAnswerPeerId = callerId
                runOnUiThread { binding.btnAnswer.visibility = android.view.View.VISIBLE }
            }
        }
        signaling.observeIceCandidates(callerId) { webRTCClient.addIceCandidate(callerId, it) }
        signaling.observeCallInfo { info ->
            if (info.state == CallState.ENDED) {
                runOnUiThread { finishCall() }
            }
        }
    }

    // ---- Baby, cloud mode — one independent session per connected viewer ----

    private fun startBabyRoom() {
        val repo = SignalingRoomRepository(familyId)
        roomRepo = repo
        repo.observeSessions(
            onNewSession = { sessionId, _ -> runOnUiThread { handleNewViewerSession(sessionId) } },
            onSessionEnded = { sessionId -> runOnUiThread { endSession(sessionId) } }
        )
    }

    private fun handleNewViewerSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) return
        val signaling = SignalingRepository(familyId, sessionId)
        sessions[sessionId] = signaling

        webRTCClient.createPeerConnection(sessionId, remoteRenderer = null)

        signaling.observeOffer { sdp ->
            webRTCClient.setRemoteDescription(sessionId, sdp)
            if (prefs.autoAnswer) {
                createAndSendAnswer(sessionId)
            } else {
                offerPendingManualAnswer = true
                pendingManualAnswerPeerId = sessionId
                runOnUiThread { binding.btnAnswer.visibility = android.view.View.VISIBLE }
            }
        }
        signaling.observeIceCandidates(sessionId) { webRTCClient.addIceCandidate(sessionId, it) }
        updateViewerCountUi()
    }

    private fun createAndSendAnswer(peerId: String) {
        offerPendingManualAnswer = false
        pendingManualAnswerPeerId = null
        webRTCClient.createAnswer(peerId) { answerSdp ->
            lifecycleScope.launch { runCatching { sessions[peerId]?.sendAnswer(answerSdp) } }
        }
    }

    private fun updateViewerCountUi() {
        binding.tvStatus.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = getString(R.string.viewer_count_format, sessions.size)
    }

    // ---- Ending one session (works uniformly for viewer, local baby, or one of a cloud baby's viewers) ----

    private fun endSession(peerId: String) {
        webRTCClient.closePeerConnection(peerId)
        sessions.remove(peerId)?.release()
        if (pendingManualAnswerPeerId == peerId) {
            offerPendingManualAnswer = false
            pendingManualAnswerPeerId = null
            binding.btnAnswer.visibility = android.view.View.GONE
        }

        if (role == "baby" && !prefs.isLocalMode) {
            if (sessions.isEmpty()) {
                finishCall()
            } else {
                updateViewerCountUi()
            }
        } else {
            // Viewer, or a local-mode baby: this was the only session.
            finishCall()
        }
    }

    // ---- Parent UI ----

    private fun setupParentUi() {
        binding.groupBabyEndControl.visibility = android.view.View.GONE
        binding.groupParentControls.visibility = android.view.View.VISIBLE

        binding.btnEndCall.setOnClickListener {
            val key = mySessionKey
            lifecycleScope.launch {
                if (key != null) runCatching { sessions[key]?.endCall(myDeviceId) }
                finishCall()
            }
        }
        binding.btnMute.setOnClickListener {
            binding.btnMute.isSelected = !binding.btnMute.isSelected
            webRTCClient.setMuted(binding.btnMute.isSelected)
        }
        binding.btnSwitchCamera.setOnClickListener { webRTCClient.switchCamera() }
    }

    // ---- Baby UI (safety-critical) ----

    private fun setupBabyUi() {
        binding.groupParentControls.visibility = android.view.View.GONE
        binding.groupBabyEndControl.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = getString(R.string.call_connecting)

        binding.btnAnswer.setOnClickListener {
            binding.btnAnswer.visibility = android.view.View.GONE
            pendingManualAnswerPeerId?.let { createAndSendAnswer(it) }
        }

        binding.hiddenEndDot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val runnable = Runnable { showParentPinToEnd() }
                    holdRunnable = runnable
                    holdHandler.postDelayed(runnable, HOLD_DURATION_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { holdHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun engageBabySafety() {
        try {
            startLockTask()
        } catch (_: Exception) {
            // Screen pinning may require one-time user consent on first use; safe to ignore.
        }
    }

    private fun releaseBabySafety() {
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
    }

    private fun showParentPinToEnd() {
        val pinHash = prefs.pinHash ?: return
        PinDialog.show(this, titleRes = R.string.pin_dialog_end_call_title) { rawPin ->
            if (Prefs.hashPin(rawPin) == pinHash) {
                lifecycleScope.launch {
                    // Disconnects everyone at once, regardless of how many are in the room.
                    sessions.values.toList().forEach { runCatching { it.endCall(myDeviceId) } }
                    finishCall()
                }
                true
            } else {
                false
            }
        }
    }

    override fun onBackPressed() {
        if (role == "baby") {
            // Swallow back presses entirely; ending a baby-side call always
            // requires the hidden hold + PIN gesture, never a system gesture.
            return
        }
        super.onBackPressed()
    }

    // ---- Teardown ----

    private fun finishCall() {
        if (callEnded) return
        callEnded = true
        releaseBabySafety()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        roomRepo?.release()
        sessions.values.forEach { it.release() }
        sessions.clear()
        holdRunnable?.let { holdHandler.removeCallbacks(it) }
        webRTCClient.close()
    }

    companion object {
        const val EXTRA_CALLEE_ID = "extra_callee_id"
        const val EXTRA_CALLER_ID = "extra_caller_id"
        private const val HOLD_DURATION_MS = 3000L
    }
}
