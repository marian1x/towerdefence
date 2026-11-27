package com.marian1x.agesofsiege.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.marian1x.agesofsiege.core.MatchMode
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.net.FriendsLobby
import com.marian1x.agesofsiege.net.LobbyServer
import com.marian1x.agesofsiege.net.SavedLobbyServers

@Composable
internal fun FriendsLobbyPanel(controller: GameController) {
    val context = LocalContext.current
    val store = remember { SavedLobbyServers(context) }
    var servers by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<LobbyServer?>(null) }
    var name by remember {
        mutableStateOf(controller.hud.settings.lordName.takeUnless { it == "Lord of the Keep" }.orEmpty())
    }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(updated: List<LobbyServer>): Boolean {
        return runCatching { store.save(updated) }.fold(
            onSuccess = {
                servers = updated
                error = null
                true
            },
            onFailure = {
                error = "Couldn't save the server list."
                false
            },
        )
    }

    val server = selected
    if (server != null) {
        ConnectedLobby(
            controller = controller,
            server = server,
            name = name.trim().take(18).ifBlank { "Player" },
            onBack = { selected = null },
            onForget = {
                if (save(servers.filter { it !== server })) selected = null
            },
        )
    } else {
        TextFieldRow(name, { name = it.filterNot(Char::isISOControl).take(18) }, "Your name")
        if (servers.isNotEmpty()) SectionLabel("Saved servers")
        for (saved in servers) {
            MenuButton(saved.endpoint.toString(), Modifier.fillMaxWidth()) { selected = saved }
            Spacer(Modifier.height(4.dp))
        }
        SectionLabel("Add a server")
        TextFieldRow(code, { code = it.take(400) }, "Paste a lobby access code", KeyboardType.Password)
        Spacer(Modifier.height(8.dp))
        val newServer = LobbyServer.parse(code)
        MenuButton("ADD SERVER", Modifier.fillMaxWidth(), primary = true, enabled = newServer != null) {
            if (newServer != null) {
                val updated = servers.filter { it.endpoint.toString() != newServer.endpoint.toString() } + newServer
                if (save(updated)) {
                    selected = newServer
                    code = ""
                }
            }
        }
    }
    error?.let { Text(it, color = Danger, fontSize = 12.sp) }
}

@Composable
private fun ConnectedLobby(
    controller: GameController,
    server: LobbyServer,
    name: String,
    onBack: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = (LocalActivity.current as? LifecycleOwner)?.lifecycle
    var lobby by remember(server) { mutableStateOf<FriendsLobby?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(MatchMode.VERSUS) }
    var copied by remember(server) { mutableStateOf(false) }

    DisposableEffect(server, name, lifecycle, retry) {
        fun connect() {
            lobby?.close()
            lobby = FriendsLobby(server, name) { invite, matchMode, ticket ->
                if (ticket == null) controller.joinOnlineMatch(name, invite)
                else controller.hostLobbyMatch(name, matchMode, invite, ticket)
            }.also { it.start() }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> connect()
                Lifecycle.Event.ON_PAUSE -> {
                    lobby?.close()
                    lobby = null
                }
                else -> Unit
            }
        }
        if (lifecycle != null) lifecycle.addObserver(observer) else connect()
        onDispose {
            lifecycle?.removeObserver(observer)
            lobby?.close()
            lobby = null
        }
    }

    Text(server.endpoint.toString(), color = Gold, fontSize = 16.sp)
    val active = lobby
    if (active != null) {
        Text(active.status, color = TextDim, fontSize = 12.sp)
        if (active.connected) {
            SectionLabel("Online as $name")
            val incoming = active.incoming
            val outgoing = active.outgoing
            if (incoming != null) {
                Text("${incoming.name} invited you to ${incoming.mode?.label}.", color = Color.White, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuButton("ACCEPT", Modifier.weight(1f), primary = true) { active.accept(incoming) }
                    MenuButton("DECLINE", Modifier.weight(1f)) { active.decline(incoming) }
                }
            } else if (outgoing != null) {
                Text("Waiting for ${outgoing.name} to accept…", color = Color.White, fontSize = 14.sp)
                MenuButton("CANCEL INVITE") { active.decline(outgoing) }
            }
            SectionLabel("Match mode")
            ChipRow(MatchMode.entries.map { it.label }, mode.ordinal) { mode = MatchMode.entries[it] }
            SectionLabel("Players")
            if (active.players.isEmpty()) Text("No other players are online.", color = TextDim, fontSize = 12.sp)
            for (player in active.players) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(player.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MenuButton("INVITE", enabled = player.available && incoming == null && outgoing == null) {
                        active.invite(player, mode)
                    }
                }
            }
        } else if (active.finished) {
            MenuButton("RECONNECT") { retry++ }
        }
    }
    Spacer(Modifier.height(8.dp))
    MenuButton(if (copied) "LOBBY CODE COPIED" else "COPY LOBBY CODE", Modifier.fillMaxWidth()) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Ages of Siege lobby", server.accessCode))
        copied = true
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuButton("BACK", Modifier.weight(1f)) { onBack() }
        MenuButton("FORGET SERVER", Modifier.weight(1f)) { onForget() }
    }
}
