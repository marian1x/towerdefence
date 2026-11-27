package com.marian1x.agesofsiege.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.marian1x.agesofsiege.core.Difficulty
import com.marian1x.agesofsiege.core.MatchMode
import com.marian1x.agesofsiege.core.MatchRules
import com.marian1x.agesofsiege.core.Pace
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.MpSession
import com.marian1x.agesofsiege.game.Overlay
import com.marian1x.agesofsiege.net.LanDiscovery
import com.marian1x.agesofsiege.net.RelayEndpoint
import com.marian1x.agesofsiege.net.RelayInvite
import com.marian1x.agesofsiege.net.localIpAddresses
import com.marian1x.agesofsiege.net.validRelayHostKey

@Composable
private fun TextFieldRow(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Gold, unfocusedBorderColor = PanelBorder, cursorColor = Gold,
            focusedLabelColor = Gold, unfocusedLabelColor = TextDim,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Host or join a game over Wi-Fi or through an internet relay. */
@Composable
fun MultiplayerPanel(c: GameController) {
    val session = c.mp
    var connectionMode by remember { mutableIntStateOf(0) }
    val close = {
        if (c.mp?.phase != MpSession.Phase.PLAYING) c.cancelMatch()
        c.hud.overlay = Overlay.NONE
    }
    GameDialog("Play with a friend", width = 520, onClose = close) {
        if (session == null || (session.phase == MpSession.Phase.ENDED && session.setup == null && !session.isHost)) {
            if (session?.phase == MpSession.Phase.ENDED) {
                Text(session.status, color = Color(0xFFFFB070), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
            }
            ChipRow(listOf("SAME WI-FI", "ONLINE"), connectionMode) { connectionMode = it }
            Spacer(Modifier.height(8.dp))
            when {
                connectionMode == 1 -> SetupTabs(c, online = true, retryJoin = session != null)
                Build.VERSION.SDK_INT >= 37 -> LocalNetworkPermission { SetupTabs(c, online = false, retryJoin = session != null) }
                else -> SetupTabs(c, online = false, retryJoin = session != null)
            }
        } else {
            Lobby(c, session)
        }
    }
}

@RequiresApi(37)
@Composable
private fun LocalNetworkPermission(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    var denialNeedsSettings by remember { mutableStateOf<Boolean?>(null) }
    var permissionCheck by remember { mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denialNeedsSettings = if (granted) null else activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
        } == true
        permissionCheck++
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        denialNeedsSettings = null
        permissionCheck++
    }
    val granted = key(permissionCheck) {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) {
        content()
    } else {
        Text("Allow local network access to host or join a game on the same Wi-Fi.", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        if (denialNeedsSettings == true) {
            MenuButton("OPEN SETTINGS", Modifier.fillMaxWidth(), primary = true) {
                settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        } else {
            val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
            MenuButton(if (rationale) "CONTINUE" else "ALLOW LOCAL NETWORK", Modifier.fillMaxWidth(), primary = true) {
                request.launch(permission)
            }
        }
    }
}

@Composable
private fun SetupTabs(c: GameController, online: Boolean, retryJoin: Boolean) {
    var tab by remember(online) { mutableIntStateOf(if (retryJoin) 1 else 0) }
    var name by remember { mutableStateOf(c.hud.settings.lordName.takeIf { it != "Lord of the Keep" } ?: "") }
    ChipRow(listOf("HOST A GAME", "JOIN A GAME"), tab) { tab = it }
    Spacer(Modifier.height(8.dp))
    TextFieldRow(name, { name = it.take(18) }, "Your name")
    when {
        tab == 0 -> HostSetup(c, name, online)
        online -> JoinOnlineSetup(c, name)
        else -> JoinSetup(c, name)
    }
}

@Composable
private fun HostSetup(c: GameController, name: String, online: Boolean) {
    var mode by remember { mutableStateOf(MatchMode.VERSUS) }
    var difficulty by remember { mutableStateOf(Difficulty.NORMAL) }
    var pace by remember { mutableStateOf(Pace.STANDARD) }
    var address by remember { mutableStateOf(c.prefs.relayEndpoint) }
    var hostKey by remember { mutableStateOf("") }
    val endpoint = if (online) RelayEndpoint.parse(address) else null
    if (online) {
        Spacer(Modifier.height(8.dp))
        TextFieldRow(address, { address = it.take(280) }, "Relay server hostname", KeyboardType.Uri)
        TextFieldRow(hostKey, { hostKey = it.take(128) }, "Relay host key", KeyboardType.Password)
    }
    SectionLabel("Mode")
    ChipRow(MatchMode.entries.map { it.label }, mode.ordinal) { mode = MatchMode.entries[it] }
    Text(
        if (mode == MatchMode.VERSUS) "Each of you rules one end of the realm. Race for loot and storm your friend's base – their towers fight back live."
        else "Neighbouring capitals. Share soldiers and supplies and defeat the rival lord together.",
        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
    )
    SectionLabel("Raider strength")
    ChipRow(Difficulty.entries.map { it.label }, difficulty.ordinal) { difficulty = Difficulty.entries[it] }
    SectionLabel("Game pace")
    ChipRow(Pace.entries.map { it.label }, pace.ordinal) { pace = Pace.entries[it] }
    Spacer(Modifier.height(12.dp))
    MenuButton(
        "HOST GAME",
        Modifier.fillMaxWidth(),
        primary = true,
        enabled = !online || (endpoint != null && validRelayHostKey(hostKey.trim())),
    ) { c.hostMatch(name, mode, difficulty, pace, endpoint, hostKey.trim()) }
    Text("Your single-player game stays saved and untouched.", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun JoinOnlineSetup(c: GameController, name: String) {
    var inviteText by remember { mutableStateOf("") }
    val invite = RelayInvite.parse(inviteText)
    Spacer(Modifier.height(8.dp))
    TextFieldRow(inviteText, { inviteText = it.take(320) }, "Paste your friend's invite", KeyboardType.Uri)
    Spacer(Modifier.height(8.dp))
    MenuButton("JOIN", Modifier.fillMaxWidth(), primary = true, enabled = invite != null) {
        invite?.let { c.joinOnlineMatch(name, it) }
    }
}

@Composable
private fun JoinSetup(c: GameController, name: String) {
    val context = LocalContext.current
    val discovery = remember { LanDiscovery(context) }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    var ip by remember { mutableStateOf("") }
    SectionLabel("Games on this Wi-Fi")
    if (discovery.found.isEmpty()) {
        Text("Looking for games… (ask your friend to press HOST GAME)", color = TextDim, fontSize = 12.sp)
    }
    for (g in discovery.found) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232A39))
                .clickable { c.joinMatch(name, g.host, g.port) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚔ ${g.name}", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("JOIN", fontFamily = GameFont, color = Gold, fontSize = 16.sp)
        }
    }
    SectionLabel("Or type the host's address")
    TextFieldRow(ip, { ip = it.take(40) }, "e.g. 192.168.1.23", KeyboardType.Uri)
    Spacer(Modifier.height(8.dp))
    MenuButton("JOIN", Modifier.fillMaxWidth(), primary = true, enabled = ip.isNotBlank()) {
        val address = ip.trim()
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', MatchRules.PORT.toString()).toIntOrNull()
        if (host.isNotBlank() && port != null && port in 1..65535) c.joinMatch(name, host, port)
    }
}

@Composable
private fun Lobby(c: GameController, s: MpSession) {
    val context = LocalContext.current
    Text(s.status, color = Color.White, fontSize = 15.sp)
    Spacer(Modifier.height(8.dp))
    if (s.isHost) {
        if (s.isOnline) {
            s.onlineInvite?.let { invite ->
                var copied by remember(invite) { mutableStateOf(false) }
                Text("Ask your friend to open Online → Join a game and paste the invite.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                MenuButton(if (copied) "INVITE COPIED" else "COPY INVITE", Modifier.fillMaxWidth()) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Ages of Siege invite", invite))
                    copied = true
                }
            }
        } else {
            val ips = remember { localIpAddresses(context) }
            Text(
                if (ips.isEmpty()) "Connect to Wi-Fi so your friend can find you."
                else "Your friend can also join by typing: ${ips.joinToString(" or ", transform = s::joinAddress)}",
                color = TextDim, fontSize = 12.sp,
            )
        }
        s.setup?.let { setup ->
            SectionLabel("Match")
            Text("${setup.mode.label} · ${setup.difficulty.label} raiders · ${setup.pace.label} pace", color = Color.White, fontSize = 13.sp)
            for (p in setup.players) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.slot == 0) Color(0xFF6FA0FF) else Color(0xFFFF8A7A)))
                    Spacer(Modifier.padding(4.dp))
                    Text(p.name + if (p.slot == 0) " (you)" else "", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    } else s.setup?.let { setup ->
        Text("${setup.mode.label} · ${setup.difficulty.label} raiders · ${setup.pace.label} pace", color = TextDim, fontSize = 12.sp)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MenuButton(if (s.phase == MpSession.Phase.ENDED) "BACK" else "CANCEL", Modifier.weight(1f)) { c.cancelMatch() }
        if (s.isHost) {
            MenuButton("START", Modifier.weight(1f), primary = true, enabled = s.setup != null && s.peerConnected) { s.startMatch() }
        } else if (s.phase == MpSession.Phase.LOBBY && !s.peerConnected) {
            MenuButton("RECONNECT", Modifier.weight(1f), primary = true, enabled = !s.connecting) { s.reconnect() }
        }
    }
}

