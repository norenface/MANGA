package com.babycall.peer

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PeerConnectException(message: String) : Exception(message)

data class PeerPairedInfo(
    val familyId: String,
    val parentDeviceId: String,
    val parentName: String,
    val pinHash: String?,
    val autoAnswer: Boolean
)

/** Baby-side, temporary: redeems a family code against whichever device is currently hosting it (see [PeerPairingHost]). */
object PeerPairingClient {

    suspend fun redeem(familyCode: String, babyDeviceId: String, babyName: String): PeerPairedInfo =
        suspendCancellableCoroutine { cont ->
            var client: PeerBrokerClient? = null
            val myId = PeerProtocol.randomPeerId("bcb")
            client = PeerBrokerClient(
                myPeerId = myId,
                onOpen = {
                    client?.send(
                        PeerProtocol.hubPeerId(familyCode),
                        JSONObject()
                            .put("appType", PeerProtocol.APP_PAIR_REDEEM_REQUEST)
                            .put("babyDeviceId", babyDeviceId)
                            .put("babyName", babyName)
                    )
                },
                onAppMessage = { _, payload ->
                    if (payload.optString("appType") == PeerProtocol.APP_PAIR_REDEEM_RESPONSE && cont.isActive) {
                        if (payload.optBoolean("ok", false)) {
                            cont.resume(
                                PeerPairedInfo(
                                    familyId = payload.optString("familyId"),
                                    parentDeviceId = payload.optString("parentDeviceId"),
                                    parentName = payload.optString("parentName").ifEmpty { "パパ・ママ" },
                                    pinHash = payload.optString("pinHash").ifEmpty { null },
                                    autoAnswer = payload.optBoolean("autoAnswer", true)
                                )
                            )
                        } else {
                            cont.resumeWithException(PeerConnectException("ペアリングに失敗しました。番号を確認してください。"))
                        }
                        client?.close()
                    }
                },
                onFatalError = { message ->
                    if (cont.isActive) cont.resumeWithException(PeerConnectException(message))
                    client?.close()
                }
            )
            cont.invokeOnCancellation { client?.close() }
            client.connect()
        }
}
