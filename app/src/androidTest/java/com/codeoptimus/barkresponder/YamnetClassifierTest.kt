package com.codeoptimus.barkresponder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class YamnetClassifierTest {
    private lateinit var classifier: YamnetClassifier

    @Before
    fun load() {
        classifier = YamnetClassifier(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    }

    @After
    fun release() = classifier.close()

    // Prints a measurement in logcat and in `am instrument -r` output, where
    // bin/device-test shows it; some devices drop app info lines from logcat.
    private fun report(line: String) {
        android.util.Log.i("YamnetClassifierTest", line)
        val status = android.os.Bundle().apply { putString("measurement", line) }
        InstrumentationRegistry.getInstrumentation().sendStatus(MEASUREMENT, status)
    }

    // Reads the 16-bit mono PCM of a WAV from the test APK's assets.
    private fun wav(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val pcm = ShortArray(size / 2)
                buf.position(pos + 8)
                buf.asShortBuffer().get(pcm)
                return pcm
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    @Test
    fun scoresACc0BarkAsABark() {
        val pcm = wav("bark_freesound_163459.wav")
        assertEquals(42_998, pcm.size)

        val scores = classifier.classify(Resampler.toModelInput(pcm))

        assertEquals(521, scores.size)
        report("bark score of the CC0 bark: %.3f".format(scores[YamnetClassifier.BARK]))
        assertTrue("bark score ${scores[YamnetClassifier.BARK]}", scores[YamnetClassifier.BARK] >= 0.4f)
    }

    @Test
    fun scoresSilenceAsNoBark() {
        val scores = classifier.classify(Resampler.toModelInput(ShortArray(44_100)))

        assertTrue("bark score ${scores[YamnetClassifier.BARK]}", scores[YamnetClassifier.BARK] < 0.1f)
    }

    @Test
    fun reportsInferenceTimePerWindow() {
        val input = Resampler.toModelInput(wav("bark_freesound_163459.wav"))
        classifier.classify(input)
        val runs = 20
        val t0 = System.nanoTime()
        repeat(runs) { classifier.classify(input) }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        report("inference ms per window: %.1f".format(ms))
        assertTrue("inference took $ms ms", ms < 500)
    }

    private companion object {
        // A status code AndroidJUnitRunner does not use (it sends 1, 0, -1, -2, -3, -4).
        const val MEASUREMENT = 2
    }
}
