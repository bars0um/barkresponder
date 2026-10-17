package com.codeoptimus.barkresponder

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Remote access web server: the page plus a small JSON API over the saved
 * clips, on a plain socket. It speaks the HTTP/1.1 subset a browser needs:
 * one request per connection, `Content-Length` bodies, `Connection: close`.
 *
 * Anyone who can reach the port can use it; the owner chose no PIN or origin
 * checks (#11). The limits keep one stuck or slow client from holding the
 * server: [Limits.maxConnections] at once, [Limits.requestMs] to send the
 * whole request, capped header and body sizes, and [Limits.connectionMs] for
 * a connection's whole life. Clip ids reach the store only as numbers it has
 * listed, never as file names.
 *
 * Plain Kotlin, so JVM tests run it on the loopback address.
 */
class RemoteServer(
    private val store: SampleStore,
    private val page: ByteArray,
    private val onClipsChanged: () -> Unit = {},
    /** Runs once, on whichever thread called [stop]. */
    private val onStopped: () -> Unit = {},
    private val limits: Limits = Limits(),
) {
    class Limits(
        val requestMs: Long = 10_000,
        val maxConnections: Int = 4,
        val maxHeaderBytes: Int = 8 * 1024,
        val maxBodyBytes: Int = 256,
        /** Ends a connection still open this long, such as a download nobody reads. */
        val connectionMs: Long = 10 * 60_000,
    )

    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "remote-request").apply { isDaemon = true } }
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remote-timer").apply { isDaemon = true } }

    val isRunning get() = running.get()

    /** The bound port; read after [start]. */
    val port get() = socket?.localPort ?: -1

    /** Throws [IOException] when the port is taken; [stop] is not called then. */
    fun start(port: Int, bindAddress: InetAddress? = null) {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress(bindAddress, port), 16)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        running.set(true)
        Thread({ acceptLoop(s) }, "remote-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close() } catch (_: IOException) {}
        synchronized(open) { open.toList() }.forEach { closeQuietly(it) }
        workers.shutdownNow()
        timers.shutdownNow()
        onStopped()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val client = try { s.accept() } catch (_: IOException) { break }
            if (active.incrementAndGet() > limits.maxConnections) {
                active.decrementAndGet()
                try {
                    respond(client.getOutputStream(), 503, "Busy: too many connections. Try again.")
                } catch (_: IOException) {
                }
                finish(client, drainMs = 100)
                continue
            }
            open.add(client)
            // stop() may have closed the open sockets just before this one joined them.
            if (!running.get()) closeQuietly(client)
            try {
                val cap = timers.schedule({ closeQuietly(client) }, limits.connectionMs, TimeUnit.MILLISECONDS)
                workers.execute {
                    try {
                        handle(client)
                    } finally {
                        finish(client, drainMs = 500)
                        cap.cancel(false)
                        open.remove(client)
                        active.decrementAndGet()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                open.remove(client)
                active.decrementAndGet()
                closeQuietly(client) // stopping
            }
        }
        stop() // a failed accept ends the server the same way the switch does
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun handle(client: Socket) {
        val out = try { client.getOutputStream() } catch (_: IOException) { return }
        val request = try {
            read(client)
        } catch (e: HttpError) {
            try { respond(out, e.status, e.message ?: "") } catch (_: IOException) {}
            return
        } catch (_: IOException) {
            return
        }
        try {
            route(request, out)
        } catch (e: HttpError) {
            respond(out, e.status, e.message ?: "")
        } catch (_: SocketException) {
            // The client left.
        } catch (_: IOException) {
            try { respond(out, 500, "The tablet could not do that.") } catch (_: IOException) {}
        }
    }

    // Reads the request line, headers and body within one deadline for the whole request.
    private fun read(client: Socket): Request {
        val deadline = System.nanoTime() + limits.requestMs * 1_000_000
        val input = java.io.BufferedInputStream(client.getInputStream())
        val head = java.io.ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = readByte(client, input, deadline)
            if (b < 0) throw IOException("closed")
            if (head.size() >= limits.maxHeaderBytes) throw HttpError(431, "Headers too large.")
            head.write(b)
            tail = (tail shl 8) or b
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n").split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/")) {
            throw HttpError(400, "Bad request.")
        }
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) throw HttpError(400, "Bad header.")
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = readBody(client, input, headers, deadline)
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }

    private fun readBody(client: Socket, input: InputStream, headers: Map<String, String>, deadline: Long): ByteArray {
        if ("transfer-encoding" in headers) throw HttpError(411, "Send a Content-Length.")
        val declared = headers["content-length"] ?: return ByteArray(0)
        val length = declared.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "Bad Content-Length.")
        if (length > limits.maxBodyBytes) throw HttpError(413, "Body too large.")
        val body = ByteArray(length.toInt())
        var got = 0
        while (got < body.size) {
            setTimeout(client, deadline)
            val n = try { input.read(body, got, body.size - got) } catch (_: SocketTimeoutException) {
                throw HttpError(408, "Request took too long.")
            }
            if (n < 0) throw IOException("closed")
            got += n
        }
        return body
    }

    private fun readByte(client: Socket, input: InputStream, deadline: Long): Int {
        setTimeout(client, deadline)
        return try { input.read() } catch (_: SocketTimeoutException) { throw HttpError(408, "Request took too long.") }
    }

    private fun setTimeout(client: Socket, deadline: Long) {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) throw HttpError(408, "Request took too long.")
        client.soTimeout = left.toInt().coerceAtLeast(1)
    }

    private fun route(r: Request, out: OutputStream) {
        val wav = WAV.matchEntire(r.path)
        val label = LABEL.matchEntire(r.path)
        val clip = CLIP.matchEntire(r.path)
        when {
            r.path == "/" -> {
                allow(r, "GET")
                respond(out, 200, "text/html; charset=utf-8", page)
            }
            r.path == "/api/clips" -> {
                allow(r, "GET")
                respond(out, 200, JSON, clipsJson().toByteArray(Charsets.UTF_8))
            }
            r.path == "/api/export.zip" -> {
                allow(r, "GET")
                // Chunked, so a browser can tell a zip cut short by an error from a whole one.
                writeHead(out, 200, "application/zip", null,
                    "Content-Disposition: attachment; filename=\"bark-samples.zip\"\r\nTransfer-Encoding: chunked\r\n")
                val chunked = ChunkedOutput(out)
                try {
                    val buffered = java.io.BufferedOutputStream(chunked, 64 * 1024)
                    SampleExport.write(store, buffered)
                    buffered.flush()
                } catch (_: IOException) {
                    return // the connection closes without the last chunk
                }
                chunked.finish()
            }
            wav != null -> {
                allow(r, "GET")
                val id = savedId(wav.groupValues[1])
                val bytes = try { store.wavFile(id).readBytes() } catch (_: IOException) { throw HttpError(404, "No such clip.") }
                respond(out, 200, "audio/wav", bytes)
            }
            label != null -> {
                allow(r, "POST")
                val id = savedId(label.groupValues[1])
                val next = parseLabel(r.body)
                if (!store.setLabel(id, next)) throw HttpError(404, "No such clip.")
                onClipsChanged()
                respond(out, 200, JSON, "{}".toByteArray())
            }
            clip != null -> {
                allow(r, "DELETE")
                store.delete(savedId(clip.groupValues[1]))
                onClipsChanged()
                writeHead(out, 204, null, 0)
                out.flush()
            }
            else -> throw HttpError(404, "Not found.")
        }
    }

    private fun allow(r: Request, method: String) {
        if (r.method != method) throw HttpError(405, "Method not allowed.")
    }

    // An id the store has listed; anything else is 404 before any file is named.
    private fun savedId(text: String): Long {
        val id = text.toLongOrNull() ?: throw HttpError(404, "No such clip.")
        if (store.list().none { it.id == id }) throw HttpError(404, "No such clip.")
        return id
    }

    private fun parseLabel(body: ByteArray): ClipLabel? {
        val m = try {
            MiniJson.parse(String(body, Charsets.UTF_8)) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw HttpError(400, "Send {\"label\": \"bark\", \"not_bark\" or null}.")
        if (!m.containsKey("label")) throw HttpError(400, "Send {\"label\": \"bark\", \"not_bark\" or null}.")
        val v = m["label"] ?: return null
        return ClipLabel.of(v as? String) ?: throw HttpError(400, "Send {\"label\": \"bark\", \"not_bark\" or null}.")
    }

    private fun clipsJson(): String = buildString {
        append("{\"clips\":[")
        store.list().forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"id\":").append(MiniJson.quote(c.id.toString()))
            append(",\"recordedAt\":").append(c.recordedAt)
            append(",\"barkScore\":").append(MiniJson.number(c.barkScore))
            append(",\"responded\":").append(c.responded)
            append(",\"label\":").append(c.label?.let { MiniJson.quote(it.json) } ?: "null")
            append('}')
        }
        append("]}")
    }

    private fun respond(out: OutputStream, status: Int, message: String) =
        respond(out, status, "text/plain; charset=utf-8", (message + "\n").toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        writeHead(out, status, type, body.size.toLong())
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, type: String?, length: Long?, extra: String = "") {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "Error").append("\r\n")
            if (type != null) append("Content-Type: ").append(type).append("\r\n")
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append(extra)
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    // Lets the client read the response before the socket goes, then closes it.
    // [drainMs] bounds the whole drain, so a client that keeps sending can't hold it.
    private fun finish(client: Socket, drainMs: Int) {
        try {
            client.shutdownOutput()
            val input = client.getInputStream()
            val drain = ByteArray(4096)
            var left = 64 * 1024
            val deadline = System.nanoTime() + drainMs * 1_000_000L
            while (left > 0) {
                val wait = (deadline - System.nanoTime()) / 1_000_000
                if (wait <= 0) break
                client.soTimeout = wait.toInt().coerceAtLeast(1)
                val n = input.read(drain)
                if (n < 0) break
                left -= n
            }
        } catch (_: IOException) {
        }
        closeQuietly(client)
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: IOException) {}
    }

    private class ChunkedOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(b, off, len)
            out.write(CRLF)
        }

        fun finish() {
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        /** The port the tablet shows; an app may hold it, and the switch says so. */
        const val PORT = 8642
        private const val JSON = "application/json"
        private val WAV = Regex("""/api/clips/(\d{1,18})\.wav""")
        private val LABEL = Regex("""/api/clips/(\d{1,18})/label""")
        private val CLIP = Regex("""/api/clips/(\d{1,18})""")
        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 400 to "Bad Request", 404 to "Not Found",
            405 to "Method Not Allowed", 408 to "Request Timeout", 411 to "Length Required",
            413 to "Content Too Large", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
            503 to "Service Unavailable",
        )
    }
}
