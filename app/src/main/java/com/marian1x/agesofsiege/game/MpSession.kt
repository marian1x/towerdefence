package com.marian1x.agesofsiege.game

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marian1x.agesofsiege.core.Cost
import com.marian1x.agesofsiege.core.Difficulty
import com.marian1x.agesofsiege.core.GameEvent
import com.marian1x.agesofsiege.core.MatchMode
import com.marian1x.agesofsiege.core.MatchRealm
import com.marian1x.agesofsiege.core.MatchRules
import com.marian1x.agesofsiege.core.MatchSetup
import com.marian1x.agesofsiege.core.MpPlayer
import com.marian1x.agesofsiege.core.NetMsg
import com.marian1x.agesofsiege.core.Pace
import com.marian1x.agesofsiege.core.World
import com.marian1x.agesofsiege.net.Connection
import com.marian1x.agesofsiege.net.LanHost
import com.marian1x.agesofsiege.net.RelayEndpoint
import com.marian1x.agesofsiege.net.RelayHost
import com.marian1x.agesofsiege.net.RelayInvite
import com.marian1x.agesofsiege.net.connectTo
import com.marian1x.agesofsiege.net.joinRelay
import com.marian1x.agesofsiege.net.onlineConnectionError
import java.net.Socket
import java.util.UUID

