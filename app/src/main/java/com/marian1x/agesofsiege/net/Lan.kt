package com.marian1x.agesofsiege.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.marian1x.agesofsiege.core.MatchRules
import com.marian1x.agesofsiege.core.NetCodec
import com.marian1x.agesofsiege.core.NetMsg
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "AgesOfSiege.Net"
private const val SERVICE_TYPE = "_agesofsiege._tcp."
private const val MAX_FRAME_CHARS = 1_048_576
private val main = Handler(Looper.getMainLooper())

/**
 * One TCP connection carrying newline-delimited JSON [NetMsg]s.
 * Callbacks always run on the main thread.
 */
class Connection(
    private val socket: Socket,
    private val onMessage: (Connection, NetMsg) -> Unit,
    private val onClosed: (Connection, String) -> Unit,
) {
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "net-write").apply { isDaemon = true }
    }
    private val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    @Volatile
    var closed = false
        private set
    /** Last time anything arrived (main thread clock, for the connection watchdog). */
    var lastHeard = SystemClock.elapsedRealtime()
        private set
    val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"

    fun start() {
        socket.tcpNoDelay = true
        thread(name = "net-read", isDaemon = true) {
            var reason = "Connection closed"
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (!closed) {
                    val line = reader.readGameFrame() ?: break
                    if (line.isBlank()) continue
                    val msg = try {
                        NetCodec.decode(line)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Bad message: ${line.take(120)}", t)
                        continue
                    }
                    main.post {
                        lastHeard = SystemClock.elapsedRealtime()
                        onMessage(this, msg)
                    }
                }
            } catch (t: Throwable) {
                if (!closed) reason = "Connection lost"
            }
            close(reason)
        }
    }

    @Synchronized
    fun send(msg: NetMsg) {
        if (closed) return
        writer.execute {
            try {
                write(msg)
            } catch (_: Exception) {
                close("Connection lost")
            }
        }
    }

    private fun write(msg: NetMsg) {
        out.write(NetCodec.encode(msg))
        out.newLine()
        out.flush()
    }

    @Synchronized
    fun close(reason: String, finalMessage: NetMsg? = null) {
        if (closed) return
        closed = true
        if (finalMessage == null) {
            writer.shutdownNow()
            runCatching { socket.close() }
        } else {
            writer.execute {
                try {
                    write(finalMessage)
                } catch (e: Exception) {
                    Log.d(TAG, "Could not send final message", e)
                } finally {
                    runCatching { socket.close() }
                }
            }
            writer.shutdown()
            main.postDelayed({ runCatching { socket.close() } }, 1000)
        }
        main.post { onClosed(this, reason) }
    }
}

private fun BufferedReader.readGameFrame(): String? {
    val frame = StringBuilder()
    while (true) {
        when (val next = read()) {
            -1 -> return null
            '\n'.code -> return frame.toString()
            else -> {
                if (frame.length == MAX_FRAME_CHARS) throw IOException("Game message is too large")
                frame.append(next.toChar())
            }
        }
    }
}

/** Listens for a friend on the local network and announces the game via NSD (mDNS). */
class LanHost(private val context: Context, private val gameName: String, private val onIncoming: (Socket) -> Unit) {
    private var server: ServerSocket? = null
    private var stopped = false
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    var port = MatchRules.PORT
        private set

    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        thread(name = "net-accept", isDaemon = true) {
            val s = try {
                ServerSocket(MatchRules.PORT)
            } catch (t: Throwable) {
                try {
                    ServerSocket(0)
                } catch (t2: Throwable) {
                    main.post { if (!stopped) onError("Couldn't open a game on this phone: ${t2.message}") }
                    return@thread
                }
            }
            main.post {
                if (stopped) {
                    runCatching { s.close() }
                } else {
                    server = s
                    port = s.localPort
                    register(port)
                    onReady()
                }
            }
            while (!s.isClosed) {
                val socket = try {
                    s.accept()
                } catch (t: Throwable) {
                    break
                }
                main.post {
                    if (stopped) runCatching { socket.close() }
                    else onIncoming(socket)
                }
            }
        }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "AgesOfSiege-$gameName".take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "NSD register failed $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD register", t)
        }
    }

    fun stop() {
        stopped = true
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }
}

/** A game found on the local network. */
data class FoundGame(val name: String, val host: String, val port: Int)

/** Finds hosted games on the same Wi-Fi. */
class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    val found = mutableStateListOf<FoundGame>()
    private val multicastLock = if (Build.VERSION.SDK_INT <= 33) {
        context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock(TAG)
    } else null
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed $errorCode")
                main.post { if (listener === this) stop() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("AgesOfSiege-")) return
                main.post {
                    if (listener !== this) return@post
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (listener !== this) return@post
                    pending.removeAll { it.serviceName == info.serviceName }
                    found.removeAll { it.name == info.serviceName.removePrefix("AgesOfSiege-") }
                }
            }
        }
        listener = l
        runCatching { multicastLock?.acquire() }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD discover", t)
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val activeListener = listener ?: return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        if (listener !== activeListener) return@post
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    main.post {
                        if (listener !== activeListener) return@post
                        resolving = false
                        if (host != null) {
                            val g = FoundGame(info.serviceName.removePrefix("AgesOfSiege-"), host, info.port)
                            found.removeAll { it.name == g.name }
                            found += g
                        }
                        resolveNext()
                    }
                }
            })
        } catch (t: Throwable) {
            resolving = false
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        resolving = false
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        found.clear()
        pending.clear()
    }
}

/** Opens a connection to a host (off the main thread). */
fun connectTo(host: String, port: Int, done: (Result<Socket>) -> Unit) {
    thread(name = "net-connect", isDaemon = true) {
        val socket = Socket()
        val r = runCatching {
            socket.connect(InetSocketAddress(host, port), 6000)
            socket
        }.onFailure { runCatching { socket.close() } }
        main.post { done(r) }
    }
}

/** This phone's IPv4 address(es) on the current network, shown so a friend can type it in. */
fun localIpAddresses(context: Context): List<String> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
    return props.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.filter { !it.startsWith("127.") }
}
