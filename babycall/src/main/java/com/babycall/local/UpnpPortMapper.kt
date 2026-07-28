package com.babycall.local

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Minimal UPnP IGD (Internet Gateway Device) client. Asks the home router to
 * forward one TCP port to this device so a family member on a different
 * network can reach it directly -- no account, no relay server, nothing
 * beyond what the router itself already supports.
 *
 * This is best-effort only: many routers ship with UPnP disabled, and some
 * ISPs (especially mobile carriers, and increasingly some home ISPs) put
 * customers behind carrier-grade NAT, which no amount of router
 * configuration can work around. When it fails, the app simply falls back
 * to same-Wi-Fi-only behavior.
 */
object UpnpPortMapper {

    private const val TAG = "UpnpPortMapper"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"

    private data class GatewayControl(val controlUrl: String, val serviceType: String)

    fun mapPort(context: Context, externalPort: Int, internalPort: Int, description: String): Boolean =
        runCatching {
            withMulticastLock(context) {
                val gateway = discoverGateway() ?: return@withMulticastLock false
                val localIp = localIpAddress() ?: return@withMulticastLock false
                soapAddPortMapping(gateway, externalPort, internalPort, localIp, description)
            }
        }.getOrDefault(false)

    fun unmapPort(context: Context, externalPort: Int) {
        runCatching {
            withMulticastLock(context) {
                val gateway = discoverGateway() ?: return@withMulticastLock
                soapDeletePortMapping(gateway, externalPort)
            }
        }
    }

    private fun <T> withMulticastLock(context: Context, block: () -> T): T {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifiManager?.createMulticastLock("babycall-upnp")
        lock?.setReferenceCounted(true)
        runCatching { lock?.acquire() }
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun discoverGateway(): GatewayControl? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $SEARCH_TARGET\r\n\r\n"
                ).toByteArray()
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(SSDP_ADDRESS, SSDP_PORT)))

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    break
                }
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val location = Regex("(?i)location:\\s*(\\S+)").find(response)?.groupValues?.get(1) ?: continue
                runCatching { fetchGatewayControl(location.trim()) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun fetchGatewayControl(descriptionUrl: String): GatewayControl? {
        val conn = URL(descriptionUrl).openConnection() as HttpURLConnection
        val xml = try {
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val serviceBlock = Regex(
            "<service>(?:(?!</service>).)*?(WANIPConnection|WANPPPConnection)(?:(?!</service>).)*?</service>",
            RegexOption.DOT_MATCHES_ALL
        ).find(xml) ?: return null
        val block = serviceBlock.value
        val serviceType = Regex("<serviceType>([^<]+)</serviceType>").find(block)?.groupValues?.get(1) ?: return null
        val controlPath = Regex("<controlURL>([^<]+)</controlURL>").find(block)?.groupValues?.get(1) ?: return null
        val controlUrl = URL(URL(descriptionUrl), controlPath).toString()
        return GatewayControl(controlUrl, serviceType)
    }

    private fun soapAddPortMapping(
        gateway: GatewayControl,
        externalPort: Int,
        internalPort: Int,
        internalClient: String,
        description: String
    ): Boolean {
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:AddPortMapping xmlns:u="${gateway.serviceType}">
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            <NewInternalPort>$internalPort</NewInternalPort>
            <NewInternalClient>$internalClient</NewInternalClient>
            <NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>$description</NewPortMappingDescription>
            <NewLeaseDuration>0</NewLeaseDuration>
            </u:AddPortMapping>
            </s:Body>
            </s:Envelope>
        """.trimIndent()
        return soapCall(gateway, "AddPortMapping", body)
    }

    private fun soapDeletePortMapping(gateway: GatewayControl, externalPort: Int): Boolean {
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:DeletePortMapping xmlns:u="${gateway.serviceType}">
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            </u:DeletePortMapping>
            </s:Body>
            </s:Envelope>
        """.trimIndent()
        return soapCall(gateway, "DeletePortMapping", body)
    }

    private fun soapCall(gateway: GatewayControl, action: String, body: String): Boolean {
        val conn = URL(gateway.controlUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"${gateway.serviceType}#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            if (!ok) Log.d(TAG, "$action rejected by router: HTTP ${conn.responseCode}")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "$action failed: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun localIpAddress(): String? {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            if (!iface.isUp || iface.isLoopback) return@forEach
            iface.inetAddresses?.toList()?.forEach { addr ->
                if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
