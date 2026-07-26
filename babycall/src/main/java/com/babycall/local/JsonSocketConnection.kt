package com.babycall.local

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * One JSON-line-per-message protocol over a plain TCP socket. Both the baby
 * (server) and parent (client) sides use this identically once connected.
 */
class JsonSocketConnection(private val socket: Socket) {

    private val writeLock = Any()
    private val output: OutputStream = socket.getOutputStream()
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    @Volatile
    private var closed = false

    fun send(json: JSONObject) {
        if (closed) return
        try {
            synchronized(writeLock) {
                output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            close()
        }
    }

    /** Blocks the calling thread for a single line; used for the initial hello handshake. */
    fun readOneBlocking(): JSONObject? {
        return try {
            val line = reader.readLine() ?: return null
            if (line.isBlank()) null else runCatching { JSONObject(line) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Blocks the calling thread reading lines until the socket closes or an error occurs. */
    fun readLoop(onMessage: (JSONObject) -> Unit) {
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { JSONObject(line) }.onSuccess { onMessage(it) }
            }
        } catch (e: Exception) {
            if (!closed) Log.d(TAG, "readLoop ended: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "JsonSocketConnection"
    }
}
