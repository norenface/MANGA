package com.babycall.peer

import org.json.JSONObject

/**
 * Parent-side, temporary: hosts the family's peer id on the broker until a
 * baby device redeems the code, or the setup screen is closed. Mirrors
 * [com.babycall.local.LocalPairingHost] but meets the baby device over the
 * internet (via the broker) instead of the LAN (via mDNS), so both devices
 * do not need to be on the same Wi-Fi.
 */
class PeerPairingHost {
    private var client: PeerBrokerClient? = null
    @Volatile private var redeemed = false
    private val createdAt = System.currentTimeMillis()

    fun start(
        familyCode: String,
        parentName: String,
        pinHash: String,
        autoAnswer: Boolean,
        parentDeviceId: String,
        onPaired: (babyName: String, babyDeviceId: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val hubId = PeerProtocol.hubPeerId(familyCode)
        val c = PeerBrokerClient(
            myPeerId = hubId,
            onOpen = {},
            onAppMessage = { fromPeerId, payload ->
                if (!redeemed && payload.optString("appType") == PeerProtocol.APP_PAIR_REDEEM_REQUEST) {
                    val expired = System.currentTimeMillis() - createdAt > CODE_EXPIRY_MS
                    if (expired) {
                        client?.send(fromPeerId, JSONObject().put("appType", PeerProtocol.APP_PAIR_REDEEM_RESPONSE).put("ok", false))
                    } else {
                        redeemed = true
                        val babyDeviceId = payload.optString("babyDeviceId")
                        val babyName = payload.optString("babyName").ifEmpty { "赤ちゃん" }
                        client?.send(
                            fromPeerId,
                            JSONObject()
                                .put("appType", PeerProtocol.APP_PAIR_REDEEM_RESPONSE)
                                .put("ok", true)
                                .put("familyId", familyCode)
                                .put("parentDeviceId", parentDeviceId)
                                .put("parentName", parentName)
                                .put("pinHash", pinHash)
                                .put("autoAnswer", autoAnswer)
                        )
                        onPaired(babyName, babyDeviceId)
                    }
                }
            },
            onFatalError = { message -> if (!redeemed) onError(message) }
        )
        client = c
        c.connect()
    }

    fun stop() {
        client?.close()
        client = null
    }

    companion object {
        private const val CODE_EXPIRY_MS = 10 * 60 * 1000L
    }
}
