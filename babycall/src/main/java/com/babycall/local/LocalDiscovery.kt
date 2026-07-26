package com.babycall.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocalConnectException(message: String) : Exception(message)

/** Finds the paired baby device's address on the local network via mDNS (NSD). */
object LocalDiscovery {

    /**
     * Resolves the current IP/port of the baby device advertising
     * [LocalProtocol.callServiceName]\(familyId\), or null if it can't be
     * found within [timeoutMs] (not on this Wi-Fi, powered off, etc.).
     */
    suspend fun resolveBabyAddress(context: Context, familyId: String, timeoutMs: Long = 6000): NsdServiceInfo? {
        val targetName = LocalProtocol.callServiceName(familyId)
        return withTimeoutOrNull(timeoutMs) {
            discoverAndResolve(context, LocalProtocol.CALL_SERVICE_TYPE, targetName)
        }
    }

    suspend fun discoverAndResolve(
        context: Context,
        serviceType: String,
        targetName: String
    ): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        var resolved = false
        var discoveryListener: NsdManager.DiscoveryListener? = null

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve failed for ${info.serviceName}: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (!resolved) {
                    resolved = true
                    discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
                    if (cont.isActive) cont.resume(info)
                }
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == targetName && !resolved) {
                    runCatching { nsdManager.resolveService(info, resolveListener) }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        val listener = discoveryListener!!

        cont.invokeOnCancellation {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }

        runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { if (cont.isActive) cont.resume(null) }
    }

    private const val TAG = "LocalDiscovery"
}
