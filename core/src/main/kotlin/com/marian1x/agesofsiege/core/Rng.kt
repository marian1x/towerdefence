package com.marian1x.agesofsiege.core

/** Small deterministic xorshift RNG: same seed gives the same game on every device and on a server. */
class Rng(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    fun range(min: Float, max: Float): Float = min + (max - min) * nextFloat()
}
