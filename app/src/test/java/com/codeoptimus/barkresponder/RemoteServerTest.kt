package com.codeoptimus.barkresponder

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.zip.ZipInputStream

class RemoteServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir: File by lazy { tmp.newFolder("samples") }
    private val store by lazy { SampleStore(dir) }
    private val page = File("src/main/assets/web/index.html").readBytes()
    private var server: RemoteServer? = null
    private var changes = 0

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun start(limits: RemoteServer.Limits = RemoteServer.Limits()): RemoteServer =
        RemoteServer(store, page, onClipsChanged = { changes++ }, limits = limits).also {
            it.start(0, InetAddress.getLoopbackAddress())
            server = it
        }

    private fun meta(at: Long, bark: Float = 0.5f, responded: Boolean = false) = ClipMeta(
        recordedAt = at,
        scores = FloatArray(521) { if (it == YamnetClassifier.BARK) bark else 0f },
        responded = responded,
        minScoreSetting = 0.4f,
    )

    private fun save(at: Long, bark: Float = 0.5f, responded: Boolean = false) =
        store.save(FloatArray(320) { (at % 7) / 10f }, meta(at, bark, responded))!!

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    private fun readResponse(input: InputStream): Response {
        val all = input.readBytes()
        val split = (0 until all.size - 3).first {
            all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() &&
                all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte()
        }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head[0].split(" ")[1].toInt()
        val headers = head.drop(1).associate { line ->
            val i = line.indexOf(':')
            line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }
        val rest = all.copyOfRange(split + 4, all.size)
        return Response(status, headers, if (headers["transfer-encoding"] == "chunked") dechunk(rest) else rest)
    }

    // Fails unless the stream ends with the last, empty chunk.
    private fun dechunk(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (true) {
            val eol = (i until bytes.size - 1).first { bytes[it] == '\r'.code.toByte() && bytes[it + 1] == '\n'.code.toByte() }
            val size = String(bytes, i, eol - i, Charsets.ISO_8859_1).toInt(16)
            i = eol + 2
            if (size == 0) return out.toByteArray()
            out.write(bytes, i, size)
            i += size + 2
        }
    }

    private fun send(raw: ByteArray, port: Int = server!!.port): Response =
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(raw)
            s.getOutputStream().flush()
            readResponse(s.getInputStream())
        }

    private fun request(method: String, path: String, body: String? = null, extra: String = ""): Response {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:${server!!.port}\r\n")
            append(extra)
            if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        return send(head.toByteArray(Charsets.ISO_8859_1) + (bytes ?: ByteArray(0)))
    }

    private fun clipsJson(): List<Map<*, *>> {
        val r = request("GET", "/api/clips")
        assertEquals(200, r.status)
        return ((MiniJson.parse(r.text) as Map<*, *>)["clips"] as List<*>).map { it as Map<*, *> }
    }

    private fun unzip(bytes: ByteArray): Map<String, List<Byte>> {
        val entries = linkedMapOf<String, List<Byte>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes().toList()
            }
        }
        return entries
    }

    @Test
    fun `serves the page and lists each clip newest first with its fields`() {
        val older = save(1_760_000_000_000, bark = 0.04f)
        val newer = save(1_760_000_100_000, bark = 0.81f, responded = true)
        store.setLabel(newer.id, ClipLabel.BARK)
        start()

        val home = request("GET", "/")
        assertEquals(200, home.status)
        assertTrue(home.headers["content-type"]!!.startsWith("text/html"))
        assertArrayEquals(page, home.body)

        val clips = clipsJson()
        assertEquals(listOf(newer.id.toString(), older.id.toString()), clips.map { it["id"] })
        assertEquals(1_760_000_100_000.0, clips[0]["recordedAt"])
        assertEquals(0.81, clips[0]["barkScore"] as Double, 1e-6)
        assertEquals(true, clips[0]["responded"])
        assertEquals("bark", clips[0]["label"])
        assertEquals(false, clips[1]["responded"])
        assertNull(clips[1]["label"])
    }

    @Test
    fun `returns 404 for ids that are not digits, too long or not saved, and the exact WAV for a saved one`() {
        val clip = save(1_760_000_000_000)
        File(dir.parentFile, "shared_prefs").apply { mkdirs(); File(this, "x.wav").writeText("secret") }
        start()

        for (path in listOf(
            "/api/clips/..%2F..%2Fshared_prefs.wav",
            "/api/clips/../shared_prefs/x.wav",
            "/api/clips/1.json",
            "/api/clips/${clip.id}.json",
            "/api/clips/1234567890123456789.wav",
            "/api/clips/42.wav",
        )) {
            val r = request("GET", path)
            assertEquals(path, 404, r.status)
            assertFalse(path, r.text.contains("secret"))
        }
        // A WAV on disk that the store does not list stays out of reach.
        File(dir, "99.wav").writeBytes(store.wavFile(clip.id).readBytes())
        assertEquals(404, request("GET", "/api/clips/99.wav").status)
        assertEquals(404, request("POST", "/api/clips/42/label", """{"label":"bark"}""").status)
        assertEquals(404, request("DELETE", "/api/clips/..%2F${clip.id}").status)

        val wav = request("GET", "/api/clips/${clip.id}.wav")
        assertEquals(200, wav.status)
        assertEquals("audio/wav", wav.headers["content-type"])
        assertArrayEquals(store.wavFile(clip.id).readBytes(), wav.body)
    }

    @Test
    fun `sets and clears a label and leaves other clips alone`() {
        val clip = save(1_760_000_000_000)
        val other = save(1_760_000_100_000)
        store.setLabel(other.id, ClipLabel.NOT_BARK)
        start()

        assertEquals(200, request("POST", "/api/clips/${clip.id}/label", """{"label":"bark"}""").status)
        var reread = SampleStore(dir).list().associateBy { it.id }
        assertEquals(ClipLabel.BARK, reread.getValue(clip.id).label)
        assertEquals(ClipLabel.NOT_BARK, reread.getValue(other.id).label)
        assertEquals(1, changes)

        assertEquals(200, request("POST", "/api/clips/${clip.id}/label", """{"label":null}""").status)
        reread = SampleStore(dir).list().associateBy { it.id }
        assertNull(reread.getValue(clip.id).label)
        assertEquals(ClipLabel.NOT_BARK, reread.getValue(other.id).label)
    }

    @Test
    fun `refuses a label that is not bark, not_bark or null`() {
        val clip = save(1_760_000_000_000)
        store.setLabel(clip.id, ClipLabel.BARK)
        start()

        for (body in listOf("""{"label":"cat"}""", """{}""", """["bark"]""", "bark", """{"label":"bark"""")) {
            assertEquals(body, 400, request("POST", "/api/clips/${clip.id}/label", body).status)
        }
        assertEquals(ClipLabel.BARK, SampleStore(dir).list().single().label)
    }

    @Test
    fun `deletes the WAV and sidecar of that clip only`() {
        val clip = save(1_760_000_000_000)
        val other = save(1_760_000_100_000)
        start()

        assertEquals(204, request("DELETE", "/api/clips/${clip.id}").status)

        assertEquals(setOf("${other.id}.wav", "${other.id}.json"), dir.list()!!.toSet())
        assertEquals(listOf(other.id.toString()), clipsJson().map { it["id"] })
        assertEquals(1, changes)
    }

    @Test
    fun `the export holds what the tablet's Export button writes`() {
        val bark = save(1_760_000_000_000)
        val notBark = save(1_760_000_100_000)
        save(1_760_000_200_000)
        store.setLabel(bark.id, ClipLabel.BARK)
        store.setLabel(notBark.id, ClipLabel.NOT_BARK)
        start()

        val r = request("GET", "/api/export.zip")
        assertEquals(200, r.status)
        assertEquals("application/zip", r.headers["content-type"])
        // Chunked, so a zip cut short lacks the last chunk and the browser reports it.
        assertEquals("chunked", r.headers["transfer-encoding"])
        val expected = ByteArrayOutputStream().also { SampleExport.write(store, it) }.toByteArray()
        val got = unzip(r.body)
        assertEquals(unzip(expected), got)
        assertEquals(
            setOf("labels.csv", "clips/${bark.id}.wav", "clips/${bark.id}.json",
                "clips/${notBark.id}.wav", "clips/${notBark.id}.json"),
            got.keys,
        )
    }

    @Test
    fun `refuses headers over 8 KB and a label body over 256 bytes`() {
        val clip = save(1_760_000_000_000)
        store.setLabel(clip.id, ClipLabel.BARK)
        start()

        val big = request("GET", "/api/clips", extra = "X-Filler: ${"a".repeat(9_000)}\r\n")
        assertEquals(431, big.status)

        val padded = """{"label":null,"pad":"${"a".repeat(1_000)}"}"""
        assertEquals(413, request("POST", "/api/clips/${clip.id}/label", padded).status)
        assertEquals(ClipLabel.BARK, SampleStore(dir).list().single().label)

        val chunked = "POST /api/clips/${clip.id}/label HTTP/1.1\r\nHost: x\r\n" +
            "Transfer-Encoding: chunked\r\n\r\nd\r\n{\"label\":null}\r\n0\r\n\r\n"
        assertEquals(411, send(chunked.toByteArray()).status)
        assertEquals(ClipLabel.BARK, SampleStore(dir).list().single().label)
    }

    @Test
    fun `closes a client that sends nothing, or one byte at a time, at the request deadline`() {
        val srv = start(RemoteServer.Limits(requestMs = 300))

        Socket(InetAddress.getLoopbackAddress(), srv.port).use { s ->
            s.soTimeout = 3_000
            val began = System.nanoTime()
            val r = readResponse(s.getInputStream())
            assertEquals(408, r.status)
            assertTrue((System.nanoTime() - began) / 1_000_000 < 2_000)
        }

        Socket(InetAddress.getLoopbackAddress(), srv.port).use { s ->
            s.soTimeout = 3_000
            val began = System.nanoTime()
            val out = s.getOutputStream()
            val trickle = Thread {
                try {
                    for (b in "GET /api/clips HTTP/1.1\r\nX-Slow: ".toByteArray()) {
                        out.write(b.toInt()); out.flush(); Thread.sleep(50)
                    }
                    while (true) { out.write('a'.code); out.flush(); Thread.sleep(50) }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            val r = readResponse(s.getInputStream())
            assertEquals(408, r.status)
            assertTrue((System.nanoTime() - began) / 1_000_000 < 2_000)
            trickle.interrupt()
        }
    }

    @Test
    fun `closes a client that keeps sending after its response`() {
        val srv = start()
        Socket(InetAddress.getLoopbackAddress(), srv.port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            out.write("GET /api/clips HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            out.flush()
            assertEquals(200, readResponse(s.getInputStream()).status)
            // The server stops reading within its drain time, so a write soon fails on the closed socket.
            val began = System.nanoTime()
            val closed = runCatching {
                while ((System.nanoTime() - began) / 1_000_000 < 4_000) {
                    out.write('a'.code); out.flush(); Thread.sleep(50)
                }
            }.isFailure
            assertTrue("the server kept reading", closed)
        }
    }

    @Test
    fun `ends a connection at its life cap`() {
        val srv = start(RemoteServer.Limits(requestMs = 10_000, connectionMs = 300))
        Socket(InetAddress.getLoopbackAddress(), srv.port).use { s ->
            s.soTimeout = 5_000
            val began = System.nanoTime()
            val eof = runCatching { s.getInputStream().read() }.getOrDefault(-1)
            assertEquals(-1, eof)
            assertTrue((System.nanoTime() - began) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun `a fifth open connection gets 503, and requests answer again once the four close`() {
        val srv = start(RemoteServer.Limits(requestMs = 5_000))
        val held = (1..4).map { Socket(InetAddress.getLoopbackAddress(), srv.port) }
        try {
            Socket(InetAddress.getLoopbackAddress(), srv.port).use { s ->
                s.soTimeout = 3_000
                assertEquals(503, readResponse(s.getInputStream()).status)
            }
        } finally {
            held.forEach { it.close() }
        }

        val deadline = System.currentTimeMillis() + 3_000
        var status = 0
        while (System.currentTimeMillis() < deadline) {
            status = request("GET", "/api/clips").status
            if (status == 200) break
            Thread.sleep(20)
        }
        assertEquals(200, status)
    }

    @Test
    fun `refuses other methods and paths, and sends no cross-site headers`() {
        start()

        val options = request("OPTIONS", "/api/clips")
        assertEquals(405, options.status)
        assertTrue(options.headers.keys.none { it.startsWith("access-control-") })
        assertEquals(404, request("GET", "/etc/passwd").status)
        assertEquals(400, send("GET http://evil.example/ HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()).status)
        assertEquals(400, send("nonsense\r\n\r\n".toByteArray()).status)
    }

    @Test
    fun `stop closes the port and reports it once`() {
        var stops = 0
        val srv = RemoteServer(store, page, onStopped = { stops++ })
        srv.start(0, InetAddress.getLoopbackAddress())
        val port = srv.port

        srv.stop()
        srv.stop()

        assertFalse(srv.isRunning)
        assertEquals(1, stops)
        val refused = runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure
        assertTrue(refused)
    }
}
