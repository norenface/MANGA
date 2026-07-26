package com.babycall.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque

data class DiscoveredParent(
    val serviceInfo: NsdServiceInfo,
    val displayName: String
)

data class LocalPairedInfo(
    val familyId: String,
    val authToken: String,
    val parentDeviceId: String,
    val parentName: String,
    val pinHash: String?,
    val autoAnswer: Boolean
)

/** Baby-side local pairing: finds parent device(s) currently showing a pairing code, then redeems one. */
object LocalPairingClient {

    /** Scans for [timeoutMs] and returns every parent device found showing a pairing code. */
    suspend fun discoverHosts(context: Context, timeoutMs: Long = 5000): List<DiscoveredParent> {
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()

        val results = mutableListOf<DiscoveredParent>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving = false
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val name = info.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: info.serviceName
                results.add(DiscoveredParent(info, name))
                resolving = false
            }
        }

        fun maybeResolveNext() {
            if (!resolving && pending.isNotEmpty()) {
                resolving = true
                val next = pending.poll()
                runCatching { nsdManager.resolveService(next, resolveListener) }
                    .onFailure { resolving = false }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                pending.add(info)
                maybeResolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        runCatching { nsdManager.discoverServices(LocalProtocol.PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { return emptyList() }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            maybeResolveNext()
            delay(200)
        }
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }

        return results
    }

    suspend fun redeem(parent: DiscoveredParent, code: String, babyName: String, babyDeviceId: String): LocalPairedInfo {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(parent.serviceInfo.host, parent.serviceInfo.port), 6000)
            val conn = JsonSocketConnection(socket)
            conn.send(
                JSONObject()
                    .put("type", LocalProtocol.MSG_REDEEM)
                    .put("code", code)
                    .put("babyName", babyName)
                    .put("deviceId", babyDeviceId)
            )
            socket.soTimeout = 6000
            val resp = conn.readOneBlocking()
                ?: throw LocalConnectException("応答がありませんでした。もう一度お試しください。")

            when (resp.optString("type")) {
                LocalProtocol.MSG_PAIRED -> LocalPairedInfo(
                    familyId = resp.getString("familyId"),
                    authToken = resp.getString("token"),
                    parentDeviceId = resp.getString("parentDeviceId"),
                    parentName = resp.optString("parentName"),
                    pinHash = resp.optString("pinHash").ifEmpty { null },
                    autoAnswer = resp.optBoolean("autoAnswer", true)
                )
                LocalProtocol.MSG_PAIR_REJECT -> throw LocalConnectException("番号が違います。保護者の画面を確認してください。")
                else -> throw LocalConnectException("予期しない応答でした。")
            }
        } finally {
            runCatching { socket.close() }
        }
    }
}
