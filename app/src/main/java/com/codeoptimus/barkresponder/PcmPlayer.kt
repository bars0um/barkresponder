package com.codeoptimus.barkresponder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock

/** Plays one response's samples to the end on the calling thread, then frees the player. */
object PcmPlayer {
    private const val CHUNK = 4096

    /** Returns how many samples the speaker played. */
    fun play(samples: ShortArray, sampleRate: Int): Int {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Streamed, not static: a 10 s run is 882 KB, more than some devices give a static track.
        val track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minBytes, CHUNK * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try {
            track.play()
            // One deadline for the whole play: a stalled audio output must not hold this
            // thread, and with it the mic's "own sound" mark, forever.
            val deadline = SystemClock.elapsedRealtime() + samples.size * 1000L / sampleRate + 2000
            var written = 0
            while (written < samples.size && SystemClock.elapsedRealtime() < deadline) {
                val n = track.write(samples, written, minOf(CHUNK, samples.size - written), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                if (n == 0) Thread.sleep(10) else written += n
            }
            // The last chunk is queued; wait until the speaker has played it.
            while (track.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(10)
            }
            return track.playbackHeadPosition.coerceAtMost(written)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
