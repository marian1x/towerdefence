package com.marian1x.agesofsiege.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.marian1x.agesofsiege.core.LevelTheme
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sound effects (Kenney CC0 samples + a few synthesized ones), procedural music and vibration.
 * Everything is cheap: SoundPool for effects, one streaming AudioTrack for music.
 */
class Audio(appContext: Context, private val prefs: Prefs) {
    private val context: Context = appContext.applicationContext
    private val pool = SoundPool.Builder()
        .setMaxStreams(14)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
    private val sounds = HashMap<String, MutableList<Int>>()
    private val lastPlayed = HashMap<String, Long>()
    private val rnd = Random(5)
    val music = Music()

    init {
        val names = runCatching { context.assets.list("sfx")?.toList() }.getOrNull().orEmpty()
        for (file in names) {
            if (!file.endsWith(".ogg")) continue
            val key = file.removeSuffix(".ogg").replace(Regex("_\\d$"), "")
            runCatching {
                context.assets.openFd("sfx/$file").use { fd -> sounds.getOrPut(key) { ArrayList() } += pool.load(fd, 1) }
            }.onFailure { Log.w("AgesOfSiege", "sfx $file", it) }
        }
        // Synthesized sounds are generated once into the cache.
        thread(name = "sfx-synth", isDaemon = true) {
            val dir = File(context.cacheDir, "sfx-v1").apply { mkdirs() }
            for ((name, gen) in Synth.all) {
                runCatching {
                    val f = File(dir, "$name.wav")
                    if (!f.exists()) Synth.writeWav(f, gen())
                    val id = pool.load(f.path, 1)
                    synchronized(sounds) { sounds.getOrPut(name) { ArrayList() } += id }
                }.onFailure { Log.w("AgesOfSiege", "synth $name", it) }
            }
        }
    }

    /** Plays [name] (a random variant) unless it played in the last [minGapMs]. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f, minGapMs: Long = 45L) {
        val v = prefs.sfxVolume * volume
        if (v <= 0.01f) return
        val now = SystemClock.uptimeMillis()
        val last = lastPlayed[name] ?: 0L
        if (now - last < minGapMs) return
        val ids = synchronized(sounds) { sounds[name] } ?: return
        if (ids.isEmpty()) return
        lastPlayed[name] = now
        val r = (rate * (0.94f + rnd.nextFloat() * 0.12f)).coerceIn(0.5f, 2f)
        pool.play(ids[rnd.nextInt(ids.size)], v, v, 1, 0, r)
    }

    // ------------------------------------------------------------ vibration

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    fun haptic(ms: Long, strength: Int = 120) {
        if (!prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, strength.coerceIn(1, 255))) }
    }

    fun hapticPattern(timings: LongArray, amplitudes: IntArray) {
        if (!prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    fun pause() = music.pause()
    fun resume() = music.resume(prefs.musicVolume)
    fun applyVolumes() = music.setVolume(prefs.musicVolume)

    fun release() {
        music.stop()
        pool.release()
    }
}

/** Lets Compose buttons click without passing the controller around. */
object UiSound {
    var audio: Audio? = null
    fun click() = audio?.play("click", 0.55f, minGapMs = 30)
    fun open() = audio?.play("open", 0.5f)
    fun close() = audio?.play("close", 0.45f)
    fun star(index: Int) = audio?.play("star", 0.75f, rate = 1f + 0.18f * index, minGapMs = 0)
}

/** Small offline synthesizer for sounds that are not in the sample packs. */
private object Synth {
    const val RATE = 22050

    private fun buf(seconds: Float) = FloatArray((seconds * RATE).toInt())

    private fun pluck(out: FloatArray, freq: Float, start: Int, amp: Float, decay: Float = 0.996f, rng: Random) {
        val n = (RATE / freq).toInt().coerceAtLeast(2)
        val ring = FloatArray(n) { rng.nextFloat() * 2f - 1f }
        var i = 0
        var s = start
        while (s < out.size) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            ring[i] = (a + b) * 0.5f * decay
            out[s] += a * amp
            i = (i + 1) % n
            s++
        }
    }

    private fun noise(out: FloatArray, start: Float, len: Float, amp: Float, attack: Float, decay: Float, lowpass: Float, rng: Random) {
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        var lp = 0f
        for (k in 0 until n) {
            val idx = s0 + k
            if (idx >= out.size) break
            val t = k.toFloat() / RATE
            val env = (t / attack).coerceAtMost(1f) * exp(-t / decay)
            lp += (rng.nextFloat() * 2f - 1f - lp) * lowpass
            out[idx] += lp * env * amp
        }
    }

