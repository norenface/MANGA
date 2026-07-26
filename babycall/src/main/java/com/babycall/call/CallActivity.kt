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
import com.babycall.pairing.FamilySettings
import com.babycall.pairing.PairingRepository
import com.babycall.security.PinDialog
import com.babycall.webrtc.SignalingRepository
import com.babycall.webrtc.WebRTCClient
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

/**
 * Shared call screen for both roles.
 *
 * Baby role: no end-call button is shown at all. Ending a call requires
 * holding a small, unlabeled dot in the corner for 3 uninterrupted seconds
 * and then entering the family PIN — something a baby cannot do by chance.
 * The screen is pinned (Lock Task) so the back/recents/home gestures cannot
 * exit the call either.
 *
 * Parent role: normal call controls, no restrictions.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var prefs: Prefs
    private lateinit var role: String
    private lateinit var familyId: String
    private lateinit var myDeviceId: String

    private lateinit var signaling: SignalingRepository
    private lateinit var webRTCClient: WebRTCClient
    private val pairingRepo = PairingRepository()

    private var opponentId: String? = null
    private var signalingWired = false
    private var callEnded = false

    private var callInfoListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null
    private var settingsListener: ValueEventListener? = null

    private var currentSettings = FamilySettings()
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
        signaling = SignalingRepository(familyId)

        webRTCClient = WebRTCClient(this)
        webRTCClient.onIceCandidate = { candidate ->
            opponentId?.let { opp ->
                lifecycleScope.launch { runCatching { signaling.sendIceCandidate(myDeviceId, candidate) } }
            }
        }
        webRTCClient.onConnectionFailed = {
            runOnUiThread { finishCall() }
        }

        webRTCClient.startLocalVideo(binding.localRenderer)
        webRTCClient.initPeerConnection(binding.remoteRenderer)

        if (role == "parent") {
            setupParentUi()
        } else {
            setupBabyUi()
            engageBabySafety()
        }

        lifecycleScope.launch {
            runCatching { AuthGate.ensureSignedIn() }

            settingsListener = pairingRepo.observeSettings(familyId) { currentSettings = it }

            if (role == "parent") {
                val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID)
                if (calleeId != null) {
                    opponentId = calleeId
                    wireOutgoing(calleeId)
                }
            }

            observeCallLifecycle()
        }
    }

    // ---- Signaling wiring ----

    private fun wireOutgoing(calleeId: String) {
        signalingWired = true
        lifecycleScope.launch {
            runCatching {
                signaling.startCall(myDeviceId, calleeId)
                webRTCClient.createOffer { sdp ->
                    lifecycleScope.launch { runCatching { signaling.sendOffer(sdp) } }
                }
            }
        }
        answerListener = signaling.observeAnswer { sdp -> webRTCClient.setRemoteDescription(sdp) }
        iceListener = signaling.observeIceCandidates(calleeId) { webRTCClient.addIceCandidate(it) }
    }

    private fun wireIncoming(callerId: String) {
        if (signalingWired) return
        signalingWired = true
        opponentId = callerId
        offerListener = signaling.observeOffer { sdp ->
            webRTCClient.setRemoteDescription(sdp)
            webRTCClient.createAnswer { answerSdp ->
                lifecycleScope.launch { runCatching { signaling.sendAnswer(answerSdp) } }
            }
        }
        iceListener = signaling.observeIceCandidates(callerId) { webRTCClient.addIceCandidate(it) }
    }

    private fun observeCallLifecycle() {
        callInfoListener = signaling.observeCallInfo { info ->
            if (role == "baby" && !signalingWired && info.state == CallState.RINGING && info.calleeId == myDeviceId) {
                runOnUiThread { wireIncoming(info.callerId) }
            }
            if (info.state == CallState.CONNECTED) {
                runOnUiThread { binding.tvStatus.visibility = android.view.View.GONE }
            }
            if (info.state == CallState.ENDED) {
                runOnUiThread { finishCall() }
            }
        }
    }

    // ---- Parent UI ----

    private fun setupParentUi() {
        binding.groupBabyEndControl.visibility = android.view.View.GONE
        binding.groupParentControls.visibility = android.view.View.VISIBLE

        binding.btnEndCall.setOnClickListener {
            lifecycleScope.launch {
                runCatching { signaling.endCall(myDeviceId) }
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
        val pinHash = currentSettings.pinHash
        if (pinHash == null) return
        PinDialog.show(this, titleRes = R.string.pin_dialog_end_call_title) { rawPin ->
            if (Prefs.hashPin(rawPin) == pinHash) {
                lifecycleScope.launch {
                    runCatching { signaling.endCall(myDeviceId) }
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
        callInfoListener?.let { signaling.removeCallInfoListener(it) }
        offerListener?.let { signaling.removeOfferListener(it) }
        answerListener?.let { signaling.removeAnswerListener(it) }
        opponentId?.let { opp -> iceListener?.let { signaling.removeIceCandidatesListener(opp, it) } }
        settingsListener?.let { pairingRepo.removeSettingsListener(familyId, it) }
        holdRunnable?.let { holdHandler.removeCallbacks(it) }
        webRTCClient.close()
    }

    companion object {
        const val EXTRA_CALLEE_ID = "extra_callee_id"
        private const val HOLD_DURATION_MS = 3000L
    }
}
