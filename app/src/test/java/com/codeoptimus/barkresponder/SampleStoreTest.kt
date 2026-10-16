package com.codeoptimus.barkresponder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SampleStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scores(bark: Float) = FloatArray(521) { if (it == YamnetClassifier.BARK) bark else it / 1_000f }

    private fun meta(at: Long, bark: Float = 0.5f, responded: Boolean = false) =
        ClipMeta(recordedAt = at, scores = scores(bark), responded = responded, minScoreSetting = 0.4f)

    private val clip = FloatArray(320) { 0.1f }

    @Test
    fun `lists saved clips newest first with their scores and keeps a label across a reopen`() {
        val dir = tmp.newFolder("samples")
        val store = SampleStore(dir)
        store.save(clip, meta(1_000, bark = 0.2f))
        val newer = store.save(clip, meta(2_000, bark = 0.82f, responded = true))!!

        val listed = store.list()
        assertEquals(listOf(2_000L, 1_000L), listed.map { it.recordedAt })
        assertEquals(0.82f, listed[0].barkScore)
        assertTrue(listed[0].responded)
        assertEquals(0.4f, listed[0].minScoreSetting)
        assertArrayEquals(scores(0.82f), store.scores(listed[0].id), 0f)
        assertNull(listed[0].label)

        store.setLabel(newer.id, ClipLabel.NOT_BARK)

        val reopened = SampleStore(dir).list()
        assertEquals(ClipLabel.NOT_BARK, reopened.first { it.id == newer.id }.label)
        assertNull(reopened.first { it.id != newer.id }.label)
        assertArrayEquals(scores(0.82f), SampleStore(dir).scores(newer.id), 0f)
    }

    @Test
    fun `writes each clip as a wav and a json sidecar named by its id`() {
        val dir = tmp.newFolder("samples")
        val saved = SampleStore(dir).save(clip, meta(1_760_000_000_123))!!

        assertEquals(setOf("1760000000123.wav", "1760000000123.json"), dir.list()!!.toSet())
        assertEquals(44 + 2 * clip.size, File(dir, "${saved.id}.wav").length().toInt())
    }

    @Test
    fun `clears a label`() {
        val store = SampleStore(tmp.newFolder("samples"))
        val saved = store.save(clip, meta(1_000))!!
        store.setLabel(saved.id, ClipLabel.BARK)
        store.setLabel(saved.id, null)

        assertNull(store.list().single().label)
    }

    @Test
    fun `two clips recorded in the same millisecond both stay`() {
        val store = SampleStore(tmp.newFolder("samples"))
        store.save(clip, meta(1_000))
        store.save(clip, meta(1_000))

        assertEquals(2, store.list().size)
    }

    @Test
    fun `past 1000 unlabelled clips deletes the oldest unlabelled one and keeps labelled ones`() {
        val dir = tmp.newFolder("samples")
        val store = SampleStore(dir)
        val labelled = store.save(clip, meta(1))!!
        store.setLabel(labelled.id, ClipLabel.BARK)

        for (i in 0 until 1_001) store.save(clip, meta(1_000L + i))

        val ids = store.list().map { it.recordedAt }.toSet()
        assertEquals(1_001, ids.size)
        assertFalse("oldest unlabelled clip is gone", 1_000L in ids)
        assertTrue("labelled clip stays", 1L in ids)
        assertTrue("newest unlabelled clip stays", 2_000L in ids)
        assertFalse(File(dir, "1000.wav").exists())
        assertFalse(File(dir, "1000.json").exists())
    }

    @Test
    fun `deletes one clip, then all of them`() {
        val dir = tmp.newFolder("samples")
        val store = SampleStore(dir)
        val first = store.save(clip, meta(1_000))!!
        store.save(clip, meta(2_000))
        store.setLabel(first.id, ClipLabel.BARK)

        store.delete(first.id)
        assertEquals(listOf(2_000L), store.list().map { it.recordedAt })
        assertFalse(File(dir, "${first.id}.wav").exists())

        store.deleteAll()
        assertEquals(emptyList<SavedClip>(), store.list())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(emptyList<SavedClip>(), SampleStore(dir).list())
    }

    @Test
    fun `skips and removes a leftover tmp file`() {
        val dir = tmp.newFolder("samples")
        SampleStore(dir).save(clip, meta(1_000))
        File(dir, "2000.json.tmp").writeText("{\"recordedAt\":2000")
        File(dir, "2000.wav.tmp").writeBytes(ByteArray(10))

        val listed = SampleStore(dir).list()

        assertEquals(listOf(1_000L), listed.map { it.recordedAt })
        assertEquals(setOf("1000.wav", "1000.json"), dir.list()!!.toSet())
    }

    @Test
    fun `skips and removes a sidecar whose wav is missing`() {
        val dir = tmp.newFolder("samples")
        val saved = SampleStore(dir).save(clip, meta(1_000))!!
        File(dir, "${saved.id}.wav").delete()

        assertEquals(emptyList<SavedClip>(), SampleStore(dir).list())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `removes a clip whose sidecar it cannot read, and keeps a readable one`() {
        val dir = tmp.newFolder("samples")
        val store = SampleStore(dir)
        store.save(clip, meta(1_000))
        val broken = store.save(clip, meta(2_000))!!
        val huge = store.save(clip, meta(3_000))!!
        File(dir, "${broken.id}.json").writeText("{\"recordedAt\":2000,")
        // A valid sidecar padded past 64 KB.
        val hugeFile = File(dir, "${huge.id}.json")
        hugeFile.writeText(hugeFile.readText().trimEnd().dropLast(1) + " ".repeat(70_000) + "}")

        assertEquals(listOf(1_000L), SampleStore(dir).list().map { it.recordedAt })
        assertEquals(setOf("1000.wav", "1000.json"), dir.list()!!.toSet())
    }

    @Test
    fun `refuses a clip recorded before Delete all and takes a later one`() {
        val store = SampleStore(tmp.newFolder("samples"), clock = { 5_000 })
        store.save(clip, meta(1_000))
        store.deleteAll()

        assertNull(store.save(clip, meta(4_000)))
        assertEquals(6_000L, store.save(clip, meta(6_000))!!.recordedAt)
        assertEquals(listOf(6_000L), store.list().map { it.recordedAt })
    }
}
