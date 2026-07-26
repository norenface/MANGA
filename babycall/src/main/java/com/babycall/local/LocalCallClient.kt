package com.babycall.local

import android.content.Context
import com.babycall.Prefs
import com.babycall.model.CallState
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.RemoteCallInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Runs on the parent device in local (same-Wi-Fi) mode. Discovers the baby
 * device on the LAN via mDNS and connects directly to it — no account, no
 * server, no data leaves the home network. One instance is used per call.
 */
class LocalCallClient(private val context: Context, private val prefs: Prefs) : CallSignaling {

    private var connection: JsonSocketConnection? = null
    private var callInfoCb: ((RemoteCallInfo) -> Unit)? = null
    private var answerCb: ((SessionDescription) -> Unit)? = null
    private var iceCb: ((IceCandidate) -> Unit)? = null

    override fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit) {
        callInfoCb = onChange
    }

    override fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        // The parent device only ever sends an offer; it never receives one.
    }

    override fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        answerCb = onAnswer
    }

    override fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceCb = onCandidate
    }

    override suspend fun startCall(callerId: String, calleeId: String) {
        val familyId = prefs.familyId ?: throw LocalConnectException("ペアリングされていません")
        val token = prefs.localAuthToken ?: throw LocalConnectException("ペアリング情報が壊れています")

        val serviceInfo = LocalDiscovery.resolveBabyAddress(context, familyId)
            ?: throw LocalConnectException("赤ちゃん端末が見つかりません。同じWi-Fiに接続されているか確認してください。")

        withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(serviceInfo.host, serviceInfo.port), CONNECT_TIMEOUT_MS)
            val conn = JsonSocketConnection(socket)
            connection = conn

            conn.send(JSONObject().put("type", LocalProtocol.MSG_HELLO).put("familyId", familyId).put("token", token))
            socket.soTimeout = HELLO_TIMEOUT_MS
            val resp = conn.readOneBlocking()
            socket.soTimeout = 0
            if (resp == null || resp.optString("type") != LocalProtocol.MSG_HELLO_OK) {
                conn.close()
                connection = null
                throw LocalConnectException("赤ちゃん端末との接続が拒否されました。PINやペアリング状態を確認してください。")
            }

            thread(name = "LocalCallClient-read") {
                conn.readLoop { msg -> handleMessage(msg) }
                if (connection === conn) {
                    callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, callerId, calleeId))
                }
            }

            conn.send(
                JSONObject()
                    .put("type", LocalProtocol.MSG_CALL_START)
                    .put("callerId", callerId)
                    .put("calleeId", calleeId)
            )
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            LocalProtocol.MSG_ANSWER -> {
                val sdp = msg.optString("sdp")
                answerCb?.invoke(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                callInfoCb?.invoke(RemoteCallInfo(CallState.CONNECTED, "", ""))
            }
            LocalProtocol.MSG_ICE -> {
                val sdpMid = msg.optString("sdpMid")
                val sdpMLineIndex = msg.optInt("sdpMLineIndex")
                val sdp = msg.optString("sdp")
                iceCb?.invoke(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
            LocalProtocol.MSG_END -> {
                callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", ""))
            }
        }
    }

    override suspend fun sendOffer(sdp: SessionDescription) {
        connection?.send(JSONObject().put("type", LocalProtocol.MSG_OFFER).put("sdp", sdp.description))
    }

    override suspend fun sendAnswer(sdp: SessionDescription) {
        // The parent device never sends an answer.
    }

    override suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        connection?.send(
            JSONObject()
                .put("type", LocalProtocol.MSG_ICE)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
        )
    }

    override suspend fun endCall(endedBy: String) {
        connection?.send(JSONObject().put("type", LocalProtocol.MSG_END).put("endedBy", endedBy))
    }

    override fun release() {
        callInfoCb = null
        answerCb = null
        iceCb = null
        connection?.close()
        connection = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val HELLO_TIMEOUT_MS = 6000
    }
}
