package com.marian1x.agesofsiege.core

import java.util.PriorityQueue

/**
 * Distance-to-goal field over the base grid (Dijkstra, 8 directions, no corner cutting).
 * Every raider simply walks "downhill", so one field serves hundreds of enemies.
 */
class FlowField(val width: Int, val height: Int) {
    val dist = IntArray(width * height)

    companion object {
        const val INF = Int.MAX_VALUE
        const val STRAIGHT = 10
        const val DIAGONAL = 14
        val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }

    /**
     * @param blocked tiles that cannot be walked on
     * @param goal tiles that are the destination (they may be blocked, e.g. the Keep)
     */
    fun compute(blocked: BooleanArray, goal: BooleanArray, out: IntArray = dist) {
        out.fill(INF)
        val queue = PriorityQueue<Long>()
        for (i in 0 until width * height) {
            if (goal[i]) {
                out[i] = 0
                queue.add(i.toLong())
            }
        }
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            val d = (e ushr 32).toInt()
            val i = (e and 0xffffffffL).toInt()
            if (d > out[i]) continue
            val x = i % width
            val y = i / width
            for (k in 0 until 8) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val ni = ny * width + nx
                if (blocked[ni] || goal[ni]) continue
                if (k >= 4) {
                    // No cutting corners past blocked tiles.
                    if (isBlockedNotGoal(blocked, goal, x + DX[k], y) || isBlockedNotGoal(blocked, goal, x, y + DY[k])) continue
                }
                val nd = d + if (k < 4) STRAIGHT else DIAGONAL
                if (nd < out[ni]) {
                    out[ni] = nd
                    queue.add((nd.toLong() shl 32) or ni.toLong())
                }
            }
        }
    }

    private fun isBlockedNotGoal(blocked: BooleanArray, goal: BooleanArray, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return true
        val i = y * width + x
        return blocked[i] && !goal[i]
    }
}
