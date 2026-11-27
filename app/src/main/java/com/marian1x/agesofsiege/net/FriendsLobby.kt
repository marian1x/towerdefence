package com.marian1x.agesofsiege.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marian1x.agesofsiege.core.MatchMode
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

internal class LobbyServer private constructor(val endpoint: RelayEndpoint, val key: String) {
    val accessCode get() = "$endpoint/$key"

    companion object {
        fun parse(value: String): LobbyServer? {
            val address = value.trim()
            val endpoint = RelayEndpoint.parse(address.substringBeforeLast('/')) ?: return null
            val key = address.substringAfterLast('/', "")
            return if (validRelayHostKey(key)) LobbyServer(endpoint, key) else null
        }
    }
}

/** The access codes are stored on this device and excluded from Android backup. */
internal class SavedLobbyServers(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "friends-lobbies"))

    fun load(): List<LobbyServer> = runCatching {
        file.readFully().decodeToString().lineSequence().mapNotNull(LobbyServer::parse).toList()
    }.getOrDefault(emptyList())

    fun save(servers: List<LobbyServer>) {
        val stream = file.startWrite()
        try {
            stream.write(servers.joinToString("\n") { it.accessCode }.encodeToByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal data class LobbyPlayer(val id: String, val name: String, val available: Boolean)
internal data class LobbyInvite(val id: String, val name: String, val mode: MatchMode?)

/** One authenticated presence connection while the player is viewing a lobby. */
internal class FriendsLobby(
    private val server: LobbyServer,
    val name: String,
    private val onMatch: (RelayInvite, MatchMode, String?) -> Unit,
) {
    var players by mutableStateOf(emptyList<LobbyPlayer>())
        private set
    var incoming by mutableStateOf<LobbyInvite?>(null)
        private set
    var outgoing by mutableStateOf<LobbyInvite?>(null)
        private set
    var status by mutableStateOf("Connecting…")
        private set
    var connected by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "net-lobby-write").apply { isDaemon = true }
    }
    private val raw = Socket()
    private var socket: Socket? = null
    var finished by mutableStateOf(false)
        private set

    fun start() {
        val encoded = Base64.encodeToString(
            name.encodeToByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        thread(name = "net-lobby-read", isDaemon = true) {
            var registered = false
            try {
                val tls = openSocket(raw, server.endpoint)
                tls.relayRequest("LOBBY ${server.key} $encoded")
                tls.expectReply("READY")
                registered = true
                tls.soTimeout = 46_000
                main.post {
                    if (!finished) {
                        socket = tls
                        connected = true
                        status = "Connected"
                    }
                }
                val reader = tls.getInputStream().bufferedReader()
                while (true) {
                    val frame = reader.readGameFrame() ?: break
                    val message = JSONObject(frame)
                    main.post { if (!finished) receive(message) }
                }
            } catch (error: Exception) {
                main.post { if (!finished && !registered) status = onlineConnectionError(error) }
            } finally {
                runCatching { raw.close() }
                main.post {
                    if (!finished) {
                        if (registered) status = "Disconnected from the lobby."
                        close()
                    }
                }
            }
        }
    }

    fun invite(player: LobbyPlayer, mode: MatchMode) = send("INVITE ${player.id} ${mode.name}")
    fun accept(invite: LobbyInvite) = send("ACCEPT ${invite.id}")
    fun decline(invite: LobbyInvite) = send("DECLINE ${invite.id}")

    private fun send(command: String) {
        val link = socket ?: return
        writer.execute {
            try {
                link.getOutputStream().apply {
                    write("$command\n".encodeToByteArray())
                    flush()
                }
            } catch (_: Exception) {
                runCatching { raw.close() }
            }
        }
    }

    private fun receive(message: JSONObject) {
        when (message.optString("t")) {
            "ping" -> send("PING")
            "roster" -> message.optJSONArray("players")?.let { list ->
                players = (0 until list.length()).mapNotNull { index ->
                    list.optJSONObject(index)?.let {
                        LobbyPlayer(it.optString("id"), it.optString("name"), it.optBoolean("available"))
                    }
                }
            }
            "invite" -> matchMode(message)?.let {
                incoming = LobbyInvite(message.optString("id"), message.optString("name"), it)
                status = "Connected"
            }
            "outgoing" -> {
                outgoing = LobbyInvite(message.optString("id"), message.optString("name"), null)
                status = "Connected"
            }
            "clearInvite" -> {
                incoming = null
                outgoing = null
            }
            "info" -> status = message.optString("text")
            "match" -> {
                val invite = RelayInvite.parse("${server.endpoint}/${message.optString("room")}") ?: return
                val mode = matchMode(message) ?: return
                val key = message.optString("key").takeIf(::validRelayHostKey)
                close()
                onMatch(invite, mode, key)
            }
        }
    }

    private fun matchMode(message: JSONObject) = MatchMode.entries.firstOrNull { it.name == message.optString("mode") }

    fun close() {
        if (finished) return
        finished = true
        connected = false
        players = emptyList()
        incoming = null
        outgoing = null
        socket = null
        writer.shutdownNow()
        runCatching { raw.close() }
    }
}
