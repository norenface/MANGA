package com.babycall.call

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityCallBinding
import com.babycall.model.CallState
import com.babycall.peer.PeerMeshLink
import com.babycall.peer.PeerViewerConnection
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.SignalingFactory
import com.babycall.webrtc.WebRTCClient
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * Call screen for a viewer (parent, or any invited relative) -- the baby
 * role never opens this screen at all any more; its side of every call is
 * instead handled ambiently by [BabyCallManager] (a small floating bubble,
 * never a full-screen takeover), for the reasons explained there.
 *
 * Online mode is a full group call: this viewer keeps one independent
 * WebRTC connection to the baby (see [PeerViewerConnection]), AND connects
 * directly to every other connected viewer too (see [PeerMeshLink]) so
 * relatives can see and hear each other, not just the baby. Local mode is
 * unaffected -- a single baby, a single viewer, unchanged from the original
 * design.
 *
 * The baby's connection alone may also carry a second video track: whatever
 * she's playing, shared as a full-screen backdrop (see [R.id.gameScreenRenderer])
 * behind the regular camera tile grid, if the caregiver has enabled screen
 * sharing on her device.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var prefs: Prefs
    private lateinit var familyId: String
    private lateinit var myDeviceId: String
    private lateinit var webRTCClient: WebRTCClient

    /** peerId -> that peer's baby<->viewer signaling. One entry for the baby, one per other connected viewer (mesh). */
    private val sessions = mutableMapOf<String, CallSignaling>()

    /** otherViewerId -> direct viewer-to-viewer signaling (online-mode viewers only). */
    private val meshLinks = mutableMapOf<String, PeerMeshLink>()

    private val remoteTiles = mutableMapOf<String, SurfaceViewRenderer>()

    private var callEnded = false
    private var gameScreenModeActive = false

    /** Online-mode viewer only: the one broker connection used for both the call-to-baby leg and all mesh legs. */
    private var viewerConnection: PeerViewerConnection? = null

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
        webRTCClient.onScreenTrackReceived = { peerId ->
            if (peerId == calleeId()) runOnUiThread { showGameScreenMode() }
        }

        webRTCClient.startLocalVideo(binding.localRenderer)
        setupCallControls()

        lifecycleScope.launch {
            val calleeId = calleeId()
            if (calleeId != null) {
                wireOutgoing(calleeId)
                if (!prefs.isLocalMode) startViewerMeshDiscovery()
            } else {
                finish()
            }
        }
    }

    private fun calleeId(): String? = intent.getStringExtra(EXTRA_CALLEE_ID)

    // ---- Link to the baby, either mode ----

    private fun wireOutgoing(calleeId: String) {
        val signaling: CallSignaling = if (prefs.isLocalMode) {
            SignalingFactory.create(this, prefs)
        } else {
            PeerViewerConnection(familyId, myDeviceId).also { viewerConnection = it }
        }
        sessions[calleeId] = signaling

        val tile = addRemoteTile(calleeId)
        webRTCClient.createPeerConnection(calleeId, tile, binding.gameScreenRenderer)

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
                runOnUiThread { binding.tvStatus.visibility = View.GONE }
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

    // ---- Video grid: one camera tile per currently-connected participant ----

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

    /** Shrinks the camera grid to a strip along the top once the baby's shared
     *  game screen actually starts showing something, so both stay visible at
     *  once instead of the (still full-bleed by default) camera tile hiding it. */
    private fun showGameScreenMode() {
        if (gameScreenModeActive) return
        gameScreenModeActive = true
        binding.gameScreenRenderer.visibility = View.VISIBLE
        val params = binding.remoteVideoGrid.layoutParams as ConstraintLayout.LayoutParams
        params.height = (GAME_SCREEN_MODE_STRIP_HEIGHT_DP * resources.displayMetrics.density).toInt()
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        binding.remoteVideoGrid.layoutParams = params
    }

    // ---- Ending one connection (viewer<->baby link, or a viewer<->viewer mesh link) ----

    private fun endSession(peerId: String) {
        webRTCClient.closePeerConnection(peerId)
        removeRemoteTile(peerId)

        val wasBabyLink = sessions.containsKey(peerId)
        sessions.remove(peerId)?.release()
        meshLinks.remove(peerId)?.release()

        if (wasBabyLink) {
            // My own connection to the baby ended.
            finishCall()
        }
        // Otherwise this was a mesh peer (another viewer) leaving — just
        // drop their tile; my own call with the baby carries on.
    }

    // ---- Call controls ----

    private fun setupCallControls() {
        binding.tvStatus.text = getString(R.string.call_connecting)
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

    // ---- Teardown ----

    private fun finishCall() {
        if (callEnded) return
        callEnded = true
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessions.values.forEach { it.release() }
        sessions.clear()
        meshLinks.values.forEach { it.release() }
        meshLinks.clear()
        webRTCClient.close()
    }

    companion object {
        const val EXTRA_CALLEE_ID = "extra_callee_id"
        private const val GAME_SCREEN_MODE_STRIP_HEIGHT_DP = 140
    }
}
