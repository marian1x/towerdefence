package com.marian1x.agesofsiege.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Friends-only leaderboards on the game server (the Raspberry Pi relay).
 * The first saved friends-lobby access code is used: everyone in that lobby shares the boards.
 */
object Leaderboards {
    data class Entry(val name: String, val score: Int, val stars: Int, val wave: Int)

    private val main = Handler(Looper.getMainLooper())

    private fun server(context: Context): LobbyServer? = runCatching { SavedLobbyServers(context).load().firstOrNull() }.getOrNull()

    fun available(context: Context) = server(context) != null

    /** Sends a score in the background; the server keeps each player's best. */
    fun submit(context: Context, board: String, name: String, score: Int, stars: Int, wave: Int) {
        val s = server(context) ?: return
        val msg = JSONObject().put("t", "submit").put("board", board).put("name", name.take(18))
            .put("score", score).put("stars", stars).put("wave", wave)
        thread(name = "net-scores", isDaemon = true) {
            runCatching { request(s, msg) }.onFailure { Log.d("AgesOfSiege", "score submit failed", it) }
        }
    }

    /** Loads the top scores of [board]; [done] runs on the main thread. */
    fun top(context: Context, board: String, done: (Result<List<Entry>>) -> Unit) {
        val s = server(context)
        if (s == null) {
            done(Result.failure(IllegalStateException("Add a friends lobby (Play with a friend → Friends lobby) to share leaderboards.")))
            return
        }
        thread(name = "net-scores", isDaemon = true) {
            val result = runCatching {
                val reply = request(s, JSONObject().put("t", "top").put("board", board))
                val list = reply.optJSONArray("entries") ?: throw IllegalStateException(reply.optString("text", "No scores"))
                (0 until list.length()).map { i ->
                    val e = list.getJSONObject(i)
                    Entry(e.optString("name"), e.optInt("score"), e.optInt("stars"), e.optInt("wave"))
                }
            }
            val error = result.exceptionOrNull()
            val mapped = if (error == null) result else Result.failure(IllegalStateException(onlineConnectionError(error)))
            main.post { done(mapped) }
        }
    }

    private fun request(s: LobbyServer, msg: JSONObject): JSONObject {
        val raw = Socket()
        try {
            val tls = openSocket(raw, s.endpoint)
            tls.relayRequest("SCORES ${s.key}")
            tls.expectReply("READY")
            tls.getOutputStream().apply {
                write((msg.toString() + "\n").encodeToByteArray())
                flush()
            }
            val reader = tls.getInputStream().bufferedReader()
            val line = reader.readGameFrame() ?: throw IllegalStateException("No reply")
            return JSONObject(line)
        } finally {
            runCatching { raw.close() }
        }
    }
}
