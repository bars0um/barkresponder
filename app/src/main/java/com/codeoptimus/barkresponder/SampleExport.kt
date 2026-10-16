package com.codeoptimus.barkresponder

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The training set: a zip of every labelled clip as `clips/<id>.wav` and
 * `clips/<id>.json`, plus `labels.csv` with one row per clip, newest first:
 * `file,label,recorded_at,bark_score,responded`. `file` is the WAV's path in
 * the zip, `label` is `bark` or `not_bark`, `recorded_at` is UTC ISO 8601
 * with milliseconds, `bark_score` has four decimals, `responded` is `true` or
 * `false`. Unlabelled clips stay out.
 */
object SampleExport {
    const val LABELS = "labels.csv"
    const val HEADER = "file,label,recorded_at,bark_score,responded"

    /** Writes the zip and returns how many clips it holds. Leaves [out] open. */
    fun write(store: SampleStore, out: OutputStream): Int {
        val labelled = store.list().filter { it.label != null }
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val zip = ZipOutputStream(out)
        val csv = StringBuilder(HEADER).append('\n')
        var count = 0
        for (c in labelled) {
            val wav = store.wavFile(c.id)
            val json = store.sidecarFile(c.id)
            if (!wav.isFile || !json.isFile) continue // deleted while we were writing
            zip.putNextEntry(ZipEntry("clips/${c.id}.wav"))
            wav.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("clips/${c.id}.json"))
            json.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            csv.append("clips/${c.id}.wav,").append(c.label!!.json).append(',')
                .append(time.format(c.recordedAt)).append(',')
                .append(String.format(Locale.US, "%.4f", c.barkScore)).append(',')
                .append(c.responded).append('\n')
            count++
        }
        zip.putNextEntry(ZipEntry(LABELS))
        zip.write(csv.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        return count
    }
}
