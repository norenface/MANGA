package com.babycall.local

import android.content.Context
import com.babycall.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One-shot, best-effort messages from the parent device to the baby device
 * in local mode (settings changes, unpairing) — each opens a short-lived
 * connection, sends one message, and disconnects. Requires the baby device
 * to be reachable on the same Wi-Fi right now; there is no queue/retry.
 */
object LocalControlChannel {

    suspend fun pushSettings(context: Context, prefs: Prefs, pinHash: String?, autoAnswer: Boolean): Boolean =
        send(context, prefs) { conn ->
            conn.send(
                JSONObject()
                    .put("type", LocalProtocol.MSG_SETTINGS_UPDATE)
                    .apply { if (pinHash != null) put("pinHash", pinHash) }
                    .put("autoAnswer", autoAnswer)
            )
        }

    suspend fun pushUnpair(context: Context, prefs: Prefs): Boolean =
        send(context, prefs) { conn -> conn.send(JSONObject().put("type", LocalProtocol.MSG_UNPAIR)) }

    private suspend fun send(context: Context, prefs: Prefs, body: (JsonSocketConnection) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val familyId = prefs.familyId ?: return@withContext false
            val token = prefs.localAuthToken ?: return@withContext false
            val serviceInfo = LocalDiscovery.resolveBabyAddress(context, familyId) ?: return@withContext false

            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(serviceInfo.host, serviceInfo.port), 5000)
                    val conn = JsonSocketConnection(socket)
                    conn.send(JSONObject().put("type", LocalProtocol.MSG_HELLO).put("familyId", familyId).put("token", token))
                    socket.soTimeout = 5000
                    val resp = conn.readOneBlocking()
                    if (resp?.optString("type") != LocalProtocol.MSG_HELLO_OK) return@withContext false

                    body(conn)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
}