/** In-game: who you're playing with, and a pause banner while they're away. */
@Composable
internal fun MatchBadge(c: GameController, modifier: Modifier = Modifier) {
    val view = c.campaign.mp ?: return
    val other = view.other ?: return
    val connected = c.mp?.peerConnected == true
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(PanelBg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Color(0xFF7CFC9A) else Color(0xFFFFB070)))
        Spacer(Modifier.padding(3.dp))
        Text(
            (if (view.mode == MatchMode.VERSUS) "⚔ vs " else "🤝 ") + other.name,
            color = Color.White, fontSize = 13.sp, fontFamily = GameFont,
        )
    }
}

@Composable
internal fun WaitingForFriend(c: GameController) {
    val view = c.campaign.mp ?: return
    val s = c.mp ?: return
    if (s.peerConnected) return
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000))
            .clickable(interactionSource = null, indication = null) {}.safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(PanelBg).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ended = s.phase == MpSession.Phase.ENDED
            val title = if (ended) "Match ended" else "Waiting for ${view.other?.name ?: "your friend"}…"
            Text(title, fontFamily = GameFont, fontSize = 22.sp, color = Gold)
            Text(
                when {
                    ended -> s.status
                    s.isHost -> "The game is paused. Your friend can reconnect from their match screen."
                    s.isOnline -> "The game is paused. Check your internet connection and reconnect."
                    else -> "The game is paused. Keep both phones on the same Wi-Fi, then reconnect."
                },
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!s.isHost && !ended) {
                    MenuButton("RECONNECT", primary = true, enabled = !s.connecting) { c.reconnectMatch() }
                }
                MenuButton("LEAVE MATCH", danger = true) { c.leaveMatch() }
            }
            if (!ended && (!s.isHost || s.isOnline) && s.status.isNotBlank()) {
                Text(s.status, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
