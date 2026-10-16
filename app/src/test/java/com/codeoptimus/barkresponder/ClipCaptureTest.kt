package com.codeoptimus.barkresponder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

class ClipCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ring = AudioRing()
    private val dir: File by lazy { tmp.newFolder("samples") }
    private val store by lazy { SampleStore(dir) }
    private var saveClips = true
    private var minScore = 0.4f
    private var bark = 0.9f
    private var scoreFn: (ShortArray) -> FloatArray = { scores(bark) }
    private val scoredLengths = mutableListOf<Int>()

    private fun scores(bark: Float) = FloatArray(521) { if (it == YamnetClassifier.BARK) bark else 0.001f }

    private val direct = Executor { it.run() }
    private var writer = direct

    private fun capture(executor: Executor = direct) =
        ClipCapture(ring, { saveClips }, executor, { scoredLengths += it.size; scoreFn(it) }, store, writer, clock = { 1_760_000_000_000 })

    private fun check(clips: ClipCapture, executor: Executor = direct) =
        BarkCheck(ring, { minScore }, executor, { scoredLengths += it.size; scoreFn(it) }, { true }, clips) { _, _ -> }

    // Appends `count` samples of 1,000, then lets both stages look for due work.
    private fun feed(c: BarkCheck, clips: ClipCapture, count: Int, ownSound: Boolean = false) {
        var left = count
        while (left > 0) {
            val n = minOf(1_792, left)
            ring.append(ShortArray(n) { 1_000 }, n, ownSound)
            c.afterAppend()
            clips.afterAppend()
            left -= n
        }
    }

    private fun wavSamples(id: Long): ShortArray {
        val b = ByteBuffer.wrap(store.wavFile(id).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((b.limit() - 44) / 2) { b.getShort(44 + 2 * it) }
    }

    @Test
    fun `with saving off a candidate writes no file`() {
        saveClips = false
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `with saving on a candidate writes one wav and one sidecar with the scores the check used`() {
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        val saved = store.list().single()
        assertEquals(setOf("${saved.id}.wav", "${saved.id}.json"), dir.list()!!.toSet())
        assertEquals(0.9f, saved.barkScore)
        assertArrayEquals(scores(0.9f), store.scores(saved.id), 0f)
        assertTrue(saved.responded)
        assertEquals(0.4f, saved.minScoreSetting)
        assertEquals(1_760_000_000_000, saved.recordedAt)
        assertEquals("the classifier ran once", listOf(BarkGate.WINDOW), scoredLengths)
        assertEquals(32_000, wavSamples(saved.id).size)
    }

    @Test
    fun `saves nothing when saving is turned off between the onset and the cut`() {
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 40_000)
        saveClips = false
        feed(c, clips, 40_000)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `saves an ignored candidate as not responded`() {
        bark = 0.2f
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        val saved = store.list().single()
        assertEquals(false, saved.responded)
        assertEquals(0.2f, saved.barkScore)
    }

    @Test
    fun `cuts the clip once 1_5 s after the onset is recorded`() {
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 66_150 - 1)
        assertEquals(emptyList<SavedClip>(), store.list())

        feed(c, clips, 1)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `silences our own response inside the clip`() {
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        // The window is scored 0.725 s after the onset; the response plays from 0.8 s to 1.2 s.
        feed(c, clips, 35_280)
        feed(c, clips, 17_640, ownSound = true)
        feed(c, clips, 30_000)

        val wav = wavSamples(store.list().single().id)
        // 16 kHz: the onset sits at 0.5 s (8,000); the response from 1.3 s to 1.7 s.
        assertEquals(1_000.0, wav[4_000].toDouble(), 5.0)
        assertEquals(0, wav[24_000].toInt())
        assertEquals(1_000.0, wav[30_000].toDouble(), 5.0)
    }

    @Test
    fun `saves nothing when the scored window overlaps our own sound`() {
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 5_000, ownSound = true)
        feed(c, clips, 80_000)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `saves nothing when the classifier fails`() {
        scoreFn = { throw UnsatisfiedLinkError("no liblitert_jni.so for this ABI") }
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `saves nothing for a candidate the check failed to score, even when a later score works`() {
        var calls = 0
        scoreFn = { if (calls++ == 0) throw IllegalStateException("model busy") else scores(0.9f) }
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `with the check off still scores the window and saves the clip as responded`() {
        minScore = 0f
        val clips = capture()
        val c = check(clips)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)

        val saved = store.list().single()
        assertTrue(saved.responded)
        assertEquals(0.9f, saved.barkScore)
        assertEquals(0f, saved.minScoreSetting)
        assertEquals(listOf(BarkGate.WINDOW), scoredLengths)
    }

    @Test
    fun `saves through a queued worker in the order the scores land`() {
        val queued = ArrayDeque<Runnable>()
        val worker = Executor { queued.addLast(it) }
        writer = worker
        val clips = capture(worker)
        val c = check(clips, worker)
        feed(c, clips, 30_000)
        c.onset(30_000)
        feed(c, clips, 80_000)
        assertEquals(emptyList<String>(), dir.list()!!.toList())

        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(0.9f, store.list().single().barkScore)
    }
}
