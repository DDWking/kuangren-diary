package com.ddw.kuangren.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random

class WhiteNoisePlayer {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start(kind: String) {
        stop()
        running = true
        thread = Thread {
            playLoop(kind)
        }.also { it.name = "kuangren-noise"; it.start() }
    }

    private fun playLoop(kind: String) {
        val sampleRate = 22050
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val bufferSize = minBuffer * 2
        val audioTrack = AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        try {
            audioTrack.play()
            val samples = ShortArray(bufferSize)
            val random = Random()
            var low = 0.0
            var mid = 0.0
            while (running) {
                for (i in samples.indices) {
                    val white = random.nextDouble() * 2.0 - 1.0
                    val value = when (kind) {
                        "rain" -> {
                            low = low * 0.92 + white * 0.08
                            (white - low) * 0.28
                        }
                        "cafe" -> {
                            low = low * 0.995 + white * 0.005
                            mid = mid * 0.7 + low * 0.3
                            mid * 2.6 + 0.02
                        }
                        else -> {
                            low = low * 0.99 + white * 0.01
                            low * 2.2
                        }
                    }
                    val clipped = value.coerceIn(-0.95, 0.95)
                    samples[i] = (clipped * 22000).toInt().toShort()
                }
                audioTrack.write(samples, 0, samples.size)
            }
        } catch (_: Exception) {
        } finally {
            try {
                audioTrack.stop()
            } catch (_: Exception) {
            }
            try {
                audioTrack.release()
            } catch (_: Exception) {
            }
            if (track === audioTrack) track = null
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(400)
        } catch (_: Exception) {
        }
        thread = null
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}