    private fun tone(out: FloatArray, f0: Float, f1: Float, start: Float, len: Float, amp: Float, attack: Float, decay: Float, saw: Boolean = false, vibrato: Float = 0f) {
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val idx = s0 + k
            if (idx >= out.size) break
            val t = k.toFloat() / RATE
            val f = f0 + (f1 - f0) * (t / len) + vibrato * sin(2 * PI * 5.5 * t).toFloat()
            ph += 2 * PI * f / RATE
            val v = if (saw) (((ph / (2 * PI)) % 1.0) * 2 - 1).toFloat() else sin(ph).toFloat()
            val env = (t / attack).coerceAtMost(1f) * if (t > len - 0.15f) ((len - t) / 0.15f).coerceAtLeast(0f) else 1f
            out[idx] += v * env * amp * exp(-t / decay)
        }
    }

    val all: List<Pair<String, () -> FloatArray>> = listOf(
        "bow" to {
            val o = buf(0.3f); val r = Random(1)
            pluck(o, 196f, 0, 0.55f, 0.985f, r); noise(o, 0f, 0.18f, 0.25f, 0.005f, 0.05f, 0.5f, r); o
        },
        "twang" to {
            val o = buf(0.45f); val r = Random(2)
            pluck(o, 98f, 0, 0.7f, 0.99f, r); noise(o, 0f, 0.25f, 0.3f, 0.004f, 0.06f, 0.25f, r); o
        },
        "launch" to {
            val o = buf(0.6f); val r = Random(3)
            tone(o, 90f, 50f, 0f, 0.35f, 0.7f, 0.004f, 0.12f); noise(o, 0.02f, 0.55f, 0.35f, 0.08f, 0.2f, 0.08f, r); o
        },
        "boom" to {
            val o = buf(1.0f); val r = Random(4)
            tone(o, 70f, 38f, 0f, 0.8f, 0.8f, 0.003f, 0.25f); noise(o, 0f, 1f, 0.9f, 0.002f, 0.22f, 0.12f, r); o
        },
        "fire" to {
            val o = buf(0.5f); val r = Random(5)
            noise(o, 0f, 0.5f, 0.7f, 0.06f, 0.18f, 0.35f, r); noise(o, 0f, 0.4f, 0.25f, 0.01f, 0.08f, 0.9f, r); o
        },
        "whoosh" to {
            val o = buf(0.7f); val r = Random(6)
            noise(o, 0f, 0.7f, 0.8f, 0.25f, 0.2f, 0.15f, r); o
        },
        "horn" to {
            val o = buf(1.6f)
            tone(o, 146.8f, 146.8f, 0f, 1.5f, 0.32f, 0.18f, 6f, saw = true, vibrato = 1.6f)
            tone(o, 220f, 220f, 0.05f, 1.45f, 0.18f, 0.22f, 6f, saw = true, vibrato = 1.6f)
            tone(o, 73.4f, 73.4f, 0f, 1.5f, 0.3f, 0.2f, 6f); o
        },
        "roar" to {
            val o = buf(1.2f); val r = Random(7)
            tone(o, 95f, 55f, 0f, 1.1f, 0.6f, 0.08f, 0.6f, saw = true, vibrato = 6f); noise(o, 0f, 1.1f, 0.5f, 0.1f, 0.5f, 0.2f, r); o
        },
        "chime" to {
            val o = buf(1.0f)
            tone(o, 880f, 880f, 0f, 0.9f, 0.25f, 0.005f, 0.35f); tone(o, 1318f, 1318f, 0.08f, 0.8f, 0.18f, 0.005f, 0.3f); o
        },
    )

    fun writeWav(f: File, samples: FloatArray) {
        var peak = 0.001f
        for (s in samples) peak = maxOf(peak, abs(s))
        val gain = 0.9f / peak
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples.size * 2)
        for (s in samples) data.putShort((s * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        FileOutputStream(f).use { it.write(data.array()) }
    }
}

/**
 * Procedural medieval music: a plucked lute arpeggio over a drone and bass, with frame drums
 * that fade in while a wave is attacking. Each level theme has its own mode, key and tempo.
 */
class Music {
    @Volatile private var running = false
    @Volatile private var paused = true
    @Volatile var intensity = 0f
    @Volatile private var volume = 0.6f
    @Volatile private var theme = LevelTheme.MEADOW
    @Volatile private var menu = true
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun setTheme(t: LevelTheme, isMenu: Boolean) {
        theme = t
        menu = isMenu
    }

    fun setVolume(v: Float) {
        volume = v
        track?.setVolume(v)
    }

    fun resume(v: Float) {
        volume = v
        paused = false
        if (!running) start()
        track?.setVolume(v)
        runCatching { track?.play() }
    }

    fun pause() {
        paused = true
        runCatching { track?.pause() }
    }

    fun stop() {
        running = false
        worker?.join(300)
        runCatching { track?.release() }
        track = null
    }

    private fun start() {
        running = true
        val rate = 22050
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(volume)
        t.play()
        worker = thread(name = "music", isDaemon = true, priority = Thread.NORM_PRIORITY + 1) { loop(t, rate) }
    }

    private class Voice(n: Int) {
        val ring = FloatArray(n)
        var len = 0
        var pos = 0
        var amp = 0f
        var decay = 0.996f
        var active = false
    }

