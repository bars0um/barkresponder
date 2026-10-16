package com.codeoptimus.barkresponder

import java.io.File
import java.io.IOException
import java.util.TreeMap

enum class ClipLabel(val json: String) {
    BARK("bark"),
    NOT_BARK("not_bark");

    companion object {
        fun of(json: String?): ClipLabel? = entries.firstOrNull { it.json == json }
    }
}

/** What the bark check knew about a candidate sound when it saved the clip. */
class ClipMeta(
    val recordedAt: Long,
    val scores: FloatArray,
    val responded: Boolean,
    val minScoreSetting: Float,
)

class SavedClip(
    /** The file name stem: the recording time in milliseconds, moved on by 1 ms when taken. */
    val id: Long,
    val recordedAt: Long,
    val barkScore: Float,
    val responded: Boolean,
    val minScoreSetting: Float,
    val label: ClipLabel?,
) {
    fun withLabel(l: ClipLabel?) = SavedClip(id, recordedAt, barkScore, responded, minScoreSetting, l)
}

/**
 * The saved clips in one folder: `<id>.wav` (16 kHz mono) and a `<id>.json`
 * sidecar with the scores and the label. Each file is written to `.tmp` and
 * renamed, so a crash leaves either the old file or the new one. A clip counts
 * once its sidecar exists. The list holds no scores: reading 521 numbers
 * for each of 1,000 clips took 16 s on our Android 7 test tablet; [scores] reads one
 * clip's. Past [maxUnlabelled] unlabelled clips, the oldest
 * unlabelled one goes; labelled clips stay until deleted. A sidecar it cannot
 * read goes with its WAV, so no audio sits on disk out of sight. Thread-safe;
 * keep one store per folder in the process, since the lock is per store.
 */
class SampleStore(
    val dir: File,
    private val maxUnlabelled: Int = MAX_UNLABELLED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var index: TreeMap<Long, SavedClip>? = null
    // Clips heard before the last Delete all can still be in the save queue.
    private var clearedAt = Long.MIN_VALUE

    fun wavFile(id: Long) = File(dir, "$id.wav")
    fun sidecarFile(id: Long) = File(dir, "$id.json")

    /** Newest first. */
    @Synchronized
    fun list(): List<SavedClip> = clips().descendingMap().values.toList()

    /** Null when the clip was recorded before the last [deleteAll]. */
    @Synchronized
    fun save(samples: FloatArray, meta: ClipMeta): SavedClip? {
        if (meta.recordedAt <= clearedAt) return null
        val clips = clips()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the samples folder")
        var id = meta.recordedAt
        while (clips.containsKey(id) || sidecarFile(id).exists()) id++
        val clip = SavedClip(
            id, meta.recordedAt, meta.scores.getOrElse(YamnetClassifier.BARK) { 0f },
            meta.responded, meta.minScoreSetting, null,
        )
        writeAtomically(wavFile(id)) { WavWriter.write(it, samples) }
        try {
            writeSidecar(clip, meta.scores)
        } catch (e: IOException) {
            wavFile(id).delete()
            throw e
        }
        clips[id] = clip
        trim(clips)
        return clip
    }

    /** Sets or, with null, clears the label. False when the clip is gone. */
    @Synchronized
    fun setLabel(id: Long, label: ClipLabel?): Boolean {
        val clips = clips()
        val clip = clips[id] ?: return false
        val labelled = clip.withLabel(label)
        val scores = scores(id) ?: throw IOException("cannot read the sidecar of $id")
        writeSidecar(labelled, scores)
        clips[id] = labelled
        return true
    }

    /** All of YAMNet's class scores for one clip, or null when it is gone or unreadable. */
    @Synchronized
    fun scores(id: Long): FloatArray? = try {
        val m = MiniJson.parse(sidecarFile(id).readText()) as Map<*, *>
        (m["scores"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun delete(id: Long) {
        clips().remove(id)
        removeFiles(id)
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        index = TreeMap()
        clearedAt = clock()
    }

    private fun trim(clips: TreeMap<Long, SavedClip>) {
        var unlabelled = clips.values.count { it.label == null }
        if (unlabelled <= maxUnlabelled) return
        for (old in clips.values.filter { it.label == null }) {
            if (unlabelled <= maxUnlabelled) break
            clips.remove(old.id)
            removeFiles(old.id)
            unlabelled--
        }
    }

    private fun removeFiles(id: Long) {
        sidecarFile(id).delete()
        wavFile(id).delete()
    }

    private fun clips(): TreeMap<Long, SavedClip> = index ?: load().also { index = it }

    // Drops what a crash can leave behind (a `.tmp` file, a wav or a sidecar
    // without its partner) and any clip whose sidecar it cannot read.
    private fun load(): TreeMap<Long, SavedClip> {
        val clips = TreeMap<Long, SavedClip>()
        val files = dir.listFiles() ?: return clips
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        for (f in files) {
            val id = SIDECAR.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val clip = if (wavFile(id).isFile) readSidecar(id, f) else null
            if (clip != null) clips[id] = clip else removeFiles(id)
        }
        for (f in files) {
            val id = WAV.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (!sidecarFile(id).exists()) f.delete()
        }
        return clips
    }

    private fun readSidecar(id: Long, f: File): SavedClip? = try {
        require(f.length() <= MAX_SIDECAR_BYTES) { "sidecar too large" }
        val m = MiniJson.parse(f.readText(), skip = setOf("scores")) as Map<*, *>
        SavedClip(
            id = id,
            recordedAt = (m["recordedAt"] as Number).toLong(),
            barkScore = (m["barkScore"] as Number).toFloat(),
            responded = m["responded"] as Boolean,
            minScoreSetting = (m["minScoreSetting"] as Number).toFloat(),
            label = ClipLabel.of(m["label"] as String?),
        )
    } catch (_: Exception) {
        null
    }

    private fun writeSidecar(c: SavedClip, scores: FloatArray) {
        val json = buildString {
            append("{\"recordedAt\":").append(c.recordedAt)
            append(",\"barkScore\":").append(MiniJson.number(c.barkScore))
            append(",\"responded\":").append(c.responded)
            append(",\"minScoreSetting\":").append(MiniJson.number(c.minScoreSetting))
            append(",\"label\":").append(c.label?.let { MiniJson.quote(it.json) } ?: "null")
            append(",\"scores\":[")
            scores.forEachIndexed { i, s -> if (i > 0) append(','); append(MiniJson.number(s)) }
            append("]}\n")
        }
        writeAtomically(sidecarFile(c.id)) { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                write(out)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("cannot rename ${tmp.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        /** The folder name under the app's files folder; the backup rules leave it out. */
        const val DIR_NAME = "samples"
        /** About 64 MB of 2-second clips. */
        const val MAX_UNLABELLED = 1_000
        /** Ours run about 2.5 KB. */
        private const val MAX_SIDECAR_BYTES = 64 * 1024L
        private val SIDECAR = Regex("""(\d{1,18})\.json""")
        private val WAV = Regex("""(\d{1,18})\.wav""")
    }
}
