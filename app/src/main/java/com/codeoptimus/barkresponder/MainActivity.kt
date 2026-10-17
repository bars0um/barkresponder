package com.codeoptimus.barkresponder
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ResponseMode { IMPULSE, TONE, MELODY, GUNSHOT, DISTRACT }

/**
 * Membrane driver with sound-triggered impulse.
 *
 *  - Drive tone / Sweep: setup tools to find the membrane's resonant frequency.
 *  - Impulse: the percussive hit that drives the diaphragm and lifts the ball.
 *  - Sound trigger: the mic listens; a sound above the threshold fires one
 *    impulse (with a cooldown). The ball rise happens physically in the rig and
 *    is shown on the external video display -- nothing is drawn in the app.
 *
 * UI built in code (no layout XML). Requires RECORD_AUDIO for the trigger mode.
 *
 * Two screens: Simple (default) and Advanced (full controls).
 */
class MainActivity : Activity() {

    private val sampleRate = 44100
    private val TAU = 2.0 * PI
    // Lazy: the Activity is constructed (field initializers run) before attach()
    // installs a context, so we can't call getSharedPreferences in the initializer.
    private val prefs: SharedPreferences
        get() = getSharedPreferences("settings", MODE_PRIVATE)

    @Volatile private var amp = 0.7

    // continuous engine (tone + sweep)
    @Volatile private var playing = false
    private enum class Mode { TONE, SWEEP }
    @Volatile private var mode = Mode.TONE
    @Volatile private var toneFreq = 60.0
    @Volatile private var sweepStart = 20.0
    @Volatile private var sweepEnd = 200.0
    @Volatile private var sweepDur = 15.0
    @Volatile private var sweepT0 = 0L

    // impulse
    @Volatile private var boomF0 = 120.0
    @Volatile private var boomF1 = 45.0
    @Volatile private var boomDecay = 0.35
    @Volatile private var chaos = 0.75   // 0 = fixed, 1 = fully random
    @Volatile private var strikeCount = 3
    @Volatile private var gapBase = 150.0   // base ms between strikes
    @Volatile private var gapRand = 100.0   // random spread added to the gap

    // whistle (TONE response) settings
    @Volatile private var whistleDurMs = 1500   // how long each whistle play lasts (ms)
    @Volatile private var whistleRepeats = 1    // how many times the whistle fires per trigger
    @Volatile private var whistleJitter = 0.0   // 0 = fixed pitch, 1 = each repeat spreads ±30% around the set freq
    @Volatile private var whistleVol = 1.0      // 0..1, per-whistle level on top of the global volume
    @Volatile private var whistleGapMs = 120    // pause between toots (ms)

    // gunshot (synthesized bang) settings
    @Volatile private var gsIntensity = 0.9   // 0..1, crack level (0=silence, 1=max)
    @Volatile private var gsBoomFreq = 50.0   // Hz, starting pitch of the low-frequency boom
    @Volatile private var gsBoomTail = 0.45   // seconds, how long the low boom decays
    @Volatile private var gsChaos = 0.35      // 0..1, randomize pitch/level/tail per shot

    // distract (run of high trills and chirps) settings
    @Volatile private var distractSeconds = DistractSound.DEFAULT_SECONDS
    @Volatile private var distractLowHz = DistractSound.DEFAULT_LOW_HZ
    @Volatile private var distractHighHz = DistractSound.DEFAULT_HIGH_HZ

    // sound trigger (defaults tuned for bark detection)
    @Volatile private var listening = false
    @Volatile private var threshold = 20.0   // lower: bandpass narrows the band, selectivity does the discrimination
    @Volatile private var micGain = 15.0      // low: background stays quiet, bark spikes past
    @Volatile private var barkSelect = 0.8   // high: require a sharp transient, reject steady noise
    @Volatile private var cooldownMs = 100   // short: a rapid woof-woof-woof fires on each bark
    @Volatile private var sustainMs = 150   // level must hold above threshold this long (rejects single blips)
    @Volatile private var suppressUntil = 0L
    // Responses playing now; the mic ignores what it hears while any plays. A count,
    // not a flag, so one response ending doesn't unmark another still playing.
    private val ownSounds = AtomicInteger(0)
    // Cleared when this listening session stops, so its late work can't act in the next one.
    @Volatile private var listenSession: AtomicBoolean? = null
    @Volatile private var barkMinScore = 0.4f  // YAMNet Bark score needed to respond; 0 turns the check off
    @Volatile private var triggerCount = 0
    @Volatile private var saveClips = true   // keep a clip of each candidate for labelling
    private val sampleStore: SampleStore
        get() = synchronized(MainActivity) {
            sharedStore ?: SampleStore(File(filesDir, SampleStore.DIR_NAME)).also { sharedStore = it }
        }

    // response mode (simple screen)
    @Volatile private var responseMode: ResponseMode = ResponseMode.IMPULSE

    private val handler = Handler(Looper.getMainLooper())

    // UI fields — simple screen
    private lateinit var simpleListenBtn: Button
    private lateinit var stateLbl: TextView
    private lateinit var micLevel: ProgressBar
    private lateinit var countLbl: TextView
    private lateinit var lastSoundLbl: TextView
    private lateinit var impToggleBtn: Button
    private lateinit var whistleToggleBtn: Button
    private lateinit var melodyToggleBtn: Button
    private lateinit var gunshotToggleBtn: Button
    private lateinit var distractToggleBtn: Button
    private lateinit var verifyResultLbl: TextView
    private lateinit var freqSliderImpulse: SeekBar
    private lateinit var freqLblImpulse: TextView
    private lateinit var freqSliderTone: SeekBar
    private lateinit var freqLblTone: TextView

    // UI fields — advanced screen
    private lateinit var readout: TextView
    private lateinit var toneBtn: Button
    private lateinit var sweepBtn: Button
    private lateinit var listenBtn: Button
    private lateinit var batteryLbl: TextView
    private lateinit var batteryBtn: Button
    private lateinit var samplesBtn: Button

    // screens
    private lateinit var simpleScreen: ScrollView
    private lateinit var advancedScreen: ScrollView
    private lateinit var samplesScreen: SamplesScreen

    // keep-alive: holds the CPU awake and keeps the Bluetooth A2DP link from sleeping
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var keepAlive = false
    private val KEEP_ALIVE_MS = 25_000L   // blip cadence, well under the ~30-60 s A2DP idle timeout
    private val REQUEST_EXPORT = 2
    private val REQUEST_LOCAL_NETWORK = 3
    private val ANDROID_17 = 37

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()

        // Hold a partial WakeLock so Doze / battery-optimization can't suspend the
        // CPU (which would kill the mic thread and the audio output after a while).
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barkresponder:keepalive")
            wakeLock?.setReferenceCounted(false)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val frameRoot = FrameLayout(this).apply {
            setBackgroundColor(context.getColor(R.color.bg_primary))
        }

        // ============================================================
        // SIMPLE SCREEN (default visible)
        // ============================================================
        val simpleRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Icon
        simpleRoot.addView(ImageView(this).apply {
            setImageResource(R.drawable.icon_boom)
            val lp = LinearLayout.LayoutParams(48.dp(), 48.dp())
            lp.topMargin = 8.dp()
            layoutParams = lp
            contentDescription = "Bark Responder"
        })