/** A two-player match. The host simulates the shared realm and each phone simulates its base. */
class MpSession private constructor(
    private val c: GameController,
    val isHost: Boolean,
    val myName: String,
    val isOnline: Boolean = false,
) {
    enum class Phase { CONNECTING, LOBBY, PLAYING, ENDED }

    var phase by mutableStateOf(Phase.CONNECTING)
        private set
    var status by mutableStateOf("")
        private set
    var peerConnected by mutableStateOf(false)
        private set
    var setup by mutableStateOf<MatchSetup?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set

    private val mySlot get() = if (isHost) 0 else 1
    private var peerName: String? = null
    private var mode = MatchMode.VERSUS
    /** Race: the level the host picked (null = random). */
    private var raceLevel: String? = null
    private var raceSquads = 0
    private var difficulty = Difficulty.NORMAL
    private var pace = Pace.STANDARD
    private var lanHost: LanHost? = null
    private var relayHost: RelayHost? = null
    private var guestInvite: RelayInvite? = null
    private var relayReady by mutableStateOf(false)
    private var startOnJoin = false
    val onlineInvite get() = relayHost?.invite?.takeIf { relayReady && !startOnJoin }?.toString()
    private var realm: MatchRealm? = null
    private var link: Connection? = null
    private var hostAddress = ""
    private var hostPort = MatchRules.PORT
    private var playerToken = if (isHost) "" else UUID.randomUUID().toString()
    private var statusTimer = 0f
    private var snapTimer = 0f

    // Numbered deliveries are replayed in order after reconnecting. The receiver applies each once.
    private var nextSequence = 1L
    private var received = 0L
    private val pending = LinkedHashMap<Long, NetMsg.Delivery>()

    companion object {
        private const val TIMEOUT_MS = 7000L

        fun host(
            c: GameController,
            context: Context,
            name: String,
            mode: MatchMode,
            difficulty: Difficulty,
            pace: Pace,
            endpoint: RelayEndpoint? = null,
            hostKey: String = "",
            lobbyInvite: RelayInvite? = null,
            raceLevel: String? = null,
        ) = MpSession(c, true, name, isOnline = endpoint != null).apply {
                this.mode = mode
                this.raceLevel = raceLevel
                this.difficulty = difficulty
                this.pace = pace
                startOnJoin = lobbyInvite != null
                phase = Phase.LOBBY
                status = "Opening the game…"
                if (endpoint == null) {
                    lanHost = LanHost(context, name, ::onIncoming).also { host ->
                        host.start(
                            onReady = { status = "Waiting for a friend to join…" },
                            onError = { close(); status = it },
                        )
                    }
                } else {
                    relayHost = RelayHost(
                        invite = lobbyInvite ?: RelayInvite.create(endpoint),
                        key = hostKey,
                        onReady = {
                            relayReady = true
                            status = if (phase == Phase.PLAYING) "Room is ready for your friend to reconnect."
                            else if (startOnJoin) "Waiting for your friend to join…"
                            else "Room is ready. Send the invite to your friend."
                        },
                        onIncoming = ::onIncoming,
                        onError = { error, retrying ->
                            relayReady = false
                            if (!retrying) close()
                            status = if (retrying) "$error Retrying…" else error
                        },
                    ).also { it.listen() }
                }
            }

        fun join(c: GameController, name: String, host: String, port: Int) =
            MpSession(c, false, name).apply {
                hostAddress = host
                hostPort = port
                reconnect()
            }

        fun joinOnline(c: GameController, name: String, invite: RelayInvite) =
            MpSession(c, false, name, isOnline = true).apply {
                guestInvite = invite
                reconnect()
            }
    }

    /** The address shown to a friend must include the port when the default was already in use. */
    fun joinAddress(ip: String): String {
        val port = lanHost?.port ?: MatchRules.PORT
        return if (port == MatchRules.PORT) ip else "$ip:$port"
    }

    fun reconnect() {
        if (isHost || phase == Phase.ENDED || connecting || peerConnected) return
        connecting = true
        status = if (isOnline) "Joining the room…" else "Connecting to $hostAddress…"
        val connected: (Result<Socket>) -> Unit = { result ->
            result.onSuccess { socket ->
                if (phase == Phase.ENDED) {
                    runCatching { socket.close() }
                    return@onSuccess
                }
                val connection = Connection(socket, ::onGuestMessage, ::onClosed)
                link = connection
                connection.start()
                connection.send(NetMsg.Hello(myName, playerToken = playerToken, resumeMatchId = setup?.matchId))
                status = "Waiting for the host…"
            }.onFailure {
                connecting = false
                if (phase != Phase.ENDED) {
                    status = if (isOnline) onlineConnectionError(it)
                    else "Couldn't reach $hostAddress. Check that the host is on the same Wi-Fi."
                    if (setup == null) phase = Phase.ENDED
                }
            }
        }
        val invite = guestInvite
        if (invite != null) joinRelay(invite, connected) else connectTo(hostAddress, hostPort, connected)
    }

    private fun onIncoming(socket: Socket) {
        if (link != null) {
            Connection(socket, { _, _ -> }, { _, _ -> })
                .close("full", NetMsg.Reject("This game is full"))
            return
        }
        link = Connection(socket, ::onHostMessage, ::onClosed).also { it.start() }
        if (phase == Phase.LOBBY) status = "Someone is joining…"
    }

    private fun buildSetup(guestName: String): MatchSetup {
        val now = System.currentTimeMillis()
        val seed = now and 0xffffff
        val world = World(MatchRules.worldSeed(seed))
        val homes = MatchRules.homes(world, mode)
        val level = if (mode != MatchMode.RACE) null else
            raceLevel ?: com.marian1x.agesofsiege.core.Levels.all[(seed % com.marian1x.agesofsiege.core.Levels.all.size).toInt()].id
        return MatchSetup(
            matchId = now,
            seed = seed, mode = mode, difficulty = difficulty, pace = pace,
            players = listOf(
                MpPlayer(0, myName, homes[0].first, homes[0].second),
                MpPlayer(1, guestName, homes[1].first, homes[1].second),
            ),
            level = level,
        )
    }

    private fun onHostMessage(from: Connection, msg: NetMsg) {
        if (from !== link || phase == Phase.ENDED) return
        if (!peerConnected) {
            if (msg is NetMsg.Hello) welcomeGuest(from, msg)
            else from.close("Invalid handshake")
            return
        }
        if (phase == Phase.PLAYING) {
            when (msg) {
                is NetMsg.Status -> realm?.handle(1, msg)
                is NetMsg.RaceStatus -> c.onRaceStatus(msg)
                is NetMsg.Delivery -> if (msg.message is NetMsg.RaceSend) {
                    receive(from, msg) { c.onRaceAttack(it as NetMsg.RaceSend) }
                } else if (msg.message is NetMsg.SendArmy ||
                    msg.message is NetMsg.AssaultResult || msg.message is NetMsg.Supplies) {
                    receive(from, msg) { realm?.handle(1, it) }
                }
                is NetMsg.Ack -> pending.remove(msg.sequence)
                is NetMsg.Bye -> endFromPeer(msg.reason)
                else -> Unit
            }
        }
    }

    private fun welcomeGuest(from: Connection, msg: NetMsg.Hello) {
        if (msg.protocol != MatchRules.PROTOCOL || msg.playerToken.length != 36) {
            from.close("version", NetMsg.Reject("Different game versions. Update both phones."))
            return
        }
        if (phase == Phase.PLAYING) {
            val current = setup ?: return
            if (msg.resumeMatchId != current.matchId || msg.playerToken != playerToken) {
                from.close("resume", NetMsg.Reject("Only the original guest can reconnect to this match."))
                return
            }
            from.send(NetMsg.Welcome(current, 1, started = true))
            peerConnected = true
            realm?.setConnected(1, true)
            pending.values.forEach(from::send)
            c.mpToast("${peerName ?: "Your friend"} is back!", good = true)
            return
        }
        val existing = setup
        if (msg.resumeMatchId != null && (msg.resumeMatchId != existing?.matchId || msg.playerToken != playerToken)) {
            from.close("ended", NetMsg.Reject("That match has ended."))
            return
        }
        val name = if (msg.resumeMatchId != null) existing?.players?.get(1)?.name ?: msg.name
        else msg.name.trim().take(18).ifBlank { "Guest" }
        val s = if (msg.resumeMatchId != null && existing != null) existing else buildSetup(name)
        setup = s
        peerName = name
        playerToken = msg.playerToken
        peerConnected = true
        status = "$name joined! Press START when you're ready."
        from.send(NetMsg.Welcome(s, 1, started = false))
        if (startOnJoin) startMatch()
    }

    fun startMatch() {
        val s = setup ?: return
        if (!isHost || phase != Phase.LOBBY || !peerConnected || link?.closed != false) return
        phase = Phase.PLAYING
        link?.send(NetMsg.Start(s))
        if (s.mode == MatchMode.RACE) {
            c.beginRace(this, s, 0)
        } else {
            realm = MatchRealm(s)
            c.beginMatch(this, s, 0)
        }
    }

    private fun begin(s: MatchSetup) {
        if (s.mode == MatchMode.RACE) c.beginRace(this, s, 1) else c.beginMatch(this, s, 1)
    }

    val isRace: Boolean get() = setup?.mode == MatchMode.RACE

    /** Race: tell the other phone how this battle is going. */
    fun raceStatus(g: com.marian1x.agesofsiege.core.BaseGame, force: Boolean = false) {
        if (!isRace || (!force && !running)) return
        val o = g.outcome
        link?.send(NetMsg.RaceStatus(mySlot, g.wave, g.keepHp.coerceAtLeast(0), g.keepMaxHp, g.leaks, g.score(), o != null, o == com.marian1x.agesofsiege.core.Outcome.WON))
    }

    /** Race: a raider squad marches into the opponent's battlefield. Returns an error or null. */
    fun sendRaceSquad(g: com.marian1x.agesofsiege.core.BaseGame): String? {
        if (!running) return "Wait for your friend to reconnect"
        val level = g.level ?: return "Not in a race"
        val (enemyId, units) = when {
            g.wave < 4 -> "raider" to 4
            g.wave < 8 -> "outrider" to 3
            else -> "brute" to 2
        }
        val def = com.marian1x.agesofsiege.core.Content.enemy(enemyId)
        raceSquads++
        sendReliably(NetMsg.RaceSend((mySlot + 1) * 100_000 + raceSquads, units, def.hp * level.hpFor(g.wave) * 1.1f, enemyId))
        return null
    }

    private fun onGuestMessage(from: Connection, msg: NetMsg) {
        if (from !== link || phase == Phase.ENDED) return
        when (msg) {
            is NetMsg.Reject -> {
                close()
                status = msg.reason
            }
            is NetMsg.Welcome -> {
                if (!connecting || (setup != null && setup?.matchId != msg.setup.matchId)) return
                connecting = false
                setup = msg.setup
                peerName = msg.setup.players[0].name
                peerConnected = true
                if (msg.started) {
                    phase = Phase.PLAYING
                    begin(msg.setup)
                    pending.values.forEach(from::send)
                } else {
                    phase = Phase.LOBBY
                    status = "Joined ${peerName}'s game. Waiting for the host to start…"
                }
            }
            is NetMsg.Start -> if (peerConnected && phase == Phase.LOBBY && setup?.matchId == msg.setup.matchId) {
                phase = Phase.PLAYING
                begin(msg.setup)
            }
            is NetMsg.RaceStatus -> if (phase == Phase.PLAYING) c.onRaceStatus(msg)
            is NetMsg.Snapshot -> if (running) applyLocal(msg)
            is NetMsg.Delivery -> if (running) receive(from, msg, ::applyLocal)
            is NetMsg.Ack -> if (running) pending.remove(msg.sequence)
            is NetMsg.Bye -> endFromPeer(msg.reason)
            else -> Unit
        }
    }

    private fun onClosed(connection: Connection, reason: String) {
        if (connection !== link || phase == Phase.ENDED) return
        link = null
        peerConnected = false
        connecting = false
        if (isHost) {
            realm?.setConnected(1, false)
            if (phase == Phase.PLAYING) {
                c.mpToast("${peerName ?: "Your friend"} disconnected", good = false)
            } else {
                status = "Waiting for a friend to join…"
                // Keep the lobby setup so the same guest can recover a dropped connection.
            }
            relayReady = false
            relayHost?.listen(rejoining = true)
        } else {
            status = reason
            if (setup == null) phase = Phase.ENDED
        }
    }

    private fun endFromPeer(reason: String) {
        close()
        status = "${peerName ?: "Your friend"} $reason."
    }

    private fun sendReliably(msg: NetMsg) {
        val delivery = NetMsg.Delivery(nextSequence++, msg)
        pending[delivery.sequence] = delivery
        if (peerConnected) link?.send(delivery)
    }

    private fun receive(from: Connection, delivery: NetMsg.Delivery, apply: (NetMsg) -> Unit) {
        if (delivery.sequence == received + 1) {
            apply(delivery.message)
            received = delivery.sequence
        }
        if (delivery.sequence <= received) from.send(NetMsg.Ack(delivery.sequence))
    }

    private fun applyLocal(msg: NetMsg) {
        val camp = c.campaign
        when (msg) {
            is NetMsg.Snapshot -> {
                camp.applySnapshot(msg)
                camp.base.awayTroops += pending.values.sumOf { (it.message as? NetMsg.SendArmy)?.troops?.size ?: 0 }
            }
            is NetMsg.ArmyHome -> {
                camp.base.returnTroops(msg.troops)
                camp.base.res.add(msg.loot)
            }
            is NetMsg.Report -> c.mpReport(msg.text, msg.good)
            is NetMsg.Assault -> camp.base.launchAssault(msg.assaultId, msg.units, msg.hp, msg.from, msg.enemyId)
            is NetMsg.RaceSend -> c.onRaceAttack(msg)
            else -> Unit
        }
    }

    private fun toRealm(msg: NetMsg) {
        if (isHost) realm?.handle(0, msg) else sendReliably(msg)
    }

    val running: Boolean get() = phase == Phase.PLAYING && peerConnected

    fun tick(dt: Float) {
        val connection = link ?: return
        if (connection.closed || phase == Phase.ENDED) return
        if (SystemClock.elapsedRealtime() - connection.lastHeard > TIMEOUT_MS) {
            connection.close("Connection timed out")
            return
        }
        if (!peerConnected) return
        statusTimer -= dt
        if (statusTimer <= 0f) {
            statusTimer = 1f
            if (running && isRace) {
                raceStatus(c.game)
            } else if (running) {
                val base = c.campaign.base
                val heartbeat = NetMsg.Status(mySlot, MatchRules.defenseOf(base), base.troops.size, base.wave)
                if (isHost) realm?.handle(0, heartbeat) else connection.send(heartbeat)
            } else {
                connection.send(NetMsg.Ping)
            }
        }
        if (!running) return

        val r = realm ?: return
        r.step(dt)
        for ((slot, msg) in r.outbox) {
            if (slot == 0 || slot == -1) applyLocal(msg)
            if (slot == 1 || slot == -1) sendReliably(msg)
        }
        r.outbox.clear()
        snapTimer -= dt
        if (snapTimer <= 0f) {
            snapTimer = 0.25f
            val snap = r.snapshot()
            applyLocal(snap)
            connection.send(snap)
        }
    }

    fun sendArmy(counts: Map<String, Int>, poiId: Int, cellX: Int, cellY: Int, targetPlayer: Int): String? {
        if (!running) return "Wait for your friend to reconnect"
        val (troops, err) = c.campaign.takeArmy(counts)
        if (troops == null) return err
        c.campaign.base.awayTroops += troops.size
        toRealm(NetMsg.SendArmy(mySlot, troops, poiId, cellX, cellY, targetPlayer))
        return null
    }

    fun sendSupplies(amount: Cost): String? {
        if (!running) return "Wait for your friend to reconnect"
        val res = c.campaign.base.res
        if (!res.canAfford(amount)) return "Not enough resources"
        res.pay(amount)
        toRealm(NetMsg.Supplies(mySlot, 1 - mySlot, amount))
        return null
    }

    fun onAssaultEnded(ev: GameEvent.AssaultEnded) {
        if (phase != Phase.PLAYING) return
        val base = c.campaign.base
        val stolen = MatchRules.plunder(base.res, ev.reached)
        base.res.pay(stolen)
        toRealm(NetMsg.AssaultResult(ev.assaultId, ev.total, ev.killed, ev.reached, stolen))
    }

    fun close() {
        if (phase == Phase.ENDED) return
        val goodbye = if (phase == Phase.PLAYING && peerConnected) NetMsg.Bye("left the match") else null
        phase = Phase.ENDED
        peerConnected = false
        connecting = false
        link?.close("bye", goodbye)
        link = null
        lanHost?.stop()
        lanHost = null
        relayHost?.stop()
        relayHost = null
    }
}
