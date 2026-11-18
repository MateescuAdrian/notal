package com.notal.atelier.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.notal.atelier.engine.CelloFingeringEngine
import com.notal.atelier.model.Instrument
import com.notal.atelier.model.NoteEvent
import com.notal.atelier.model.SheetScore
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class CelloAudioSynthesizer {

    private val sampleRate = 44100
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isPlaying = false

    @Volatile
    private var activeThread: Thread? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun playNotePreview(note: NoteEvent, instrument: Instrument = Instrument.CELLO) {
        stop()
        val midi = CelloFingeringEngine.computeMidi(note.step, note.octave, note.accidental)
        val freq = CelloFingeringEngine.midiToFrequencyHz(midi)
        val thread = Thread {
            val track = createTrack(sampleRate / 2)
            activeTrack = track
            try {
                track.play()
                val pcm = synthesizeBowedNote(freq, durationSec = 0.68, instrument = instrument)
                track.write(pcm, 0, pcm.size)
            } catch (_: Throwable) {
            } finally {
                safeReleaseTrack(track)
                if (activeTrack == track) activeTrack = null
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    fun playScore(
        score: SheetScore,
        tempoBpm: Int,
        instrument: Instrument,
        loop: Boolean,
        onNoteActive: (Int, NoteEvent?) -> Unit,
        onPlaybackComplete: () -> Unit
    ) {
        stop()
        if (score.notes.isEmpty()) {
            onPlaybackComplete()
            return
        }

        isPlaying = true
        val thread = Thread {
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = createTrack(minBufBytes)
            activeTrack = track

            try {
                track.play()
                do {
                    for ((index, note) in score.notes.withIndex()) {
                        if (!isPlaying) break

                        val midi = CelloFingeringEngine.computeMidi(note.step, note.octave, note.accidental)
                        val freq = CelloFingeringEngine.midiToFrequencyHz(midi)
                        val beatSec = 60.0 / tempoBpm.coerceIn(36, 200)
                        val durationSec = (note.durationBeats * beatSec).coerceIn(0.18, 3.0)

                        mainHandler.post {
                            if (isPlaying) onNoteActive(index, note)
                        }

                        val pcm = synthesizeBowedNote(freq, durationSec, instrument)
                        var offset = 0
                        val chunkSize = 2048
                        while (offset < pcm.size && isPlaying) {
                            val count = min(chunkSize, pcm.size - offset)
                            val written = track.write(pcm, offset, count)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                } while (loop && isPlaying)
            } catch (_: Throwable) {
            } finally {
                isPlaying = false
                safeReleaseTrack(track)
                if (activeTrack == track) activeTrack = null
                mainHandler.post {
                    onNoteActive(-1, null)
                    onPlaybackComplete()
                }
            }
        }
        activeThread = thread
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        isPlaying = false
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            try {
                track.pause()
                track.flush()
            } catch (_: Throwable) {
            }
        }
        activeThread?.interrupt()
        activeThread = null
    }

    private fun createTrack(bufferBytes: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private fun safeReleaseTrack(track: AudioTrack) {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.release()
        } catch (_: Throwable) {
        }
    }

    private fun synthesizeBowedNote(
        freqHz: Double,
        durationSec: Double,
        instrument: Instrument
    ): ShortArray {
        val totalSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1024)
        val out = ShortArray(totalSamples)

        val attackSamples = (sampleRate * 0.048).toInt().coerceAtMost(totalSamples / 3)
        val releaseSamples = (sampleRate * 0.065).toInt().coerceAtMost(totalSamples / 3)
        val vibratoDelaySamples = (sampleRate * 0.11).toInt()
        val rosinBurstSamples = (sampleRate * 0.025).toInt()

        // Harmonic weights tuned for bowed string warmth (strong fundamental + rich 2nd/3rd harmonics)
        val harmonicWeights = when (instrument) {
            Instrument.CELLO -> doubleArrayOf(1.0, 0.72, 0.48, 0.28, 0.19, 0.12, 0.07, 0.04)
            Instrument.DOUBLE_BASS -> doubleArrayOf(1.0, 0.80, 0.52, 0.30, 0.16, 0.08, 0.04, 0.02)
            Instrument.VIOLA -> doubleArrayOf(0.95, 0.68, 0.50, 0.34, 0.21, 0.12, 0.06, 0.03)
            Instrument.VIOLIN -> doubleArrayOf(0.85, 0.62, 0.45, 0.35, 0.25, 0.16, 0.09, 0.05)
        }
        val normFactor = harmonicWeights.sum()

        var phase = 0.0
        val rand = Random(freqHz.toInt())

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // Bow envelope
            val env = when {
                i < attackSamples -> (i.toDouble() / attackSamples).let { it * it }
                i > totalSamples - releaseSamples -> {
                    val rem = (totalSamples - i).toDouble() / releaseSamples.coerceAtLeast(1)
                    rem.coerceIn(0.0, 1.0)
                }
                else -> 1.0
            }

            // Expressive string vibrato after bow settles
            val vibratoDepth = if (i > vibratoDelaySamples) {
                val ramp = ((i - vibratoDelaySamples).toDouble() / (sampleRate * 0.15)).coerceIn(0.0, 1.0)
                0.0042 * ramp * sin(2.0 * PI * 5.4 * t)
            } else {
                0.0
            }

            val instFreq = freqHz * (1.0 + vibratoDepth)
            phase += 2.0 * PI * instFreq / sampleRate

            var sample = 0.0
            for (h in harmonicWeights.indices) {
                val harmonicNum = h + 1
                if (instFreq * harmonicNum < sampleRate * 0.45) {
                    sample += harmonicWeights[h] * sin(phase * harmonicNum)
                }
            }
            sample /= normFactor

            // Subtle bow-hair rosin attack transient
            if (i < rosinBurstSamples) {
                val noise = (rand.nextDouble() * 2.0 - 1.0) * 0.08 * (1.0 - i.toDouble() / rosinBurstSamples)
                sample += noise
            }

            val pcmVal = (sample * env * 22000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = pcmVal.toShort()
        }

        return out
    }
}
