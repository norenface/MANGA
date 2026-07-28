package com.babycall.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.babycall.Prefs
import com.babycall.model.CallState
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.RemoteCallInfo
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Runs on the baby device in local (same-Wi-Fi) mode. Accepts exactly one
 * inbound connection at a time from the paired parent device (verified by
 * familyId + a shared token established at pairing time — any other device
 * on the network that happens to find the advertised mDNS service gets
 * rejected before any call data is exchanged) and advertises itself so the
 * parent app can find it without typing an IP address.
 *
 * One instance is created for the lifetime of the baby app (see
 * [LocalCallServerHolder]) and shared between [com.babycall.call.CallListenerService]
 * (which only cares about [onIncomingCall]) and [com.babycall.call.CallActivity]
 * (which uses this as a [CallSignaling] for the duration of one call).
 */
class LocalCallServer(private val context: Context, private val prefs: Prefs) : CallSignaling {

    /** Fired the moment a validated peer asks to start a call — used by CallListenerService only. */
    var onIncomingCall: ((callerId: String) -> Unit)? = null

    /** Fired when the parent asks this device to forget its pairing — used by CallListenerService only. */
    var onUnpaired: (() -> Unit)? = null

    /** Fired whenever a call ends (either side hangs up, or the connection drops) — used by CallListenerService to know it can launch a new CallActivity for the next call. */
    var onCallEnded: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceInfo: NsdServiceInfo? = null

    @Volatile private var activeConnection: JsonSocketConnection? = null
    @Volatile private var activeCallerId: String? = null
    @Volatile private var running = false

    private var callInfoCb: ((RemoteCallInfo) -> Unit)? = null
    private var offerCb: ((SessionDescription) -> Unit)? = null
    private var iceCb: ((IceCandidate) -> Unit)? = null

