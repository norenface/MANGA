package com.babycall.local

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Minimal STUN (RFC 5389) binding-request client used only to learn this
 * device's public IP address as seen from the internet. This is needed so a
 * remote family member's app knows where to reconnect once UPnP has opened a
 * port on the home router. No account or server of ours is involved -- the
 * protocol carries no identity at all, so any public STUN server works;
 * Google's is used here only because it's free and highly available.
 */
object StunClient {

    fun discoverPublicIp(
        stunHost: String = "stun.l.google.com",
        stunPort: Int = 19302,
        timeoutMs: Int = 4000
    ): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val transactionId = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val request = buildBindingRequest(transactionId)
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(stunHost, stunPort)))

            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            parseMappedAddress(buf, packet.length, transactionId)
        }
    }.getOrNull()

    private fun buildBindingRequest(transactionId: ByteArray): ByteArray {
        val header = ByteArray(20)
        header[0] = 0x00; header[1] = 0x01 // Binding Request
        header[2] = 0x00; header[3] = 0x00 // message length: no attributes
        header[4] = 0x21; header[5] = 0x12; header[6] = 0xA4.toByte(); header[7] = 0x42 // magic cookie
        System.arraycopy(transactionId, 0, header, 8, 12)
        return header
    }

    private fun parseMappedAddress(buf: ByteArray, length: Int, transactionId: ByteArray): String? {
        if (length < 20) return null
        if (buf[4] != 0x21.toByte() || buf[5] != 0x12.toByte() || buf[6] != 0xA4.toByte() || buf[7] != 0x42.toByte()) return null
        for (i in 0 until 12) if (buf[8 + i] != transactionId[i]) return null

        var offset = 20
        while (offset + 4 <= length) {
            val type = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            val attrLen = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
            val valueStart = offset + 4
            if (valueStart + attrLen > length) break

            if (type == 0x0020 || type == 0x0001) { // XOR-MAPPED-ADDRESS or MAPPED-ADDRESS
                val family = buf[valueStart + 1].toInt() and 0xFF
                if (family == 0x01 && attrLen >= 8) {
                    val addrBytes = ByteArray(4)
                    for (i in 0 until 4) addrBytes[i] = buf[valueStart + 4 + i]
                    if (type == 0x0020) {
                        addrBytes[0] = (addrBytes[0].toInt() xor 0x21).toByte()
                        addrBytes[1] = (addrBytes[1].toInt() xor 0x12).toByte()
                        addrBytes[2] = (addrBytes[2].toInt() xor 0xA4).toByte()
                        addrBytes[3] = (addrBytes[3].toInt() xor 0x42).toByte()
                    }
                    return addrBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
            }
            val padding = (4 - attrLen % 4) % 4
            offset = valueStart + attrLen + padding
        }
        return null
    }
}
