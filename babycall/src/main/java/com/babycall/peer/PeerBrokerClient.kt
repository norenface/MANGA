package com.babycall.peer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Client for the PeerJS signaling relay protocol (see the open-source
 * `peers/peerjs-server` project). This is a free, public, account-less relay
 * -- anyone can register a peer id and send small JSON messages to any other
 * currently-registered id. The relay only ever forwards messages; it never
 * reads or stores them, and WebRTC media itself stays directly peer-to-peer.
 * This class replaces Firebase Realtime Database as the signaling transport
 * for BabyCall's "online" (cross-network) mode.
 *
 * IMPORTANT wire-protocol detail (verified against the server source): only
 * messages whose top-level "type" is OFFER, ANSWER, CANDIDATE, LEAVE or
 * EXPIRE are ever relayed to another peer -- any other "type" is silently
 * dropped by the relay. So every app-level message this class sends uses the
 * wire-level type "OFFER" as a carrier, with our own real message kind
 * living inside payload.appType, which the relay never inspects.
 */
class PeerBrokerClient(
    private val myPeerId: String,
    private val onOpen: () -> Unit,
    private val onAppMessage: (fromPeerId: String, payload: JSONObject) -> Unit,
    private val onFatalError: (message: String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var heartbeatThread: Thread? = null
    @Volatile private var closed = false
    @Volatile private var opened = false

    fun connect() {
        val token = randomToken()
        val url = "wss://$HOST/peerjs?key=$KEY&id=$myPeerId&token=$token"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onFatalError(t.message ?: "中継サーバーへの接続に失敗しました。")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
            }
        })
    }

    private fun handleFrame(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "OPEN" -> {
                if (!opened) {
                    opened = true
                    startHeartbeat()
                    onOpen()
                }
            }
            "ERROR" -> {
                val reason = msg.optJSONObject("payload")?.optString("msg") ?: "中継サーバーがエラーを返しました。"
                onFatalError(reason)
            }
            "ID-TAKEN" -> onFatalError("この番号は現在使用中です。しばらくしてからもう一度お試しください。")
            "OFFER", "ANSWER", "CANDIDATE" -> {
                val src = msg.optString("src")
                val payload = msg.optJSONObject("payload") ?: return
                if (src.isNotEmpty()) onAppMessage(src, payload)
            }
            "LEAVE" -> {
                val src = msg.optString("src")
                if (src.isNotEmpty()) onAppMessage(src, JSONObject().put("appType", PeerProtocol.APP_PEER_LEFT))
            }
        }
    }

    /** Sends our own [payload] (must set its own "appType") to [dstPeerId]. */
    fun send(dstPeerId: String, payload: JSONObject) {
        val envelope = JSONObject()
            .put("type", "OFFER")
            .put("dst", dstPeerId)
            .put("payload", payload)
        runCatching { webSocket?.send(envelope.toString()) }
    }

    private fun startHeartbeat() {
        heartbeatThread = thread(name = "PeerBroker-heartbeat") {
            while (!closed) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (closed) break
                runCatching { webSocket?.send(JSONObject().put("type", "HEARTBEAT").toString()) }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    fun close() {
        if (closed) return
        closed = true
        stopHeartbeat()
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
    }

    companion object {
        private const val HOST = "0.peerjs.com"
        private const val KEY = "peerjs"
        private const val HEARTBEAT_INTERVAL_MS = 20000L

        // Alive_timeout on the server default is 90s with no per-message keepalive
        // besides HEARTBEAT, so 20s gives a wide safety margin without being chatty.

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS) // we drive our own app-level HEARTBEAT instead
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