        // 2. App title
        simpleRoot.addView(TextView(this).apply {
            text = "Bark Responder"
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 4.dp())
        })

        // 3. State label
        stateLbl = TextView(this).apply {
            setPadding(0, 2.dp(), 0, 4.dp())
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.state_idle))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        stateLbl.text = "idle"
        simpleRoot.addView(stateLbl)

        // 4. Noise level bar
        micLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 8.dp()
            layoutParams = lp
        }
        simpleRoot.addView(micLevel)

        // 5. Trigger count
        countLbl = TextView(this).apply {
            text = "Triggers: 0"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.accent))
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 0)
        }
        simpleRoot.addView(countLbl)

        // 5b. Why the last sound did or didn't get a response
        lastSoundLbl = TextView(this).apply {
            text = "Last sound: none yet"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, 4.dp(), 0, 0)
        }
        simpleRoot.addView(lastSoundLbl)

        // 6. BIG LISTEN BUTTON
        simpleListenBtn = Button(this).apply {
            text = "LISTEN"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.btn_text))
            setBackgroundResource(R.drawable.btn_bg)
            isAllCaps = false
            setOnClickListener { toggleListen() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 72.dp()
            ).apply { topMargin = 16.dp() }
            layoutParams = lp
        }
        simpleRoot.addView(simpleListenBtn)

        // 7. Divider
        simpleRoot.addView(View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply { topMargin = 20.dp(); bottomMargin = 16.dp() }
        })

        // 8. "Response" section header
        sectionHeader(simpleRoot, "Response")

        // 9. Type toggle — Impulse / Whistle / Melody / Gunshot / Distract
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
            layoutParams = lp
        }
        impToggleBtn = Button(this).apply {
            text = "Impulse"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener {
                responseMode = ResponseMode.IMPULSE
                updateToggleButtons()
                saveSettings()
            }
            val lp = LinearLayout.LayoutParams(0, 48.dp(), 1f)
            lp.marginEnd = 4.dp()
            layoutParams = lp
        }
        whistleToggleBtn = Button(this).apply {
            text = "Whistle"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener {
                responseMode = ResponseMode.TONE
                updateToggleButtons()
                saveSettings()
            }
            val lp = LinearLayout.LayoutParams(0, 48.dp(), 1f)
            lp.marginEnd = 4.dp()
            layoutParams = lp
        }
        melodyToggleBtn = Button(this).apply {
            text = "Melody"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener {
                responseMode = ResponseMode.MELODY
                updateToggleButtons()
                saveSettings()
            }
            val lp = LinearLayout.LayoutParams(0, 48.dp(), 1f)
            lp.marginEnd = 4.dp()
            layoutParams = lp
        }
        gunshotToggleBtn = Button(this).apply {
            text = "Gunshot"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener {
                responseMode = ResponseMode.GUNSHOT
                updateToggleButtons()
                saveSettings()
            }
            val lp = LinearLayout.LayoutParams(0, 48.dp(), 1f)
            lp.marginEnd = 4.dp()
            layoutParams = lp
        }
        distractToggleBtn = Button(this).apply {
            text = "Distract"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener {
                responseMode = ResponseMode.DISTRACT
                updateToggleButtons()
                saveSettings()
            }
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f)
        }
        toggleRow.addView(impToggleBtn)
        toggleRow.addView(whistleToggleBtn)
        toggleRow.addView(melodyToggleBtn)
        toggleRow.addView(gunshotToggleBtn)
        toggleRow.addView(distractToggleBtn)
        simpleRoot.addView(toggleRow)

        // 10. Frequency slider — Impulse (Bark pitch)
        freqLblImpulse = label("Impulse pitch: ${boomF0.toInt()} Hz")
        simpleRoot.addView(freqLblImpulse)
        freqSliderImpulse = slider(20, 400, boomF0.toInt()) { v ->
            boomF0 = v.toDouble()
            freqLblImpulse.text = "Impulse pitch: $v Hz"
            saveSettings()
        }
        simpleRoot.addView(freqSliderImpulse)

        // 10b. Frequency slider — Tone/Whistle (log scale, 200 Hz – 20 kHz)
        freqLblTone = label("Whistle freq: ${toneFreq.toInt()} Hz")
        simpleRoot.addView(freqLblTone)
        freqSliderTone = logSlider(toneFreq.toInt().coerceIn(200, 20000)) { hz ->
            toneFreq = hz.toDouble()
            freqLblTone.text = "Whistle freq: ${"%,d".format(hz.toLong())} Hz"
            saveSettings()
        }
        simpleRoot.addView(freqSliderTone)
        // Initialize toggle button appearance (after all dependent views exist)
        updateToggleButtons()

        // 11. Volume slider
        val volLbl = label("Volume: ${(amp * 100).toInt()}%")
        simpleRoot.addView(volLbl)
        simpleRoot.addView(slider(0, 100, (amp * 100).toInt()) { v ->
            amp = v / 100.0
            volLbl.text = "Volume: $v%"
            saveSettings()
        })

        // 12. Test button
        simpleRoot.addView(Button(this).apply {
            text = "Test response"
            setTextColor(context.getColor(R.color.btn_text))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.btn_bg)
            isAllCaps = false
            setOnClickListener {
                playResponse()
                flash(responseModeState())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()
            ).apply { topMargin = 8.dp() }
            layoutParams = lp
        })

        // 13. Advanced button
        simpleRoot.addView(Button(this).apply {
            text = "⚙  Advanced settings  →"
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
            typeface = Typeface.DEFAULT
            isAllCaps = false
            setBackgroundColor(context.getColor(R.color.bg_card))
            setOnClickListener { switchToAdvanced() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 44.dp()
            ).apply { topMargin = 16.dp() }
            layoutParams = lp
        })

        simpleScreen = ScrollView(this).apply {
            setBackgroundColor(context.getColor(R.color.bg_primary))
            addView(simpleRoot)
        }

        // ============================================================
        // ADVANCED SCREEN (initially GONE)
        // ============================================================
        val advRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }

        // Back button
        advRoot.addView(Button(this).apply {
            text = "← Back"
            setTextColor(context.getColor(R.color.accent))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = null
            gravity = Gravity.START
            setOnClickListener { switchToSimple() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()
            ).apply { bottomMargin = 8.dp() }
            layoutParams = lp
        })

        // Frequency readout
        readout = TextView(this).apply {
            text = "${toneFreq.toInt()} Hz"
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        }
        advRoot.addView(readout)

        // ---- Drive level (global amp) ----
        val ampCard = card()
        sectionHeader(ampCard, "Drive level")
        val ampLbl = label("${(amp * 100).toInt()}%")
        ampCard.addView(ampLbl)
        ampCard.addView(slider(0, 100, (amp * 100).toInt()) { v ->
            amp = v / 100.0
            ampLbl.text = "$v%"
            saveSettings()
        })
        advRoot.addView(ampCard)

        // ---- Drive tone ----
        val toneCard = card()
        sectionHeader(toneCard, "Drive tone (find resonance by hand)")
        val tLbl = label("Frequency: ${toneFreq.toInt()} Hz")
        toneCard.addView(tLbl)
        toneCard.addView(slider(15, 600, toneFreq.toInt().coerceIn(15, 600)) { v ->
            toneFreq = v.toDouble(); tLbl.text = "Frequency: $v Hz"
            if (playing && mode == Mode.TONE) showFreq(v.toDouble())
            saveSettings()
        })
        toneBtn = actionButton("Start tone") { toggleTone() }
        toneCard.addView(toneBtn)
        advRoot.addView(toneCard)

        // ---- Sweep ----
        val sweepCard = card()
        sectionHeader(sweepCard, "Sweep (auto resonance finder)")
        val ssLbl = label("From: ${sweepStart.toInt()} Hz"); sweepCard.addView(ssLbl)
        sweepCard.addView(slider(10, 500, sweepStart.toInt().coerceIn(10, 500)) { v -> sweepStart = v.toDouble(); ssLbl.text = "From: $v Hz"; saveSettings() })
        val seLbl = label("To: ${sweepEnd.toInt()} Hz"); sweepCard.addView(seLbl)
        sweepCard.addView(slider(20, 1000, sweepEnd.toInt().coerceIn(20, 1000)) { v -> sweepEnd = v.toDouble(); seLbl.text = "To: $v Hz"; saveSettings() })
        val sdLbl = label("Duration: ${sweepDur.toInt()} s"); sweepCard.addView(sdLbl)
        sweepCard.addView(slider(3, 60, sweepDur.toInt().coerceIn(3, 60)) { v -> sweepDur = v.toDouble(); sdLbl.text = "Duration: $v s"; saveSettings() })
        sweepBtn = actionButton("Run sweep") { toggleSweep() }
        sweepCard.addView(sweepBtn)
        advRoot.addView(sweepCard)

        // ---- Whistle ----
        val wCard = card()
        sectionHeader(wCard, "Whistle (one-shot tone)")
        val wdLbl = settingLabel(wCard, "Duration: ${whistleDurMs} ms",
            "How long each whistle toot lasts. Shorter = a sharp chirp; longer = a sustained tone.")
        wCard.addView(slider(200, 8000, whistleDurMs.coerceIn(200, 8000)) { v -> whistleDurMs = v; wdLbl.text = "Duration: $v ms"; saveSettings() })
        val wrLbl = settingLabel(wCard, "Repeats: $whistleRepeats",
            "How many times the whistle fires per bark. Higher = a repeated toot-toot-toot.")
        wCard.addView(slider(1, 10, whistleRepeats.coerceIn(1, 10)) { v -> whistleRepeats = v; wrLbl.text = "Repeats: $v"; saveSettings() })
        val wjLbl = settingLabel(wCard, "Pitch jitter: ${(whistleJitter * 100).toInt()}%",
            "Each repeat lands at a slightly different pitch around the set value. 0% = fixed pitch; higher = more spread, like the Impulse chaos.")
        wCard.addView(slider(0, 100, (whistleJitter * 100).toInt()) { v -> whistleJitter = v / 100.0; wjLbl.text = "Pitch jitter: $v%"; saveSettings() })
        val wvLbl = settingLabel(wCard, "Volume: ${(whistleVol * 100).toInt()}%",
            "Whistle loudness on top of the global volume. Lower makes the whistle quieter than the impulse/melody.")
        wCard.addView(slider(0, 100, (whistleVol * 100).toInt()) { v -> whistleVol = v / 100.0; wvLbl.text = "Volume: $v%"; saveSettings() })
        val wgLbl = settingLabel(wCard, "Gap between toots: ${whistleGapMs} ms",
            "Silence between repeated toots. Only matters when Repeats is more than 1. Shorter = tighter toots.")
        wCard.addView(slider(0, 2000, whistleGapMs.coerceIn(0, 2000)) { v -> whistleGapMs = v; wgLbl.text = "Gap between toots: $v ms"; saveSettings() })
        wCard.addView(actionButton("Test whistle") { playToneBurst(); flash("whistle") })
        verifyResultLbl = TextView(this).apply {
            text = ""
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 0)
            visibility = View.GONE
        }
        wCard.addView(verifyResultLbl)
        wCard.addView(actionButton("🔊 Verify via mic") { verifyHighRange() })
        advRoot.addView(wCard)

        // ---- Gunshot ----
        val gsCard = card()
        sectionHeader(gsCard, "Gunshot (synthesized bang)")
        val giLbl = settingLabel(gsCard, "Intensity: ${(gsIntensity * 100).toInt()}%",
            "Loudness of the crack on top of the global volume. Lower = softer bang.")
        gsCard.addView(slider(0, 100, (gsIntensity * 100).toInt()) { v -> gsIntensity = v / 100.0; giLbl.text = "Intensity: $v%"; saveSettings() })
        val gfLbl = settingLabel(gsCard, "Boom pitch: ${gsBoomFreq.toInt()} Hz",
            "Starting pitch of the low-frequency pressure wave. Lower = deeper, longer-thrust boom.")
        gsCard.addView(slider(25, 150, gsBoomFreq.toInt().coerceIn(25, 150)) { v -> gsBoomFreq = v.toDouble(); gfLbl.text = "Boom pitch: $v Hz"; saveSettings() })
        val gtLbl = settingLabel(gsCard, "Boom tail: ${"%.2f".format(gsBoomTail)} s",
            "How long the low boom keeps pushing after the crack. Shorter = drier, longer = heavier.")
        gsCard.addView(slider(100, 1500, (gsBoomTail * 1000).toInt().coerceIn(100, 1500)) { v ->
            gsBoomTail = v / 1000.0; gtLbl.text = "Boom tail: ${"%.2f".format(v / 1000.0)} s"; saveSettings()
        })
        val gcLbl = settingLabel(gsCard, "Chaos: ${(gsChaos * 100).toInt()}%",
            "Each shot varies slightly in pitch, level, and tail. 0% = identical every time; higher = more like real shots.")
        gsCard.addView(slider(0, 100, (gsChaos * 100).toInt()) { v -> gsChaos = v / 100.0; gcLbl.text = "Chaos: $v%"; saveSettings() })
        gsCard.addView(actionButton("Test gunshot") { playGunshot(); flash("gunshot") })
        advRoot.addView(gsCard)

        // ---- Distract ----
        val dCard = card()
        sectionHeader(dCard, "Distract (high trills and chirps)")
        val dlLbl = settingLabel(dCard, "Length: $distractSeconds s",
            "How long each run of sounds plays after a bark.")
        dCard.addView(slider(DistractSound.MIN_SECONDS, DistractSound.MAX_SECONDS, distractSeconds) { v ->
            distractSeconds = v; dlLbl.text = "Length: $v s"; saveSettings()
        })
        val dloLbl = settingLabel(dCard, "Lowest pitch: ${"%,d".format(distractLowHz)} Hz",
            "The deepest sound in a run. The range always spans at least 1,000 Hz.")
        val dhiLbl = settingLabel(dCard, "Highest pitch: ${"%,d".format(distractHighHz)} Hz",
            "The highest sound in a run. It stops at 8,000 Hz: tablet speakers play little above it.")
        lateinit var dHiSlider: SeekBar
        val dLoSlider = slider(DistractSound.MIN_HZ, DistractSound.MAX_HZ - DistractSound.MIN_SPAN_HZ, distractLowHz) { v ->
            distractLowHz = v
            dloLbl.text = "Lowest pitch: ${"%,d".format(v)} Hz"
            // keep the range at least MIN_SPAN_HZ wide by pushing the top up
            if (distractHighHz < v + DistractSound.MIN_SPAN_HZ) dHiSlider.progress = v + DistractSound.MIN_SPAN_HZ - DistractSound.MIN_HZ
            saveSettings()
        }
        dHiSlider = slider(DistractSound.MIN_HZ, DistractSound.MAX_HZ, distractHighHz) { v ->
            // keep the range at least MIN_SPAN_HZ wide: snap the thumb back to the floor
            val floor = distractLowHz + DistractSound.MIN_SPAN_HZ
            if (v < floor) {
                dHiSlider.progress = floor - DistractSound.MIN_HZ
            } else {
                distractHighHz = v
                dhiLbl.text = "Highest pitch: ${"%,d".format(v)} Hz"
                saveSettings()
            }
        }
        // Each slider sits under its own label.
        dCard.addView(dLoSlider, dCard.indexOfChild(dloLbl) + 2)
        dCard.addView(dHiSlider)
        dCard.addView(actionButton("Test distract") { playDistract(); flash("distract") })
        advRoot.addView(dCard)

        // ---- Impulse ----
        val impCard = card()
        sectionHeader(impCard, "Impulse (drives the membrane)")
        val f0Lbl = label("Pitch start: ${boomF0.toInt()} Hz"); impCard.addView(f0Lbl)
        impCard.addView(slider(20, 400, boomF0.toInt().coerceIn(20, 400)) { v -> boomF0 = v.toDouble(); f0Lbl.text = "Pitch start: $v Hz"; saveSettings() })
        val f1Lbl = label("Pitch end: ${boomF1.toInt()} Hz"); impCard.addView(f1Lbl)
        impCard.addView(slider(15, 300, boomF1.toInt().coerceIn(15, 300)) { v -> boomF1 = v.toDouble(); f1Lbl.text = "Pitch end: $v Hz"; saveSettings() })
        val decLbl = label("Punch / decay: ${"%.2f".format(boomDecay)} s"); impCard.addView(decLbl)
        impCard.addView(slider(50, 2000, (boomDecay * 1000).toInt()) { v ->
            boomDecay = v / 1000.0
            decLbl.text = "Punch / decay: ${"%.2f".format(v / 1000.0)} s"
            saveSettings()
        })
        val chLbl = label("Chaos (random force): ${(chaos * 100).toInt()}%"); impCard.addView(chLbl)
        impCard.addView(slider(0, 100, (chaos * 100).toInt()) { v -> chaos = v / 100.0; chLbl.text = "Chaos (random force): $v%"; saveSettings() })
        val scLbl = label("Strikes per trigger: $strikeCount"); impCard.addView(scLbl)
        impCard.addView(slider(1, 8, strikeCount) { v -> strikeCount = v; scLbl.text = "Strikes per trigger: $v"; saveSettings() })
        val gbLbl = label("Gap base: ${gapBase.toInt()} ms"); impCard.addView(gbLbl)
        impCard.addView(slider(30, 600, gapBase.toInt().coerceIn(30, 600)) { v -> gapBase = v.toDouble(); gbLbl.text = "Gap base: $v ms"; saveSettings() })
        val grLbl = label("Gap random: ${gapRand.toInt()} ms"); impCard.addView(grLbl)
        impCard.addView(slider(0, 400, gapRand.toInt().coerceIn(0, 400)) { v -> gapRand = v.toDouble(); grLbl.text = "Gap random: $v ms"; saveSettings() })
        impCard.addView(actionButton("Test impulse") { boom(); flash("impulse") })
        advRoot.addView(impCard)

        // ---- Sound trigger ----
        val trigCard = card()
        sectionHeader(trigCard, "Sound trigger")
        val mgLbl = settingLabel(trigCard, "Mic gain: ${micGain.toInt()}",
            "How much to amplify the mic. Low keeps background quiet; raise it if real barks are too faint to cross the trigger line.")
        trigCard.addView(slider(1, 100, micGain.toInt().coerceIn(1, 100)) { v -> micGain = v.toDouble(); mgLbl.text = "Mic gain: $v"; saveSettings() })
        val bsLbl = settingLabel(trigCard, "Bark selectivity: ${(barkSelect * 100).toInt()}%",
            "How much louder a sound must spike above the surrounding noise to count. Higher rejects steady sounds (TV, fan); lower is more sensitive.")
        trigCard.addView(slider(0, 100, (barkSelect * 100).toInt()) { v -> barkSelect = v / 100.0; bsLbl.text = "Bark selectivity: $v%"; saveSettings() })
        val thLbl = settingLabel(trigCard, "Sensitivity trigger: ${threshold.toInt()}",
            "The level line a sound must cross. Lower = fires on quieter sounds (more false triggers); higher = only loud sounds.")
        trigCard.addView(slider(0, 100, threshold.toInt().coerceIn(0, 100)) { v -> threshold = v.toDouble(); thLbl.text = "Sensitivity trigger: $v"; saveSettings() })
        val suLbl = settingLabel(trigCard, "Hold time (sustain): ${sustainMs} ms",
            "How long the sound must stay above the trigger line before it fires. Shorter = each bark can trigger; longer = only a sustained bark.")
        trigCard.addView(slider(100, 2000, sustainMs.coerceIn(100, 2000)) { v -> sustainMs = v; suLbl.text = "Hold time (sustain): $v ms"; saveSettings() })
        val bcLbl = settingLabel(trigCard, "Bark confidence: ${(barkMinScore * 100).toInt()}%",
            "How sure the app must be that a sound is a bark before it responds. 0% turns the check off: any loud sound fires, as before.")
        trigCard.addView(slider(0, 100, (barkMinScore * 100).toInt()) { v -> barkMinScore = v / 100f; bcLbl.text = "Bark confidence: $v%"; saveSettings() })
        val cdLbl = settingLabel(trigCard, "Min gap between triggers: ${cooldownMs} ms",
            "Quiet period after a trigger before the app will fire again. Shorter lets a rapid woof-woof-woof fire on each bark.")
        trigCard.addView(slider(100, 5000, cooldownMs.coerceIn(100, 5000)) { v -> cooldownMs = v; cdLbl.text = "Min gap between triggers: $v ms"; saveSettings() })
        batteryLbl = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12.dp(), 0, 0)
            gravity = Gravity.CENTER
        }
        trigCard.addView(batteryLbl)
        batteryBtn = actionButton("Allow run in background (recommended)") { requestBatteryExemption() }
        trigCard.addView(batteryBtn)
        listenBtn = actionButton("Start listening") { toggleListen() }
        trigCard.addView(listenBtn)
        samplesBtn = actionButton("Samples") { switchToSamples() }
        trigCard.addView(samplesBtn)
        advRoot.addView(trigCard)

        advancedScreen = ScrollView(this).apply {
            setBackgroundColor(context.getColor(R.color.bg_primary))
            visibility = View.GONE
            addView(advRoot)
        }

        // Add both screens to the FrameLayout
        frameRoot.addView(simpleScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        frameRoot.addView(advancedScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        samplesScreen = SamplesScreen(
            this, sampleStore, ownSounds,
            saveClips = { saveClips },
            setSaveClips = { on -> saveClips = on; saveSettings() },
            onBack = { closeSamples() },
            onExport = { startExport() },
            setRemote = { on -> setRemoteAccess(on) },
        )
        samplesScreen.showRemote(RemoteAccess.state)
        RemoteAccess.listener = remoteListener
        RemoteAccess.onClipsChanged = clipsChangedRemotely
        frameRoot.addView(samplesScreen.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        updateSampleCount()

        setContentView(frameRoot)
    }

    // ---------- persistence (survives app restarts) ----------
    private fun loadSettings() {
        amp        = prefs.getFloat("amp", 0.7f).toDouble()
        toneFreq   = prefs.getFloat("toneFreq", 60.0f).toDouble()
        sweepStart = prefs.getFloat("sweepStart", 20.0f).toDouble()
        sweepEnd   = prefs.getFloat("sweepEnd", 200.0f).toDouble()
        sweepDur   = prefs.getFloat("sweepDur", 15.0f).toDouble()
        boomF0     = prefs.getFloat("boomF0", 120.0f).toDouble()
        boomF1     = prefs.getFloat("boomF1", 45.0f).toDouble()
        boomDecay  = prefs.getFloat("boomDecay", 0.35f).toDouble()
        chaos      = prefs.getFloat("chaos", 0.75f).toDouble()
        strikeCount = prefs.getInt("strikeCount", 3)
        gapBase    = prefs.getFloat("gapBase", 150.0f).toDouble()
        gapRand    = prefs.getFloat("gapRand", 100.0f).toDouble()
        whistleDurMs  = prefs.getInt("whistleDurMs", 1500)
        whistleRepeats = prefs.getInt("whistleRepeats", 1)
        whistleJitter = prefs.getFloat("whistleJitter", 0.0f).toDouble()
        whistleVol    = prefs.getFloat("whistleVol", 1.0f).toDouble()
        whistleGapMs  = prefs.getInt("whistleGapMs", 120)
        micGain    = prefs.getFloat("micGain", 15.0f).toDouble()
        barkSelect = prefs.getFloat("barkSelect", 0.8f).toDouble()
        threshold  = prefs.getFloat("threshold", 20.0f).toDouble()
        cooldownMs = prefs.getInt("cooldownMs", 100)
        sustainMs  = prefs.getInt("sustainMs", 150)
        barkMinScore = prefs.getFloat("barkMinScore", 0.4f).coerceIn(0f, 1f)
        saveClips    = prefs.getBoolean("saveClips", true)
        gsIntensity  = prefs.getFloat("gsIntensity", 0.9f).toDouble()
        gsBoomFreq   = prefs.getFloat("gsBoomFreq", 50.0f).toDouble()
        gsBoomTail   = prefs.getFloat("gsBoomTail", 0.45f).toDouble()
        gsChaos      = prefs.getFloat("gsChaos", 0.35f).toDouble()
        distractSeconds = DistractSound.seconds(prefs.getInt("distractSeconds", DistractSound.DEFAULT_SECONDS))
        distractLowHz   = prefs.getInt("distractLowHz", DistractSound.DEFAULT_LOW_HZ)
        distractHighHz  = prefs.getInt("distractHighHz", DistractSound.DEFAULT_HIGH_HZ)
        distractLowHz   = DistractSound.lowHz(distractLowHz)
        distractHighHz  = DistractSound.highHz(distractLowHz, distractHighHz)
        responseMode = when (prefs.getString("responseMode", "IMPULSE")) {
            "TONE"     -> ResponseMode.TONE
            "MELODY"   -> ResponseMode.MELODY
            "GUNSHOT"  -> ResponseMode.GUNSHOT
            "DISTRACT" -> ResponseMode.DISTRACT
            else       -> ResponseMode.IMPULSE
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putFloat("amp", amp.toFloat())
            putFloat("toneFreq", toneFreq.toFloat())
            putFloat("sweepStart", sweepStart.toFloat())
            putFloat("sweepEnd", sweepEnd.toFloat())
            putFloat("sweepDur", sweepDur.toFloat())
            putFloat("boomF0", boomF0.toFloat())
            putFloat("boomF1", boomF1.toFloat())
            putFloat("boomDecay", boomDecay.toFloat())
            putFloat("chaos", chaos.toFloat())
            putInt("strikeCount", strikeCount)
            putFloat("gapBase", gapBase.toFloat())
            putFloat("gapRand", gapRand.toFloat())
            putInt("whistleDurMs", whistleDurMs)
            putInt("whistleRepeats", whistleRepeats)
            putFloat("whistleJitter", whistleJitter.toFloat())
            putFloat("whistleVol", whistleVol.toFloat())
            putInt("whistleGapMs", whistleGapMs)
            putFloat("micGain", micGain.toFloat())
            putFloat("barkSelect", barkSelect.toFloat())
            putFloat("threshold", threshold.toFloat())
            putInt("cooldownMs", cooldownMs)
            putInt("sustainMs", sustainMs)
            putFloat("barkMinScore", barkMinScore)
            putBoolean("saveClips", saveClips)
            putFloat("gsIntensity", gsIntensity.toFloat())
            putFloat("gsBoomFreq", gsBoomFreq.toFloat())
            putFloat("gsBoomTail", gsBoomTail.toFloat())
            putFloat("gsChaos", gsChaos.toFloat())
            putInt("distractSeconds", distractSeconds)
            putInt("distractLowHz", distractLowHz)
            putInt("distractHighHz", distractHighHz)
            putString("responseMode", responseMode.name)
            apply()
        }
    }

    // ---------- UI helpers ----------
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun sectionHeader(parent: LinearLayout, t: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(4.dp(), 20.dp())
            setBackgroundColor(context.getColor(R.color.accent))
        })
        row.addView(TextView(this).apply {
            text = t
            setTextColor(context.getColor(R.color.accent))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp() }
        })
        parent.addView(row)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(context.getColor(R.color.text_secondary))
        textSize = 14f
        setPadding(0, 16, 0, 2)
    }

    // A setting label with a short description underneath. Returns the label view
    // so slider onChange callbacks can keep updating the live value.
    private fun settingLabel(parent: LinearLayout, t: String, desc: String): TextView {
        val lbl = TextView(this).apply {
            text = t
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        val d = TextView(this).apply {
            text = desc
            setTextColor(context.getColor(R.color.text_hint))
            textSize = 12f
            setPadding(0, 0, 0, 4)
        }
        parent.addView(lbl)
        parent.addView(d)
        return lbl
    }

    private fun slider(min: Int, maxV: Int, init: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            max = maxV - min; progress = (init - min).coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) = onChange(p + min)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(24.dp(), 20.dp(), 24.dp(), 20.dp())
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16.dp() }
        layoutParams = lp
    }

    private fun actionButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setTextColor(context.getColor(R.color.btn_text))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundResource(R.drawable.btn_bg)
        setOnClickListener { onClick() }
        isAllCaps = false
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()
        ).apply { topMargin = 8.dp() }
        layoutParams = lp
    }

    // Log-frequency mapping for the whistle slider (even spacing in octaves)
    private val W_FMIN = 200.0
    private val W_FMAX = 20000.0
    private val W_STEPS = 1000
    private fun logHzToPos(hz: Double): Int {
        val min = W_FMIN; val max = W_FMAX
        val x = hz.coerceIn(min, max)
        val ratio = Math.log(x / min) / Math.log(max / min)
        return (ratio * W_STEPS).toInt()
    }
    private fun logPosToHz(pos: Int): Int =
        (W_FMIN * (W_FMAX / W_FMIN).pow(pos / W_STEPS.toDouble())).toInt()
    private fun logSlider(initialHz: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            max = W_STEPS
            progress = logHzToPos(initialHz.toDouble())
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) = onChange(logPosToHz(p))
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

    private fun showFreq(f: Double) { runOnUiThread { readout.text = "${f.toInt()} Hz" } }

    private fun setState(text: String) {
        stateLbl.text = text
        val colorRes = when (text) {
            "idle" -> R.color.state_idle
            "impulse", "mic error" -> R.color.state_alert
            else -> R.color.state_active
        }
        stateLbl.setTextColor(getColor(colorRes))
    }

    private fun flash(s: String) {
        runOnUiThread { setState(s) }
        handler.postDelayed({
            if (!playing) {
                if (listening) setState("listening") else setState("idle")
            }
        }, 400)
    }

    // ---------- screen navigation ----------
    private fun switchToAdvanced() {
        runOnUiThread {
            simpleScreen.visibility = View.GONE
            advancedScreen.visibility = View.VISIBLE
            advancedScreen.fullScroll(ScrollView.FOCUS_UP)
        }
    }

    private fun switchToSimple() {
        runOnUiThread {
            advancedScreen.visibility = View.GONE
            simpleScreen.visibility = View.VISIBLE
            simpleScreen.fullScroll(ScrollView.FOCUS_UP)
        }
    }

    private fun switchToSamples() {
        advancedScreen.visibility = View.GONE
        samplesScreen.view.visibility = View.VISIBLE
        samplesScreen.reload()
    }

    private fun closeSamples() {
        samplesScreen.stopPlayback()
        samplesScreen.view.visibility = View.GONE
        advancedScreen.visibility = View.VISIBLE
        updateSampleCount()
    }

    // Runs on the classifier thread after each saved clip.
    private fun onClipSaved() {
        updateSampleCount()
        runOnUiThread { if (samplesScreen.view.visibility == View.VISIBLE) samplesScreen.reload() }
    }

    private fun updateSampleCount() {
        thread {
            val n = try { sampleStore.list().size } catch (_: Exception) { 0 }
            runOnUiThread { samplesBtn.text = "Samples ($n)" }
        }
    }

    // ---------- remote access ----------
    private val remoteListener: (RemoteAccess.State) -> Unit = { state ->
        samplesScreen.showRemote(state)
        if (state is RemoteAccess.State.Failed) toast(state.message)
    }
    private val clipsChangedRemotely: () -> Unit = { onClipSaved() }

    private fun setRemoteAccess(on: Boolean) {
        if (!on) {
            RemoteAccess.stop()
            return
        }
        // Android 17 blocks incoming connections from the local network without this.
        if (android.os.Build.VERSION.SDK_INT >= ANDROID_17 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQUEST_LOCAL_NETWORK)
            return
        }
        val page = try {
            assets.open("web/index.html").use { it.readBytes() }
        } catch (_: java.io.IOException) {
            samplesScreen.showRemote(RemoteAccess.State.Failed("The web page is missing from this build."))
            return
        }
        RemoteAccess.start(this, sampleStore, page)
    }

    // ---------- export ----------
    private fun startExport() {
        thread {
            val labelled = sampleStore.list().count { it.label != null }
            runOnUiThread {
                if (labelled == 0) {
                    toast("Label some clips first: only labelled clips are exported.")
                    return@runOnUiThread
                }
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "bark-samples-$day.zip")
                try {
                    startActivityForResult(pick, REQUEST_EXPORT)
                } catch (_: ActivityNotFoundException) {
                    toast("This device has no file picker to save the export.")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Exporting…")
        thread {
            val msg = try {
                val n = contentResolver.openOutputStream(uri, "wt")?.use { SampleExport.write(sampleStore, it) }
                    ?: throw java.io.IOException("no output stream")
                "Exported $n labelled ${if (n == 1) "clip" else "clips"}."
            } catch (_: Exception) {
                // Leave no half-written zip behind.
                try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) {}
                "Export failed: the file could not be written there."
            }
            runOnUiThread { toast(msg) }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------- response mode toggle ----------
    private fun updateToggleButtons() {
        // selected = accent button; unselected = card background
        for (b in listOf(impToggleBtn, whistleToggleBtn, melodyToggleBtn, gunshotToggleBtn, distractToggleBtn)) {
            val sel = when {
                b === impToggleBtn -> responseMode == ResponseMode.IMPULSE
                b === whistleToggleBtn -> responseMode == ResponseMode.TONE
                b === melodyToggleBtn -> responseMode == ResponseMode.MELODY
                b === gunshotToggleBtn -> responseMode == ResponseMode.GUNSHOT
                else -> responseMode == ResponseMode.DISTRACT
            }
            if (sel) {
                b.setBackgroundResource(R.drawable.btn_bg)
                b.setTextColor(getColor(R.color.btn_text))
            } else {
                b.setBackgroundColor(getColor(R.color.bg_card))
                b.setTextColor(getColor(R.color.text_secondary))
            }
        }
        // Impulse has its own pitch slider; Whistle and Melody both use the
        // whistle frequency; Gunshot and Distract have their own cards so nothing to show.
        val showImpulse = responseMode == ResponseMode.IMPULSE
        val showTone = responseMode == ResponseMode.TONE || responseMode == ResponseMode.MELODY
        freqSliderImpulse.visibility = if (showImpulse) View.VISIBLE else View.GONE
        freqLblImpulse.visibility = if (showImpulse) View.VISIBLE else View.GONE
        freqSliderTone.visibility = if (showTone) View.VISIBLE else View.GONE
        freqLblTone.visibility = if (showTone) View.VISIBLE else View.GONE
    }

    // Dispatch the currently selected response mode (used by the test button
    // and by the sound trigger so all three modes fire identically).
    private fun playResponse() {
        when (responseMode) {
            ResponseMode.IMPULSE -> boom()
            ResponseMode.TONE -> playToneBurst()
            ResponseMode.MELODY -> playMelody()
            ResponseMode.GUNSHOT -> playGunshot()
            ResponseMode.DISTRACT -> playDistract()
        }
    }

    // Short state label used by flash() to reflect the active mode.
    private fun responseModeState(): String = when (responseMode) {
        ResponseMode.IMPULSE -> "impulse"
        ResponseMode.TONE -> "tone"
        ResponseMode.MELODY -> "melody"
        ResponseMode.GUNSHOT -> "gunshot"
        ResponseMode.DISTRACT -> "distract"
    }

    // ---------- listen button sync ----------
    private fun updateListenButtons() {
        listenBtn.text = if (listening) "Stop listening" else "Start listening"
        simpleListenBtn.text = if (listening) "STOP" else "LISTEN"
    }

    // ---------- continuous engine ----------
    private fun startEngine() {
        if (playing) return
        playing = true
        thread {
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                maxOf(minBytes, 4096), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            val buf = ShortArray(1024); var phase = 0.0
            track.play()
            while (playing) {
                val f = currentFreq(); val inc = TAU * f / sampleRate
                for (i in buf.indices) {
                    phase += inc; if (phase > TAU) phase -= TAU
                    buf[i] = (sin(phase) * amp * 0.9 * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
                if (mode == Mode.SWEEP) showFreq(f)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
    }
    private fun stopEngine() { playing = false }
    private fun currentFreq(): Double {
        if (mode == Mode.SWEEP) {
            val el = (SystemClock.elapsedRealtime() - sweepT0) / 1000.0
            val p = (el / sweepDur).coerceIn(0.0, 1.0)
            if (p >= 1.0) runOnUiThread { if (mode == Mode.SWEEP) endSweep() }
            return sweepStart * (sweepEnd / sweepStart).pow(p)
        }
        return toneFreq
    }
    private fun toggleTone() {
        if (playing && mode == Mode.TONE) { stopEngine(); toneBtn.text = "Start tone"; setState("idle") }
        else { if (playing) stopEngine(); mode = Mode.TONE; showFreq(toneFreq); startEngine()
            toneBtn.text = "Stop tone"; sweepBtn.text = "Run sweep"; setState("driving") }
    }
    private fun toggleSweep() {
        if (playing && mode == Mode.SWEEP) endSweep()
        else { if (playing) stopEngine(); mode = Mode.SWEEP; sweepT0 = SystemClock.elapsedRealtime(); startEngine()
            sweepBtn.text = "Stop sweep"; toneBtn.text = "Start tone"; setState("sweeping") }
    }
    private fun endSweep() { stopEngine(); sweepBtn.text = "Run sweep"; setState("idle") }

    // ---------- verify: play a tone & listen with the mic ----------
    /**
     * Plays a 1-second tone at the current whistle frequency while simultaneously
     * recording the mic. Compares the RMS during the tone window against a 500 ms
     * pre-tone baseline to report whether the speaker is actually producing
     * that frequency (i.e. the highs are reaching the driver).
     *
     * Reports: ✓ Detected (dB above baseline) or ✗ Not detected (too quiet).
     */
    private fun verifyHighRange() {
        // require mic permission
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            flash("mic permission needed")
            return
        }
        val freq = toneFreq
        val durSec = 1.0
        val baselineSec = 0.5
        val totalSec = baselineSec + durSec
        runOnUiThread {
            stateLbl.text = "verifying…"; stateLbl.setTextColor(getColor(R.color.state_idle))
            verifyResultLbl.text = "verifying…"
            verifyResultLbl.visibility = View.VISIBLE
            verifyResultLbl.setTextColor(getColor(R.color.state_idle))
        }

        thread {
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                // --- prepare mic ---
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                val totalSamples = (totalSec * sampleRate).toInt()
                val buf = ShortArray(totalSamples)

                // --- prepare tone ---
                val n = (durSec * sampleRate).toInt()
                val s = ShortArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sampleRate
                    val env = if (t < 0.01) t / 0.01 else if (t > durSec - 0.1) (durSec - t) / 0.1 else 1.0
                    s[i] = (sin(TAU * freq * t) * env * amp * Short.MAX_VALUE).toInt().toShort()
                }
                val minOut = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    maxOf(minOut, n * 2), AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
                track.write(s, 0, n)

                // --- record while playing, from a reader thread so we never block
                //     on a single large read (that hangs on some devices after stop). ---
                val cap = 4096            // small chunk per read; always satisfied quickly
                val recorded = intArrayOf(0)  // shared by reference with the reader thread
                val stopFlag = AtomicBoolean(true)
                val reader = thread(start = true, isDaemon = true) {
                    while (stopFlag.get() && recorded[0] < totalSamples) {
                        val want = minOf(cap, totalSamples - recorded[0])
                        val rd = rec.read(buf, recorded[0], want)
                        if (rd > 0) recorded[0] += rd
                    }
                }

                rec.startRecording()
                Thread.sleep((baselineSec * 1000).toLong())   // baseline window
                track.play()                                   // tone window (mic keeps recording)
                Thread.sleep((durSec * 1000).toLong() + 80)

                stopFlag.set(false)
                rec.stop()
                reader.join(2000)   // bounded: never wait forever

                if (recorded[0] < totalSamples / 2) {
                    runOnUiThread { flash("mic read failed") }
                    return@thread
                }

                // --- compute RMS in tone window vs baseline ---
                val baselineEnd = (baselineSec * sampleRate).toInt().coerceAtMost(recorded[0])
                val toneRms = rms(buf, baselineEnd, recorded[0])
                val baseRms = rms(buf, 0, baselineEnd)
                val deltaDb = if (baseRms > 0.001) 20.0 * Math.log10(toneRms / baseRms) else 60.0

                val ok = deltaDb > 3.0
                val msg = if (ok) {
                    "✓ ${freq.toInt()} Hz detected (+${"%.1f".format(deltaDb)} dB)"
                } else {
                    "✗ ${freq.toInt()} Hz not detected (+${"%.1f".format(deltaDb)} dB) — try wired / lower freq"
                }
                runOnUiThread {
                    // show the result on the Whistle card (where the button lives)
                    verifyResultLbl.text = msg
                    verifyResultLbl.visibility = View.VISIBLE
                    verifyResultLbl.setTextColor(getColor(if (ok) R.color.state_active else R.color.state_idle))
                    // also mirror to the simple-screen state label in case the user navigates back
                    stateLbl.text = msg
                    stateLbl.setTextColor(getColor(if (ok) R.color.state_active else R.color.state_idle))
                    handler.postDelayed({
                        if (!playing) {
                            if (listening) setState("listening") else setState("idle")
                        }
                        verifyResultLbl.visibility = View.GONE
                    }, 4000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    verifyResultLbl.text = "✗ ${e.message ?: "error"}"
                    verifyResultLbl.visibility = View.VISIBLE
                    verifyResultLbl.setTextColor(getColor(R.color.state_idle))
                    stateLbl.text = "verify error"
                    stateLbl.setTextColor(getColor(R.color.state_idle))
                }
            } finally {
                try { rec?.stop(); rec?.release() } catch (_: Exception) {}
                try { track?.stop(); track?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun rms(buf: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += buf[i] * buf[i]
        return Math.sqrt(sum / (to - from))
    }

    // ---------- one-shot impulse ----------
    /**
     * Fires [strikeCount] percussive thumps.  Each strike gets its own
     * randomized pitch, decay, level, and inter-strike gap (all scaled by
     * chaos).  The last strike is slightly louder for a punchy finish.
     * 2nd & 3rd harmonics give the thump body on the SRS XV500.
     */
    private fun boom() {
        val r = Random
        val c = chaos
        val n = maxOf(strikeCount, 1)

        data class Strike(val f0: Double, val f1: Double, val dec: Double, val level: Double, val gapMs: Long)
        val strikes = (0 until n).map { i ->
            val last = (i == n - 1)
            Strike(
                f0    = boomF0    * (1.0 - c * 0.30 + r.nextDouble() * c * 0.60),
                f1    = boomF1    * (1.0 - c * 0.30 + r.nextDouble() * c * 0.60),
                dec   = boomDecay * (1.0 - c * 0.30 + r.nextDouble() * c * 0.60),
                level = amp * (1.0 - c * 0.25 + r.nextDouble() * c * 0.50)
                         * (if (last && n > 1) 1.15 else 1.0),
                gapMs = if (i == 0) (30 + r.nextDouble() * 120 * c).toLong()
                        else (gapBase + r.nextDouble() * gapRand * c).toLong()
            )
        }

        thread {
            ownSounds.incrementAndGet()
            try {
                for (sk in strikes) {
                    Thread.sleep(sk.gapMs)

                    val glide = minOf(sk.dec, 0.2)
                    val len = ((sk.dec + 0.05) * sampleRate).toInt()
                    val s = ShortArray(len); var phase = 0.0
                    val maxWave = 1.55   // 1.0 + 0.40 + 0.15
                    for (i in 0 until len) {
                        val t = i.toDouble() / sampleRate
                        val pg = (t / glide).coerceAtMost(1.0)
                        val f = sk.f0 * (sk.f1 / sk.f0).pow(pg)
                        phase += TAU * f / sampleRate
                        val env = if (t < 0.003) t / 0.003 else exp(-4.0 * t / sk.dec)
                        val wave = sin(phase) + 0.40 * sin(2.0 * phase) + 0.15 * sin(3.0 * phase)
                        val raw = (wave * env * sk.level * Short.MAX_VALUE / maxWave).toInt()
                        s[i] = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    val track = AudioTrack(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                        AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                        s.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    track.write(s, 0, s.size); track.play(); showFreq(sk.f0)
                    Thread.sleep(((sk.dec + 0.08) * 1000).toLong())
                    try { track.stop(); track.release() } catch (_: Exception) {}
                }
            } finally {
                // let the last tail fully decay before the mic listens again
                Thread.sleep(150)
                ownSounds.decrementAndGet()
            }
        }
    }

    // ---------- one-shot gunshot (synthesized) ----------
    /**
     * Synthesizes a convincing "gunshot" from two components:
     *  1. **Crack** — a very short broadband noise burst through a falling
     *     low-pass. This is the percussive, wide-band transient that ears
     *     recognize as a shot.
     *  2. **Boom** — a low-frequency sine that starts near [gsBoomFreq] Hz and
     *     glides down, decaying over [gsBoomTail] seconds. This is the
     *     physical pressure wave that drives a membrane / subwoofer cone.
     *
     * [gsIntensity] scales the crack level; [gsChaos] adds per-shot random
     * variation to pitch, level, and tail (0 = identical every time).
     *
     * The XV500 will produce a real close-range "bang" (110-115 dB SPL max)
     * but it's ~30 dB (≈1000×) below a real firearm. The *crack + boom*
     * combination is what carries the physical punch, not the absolute level.
     */
    private fun playGunshot() {
        val r = Random
        val c = gsChaos
        val intensity = gsIntensity
        val boomF0 = gsBoomFreq * (1.0 - c * 0.30 + r.nextDouble() * c * 0.60)
        val boomF1 = boomF0 * 0.55   // glide down ~1 octave
        val tailSec = (gsBoomTail * (1.0 - c * 0.25 + r.nextDouble() * c * 0.50)).coerceAtLeast(0.08)
        val level = amp * intensity

        val crackSec = 0.025   // ~25 ms of broadband noise = the "crack"
        val crackN = (crackSec * sampleRate).toInt()
        val boomN = ((tailSec + 0.1) * sampleRate).toInt()
        val n = maxOf(crackN, boomN)
        val s = ShortArray(n)

        // --- crack: white noise through a simple one-pole low-pass that sweeps
        //         from ~8 kHz to ~1.5 kHz over the burst. A real gunshot has a
        //         very sharp high-frequency onset that rolls off within ~10 ms.
        var lpPrev = 0.0
        val crackAlpha = 0.08  // ~8 kHz cutoff at 44.1 kHz
        val crackAlphaEnd = 0.02 // ~1.5 kHz cutoff
        // Box–Muller spare (Kotlin's Random has no nextGaussian)
        var spare: Double? = null
        fun gaussian(): Double {
            val s = spare
            if (s != null) { spare = null; return s }
            val u1 = 1.0 - r.nextDouble()   // (0,1]
            val u2 = r.nextDouble()
            val mag = Math.sqrt(-2.0 * Math.log(u1))
            spare = mag * sin(TAU * u2)
            return mag * Math.cos(TAU * u2)
        }
        for (i in 0 until crackN) {
            val t = i.toDouble() / sampleRate
            // fast attack (1 ms), then a quick exponential decay
            val env = if (t < 0.001) t / 0.001 else exp(-30.0 * t)
            // sweep the low-pass down during the burst
            val alpha = crackAlpha + (crackAlphaEnd - crackAlpha) * (i / crackN.toDouble())
            val w = gaussian() * 0.5   // gaussian noise, ~-6 dBFS
            lpPrev = lpPrev + alpha * (w - lpPrev)
            s[i] = (lpPrev * env * level * Short.MAX_VALUE).toInt().toShort()
        }

        // --- boom: low-frequency sine glide with a smooth attack + decay
        var phase = 0.0
        for (i in 0 until boomN) {
            val gi = i + 0  // boom starts at sample 0
            val t = i.toDouble() / sampleRate
            // 3 ms attack, then exponential decay over tailSec
            val env = if (t < 0.003) t / 0.003 else exp(-5.0 * t / tailSec)
            // glide from boomF0 down to boomF1
            val p = (t / tailSec).coerceIn(0.0, 1.0)
            val f = boomF0 * (boomF1 / boomF0).pow(p)
            phase += TAU * f / sampleRate
            val wave = sin(phase) + 0.4 * sin(2.0 * phase) + 0.15 * sin(3.0 * phase)
            val idx = gi
            if (idx < n) {
                // add to whatever the crack already wrote (crack is shorter than boom)
                s[idx] = (s[idx].toInt() + (wave * env * level * Short.MAX_VALUE / 1.55).toInt())
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        thread {
            ownSounds.incrementAndGet()
            try {
                val minBytes = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    maxOf(minBytes, s.size * 2), AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(s, 0, s.size)
                track.play()
                showFreq(boomF0)
                Thread.sleep(((tailSec + crackSec) * 1000).toLong() + 100)
                try { track.stop(); track.release() } catch (_: Exception) {}
            } finally {
                Thread.sleep(150)
                ownSounds.decrementAndGet()
            }
        }
    }

    // ---------- one-shot tone burst (whistle) ----------
    /**
     * Fires [whistleRepeats] short whistle blasts. Each repeat is [whistleDurMs]
     * long and, when [whistleJitter] > 0, lands at a slightly different random
     * pitch around the chosen whistle frequency (jitter scales with the same
     * ±30% spread used by the Impulse "Chaos" setting). A short pause separates
     * repeats so they read as distinct toots rather than one long tone.
     */
    private fun playToneBurst() {
        val r = Random
        val j = whistleJitter
        val reps = maxOf(whistleRepeats, 1)
        val durSec = (whistleDurMs / 1000.0).coerceAtLeast(0.05)
        val gapMs = whistleGapMs.toLong()
        val level = amp * whistleVol

        thread {
            ownSounds.incrementAndGet()
            try {
                for (rep in 0 until reps) {
                    // each repeat lands at a slightly different random pitch around the set value
                    val freq = toneFreq * (1.0 - j * 0.30 + r.nextDouble() * j * 0.60)
                    val n = (durSec * sampleRate).toInt()
                    val s = ShortArray(n)
                    for (i in 0 until n) {
                        val t = i.toDouble() / sampleRate
                        val env = if (t < 0.01) t / 0.01 else if (t > durSec - 0.1) (durSec - t) / 0.1 else 1.0
                        s[i] = (sin(TAU * freq * t) * env * level * Short.MAX_VALUE).toInt().toShort()
                    }
                    val minBytes = AudioTrack.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    val track = AudioTrack(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                        AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                        maxOf(minBytes, s.size * 2), AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    track.write(s, 0, s.size)
                    track.play()
                    showFreq(freq)
                    Thread.sleep((durSec * 1000).toLong() + 100)
                    try { track.stop(); track.release() } catch (_: Exception) {}
                    if (rep < reps - 1) Thread.sleep(gapMs)
                }
            } finally {
                Thread.sleep(150)
                ownSounds.decrementAndGet()
            }
        }
    }

    // ---------- one-shot melody (jingle) ----------
    private fun playMelody() {
        val s = Melody.render(toneFreq, amp, sampleRate)
        thread {
            ownSounds.incrementAndGet()
            try {
                PcmPlayer.play(s, sampleRate)
            } catch (t: Throwable) {
                // An audio error (no free player, say) skips this melody instead of killing the app.
                Log.w("MainActivity", "melody did not play", t)
            } finally {
                Thread.sleep(150)
                ownSounds.decrementAndGet()
            }
        }
    }

    // ---------- one-shot distract (run of trills and chirps) ----------
    // A new seed each time, so the order and pitches change on every bark.
    // Rendered on the playback thread: a 10 s run must not hold up the mic or the screen.
    private fun playDistract() {
        val seed = Random.nextLong()
        val seconds = distractSeconds; val low = distractLowHz; val high = distractHighHz; val level = amp
        thread {
            ownSounds.incrementAndGet()
            try {
                PcmPlayer.play(DistractSound.render(seed, seconds, low, high, level, sampleRate), sampleRate)
            } catch (t: Throwable) {
                // An audio error (no free player, say) skips this run instead of killing the app.
                Log.w("MainActivity", "distract run did not play", t)
            } finally {
                Thread.sleep(150)
                ownSounds.decrementAndGet()
            }
        }
    }

    // ---------- keep-alive (prevents app + Bluetooth A2DP sleep) ----------
    /**
     * Periodically plays a very quiet, very short low tone so that the Bluetooth
     * A2DP stream never goes idle and drops. At a ~25 s cadence this stays well
     * under the ~30-60 s A2DP link idle timeout, and at a low enough level that
     * it is inaudible through the SRS XV500 compared to the booms.
     */
    private fun startKeepAlive() {
        if (keepAlive) return
        keepAlive = true
        wakeLock?.acquire(4 * 60 * 60 * 1000L)   // 4 h safety cap in case stopKeepAlive is missed
        thread {
            while (keepAlive) {
                Thread.sleep(KEEP_ALIVE_MS)
                if (!keepAlive) break
                // 50 Hz, 40 ms, ~3% amplitude: keeps A2DP alive, inaudible vs the booms
                playKeepAliveBlip()
            }
            try { wakeLock?.release() } catch (_: Exception) {}
        }
    }

    private fun stopKeepAlive() {
        keepAlive = false
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    // ---------- battery-optimization exemption ----------
    private fun isBatteryIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryLabel() {
        val ok = isBatteryIgnored()
        batteryLbl.text = if (ok) "✓ Background run allowed" else "Background run not yet allowed"
        batteryLbl.setTextColor(getColor(if (ok) R.color.state_active else R.color.state_idle))
        batteryBtn.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: open the app's battery settings screen
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryLabel()
    }

    private fun playKeepAliveBlip() {
        thread {
            val durMs = 40
            val n = durMs * sampleRate / 1000
            val s = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                s[i] = (sin(TAU * 50.0 * t) * 0.03 * Short.MAX_VALUE).toInt().toShort()
            }
            var track: AudioTrack? = null
            try {
                track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    s.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(s, 0, s.size)
                track.play()
                Thread.sleep(durMs.toLong() + 20)
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    // ---------- sound trigger (mic) ----------
    private fun toggleListen() {
        if (listening) {
            listening = false
            listenSession?.set(false)
            updateListenButtons()
            setState("idle")
            runOnUiThread { micLevel.progress = 0 }
            stopKeepAlive()
        }
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListen()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQUEST_LOCAL_NETWORK) {
            if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) setRemoteAccess(true)
            else samplesScreen.showRemote(RemoteAccess.State.Failed("Remote access needs the nearby devices permission."))
            return
        }
        if (rc == 1 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startListen()
        else setState("mic permission denied")
    }
    private fun startListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState("mic permission needed")
            return
        }
        listening = true
        val active = AtomicBoolean(true)
        listenSession?.set(false)
        listenSession = active
        updateListenButtons()
        setState("listening")
        startKeepAlive()
        thread {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            val b = ShortArray(minBuf)

            // Bark check: every buffer goes into the ring; a loud onset waits until its
            // window is recorded, then YAMNet scores it on the scorer thread.
            val ring = AudioRing()
            val scorer = Executors.newSingleThreadExecutor()
            var classifier: YamnetClassifier? = null   // touched only on the scorer thread
            var loadFailed = false                     // one failed load is enough per session
            fun loadedClassifier(): YamnetClassifier {
                classifier?.let { return it }
                check(!loadFailed) { "the bark classifier failed to load" }
                return try {
                    YamnetClassifier(assets).also { classifier = it }
                } catch (t: Throwable) {
                    loadFailed = true
                    throw t
                }
            }
            scorer.execute { try { loadedClassifier() } catch (_: Throwable) {} }
            val score = { window: ShortArray -> loadedClassifier().classify(Resampler.toModelInput(window)) }
            val clips = ClipCapture(ring, { saveClips }, scorer, score, sampleStore, clipWriter, onSaved = { onClipSaved() })
            val barkCheck = BarkCheck(ring, { barkMinScore }, scorer, score, { active.get() }, clips) { respond, lastSound ->
                if (respond) respondToSound()
                showLastSound(lastSound)
            }
            var onsetSample = 0L

            // 1-pole bandpass: 200 Hz highpass + 5 kHz lowpass (bark frequency band)
            val hpAlpha = 1.0 / (1.0 + TAU * 200.0 / sampleRate)
            val lpAlpha = 1.0 - exp(-TAU * 5000.0 / sampleRate)
            var hpPrev = 0.0; var hpPrevY = 0.0; var lpPrev = 0.0
            var emaLevel = 0.0
            val emaDecay = 0.95   // ~1 s running average
            var aboveSince = 0L   // when the current candidate event started above threshold (0 = none)

            try {
                rec.startRecording()
                while (active.get()) {
                    val n = rec.read(b, 0, b.size); if (n <= 0) continue

                    // Apply bandpass filter, compute RMS on filtered signal
                    var sum = 0.0
                    for (i in 0 until n) {
                        val x = b[i].toDouble()
                        // highpass
                        val hpY = hpAlpha * (hpPrevY + x - hpPrev)
                        hpPrev = x; hpPrevY = hpY
                        // lowpass
                        lpPrev = lpPrev + lpAlpha * (hpY - lpPrev)
                        sum += lpPrev * lpPrev
                    }
                    val rawLvl = sqrt(sum / n) / 32767.0
                    val lvl = (rawLvl * micGain * 100.0).coerceAtMost(100.0)

                    // Attack ratio: how much louder is this buffer vs the recent average?
                    emaLevel = emaDecay * emaLevel + (1.0 - emaDecay) * rawLvl
                    val attackRatio = if (emaLevel > 0.001) rawLvl / (emaLevel + 0.001) else 1.0

                    val now = SystemClock.elapsedRealtime()
                    val ownSound = ownSounds.get() > 0
                    ring.append(b, n, ownSound)
                    barkCheck.afterAppend()
                    clips.afterAppend()
                    if (ownSound) {
                        // ignore our own output so the trigger can't chain on itself
                        runOnUiThread { micLevel.progress = 0 }
                        continue
                    }
                    runOnUiThread { micLevel.progress = lvl.toInt() }

                    // At 0% selectivity: minAttack = 1.0 (pure level).  At 100%: 3.0 (sharp transient).
                    val minAttack = 1.0 + barkSelect * 2.0
                    val releaseLvl = threshold * 0.5   // hysteresis: dips above this don't cancel the event
                    if (aboveSince == 0L) {
                        // not in an event: start one only on a sharp onset above the threshold
                        if (lvl >= threshold && attackRatio >= minAttack) {
                            aboveSince = now
                            onsetSample = ring.written - n
                        }
                    } else if (lvl < releaseLvl) {
                        // fell well below threshold: discard this candidate
                        aboveSince = 0L
                    }

                    if (aboveSince != 0L && (now - aboveSince) >= sustainMs && now > suppressUntil) {
                        aboveSince = 0L
                        barkCheck.onset(onsetSample)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setState("mic error") }
            } finally {
                try { rec.stop(); rec.release() } catch (_: Exception) {}
                // Runs after any window still queued, on the thread that owns the model.
                scorer.execute {
                    try { classifier?.close() } catch (_: Throwable) {}
                    classifier = null
                }
                scorer.shutdown()
            }
        }
    }

    // Plays the response and holds off new candidates until it and the cooldown end.
    private fun respondToSound() {
        val now = SystemClock.elapsedRealtime()
        val impulseMs = (maxOf(strikeCount, 1) * (gapBase + gapRand + boomDecay * 1500 + 200)).toLong()
        val seqMs = ResponseTiming.lengthMs(responseMode, impulseMs, distractSeconds)
        suppressUntil = now + seqMs + cooldownMs
        triggerCount++
        val count = triggerCount
        runOnUiThread { countLbl.text = "Triggers: $count" }
        playResponse()
    }

    private fun showLastSound(text: String) {
        runOnUiThread { lastSoundLbl.text = text }
    }

    override fun onDestroy() {
        super.onDestroy(); playing = false; listening = false
        samplesScreen.close()
        if (RemoteAccess.listener === remoteListener) RemoteAccess.listener = null
        if (RemoteAccess.onClipsChanged === clipsChangedRemotely) RemoteAccess.onClipsChanged = null
        // A rotation keeps the server; leaving the app turns it off.
        if (isFinishing) RemoteAccess.stop()
        listenSession?.set(false)
        stopKeepAlive()
    }

    // One store and one clip writer per process: a rotation makes a new
    // Activity, and two stores would each keep their own lock and list.
    private companion object {
        private var sharedStore: SampleStore? = null
        private val clipWriter = Executors.newSingleThreadExecutor()   // clip writes, off the classifier thread
    }
}