    private fun loop(t: AudioTrack, rate: Int) {
        val rng = Random(42)
        val chunk = 1024
        val out = ShortArray(chunk)
        val voices = Array(8) { Voice(rate / 40) }
        var sample = 0L
        var drone = 0.0
        var bass = 0.0
        var drumEnv = 0f
        var drumPh = 0.0
        var snareEnv = 0f
        var lp = 0f
        var smoothIntensity = 0f
        var step = -1L
        var bassFreq = 0.0
        while (running) {
            if (paused) {
                Thread.sleep(60)
                continue
            }
            val th = theme
            val isMenu = menu
            // Mode/key/tempo per theme. Semitone offsets from the root.
            val (root, scale, bpm) = when (th) {
                LevelTheme.MEADOW -> Triple(146.83, intArrayOf(0, 2, 3, 5, 7, 9, 10), 92.0)   // D dorian
                LevelTheme.AUTUMN -> Triple(130.81, intArrayOf(0, 2, 4, 5, 7, 9, 10), 96.0)   // C mixolydian
                LevelTheme.FROST -> Triple(110.0, intArrayOf(0, 2, 3, 5, 7, 8, 10), 78.0)     // A aeolian
                LevelTheme.NIGHT -> Triple(123.47, intArrayOf(0, 2, 3, 5, 7, 8, 10), 72.0)    // B minor
                LevelTheme.MARSH -> Triple(116.54, intArrayOf(0, 1, 3, 5, 7, 8, 10), 84.0)    // Bb phrygian
                LevelTheme.EMBER -> Triple(164.81, intArrayOf(0, 1, 4, 5, 7, 8, 10), 104.0)   // E phrygian dominant
            }
            val tempo = if (isMenu) bpm * 0.85 else bpm
            val samplesPerStep = (rate * 60.0 / tempo / 2).toLong() // eighth notes
            // Chord degrees for a 4-bar loop (i, VII, VI/IV, v).
            val chords = intArrayOf(0, 6, 5, 4)
            fun freq(degree: Int, octave: Int): Double {
                val d = Math.floorMod(degree, 7)
                val o = Math.floorDiv(degree, 7) + octave
                return root * Math.pow(2.0, (scale[d] + 12 * o) / 12.0)
            }
            for (i in 0 until chunk) {
                val s = sample + i
                val st = s / samplesPerStep
                if (st != step) {
                    step = st
                    val bar = ((st / 8) % 4).toInt()
                    val beat = (st % 8).toInt()
                    val chord = chords[bar]
                    // Lute arpeggio: chord tones with a few passing notes.
                    val pattern = intArrayOf(0, 2, 4, 7, 4, 2, if (rng.nextFloat() < 0.3f) 5 else 4, 2)
                    if (!isMenu || beat % 2 == 0 || rng.nextFloat() < 0.4f) {
                        val v = voices.firstOrNull { !it.active } ?: voices[(st % voices.size).toInt()]
                        val f = freq(chord + pattern[beat], if (beat == 3) 1 else 0)
                        v.len = (rate / f).toInt().coerceIn(2, v.ring.size)
                        for (k in 0 until v.len) v.ring[k] = rng.nextFloat() * 2f - 1f
                        v.pos = 0
                        v.amp = if (beat == 0) 0.22f else 0.15f
                        v.decay = 0.9965f
                        v.active = true
                    }
                    if (beat == 0 || beat == 4) drumEnv = 1f
                    if (beat == 2 || beat == 6) snareEnv = 1f
                    if (beat == 7 && rng.nextFloat() < 0.5f) snareEnv = 0.6f
                    bassFreq = freq(chord, -1)
                }
                smoothIntensity += (intensity - smoothIntensity) * 0.00005f
                var v = 0f
                for (vo in voices) {
                    if (!vo.active) continue
                    val a = vo.ring[vo.pos]
                    val b = vo.ring[(vo.pos + 1) % vo.len]
                    vo.ring[vo.pos] = (a + b) * 0.5f * vo.decay
                    v += a * vo.amp
                    vo.pos = (vo.pos + 1) % vo.len
                    vo.amp *= 0.99996f
                    if (vo.amp < 0.002f) vo.active = false
                }
                // Drone on the root and fifth, breathing slowly.
                drone += 2 * PI * (root / 2) / rate
                val breath = 0.6f + 0.4f * sin(2 * PI * s / (rate * 6.0)).toFloat()
                v += (sin(drone) * 0.05 + sin(drone * 1.5) * 0.03).toFloat() * breath
                bass += 2 * PI * bassFreq / rate
                v += (sin(bass) * 0.09).toFloat()
                // Frame drums when the battle heats up.
                if (smoothIntensity > 0.02f) {
                    drumPh += 2 * PI * (60.0 + 40.0 * drumEnv) / rate
                    v += (sin(drumPh) * drumEnv * 0.45 * smoothIntensity).toFloat()
                    lp += ((rng.nextFloat() * 2f - 1f) - lp) * 0.35f
                    v += lp * snareEnv * 0.2f * smoothIntensity
                }
                drumEnv *= 0.9993f
                snareEnv *= 0.9985f
                out[i] = (v * 0.8f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            sample += chunk
            if (t.write(out, 0, chunk) < 0) break
        }
    }
}
