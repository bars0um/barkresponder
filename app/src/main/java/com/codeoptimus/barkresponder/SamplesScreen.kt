package com.codeoptimus.barkresponder

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Samples screen: every saved clip, newest first, to play, label Bark or
 * Not bark, or delete. Store work runs on one background thread in order;
 * the list redraws on the UI thread. A clip that plays counts as our own
 * sound, so the mic does not take it for a new one.
 */
class SamplesScreen(
    private val activity: Activity,
    private val store: SampleStore,
    private val ownSounds: AtomicInteger,
    private val saveClips: () -> Boolean,
    private val setSaveClips: (Boolean) -> Unit,
    private val onBack: () -> Unit,
    private val onExport: () -> Unit,
    private val setRemote: (Boolean) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var all: List<SavedClip> = emptyList()
    private var shown: List<SavedClip> = emptyList()
    private var respondedOnly = false
    private var player: MediaPlayer? = null
    private var playingId: Long? = null
    private var playerDone: AtomicBoolean? = null

    private val summary: TextView
    private val empty: TextView
    private val adapter = ClipAdapter()
    private val saveSwitch: Switch
    private val remoteSwitch: Switch
    private val remoteInfo: TextView
    private var showingRemote = false

    val view: View

    private fun Int.dp() = (this * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = activity.getColor(id)

    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 8.dp())
            setBackgroundColor(color(R.color.bg_primary))
            visibility = View.GONE
        }
        root.addView(Button(activity).apply {
            text = "← Back"
            setTextColor(color(R.color.accent))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = null
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()))
        root.addView(TextView(activity).apply {
            text = "Samples"
            setTextColor(color(R.color.text_primary))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        summary = TextView(activity).apply {
            text = "Loading clips…"
            setTextColor(color(R.color.text_secondary))
            textSize = 14f
            setPadding(0, 2.dp(), 0, 8.dp())
        }
        root.addView(summary)

        saveSwitch = switch("Save sound clips") { on -> setSaveClips(on) }
        root.addView(saveSwitch)
        root.addView(TextView(activity).apply {
            text = "Keeps 2 seconds around each loud sound, to label for training. Clips stay on this device and out of backups."
            setTextColor(color(R.color.text_hint))
            textSize = 12f
            setPadding(0, 0, 0, 4.dp())
        })
        remoteSwitch = switch("Remote access") { on -> if (!showingRemote) setRemote(on) }
        root.addView(remoteSwitch)
        remoteInfo = TextView(activity).apply {
            setTextColor(color(R.color.text_hint))
            textSize = 12f
            setPadding(0, 0, 0, 4.dp())
        }
        root.addView(remoteInfo)
        showRemote(RemoteAccess.State.Off)
        root.addView(switch("Responded only") { on -> respondedOnly = on; applyFilter() })

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Export labelled", primary = true) { onExport() },
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        actions.addView(button("Delete all", primary = false) { confirmDeleteAll() },
            LinearLayout.LayoutParams(0, 44.dp(), 1f))
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp())
            .apply { topMargin = 8.dp(); bottomMargin = 8.dp() })

        empty = TextView(activity).apply {
            setTextColor(color(R.color.text_hint))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24.dp(), 0, 0)
            visibility = View.GONE
        }
        root.addView(empty)
        root.addView(ListView(activity).apply {
            adapter = this@SamplesScreen.adapter
            divider = null
            dividerHeight = 8.dp()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        view = root
    }

    /** Re-reads the clips; call when the screen opens or a clip is saved. */
    fun reload() {
        saveSwitch.isChecked = saveClips()
        io.execute { show(store.list()) }
    }

    /** Sets the Remote access switch and its line to the server's state, without calling [setRemote]. */
    fun showRemote(state: RemoteAccess.State) {
        showingRemote = true
        remoteSwitch.isChecked = state is RemoteAccess.State.On
        showingRemote = false
        when (state) {
            is RemoteAccess.State.On -> {
                remoteInfo.text = "Open ${state.urls.joinToString(" or ")} on a computer on this Wi-Fi. " +
                    "Anyone on this Wi-Fi can open it while this is on."
                remoteInfo.setTextColor(color(R.color.state_active))
            }
            is RemoteAccess.State.Failed -> {
                remoteInfo.text = state.message
                remoteInfo.setTextColor(color(R.color.state_idle))
            }
            RemoteAccess.State.Off -> {
                remoteInfo.text = "Lets a computer on this Wi-Fi play, label, delete and download clips."
                remoteInfo.setTextColor(color(R.color.text_hint))
            }
        }
    }

    fun stopPlayback() {
        playerDone?.let { finishPlayback(it) }
    }

    fun close() {
        stopPlayback()
        io.shutdown()
    }

    private fun show(clips: List<SavedClip>) = ui.post {
        all = clips
        applyFilter()
    }

    private fun applyFilter() {
        shown = if (respondedOnly) all.filter { it.responded } else all
        val labelled = all.count { it.label != null }
        summary.text = "${all.size} ${if (all.size == 1) "clip" else "clips"}, $labelled labelled"
        empty.text = when {
            all.isEmpty() -> "No clips yet. Each loud sound the app hears while listening is saved here."
            else -> "No clips the app responded to."
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun setLabel(clip: SavedClip, label: ClipLabel) {
        val next = if (clip.label == label) null else label // tapping the set label clears it
        io.execute {
            try {
                store.setLabel(clip.id, next)
            } catch (_: Exception) {
                ui.post { toast("Could not save the label.") }
            }
            show(store.list())
        }
    }

    private fun confirmDelete(clip: SavedClip) {
        AlertDialog.Builder(activity)
            .setMessage("Delete this clip? You can't undo this.")
            .setPositiveButton("Delete") { _, _ ->
                if (playingId == clip.id) stopPlayback()
                io.execute { store.delete(clip.id); show(store.list()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll() {
        if (all.isEmpty()) return
        AlertDialog.Builder(activity)
            .setMessage("Delete all ${all.size} clips? Labelled clips go too. You can't undo this.")
            .setPositiveButton("Delete all") { _, _ ->
                stopPlayback()
                io.execute { store.deleteAll(); show(store.list()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun play(clip: SavedClip) {
        val wasPlaying = playingId
        stopPlayback()
        if (wasPlaying == clip.id) return // the button read Stop
        val mp = MediaPlayer()
        try {
            // The media server cannot open our private files by path; hand it the open file.
            FileInputStream(store.wavFile(clip.id)).use { mp.setDataSource(it.fd) }
            mp.prepare()
        } catch (_: Exception) {
            mp.release()
            toast("Could not play this clip.")
            return
        }
        val done = AtomicBoolean(false)
        ownSounds.incrementAndGet()
        player = mp
        playingId = clip.id
        playerDone = done
        mp.setOnCompletionListener { finishPlayback(done) }
        mp.setOnErrorListener { _, _, _ -> finishPlayback(done); true }
        mp.start()
        adapter.notifyDataSetChanged()
    }

    // Runs once per playback, whichever comes first: the end, an error or Stop.
    private fun finishPlayback(done: AtomicBoolean) {
        if (!done.compareAndSet(false, true)) return
        if (playerDone === done) {
            player?.let { runCatching { it.stop() }; it.release() }
            player = null
            playingId = null
            playerDone = null
        }
        // Like our responses, let the room go quiet before the mic listens again.
        ui.postDelayed({ ownSounds.decrementAndGet() }, 150)
        adapter.notifyDataSetChanged()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(activity).apply {
        text = label
        setTextColor(color(R.color.text_primary))
        textSize = 15f
        setPadding(0, 8.dp(), 0, 8.dp())
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun styleButton(b: Button, selected: Boolean) {
        if (selected) {
            b.setBackgroundResource(R.drawable.btn_bg)
            b.setTextColor(color(R.color.btn_text))
        } else {
            b.setBackgroundColor(color(R.color.bg_card))
            b.setTextColor(color(R.color.text_secondary))
        }
    }

    private inner class Row(val root: LinearLayout) {
        val time = TextView(activity)
        val detail = TextView(activity)
        val label = TextView(activity)
        val play = button("Play", primary = false) {}
        val bark = button("Bark", primary = false) {}
        val notBark = button("Not bark", primary = false) {}
        val delete = button("Delete", primary = false) {}
    }

    private inner class ClipAdapter : BaseAdapter() {
        private val timeFormat = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: newRow()
            val clip = shown[position]
            row.time.text = timeFormat.format(Date(clip.recordedAt))
            row.detail.text = "Bark ${(clip.barkScore * 100).toInt()}%, ${if (clip.responded) "responded" else "ignored"}"
            row.label.text = when (clip.label) {
                ClipLabel.BARK -> "Bark"
                ClipLabel.NOT_BARK -> "Not bark"
                null -> "Not labelled"
            }
            row.label.setTextColor(color(if (clip.label == null) R.color.text_hint else R.color.state_active))
            row.play.text = if (playingId == clip.id) "Stop" else "Play"
            styleButton(row.play, playingId == clip.id)
            styleButton(row.bark, clip.label == ClipLabel.BARK)
            styleButton(row.notBark, clip.label == ClipLabel.NOT_BARK)
            row.play.setOnClickListener { play(clip) }
            row.bark.setOnClickListener { setLabel(clip, ClipLabel.BARK) }
            row.notBark.setOnClickListener { setLabel(clip, ClipLabel.NOT_BARK) }
            row.delete.setOnClickListener { confirmDelete(clip) }
            return row.root
        }

        private fun newRow(): Row {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            }
            val row = Row(root)
            val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.time.apply {
                setTextColor(color(R.color.text_primary))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            top.addView(row.time, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.label.textSize = 14f
            top.addView(row.label)
            root.addView(top)
            row.detail.apply {
                setTextColor(color(R.color.text_secondary))
                textSize = 14f
                setPadding(0, 2.dp(), 0, 6.dp())
            }
            root.addView(row.detail)
            val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, b) in listOf(row.play, row.bark, row.notBark, row.delete).withIndex()) {
                buttons.addView(b, LinearLayout.LayoutParams(0, 40.dp(), 1f).apply { if (i < 3) marginEnd = 6.dp() })
            }
            root.addView(buttons)
            root.tag = row
            return row
        }
    }
}
