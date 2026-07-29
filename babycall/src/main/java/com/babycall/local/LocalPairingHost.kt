package com.babycall.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Parent-side local pairing: shows a 6-digit code and waits for the baby
 * device to find it on the LAN (via mDNS) and redeem that code. Everything
 * here is generated on-device — no internet, no account needed.
 */
class LocalPairingHost(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    val familyId: String = UUID.randomUUID().toString()
    val authToken: String = randomToken()
    val code: String = (100000 + SecureRandom().nextInt(900000)).toString()

    private val createdAt = System.currentTimeMillis()

    /**
     * Starts listening and advertising. [onPaired] fires once, on a
     * background thread, when a baby device redeems the correct code.
     * [onRejectedAttempt] fires (repeatedly) on a wrong-code attempt so the
     * caller can keep the screen open for a retry.
     */
    fun start(
        parentName: String,
        pinHash: String,
        autoAnswer: Boolean,
        parentDeviceId: String,
        onPaired: (babyName: String, babyDeviceId: String) -> Unit,
        onRejectedAttempt: () -> Unit
    ) {
        val socket = ServerSocket(0)
        serverSocket = socket

        thread(name = "LocalPairingHost-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "LocalPairingHost-conn") {
                    client.soTimeout = 8000
                    val conn = JsonSocketConnection(client)
                    try {
                        val msg = conn.readOneBlocking()
                        if (msg == null || msg.optString("type") != LocalProtocol.MSG_REDEEM) {
                            conn.close()
                            return@thread
                        }
                        val expired = System.currentTimeMillis() - createdAt > CODE_EXPIRY_MS
                        val theirCode = msg.optString("code")
                        val babyName = msg.optString("babyName").ifEmpty { "赤ちゃん" }
                        val babyDeviceId = msg.optString("deviceId")

                        if (expired || theirCode != code) {
                            conn.send(JSONObject().put("type", LocalProtocol.MSG_PAIR_REJECT))
                            conn.close()
                            onRejectedAttempt()
                            return@thread
                        }

                        conn.send(
                            JSONObject()
                                .put("type", LocalProtocol.MSG_PAIRED)
                                .put("familyId", familyId)
                                .put("token", authToken)
                                .put("parentDeviceId", parentDeviceId)
                                .put("parentName", parentName)
                                .put("pinHash", pinHash)
                                .put("autoAnswer", autoAnswer)
                        )
                        conn.close()
                        onPaired(babyName, babyDeviceId)
                    } catch (e: Exception) {
                        Log.d(TAG, "pairing connection error: ${e.message}")
                        conn.close()
                    }
                }
            }
        }

        registerNsd(parentName, socket.localPort)
    }

    private fun registerNsd(parentName: String, port: Int) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = LocalProtocol.pairingServiceName(familyId)
            serviceType = LocalProtocol.PAIRING_SERVICE_TYPE
            this.port = port
            setAttribute("name", parentName)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "pairing NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        registrationListener?.let { listener ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            runCatching { nsdManager?.unregisterService(listener) }
        }
        serverSocket = null
        registrationListener = null
    }

    companion object {
        private const val TAG = "LocalPairingHost"
        private const val CODE_EXPIRY_MS = 10 * 60 * 1000L

        private fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
