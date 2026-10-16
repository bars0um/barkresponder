package com.codeoptimus.barkresponder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class SampleExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun meta(at: Long, bark: Float, responded: Boolean) = ClipMeta(
        recordedAt = at,
        scores = FloatArray(521) { if (it == YamnetClassifier.BARK) bark else 0f },
        responded = responded,
        minScoreSetting = 0.4f,
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test
    fun `zips each labelled clip, its sidecar and a labels row, and leaves out unlabelled clips`() {
        val store = SampleStore(tmp.newFolder("samples"))
        val bark = store.save(FloatArray(320) { 0.2f }, meta(1_760_623_402_123, 0.82f, responded = true))!!
        val notBark = store.save(FloatArray(320) { -0.2f }, meta(1_760_623_500_000, 0.41f, responded = true))!!
        val unlabelled = store.save(FloatArray(320), meta(1_760_623_600_000, 0.1f, responded = false))!!
        store.setLabel(bark.id, ClipLabel.BARK)
        store.setLabel(notBark.id, ClipLabel.NOT_BARK)

        val out = ByteArrayOutputStream()
        val count = SampleExport.write(store, out)
        val zip = unzip(out.toByteArray())

        assertEquals(2, count)
        assertEquals(
            setOf(
                "labels.csv",
                "clips/${bark.id}.wav", "clips/${bark.id}.json",
                "clips/${notBark.id}.wav", "clips/${notBark.id}.json",
            ),
            zip.keys,
        )
        assertArrayEquals(store.wavFile(bark.id).readBytes(), zip["clips/${bark.id}.wav"])
        assertArrayEquals(store.sidecarFile(notBark.id).readBytes(), zip["clips/${notBark.id}.json"])
        assertEquals(
            listOf(
                "file,label,recorded_at,bark_score,responded",
                "clips/${notBark.id}.wav,not_bark,2025-10-16T14:05:00.000Z,0.4100,true",
                "clips/${bark.id}.wav,bark,2025-10-16T14:03:22.123Z,0.8200,true",
            ),
            String(zip.getValue("labels.csv"), Charsets.UTF_8).trimEnd('\n').split("\n"),
        )
        assertEquals(false, zip.keys.any { it.contains(unlabelled.id.toString()) })
    }

    @Test
    fun `with nothing labelled writes only the labels header`() {
        val store = SampleStore(tmp.newFolder("samples"))
        store.save(FloatArray(320), meta(1_000, 0.1f, responded = false))

        val out = ByteArrayOutputStream()
        assertEquals(0, SampleExport.write(store, out))
        assertEquals(setOf("labels.csv"), unzip(out.toByteArray()).keys)
    }
}
