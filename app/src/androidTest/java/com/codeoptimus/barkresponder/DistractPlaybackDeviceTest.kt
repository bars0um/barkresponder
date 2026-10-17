package com.codeoptimus.barkresponder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Plays a 10 s Distract run through the app's player while the tablet's own mic
 * records, and checks every second of the run reached the mic. Run it with the
 * screen off to show the whole run plays then too.
 */
@RunWith(AndroidJUnit4::class)
class DistractPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rate = 44100

    private fun report(line: String) {
        android.util.Log.i("DistractPlaybackDeviceTest", line)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("measurement", line) })
    }

    @Test
    fun playsEverySecondOfATenSecondRun() {
        val run = DistractSound.render(1L, 10, 2000, 6000, 0.7, rate)
        val leadIn = rate / 2
        val heard = ShortArray(leadIn + run.size + rate)
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION: the plain MIC source runs noise suppression that smears and drops tones.
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        assertEquals("the mic did not open: grant the app microphone access",
            AudioRecord.STATE_INITIALIZED, rec.state)
        val recording = AtomicBoolean(true)
        val got = AtomicInteger(0)
        rec.startRecording()
        assertEquals("the mic did not start recording", AudioRecord.RECORDSTATE_RECORDING, rec.recordingState)
        val reader = thread {
            while (recording.get() && got.get() < heard.size) {
                val n = rec.read(heard, got.get(), minOf(4096, heard.size - got.get()))
                if (n < 0) break   // a read error: stop, and the checks below fail on the count
                got.addAndGet(n)
            }
        }
        try {
            val waitUntil = SystemClock.elapsedRealtime() + 3_000
            while (got.get() < leadIn && reader.isAlive && SystemClock.elapsedRealtime() < waitUntil) SystemClock.sleep(10)
            assertTrue("the mic delivered ${got.get()} samples before the run", got.get() >= leadIn)
            val t0 = SystemClock.elapsedRealtime()
            val played = PcmPlayer.play(run, rate)
            val ms = SystemClock.elapsedRealtime() - t0
            reader.join(3_000)
            assertTrue("the mic was still recording 3 s after the run", !reader.isAlive)
            assertEquals("the mic recorded ${got.get()} of ${heard.size} samples", heard.size, got.get())
            // -e keepRecording true keeps the raw recording for a spectrogram; the caller deletes it.
            if (InstrumentationRegistry.getArguments().getString("keepRecording") == "true") {
                val f = File(instrumentation.targetContext.cacheDir, "distract-heard.pcm")
                val b = ByteBuffer.allocate(heard.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                b.asShortBuffer().put(heard)
                f.writeBytes(b.array())
                report("kept the recording at ${f.path}")
            }
            report("played $played of ${run.size} samples in $ms ms")
            assertEquals(run.size, played)
            assertTrue("the run took $ms ms", ms >= 9_900)

            // Each second of the run against the half second before it, above 1 kHz, in
            // 50 ms frames. The room is its median frame, so a click in the lead-in (the
            // screen going off) doesn't raise it; a second counts by its 75th-percentile
            // frame, so the speaker's ~0.35 s delay and the gaps between sounds don't sink it.
            val room = frameLevels(heard, 0, leadIn).sorted().let { it[it.size / 2] }
            val louder = (0 until 10).map { s ->
                val frames = frameLevels(heard, leadIn + s * rate, leadIn + (s + 1) * rate).sorted()
                20 * log10(frames[frames.size * 3 / 4] / room)
            }
            report("second by second, dB above the room: " + louder.joinToString(" ") { "%.1f".format(it) })
            for ((s, db) in louder.withIndex()) assertTrue("second ${s + 1} was $db dB above the room", db > 10)
        } finally {
            recording.set(false)
            rec.stop()   // unblocks a read in progress
            reader.join(3_000)
            rec.release()
        }
    }

    // RMS above about 1 kHz (a one-pole high-pass) of each 50 ms frame.
    private fun frameLevels(s: ShortArray, from: Int, to: Int): List<Double> {
        val a = 1.0 / (1.0 + 2 * PI * 1000.0 / rate)
        val frame = rate / 20
        var px = s[from].toDouble(); var py = 0.0
        val out = mutableListOf<Double>()
        var sum = 0.0
        for (i in from until to) {
            val x = s[i].toDouble()
            py = a * (py + x - px); px = x
            sum += py * py
            if ((i - from + 1) % frame == 0) { out += sqrt(sum / frame).coerceAtLeast(1e-3); sum = 0.0 }
        }
        return out
    }
}
