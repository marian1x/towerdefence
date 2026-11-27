package com.marian1x.agesofsiege.net

import android.os.Handler
import android.os.Looper
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

private val relayMain = Handler(Looper.getMainLooper())
private const val RELAY_PORT = 443
private const val HANDSHAKE_TIMEOUT_MS = 6000
private const val RETRY_DELAY_MS = 2000L

class RelayEndpoint private constructor(val host: String, val port: Int) {
    override fun toString() = if (port == RELAY_PORT) host else "$host:$port"

    companion object {
        fun parse(value: String): RelayEndpoint? {
            val uri = runCatching { URI("tls://${value.trim().removePrefix("https://")}") }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (host.contains(':') || uri.rawUserInfo != null || uri.rawQuery != null ||
                uri.rawFragment != null || uri.rawPath.isNotEmpty()) return null
            val port = if (uri.port == -1) RELAY_PORT else uri.port
            return if (port in 1..65535) RelayEndpoint(host, port) else null
        }
    }
}

class RelayInvite private constructor(val endpoint: RelayEndpoint, val code: String) {
    override fun toString() = "$endpoint/$code"

    companion object {
        private val codePattern = Regex("[a-f0-9]{32}")

        fun create(endpoint: RelayEndpoint) = RelayInvite(endpoint, UUID.randomUUID().toString().replace("-", ""))

        fun parse(value: String): RelayInvite? {
            val trimmed = value.trim()
            val code = trimmed.substringAfterLast('/', "")
            if (!codePattern.matches(code)) return null
            val endpoint = RelayEndpoint.parse(trimmed.substringBeforeLast('/')) ?: return null
            return RelayInvite(endpoint, code)
        }
    }
}

private class RelayException(message: String, val retryable: Boolean = true) : IOException(message)

private fun openSocket(raw: Socket, endpoint: RelayEndpoint): SSLSocket {
    raw.connect(InetSocketAddress(endpoint.host, endpoint.port), HANDSHAKE_TIMEOUT_MS)
    raw.keepAlive = true
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    return (factory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket).apply {
        soTimeout = HANDSHAKE_TIMEOUT_MS
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        startHandshake()
    }
}

private fun Socket.relayRequest(command: String) {
    getOutputStream().apply {
        write("AOS1 $command\n".toByteArray(Charsets.US_ASCII))
        flush()
    }
}

/** Reads just the relay's reply. The first gameplay frame may already be on the same stream. */
private fun Socket.expectReply(expected: String) {
    val input = getInputStream()
    val reply = buildString {
        while (length < 128) {
            val next = input.read()
            if (next < 0) throw EOFException("The relay closed the connection.")
            if (next == '\n'.code) return@buildString
            append(next.toChar())
        }
        throw RelayException("Invalid response from the relay.", retryable = false)
    }
    if (reply == expected) return
    throw when (reply) {
        "ERROR host-key" -> RelayException("The relay rejected your host key.", retryable = false)
        "ERROR host-busy" -> RelayException("The relay is busy. Try again in a moment.")
        "ERROR room-unavailable" -> RelayException("This room is unavailable. Ask the host to check the invite.")
        else -> RelayException("Invalid response from the relay.", retryable = false)
    }
}

private fun relayError(error: Throwable): String =
    if (error is RelayException) error.message.orEmpty() else "Couldn't reach the game relay over TLS."

internal fun validRelayHostKey(key: String) = key.matches(Regex("[A-Za-z0-9_-]{32,128}"))

/** Registers on the relay over an outbound TLS connection until a guest joins. */
internal class RelayHost(
    val invite: RelayInvite,
    private val key: String,
    private val onReady: () -> Unit,
    private val onIncoming: (Socket) -> Unit,
    private val onError: (String, Boolean) -> Unit,
) {
    private var pending: Socket? = null
    private var stopped = false

    fun listen(rejoining: Boolean = false) {
        if (stopped || pending != null) return
        val raw = Socket()
        pending = raw
        thread(name = "net-relay-host", isDaemon = true) {
            var registered = false
            val result = runCatching {
                val socket = openSocket(raw, invite.endpoint)
                socket.relayRequest("HOST ${invite.code} $key")
                socket.expectReply("READY")
                registered = true
                relayMain.post { if (pending === raw) onReady() }
                socket.soTimeout = 0
                socket.expectReply("PAIRED")
                socket
            }.onFailure { runCatching { raw.close() } }
            relayMain.post {
                if (pending !== raw) {
                    result.getOrNull()?.let { runCatching { it.close() } }
                    return@post
                }
                pending = null
                result.onSuccess(onIncoming).onFailure { error ->
                    val retry = (rejoining || registered) && (error !is RelayException || error.retryable)
                    onError(relayError(error), retry)
                    if (retry) relayMain.postDelayed({ listen(rejoining = true) }, RETRY_DELAY_MS)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        pending?.let { runCatching { it.close() } }
        pending = null
    }
}

internal fun joinRelay(invite: RelayInvite, complete: (Result<Socket>) -> Unit) {
    thread(name = "net-relay-join", isDaemon = true) {
        val raw = Socket()
        val result = runCatching {
            openSocket(raw, invite.endpoint).apply {
                relayRequest("JOIN ${invite.code}")
                expectReply("PAIRED")
                soTimeout = 0
            }
        }.onFailure { runCatching { raw.close() } }
        relayMain.post { complete(result) }
    }
}

internal fun onlineConnectionError(error: Throwable) = relayError(error)
