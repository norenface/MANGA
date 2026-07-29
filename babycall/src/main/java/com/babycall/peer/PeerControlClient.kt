package com.babycall.peer

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * One-shot, best-effort requests from a parent-role device to the baby's
 * hub: each opens a short-lived broker connection, sends one message, waits
 * for an acknowledgement, and disconnects. Mirrors
 * [com.babycall.local.LocalControlChannel]'s "no queue, no retry" design --
 * this requires the baby device to be online right now.
 */
object PeerControlClient {

    suspend fun joinFamily(familyCode: String, deviceId: String, name: String): Boolean =
        request(familyCode, PeerProtocol.APP_INVITE_JOIN_REQUEST, PeerProtocol.APP_INVITE_JOIN_RESPONSE) {
            put("deviceId", deviceId)
            put("name", name)
        }?.optBoolean("ok", false) ?: false

    suspend fun setPin(familyCode: String, pinHash: String): Boolean =
        request(familyCode, PeerProtocol.APP_SETTINGS_SET, PeerProtocol.APP_SETTINGS_SET_ACK) {
            put("pinHash", pinHash)
        } != null

    suspend fun setAutoAnswer(familyCode: String, autoAnswer: Boolean): Boolean =
        request(familyCode, PeerProtocol.APP_SETTINGS_SET, PeerProtocol.APP_SETTINGS_SET_ACK) {
            put("autoAnswer", autoAnswer)
        } != null

    suspend fun unpairBaby(familyCode: String): Boolean =
        request(familyCode, PeerProtocol.APP_UNPAIR_BABY, PeerProtocol.APP_UNPAIR_BABY_ACK) {} != null

    private suspend fun request(
        familyCode: String,
        requestAppType: String,
        responseAppType: String,
        timeoutMs: Long = 8000,
        buildExtra: JSONObject.() -> Unit
    ): JSONObject? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var client: PeerBrokerClient? = null
            val myId = PeerProtocol.randomPeerId("bcc")
            client = PeerBrokerClient(
                myPeerId = myId,
                onOpen = {
                    val payload = JSONObject().put("appType", requestAppType).apply(buildExtra)
                    client?.send(PeerProtocol.hubPeerId(familyCode), payload)
                },
                onAppMessage = { _, payload ->
                    if (payload.optString("appType") == responseAppType && cont.isActive) {
                        cont.resume(payload)
                        client?.close()
                    }
                },
                onFatalError = {
                    if (cont.isActive) cont.resume(null)
                    client?.close()
                }
            )
            cont.invokeOnCancellation { client?.close() }
            client.connect()
        }
    }
}
