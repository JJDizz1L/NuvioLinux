package com.nuvio.app.features.discord

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Discord application ID for this app (create at discord.com/developers,
 * under "Applications" → "Rich Presence" assets). Set to the real ID; the
 * RPC is disabled while this starts with "REPLACE_WITH".
 */
internal const val DISCORD_APP_ID = "1532796978973638830"

private const val OP_HANDSHAKE = 0
private const val OP_FRAME = 1
private const val OP_CLOSE = 2
private const val OP_PING = 3
private const val OP_PONG = 4

/**
 * Minimal Discord IPC client. Speaks the classic rich-presence protocol over
 * the unix domain socket:
 *   frame = int32-le opcode + int32-le length + UTF-8 JSON payload
 * Handshake:  {v: 1, client_id: "..."}
 * SET_ACTIVITY: {cmd: "SET_ACTIVITY", args: {pid, activity}} (activity null = clear)
 *
 * A dedicated reader thread watches the connection for EOF/CLOSE so a dead
 * socket (Discord restarted, or another client took the slot) is detected
 * immediately — otherwise writes into a stale-but-open socket would succeed
 * silently and the presence would never reconnect. The reader also answers
 * PING frames with PONG.
 */
internal class DiscordRpcClient {
    private var socket: SocketChannel? = null
    private var handshaken = false
    private var readerThread: Thread? = null

    /** Set when the peer closed the connection unexpectedly; cleared on reconnect. */
    @Volatile
    private var dead = false

    /** Invoked (on the reader thread) when the connection dies unexpectedly. */
    @Volatile
    var onConnectionLost: (() -> Unit)? = null

    val isConnected: Boolean
        get() = !dead && (socket?.isConnected == true)

    fun connect(): Boolean {
        if (isConnected) return true
        if (dead) disconnect()
        return connectUnixSocket()
    }

    private fun connectUnixSocket(): Boolean {
        for (path in unixSocketCandidates()) {
            try {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(Path.of(path)))
                socket = channel
                handshaken = false
                dead = false
                startReader()
                return true
            } catch (_: Exception) {
                // socket not present; try the next candidate
            }
        }
        return false
    }

    /**
     * Candidate IPC socket paths, most likely first. Covers the standard
     * Discord client (which is also what Equicord/Vencord-style patches use —
     * they inject into the official client and keep discord-ipc-0), Vesktop
     * (standalone Electron client with its own socket), Flatpak, and Snap.
     */
    private fun unixSocketCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")
        if (!xdgRuntime.isNullOrBlank()) {
            candidates += "$xdgRuntime/discord-ipc-0"
            candidates += "$xdgRuntime/vesktop-ipc-0"
            candidates += "$xdgRuntime/app/com.discordapp.Discord/discord-ipc-0"
            candidates += "$xdgRuntime/snap.discord/discord-ipc-0"
        }
        candidates += "/tmp/discord-ipc-0"
        candidates += "/tmp/discord-ipc"
        return candidates
    }

    /**
     * Sends a presence update (or clear when [activity] is null). Throws
     * [IOException] when the connection is dead, so the caller can reconnect.
     */
    @Throws(IOException::class)
    fun sendActivity(activity: String?) {
        if (!connect()) throw IOException("discord ipc unavailable")

        if (!handshaken) {
            val handshake = buildJsonObject {
                put("v", JsonPrimitive(1))
                put("client_id", JsonPrimitive(DISCORD_APP_ID))
            }
            writeFrame(OP_HANDSHAKE, handshake.toString())
            handshaken = true
        }

        val args = buildJsonObject {
            put("pid", JsonPrimitive(ProcessHandle.current().pid()))
            put("activity", if (activity != null) Json.parseToJsonElement(activity) else JsonNull)
        }
        val payload = buildJsonObject {
            put("cmd", JsonPrimitive("SET_ACTIVITY"))
            // Discord rejects commands without a nonce (ERROR 4000
            // "Payload requires a nonce") — the connection stays open so the
            // failure is otherwise silent.
            put("nonce", JsonPrimitive(java.util.UUID.randomUUID().toString()))
            put("args", args)
        }
        writeFrame(OP_FRAME, payload.toString())
    }

    private fun writeFrame(opcode: Int, payload: String) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(opcode)
        frame.putInt(body.size)
        frame.put(body)
        frame.flip()

        val socket = socket
        if (socket != null) {
            while (frame.hasRemaining()) {
                socket.write(frame)
            }
        } else {
            throw IOException("not connected")
        }
    }

    /**
     * Blocks reading frames until EOF or an error, answering PINGs. Marks the
     * connection dead and notifies [onConnectionLost] when the peer goes away.
     */
    private fun startReader() {
        val reader = Thread({
            try {
                val channel = socket ?: throw IOException("not connected")
                readSocketFrames(channel)
            } catch (_: Exception) {
                // connection dropped
            }
            if (dead) return@Thread
            dead = true
            onConnectionLost?.invoke()
        }, "nuvio-discord-reader")
        reader.isDaemon = true
        readerThread = reader
        reader.start()
    }

    private fun readSocketFrames(channel: SocketChannel) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            header.clear()
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) return // EOF
            }
            header.flip()
            val op = header.int
            val length = header.int
            if (!skipSocket(channel, length)) return
            if (op == OP_PING) {
                writeFrame(OP_PONG, "")
            } else if (op == OP_CLOSE) {
                return
            }
        }
    }

    private fun skipSocket(channel: SocketChannel, length: Int): Boolean {
        var remaining = length
        val buffer = ByteBuffer.allocate(minOf(4096, maxOf(remaining, 1)))
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity(), remaining))
            val n = channel.read(buffer)
            if (n < 0) return false
            remaining -= n
        }
        return true
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        handshaken = false
        dead = false
        readerThread?.interrupt()
        readerThread = null
    }
}