    @Synchronized
    fun start() {
        if (running) return
        val familyId = prefs.familyId ?: return
        running = true

        val socket = try {
            ServerSocket(LocalProtocol.CALL_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "fixed call port unavailable, falling back to ephemeral (online/cross-network calling disabled this run): ${e.message}")
            ServerSocket(0)
        }
        serverSocket = socket

        acceptThread = thread(name = "LocalCallServer-accept") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) Log.d(TAG, "accept loop ended: ${e.message}")
                    break
                }
                thread(name = "LocalCallServer-conn") { handleConnection(client, familyId) }
            }
        }

        registerNsd(familyId, socket.localPort)
        if (socket.localPort == LocalProtocol.CALL_PORT) setupOnlineAccess(socket.localPort)
    }

    /**
     * Best-effort: opens this port on the home router (UPnP) and learns our
     * own public IP (STUN) so a family member who isn't on this Wi-Fi can
     * still reach this device. Neither step needs any account or server of
     * ours -- both are standard, anonymous protocols. If either fails (UPnP
     * disabled on the router, carrier-grade NAT, ...) [Prefs.myPublicHost]
     * simply stays null and same-Wi-Fi calling is unaffected.
     */
    private fun setupOnlineAccess(port: Int) {
        thread(name = "LocalCallServer-online-setup") {
            val mapped = UpnpPortMapper.mapPort(context, port, port, "BabyCall")
            val publicIp = StunClient.discoverPublicIp()
            prefs.myPublicHost = if (mapped && publicIp != null) "$publicIp:$port" else null
        }
    }

    private fun handleConnection(rawSocket: Socket, familyId: String) {
        rawSocket.soTimeout = HELLO_TIMEOUT_MS
        val conn = JsonSocketConnection(rawSocket)
        try {
            var helloOk = false
            conn.readLoop { msg ->
                if (!helloOk) {
                    if (msg.optString("type") != LocalProtocol.MSG_HELLO) return@readLoop
                    val theirFamilyId = msg.optString("familyId")
                    val theirToken = msg.optString("token")
                    val myToken = prefs.localAuthToken
                    if (theirFamilyId != familyId || myToken == null || theirToken != myToken) {
                        conn.send(JSONObject().put("type", LocalProtocol.MSG_HELLO_REJECT))
                        conn.close()
                        return@readLoop
                    }
                    helloOk = true
                    rawSocket.soTimeout = 0
                    conn.send(
                        JSONObject()
                            .put("type", LocalProtocol.MSG_HELLO_OK)
                            .put("publicHost", prefs.myPublicHost ?: "")
                    )
                    // Only one call peer is supported at a time; replace any stale connection.
                    activeConnection?.close()
                    activeConnection = conn
                    return@readLoop
                }

                dispatch(msg, conn)
            }
        } finally {
            if (activeConnection === conn) {
                val hadCall = activeCallerId != null
                activeConnection = null
                activeCallerId = null
                if (hadCall) {
                    callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", prefs.deviceId))
                    onCallEnded?.invoke()
                }
            }
            conn.close()
        }
    }

    private fun dispatch(msg: JSONObject, conn: JsonSocketConnection) {
        when (msg.optString("type")) {
            LocalProtocol.MSG_CALL_START -> {
                val callerId = msg.optString("callerId")
                activeCallerId = callerId
                onIncomingCall?.invoke(callerId)
                callInfoCb?.invoke(RemoteCallInfo(CallState.RINGING, callerId, prefs.deviceId))
            }
            LocalProtocol.MSG_OFFER -> {
                val sdp = msg.optString("sdp")
                offerCb?.invoke(SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            LocalProtocol.MSG_ICE -> {
                val sdpMid = msg.optString("sdpMid")
                val sdpMLineIndex = msg.optInt("sdpMLineIndex")
                val sdp = msg.optString("sdp")
                iceCb?.invoke(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
            LocalProtocol.MSG_END -> {
                activeCallerId = null
                callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", prefs.deviceId))
                onCallEnded?.invoke()
            }
            LocalProtocol.MSG_SETTINGS_UPDATE -> {
                if (msg.has("pinHash")) prefs.pinHash = msg.optString("pinHash").ifEmpty { null }
                if (msg.has("autoAnswer")) prefs.autoAnswer = msg.optBoolean("autoAnswer", prefs.autoAnswer)
            }
            LocalProtocol.MSG_UNPAIR -> {
                onUnpaired?.invoke()
            }
        }
    }

    // ---- CallSignaling: used by CallActivity (baby role) only ----

    override fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit) {
        callInfoCb = onChange
    }

    override fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        offerCb = onOffer
    }

    override fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        // The baby device only ever answers; it never receives an answer.
    }

    override fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceCb = onCandidate
    }

    override suspend fun startCall(callerId: String, calleeId: String) {
        // The baby device never originates a call in this design.
    }

    override suspend fun sendOffer(sdp: SessionDescription) {
        // The baby device never sends an offer.
    }

    override suspend fun sendAnswer(sdp: SessionDescription) {
        activeConnection?.send(
            JSONObject().put("type", LocalProtocol.MSG_ANSWER).put("sdp", sdp.description)
        )
        callInfoCb?.invoke(RemoteCallInfo(CallState.CONNECTED, activeCallerId.orEmpty(), prefs.deviceId))
    }

    override suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        activeConnection?.send(
            JSONObject()
                .put("type", LocalProtocol.MSG_ICE)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
        )
    }

    override suspend fun endCall(endedBy: String) {
        activeConnection?.send(JSONObject().put("type", LocalProtocol.MSG_END).put("endedBy", endedBy))
        activeCallerId = null
        onCallEnded?.invoke()
    }

    override fun release() {
        callInfoCb = null
        offerCb = null
        iceCb = null
    }

    // ---- mDNS advertisement ----

    private fun registerNsd(familyId: String, port: Int) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = LocalProtocol.callServiceName(familyId)
            serviceType = LocalProtocol.CALL_SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceInfo = info
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized
    fun stop() {
        running = false
        val port = serverSocket?.localPort
        runCatching { serverSocket?.close() }
        registrationListener?.let { listener ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            runCatching { nsdManager?.unregisterService(listener) }
        }
        activeConnection?.close()
        activeConnection = null
        serverSocket = null
        registrationListener = null
        prefs.myPublicHost = null
        if (port == LocalProtocol.CALL_PORT) {
            thread(name = "LocalCallServer-upnp-cleanup") { UpnpPortMapper.unmapPort(context, port) }
        }
    }

    companion object {
        private const val TAG = "LocalCallServer"
        private const val HELLO_TIMEOUT_MS = 8000
    }
}
