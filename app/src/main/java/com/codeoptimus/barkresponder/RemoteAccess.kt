package com.codeoptimus.barkresponder

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * The Remote access switch: one [RemoteServer] per process, so a rotation
 * keeps the server it had and a new process starts with it off. While it
 * runs, it holds a partial wake lock and a Wi-Fi lock so it answers with the
 * screen off; [RemoteServer]'s stop callback releases both, whatever stopped it.
 */
object RemoteAccess {
    sealed class State {
        data object Off : State()
        class On(val urls: List<String>) : State()
        class Failed(val message: String) : State()
    }

    /** Called on the main thread with every change. */
    var listener: ((State) -> Unit)? = null
    /** Called on a request thread after the page labels or deletes a clip. */
    @Volatile var onClipsChanged: (() -> Unit)? = null

    var state: State = State.Off
        private set

    private val main = Handler(Looper.getMainLooper())
    private var server: RemoteServer? = null
    private var generation = 0L
    // The start the switch still wants, or 0 once it was turned off.
    private var wanted = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Starts the server off the main thread; the result arrives through [listener]. */
    fun start(context: Context, store: SampleStore, page: ByteArray) {
        val app = context.applicationContext
        val gen = synchronized(this) {
            if (server != null) return
            wanted = ++generation
            generation
        }
        thread(name = "remote-start") {
            val urls = lanAddresses().map { "http://$it:${RemoteServer.PORT}" }
            synchronized(this) {
                // A stop, or a newer start, came while this one looked up the address.
                if (wanted != gen || server != null) return@thread
                if (urls.isEmpty()) {
                    wanted = 0
                    publish(State.Failed("No Wi-Fi address. Connect the tablet to Wi-Fi first."))
                    return@thread
                }
                var started: RemoteServer? = null
                val s = RemoteServer(store, page, onClipsChanged = { onClipsChanged?.invoke() }, onStopped = { stopped(started) })
                // Set before start: a server that ends at once calls stopped(), which waits for this lock.
                started = s
                server = s
                try {
                    s.start(RemoteServer.PORT)
                } catch (_: IOException) {
                    server = null
                    wanted = 0
                    publish(State.Failed("Port ${RemoteServer.PORT} is in use by another app, so Remote access is off."))
                    return@thread
                }
                acquireLocks(app)
                // Under the lock, so a stop's Off always comes after this On.
                publish(State.On(urls))
            }
        }
    }

    /** Stops the server, or a start still under way. */
    fun stop() {
        val s = synchronized(this) {
            wanted = 0
            server
        }
        s?.stop()
    }

    // The server's one stop callback: every way it ends comes through here.
    private fun stopped(s: RemoteServer?) {
        synchronized(this) {
            if (s == null || server !== s) return
            server = null
            wanted = 0
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
            try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
            publish(State.Off)
        }
    }

    /** How many of the two locks are held now: 2 while on, 0 once off. */
    fun heldLocks(): Int = synchronized(this) { listOf(wakeLock?.isHeld, wifiLock?.isHeld).count { it == true } }

    private fun acquireLocks(app: Context) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barkresponder:remote").apply {
            setReferenceCounted(false)
            acquire(WAKE_CAP_MS)
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // HIGH_PERF is the mode that holds with the screen off on API 24; API 34+ maps it to low latency.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "barkresponder:remote").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun publish(s: State) {
        main.post {
            state = s
            listener?.invoke(s)
        }
    }

    /** The tablet's private IPv4 addresses, the ones a computer on the same Wi-Fi can reach. */
    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: IOException) {
        emptyList()
    }

    // A cap in case every stop path is missed; the server stays on past it, the CPU may then sleep.
    private const val WAKE_CAP_MS = 24 * 60 * 60 * 1000L
}
