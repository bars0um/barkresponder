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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** The server on the tablet's own Wi-Fi address, with its locks. */
@RunWith(AndroidJUnit4::class)
class RemoteAccessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "remote-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        RemoteAccess.stop()
        dir.deleteRecursively()
    }

    private fun report(line: String) =
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("measurement", line) })

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun servesTheClipListOnTheWifiAddressAndReleasesItsLocksWhenOff() {
        val store = SampleStore(dir)
        val clip = store.save(FloatArray(16_000), ClipMeta(1_760_000_000_000, FloatArray(521), false, 0.4f))!!
        val page = context.assets.open("web/index.html").use { it.readBytes() }

        RemoteAccess.start(context, store, page)
        waitFor("the server") { RemoteAccess.state is RemoteAccess.State.On }
        val url = (RemoteAccess.state as RemoteAccess.State.On).urls.first()
        report("remote url=$url")
        assertEquals(2, RemoteAccess.heldLocks())

        val conn = URL("$url/api/clips").openConnection() as HttpURLConnection
        val body = try {
            assertEquals(200, conn.responseCode)
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        assertTrue(body, body.contains("\"id\":\"${clip.id}\""))

        RemoteAccess.stop()
        waitFor("the switch to read off") { RemoteAccess.state === RemoteAccess.State.Off }
        assertEquals(0, RemoteAccess.heldLocks())
        val host = url.removePrefix("http://").substringBefore(':')
        val refused = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, RemoteServer.PORT), 2_000) }
        }.isFailure
        assertTrue("the port still answers", refused)
    }

    @Test
    fun aStopWhileStartingLeavesTheServerOffWithNoLocks() {
        val page = context.assets.open("web/index.html").use { it.readBytes() }

        RemoteAccess.start(context, SampleStore(dir), page)
        RemoteAccess.stop()
        Thread.sleep(1_500)

        assertEquals(0, RemoteAccess.heldLocks())
        assertTrue(RemoteAccess.state !is RemoteAccess.State.On)
        val refused = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", RemoteServer.PORT), 2_000) }
        }.isFailure
        assertTrue("the port answers after the stop", refused)
    }
}
