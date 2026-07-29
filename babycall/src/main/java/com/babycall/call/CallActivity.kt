package com.babycall.call

import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityCallBinding
import com.babycall.model.CallState
import com.babycall.peer.PeerHubHolder
import com.babycall.peer.PeerMeshLink
import com.babycall.peer.PeerSessionSubscription
import com.babycall.peer.PeerViewerConnection
import com.babycall.security.PinDialog
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.SignalingFactory
import com.babycall.webrtc.WebRTCClient
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * Shared call screen for both roles, working over either transport (the
 * peer-broker "online" mode or the local Wi-Fi socket — see
 * [SignalingFactory] for local mode, [PeerViewerConnection]/[PeerHubHolder]
 * for online mode).
 *
 * Online mode is a full group call: the baby device keeps one independent
 * WebRTC connection per connected viewer (see [PeerHubHolder]), AND every
 * viewer connects directly to every other viewer too (see [PeerMeshLink])
 * so relatives can see and hear each other, not just the baby. Every
 * participant's screen shows one video tile per other participant it's
 * connected to, arranged in a simple grid that grows and shrinks as people
 * join or leave. One participant leaving only tears down their own
 * connections; everyone else stays connected. Local mode is unaffected — a
 * single baby, a single viewer, unchanged from the original design.
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

    /** peerId -> that peer's baby<->viewer signaling. One entry for a viewer or a local-mode baby; one per connected viewer for an online-mode baby. */
    private val sessions = mutableMapOf<String, CallSignaling>()

    /** otherViewerId -> direct viewer-to-viewer signaling (online-mode viewers only). */
    private val meshLinks = mutableMapOf<String, PeerMeshLink>()

    private val remoteTiles = mutableMapOf<String, SurfaceViewRenderer>()

    private var callEnded = false
    private var offerPendingManualAnswer = false
    private var pendingManualAnswerPeerId: String? = null

    /** Online-mode viewer only: the one broker connection used for both the call-to-baby leg and all mesh legs. */
    private var viewerConnection: PeerViewerConnection? = null

    /** Online-mode baby only: this CallActivity's subscription to the persistent [PeerHubHolder] instance. */
    private var hubSubscription: PeerSessionSubscription? = null

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
            lifecycleScope.launch {
                runCatching {
                    sessions[peerId]?.sendIceCandidate(myDeviceId, candidate)
                        ?: meshLinks[peerId]?.sendIceCandidate(myDeviceId, candidate)
                }
            }
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
            if (role == "parent") {
                val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID)
                if (calleeId != null) {
                    wireOutgoing(calleeId)
                    if (!prefs.isLocalMode) startViewerMeshDiscovery()
                }
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

    // ---- Viewer (parent / invited relative) — link to the baby, either mode ----

    private fun wireOutgoing(calleeId: String) {
        val signaling: CallSignaling = if (prefs.isLocalMode) {
            SignalingFactory.create(this, prefs)
        } else {
            PeerViewerConnection(familyId, myDeviceId).also { viewerConnection = it }
        }
        sessions[calleeId] = signaling

        val tile = addRemoteTile(calleeId)
        webRTCClient.createPeerConnection(calleeId, tile)

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

    // ---- Viewer-to-viewer mesh (online mode only): see and hear every other viewer directly ----

    private fun startViewerMeshDiscovery() {
        val vc = viewerConnection ?: return
        vc.observeRoster(
            onAdd = { peerId -> runOnUiThread { handleNewMeshPeer(peerId) } },
            onRemove = { peerId -> runOnUiThread { endSession(peerId) } }
        )
    }

    private fun handleNewMeshPeer(otherViewerId: String) {
        if (meshLinks.containsKey(otherViewerId)) return
        val vc = viewerConnection ?: return
        val mesh = PeerMeshLink(vc, otherViewerId)
        meshLinks[otherViewerId] = mesh

        val tile = addRemoteTile(otherViewerId)
        webRTCClient.createPeerConnection(otherViewerId, tile)

        // Deterministic roles avoid both sides creating an offer at once (glare).
        if (myDeviceId < otherViewerId) {
            webRTCClient.createOffer(otherViewerId) { sdp ->
                lifecycleScope.launch { runCatching { mesh.sendOffer(sdp) } }
            }
            mesh.observeAnswer { sdp -> webRTCClient.setRemoteDescription(otherViewerId, sdp) }
        } else {
            mesh.observeOffer { sdp ->
                webRTCClient.setRemoteDescription(otherViewerId, sdp)
                webRTCClient.createAnswer(otherViewerId) { answerSdp ->
                    lifecycleScope.launch { runCatching { mesh.sendAnswer(answerSdp) } }
                }
            }
        }
        mesh.observeIceCandidates(otherViewerId) { webRTCClient.addIceCandidate(otherViewerId, it) }
    }

    // ---- Baby, local mode — single viewer (unchanged design, now also shows their video) ----

    private fun wireIncomingSingle(callerId: String) {
        val signaling = SignalingFactory.create(this, prefs)
        sessions[callerId] = signaling

        val tile = addRemoteTile(callerId)
        webRTCClient.createPeerConnection(callerId, tile)

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

    // ---- Baby, online mode — one independent connection per connected viewer ----

    private fun startBabyRoom() {
        val hub = PeerHubHolder.getOrCreate(prefs)
        hubSubscription = hub.observeSessions(
            onNewSession = { sessionId, signaling -> runOnUiThread { handleNewViewerSession(sessionId, signaling) } },
            onSessionEnded = { sessionId -> runOnUiThread { endSession(sessionId) } }
        )
    }

    private fun handleNewViewerSession(sessionId: String, signaling: CallSignaling) {
        if (sessions.containsKey(sessionId)) return
        sessions[sessionId] = signaling

        val tile = addRemoteTile(sessionId)
        webRTCClient.createPeerConnection(sessionId, tile)

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

    // ---- Video grid (every role): one tile per currently-connected participant ----

    private fun addRemoteTile(peerId: String): SurfaceViewRenderer {
        remoteTiles[peerId]?.let { return it }
        val renderer = SurfaceViewRenderer(this)
        remoteTiles[peerId] = renderer
        rebuildVideoGrid()
        return renderer
    }

    private fun removeRemoteTile(peerId: String) {
        if (remoteTiles.remove(peerId) != null) rebuildVideoGrid()
    }

    private fun rebuildVideoGrid() {
        val grid = binding.remoteVideoGrid
        grid.removeAllViews()
        val tiles = remoteTiles.values.toList()
        if (tiles.isEmpty()) return

        val columns = if (tiles.size <= 1) 1 else 2
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            var addedInRow = 0
            while (addedInRow < columns && i < tiles.size) {
                val tile = tiles[i]
                (tile.parent as? ViewGroup)?.removeView(tile)
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                i++
                addedInRow++
            }
            grid.addView(row)
        }
    }

    // ---- Ending one connection (viewer<->baby link, or a viewer<->viewer mesh link) ----

    private fun endSession(peerId: String) {
        webRTCClient.closePeerConnection(peerId)
        removeRemoteTile(peerId)
        if (pendingManualAnswerPeerId == peerId) {
            offerPendingManualAnswer = false
            pendingManualAnswerPeerId = null
            binding.btnAnswer.visibility = android.view.View.GONE
        }

        val wasBabyLink = sessions.containsKey(peerId)
        sessions.remove(peerId)?.release()
        meshLinks.remove(peerId)?.release()

        if (role == "baby" && !prefs.isLocalMode) {
            if (sessions.isEmpty()) {
                finishCall()
            } else {
                updateViewerCountUi()
            }
        } else if (wasBabyLink) {
            // My own connection to the baby ended — for a viewer, or a
            // local-mode baby (whose only connection this always is).
            finishCall()
        }
        // Otherwise this was a mesh peer (another viewer) leaving — just
        // drop their tile; my own call with the baby carries on.
    }

    // ---- Parent UI ----

    private fun setupParentUi() {
        binding.groupBabyEndControl.visibility = android.view.View.GONE
        binding.groupParentControls.visibility = android.view.View.VISIBLE

        binding.btnEndCall.setOnClickListener {
            lifecycleScope.launch {
                sessions.values.toList().forEach { runCatching { it.endCall(myDeviceId) } }
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
        hubSubscription?.let { PeerHubHolder.getOrCreate(prefs).removeSubscriber(it) }
        sessions.values.forEach { it.release() }
        sessions.clear()
        meshLinks.values.forEach { it.release() }
        meshLinks.clear()
        holdRunnable?.let { holdHandler.removeCallbacks(it) }
        webRTCClient.close()
    }

    companion object {
        const val EXTRA_CALLEE_ID = "extra_callee_id"
        const val EXTRA_CALLER_ID = "extra_caller_id"
        private const val HOLD_DURATION_MS = 3000L
    }
}
