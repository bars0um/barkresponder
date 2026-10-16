package com.codeoptimus.barkresponder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** The clip path with the real classifier and the phone's own storage. */
@RunWith(AndroidJUnit4::class)
class ClipCaptureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var classifier: YamnetClassifier
    private lateinit var dir: File

    @Before
    fun setUp() {
        classifier = YamnetClassifier(instrumentation.targetContext.assets)
        dir = File(instrumentation.targetContext.cacheDir, "clip-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        classifier.close()
        dir.deleteRecursively()
    }

    private fun report(line: String) {
        android.util.Log.i("ClipCaptureDeviceTest", line)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("measurement", line) })
    }

    private fun barkPcm(): ShortArray {
        val bytes = instrumentation.context.assets.open("bark_freesound_163459.wav").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = buf.getInt(pos + 4)
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                return ShortArray(size / 2).also { buf.position(pos + 8); buf.asShortBuffer().get(it) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    @Test
    fun savesABarkWithTheClassifiersScores() {
        val ring = AudioRing()
        val store = SampleStore(dir)
        val direct = Executor { it.run() }
        val score = { w: ShortArray -> classifier.classify(Resampler.toModelInput(w)) }
        val clips = ClipCapture(ring, { true }, direct, score, store, direct)
        val check = BarkCheck(ring, { 0.4f }, direct, score, { true }, clips) { _, _ -> }
        fun append(pcm: ShortArray) {
            var at = 0
            while (at < pcm.size) {
                val n = minOf(1_792, pcm.size - at)
                ring.append(pcm.copyOfRange(at, at + n), n, false)
                check.afterAppend()
                clips.afterAppend()
                at += n
            }
        }

        // The bark sits exactly in the window the check scores: 0.25 s before the onset onward.
        append(ShortArray(44_100))
        check.onset(44_100 + BarkGate.PRE_ROLL)
        append(barkPcm())
        append(ShortArray(44_100))

        val saved = store.list().single()
        val expected = classifier.classify(Resampler.toModelInput(barkPcm()))
        report("saved clip bark score: %.3f".format(saved.barkScore))
        assertEquals(521, store.scores(saved.id)!!.size)
        assertEquals(expected[YamnetClassifier.BARK], saved.barkScore, 1e-6f)
        assertTrue(saved.responded)
        assertEquals(44L + 64_000L, store.wavFile(saved.id).length())
    }

    @Test
    fun reportsHowLongOpeningAThousandClipsTakes() {
        val scores = FloatArray(521) { it / 521f }
        SampleStore(dir).apply {
            for (i in 0 until 1_000) save(FloatArray(16), ClipMeta(1_000L + i, scores, false, 0.4f))
        }

        val t0 = System.nanoTime()
        val listed = SampleStore(dir).list()
        val ms = (System.nanoTime() - t0) / 1e6

        report("opening 1000 clips took %.0f ms".format(ms))
        assertEquals(1_000, listed.size)
        assertTrue("opening took $ms ms", ms < 5_000)
    }
}
