package org.ohack.flirone.spatial

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP server:
 *   GET /        tiny HTML page embedding the stream
 *   GET /stream  multipart/x-mixed-replace MJPEG of the colorized thermal view
 *   GET /status  latest device status JSON
 * View from a Mac: http://<quest-ip>:8080  (or mac_viewer.py)
 */
class MjpegServer(private val port: Int = 8080) {
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var latestStatus: String = "{}"
    @Volatile var latestThermalRaw: ByteArray? = null
    @Volatile var renderer: ThermalRenderer? = null
    /** Set by the activity: true when there is anything to export. */
    @Volatile var hasExport: (() -> Boolean)? = null
    /** Streams the export ZIP to the socket ON THE HTTP THREAD — building the
     *  whole ZIP in RAM on the main thread froze the app for seconds. */
    @Volatile var exportTo: ((OutputStream) -> Unit)? = null
    /** Debug: local feature-usage counters (see Analytics.kt). */
    @Volatile var statsJson: (() -> String)? = null

    // active /stream watchers: the activity skips JPEG encoding when zero
    private val streamClients = java.util.concurrent.atomic.AtomicInteger(0)
    val wantsFrames: Boolean get() = streamClients.get() > 0

    // access gating (release builds: user opt-in + per-session key; debug: open)
    @Volatile var sharingEnabled = false
    @Volatile var requireKey = true
    @Volatile var sessionKey = ""
    @Volatile var debugEndpoints = false   // /log and /raw exist only in debug
    private val logLines = ArrayDeque<String>()

    fun log(line: String) = synchronized(logLines) {
        logLines.addLast("${System.currentTimeMillis() % 100000} $line")
        while (logLines.size > 300) logLines.removeFirst()
    }
    private fun logText() = synchronized(logLines) { logLines.joinToString("\n") }
    @Volatile private var running = false
    private var serverThread: Thread? = null
    private var socket: ServerSocket? = null

    fun start() {
        running = true
        serverThread = Thread({
            try {
                socket = ServerSocket(port)
                if (!running) { socket?.close(); return@Thread }   // stop() raced startup
                while (running) {
                    val client = socket!!.accept()
                    Thread({ handle(client.getInputStream().bufferedReader(),
                                    client.getOutputStream()) }, "mjpeg-client").start()
                }
            } catch (e: Exception) {
                if (running) Log.w("MjpegServer", "server died: $e")
            }
        }, "mjpeg-server").also { it.start() }
    }

    fun stop() {
        running = false
        socket?.close()
    }

    private fun handle(reader: java.io.BufferedReader, out: OutputStream) {
        try {
            val request = reader.readLine() ?: return
            while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
            if (!sharingEnabled || (requireKey && !request.contains("key=$sessionKey"))) {
                out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n" +
                           "Connection: close\r\n\r\n" +
                           "Wi-Fi sharing is off. Enable 📡 Share in ThermaSpace's " +
                           "Tools menu and use the link it shows.\n")
                    .toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                return
            }
            when {
                request.contains("/stream") -> streamMjpeg(out)
                request.contains("/status") -> respond(out, "application/json", latestStatus.toByteArray())
                debugEndpoints && request.contains("/log") ->
                    respond(out, "text/plain", logText().toByteArray())
                debugEndpoints && request.contains("/raw") ->
                    respond(out, "application/octet-stream", latestThermalRaw ?: ByteArray(0))
                debugEndpoints && request.contains("/stats") ->
                    respond(out, "application/json",
                        (statsJson?.invoke() ?: "{}").toByteArray())
                request.contains("/config") -> {
                    renderer?.applyConfig(request)
                    respond(out, "application/json",
                        (renderer?.configJson() ?: "{}").toByteArray())
                }
                request.contains("/export") -> {
                    val fn = exportTo
                    if (fn == null || hasExport?.invoke() != true) {
                        respond(out, "text/plain", "nothing to export yet".toByteArray())
                    } else {
                        // no Content-Length: the connection close delimits the body
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
                                   "Content-Disposition: attachment; filename=\"thermaspace-export.zip\"\r\n" +
                                   "Connection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.US_ASCII))
                        try { fn(out) } catch (e: Exception) { log("export failed: $e") }
                        out.flush()
                    }
                }
                else -> respond(out, "text/html", indexHtml().toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    }

    private fun respond(out: OutputStream, type: String, body: ByteArray) {
        // bodies are UTF-8 (Kotlin toByteArray default); without the charset
        // browsers guess Latin-1 and emoji render as mojibake (ðŸ”¥)
        val charset = if (type.startsWith("text/") || type == "application/json")
            "; charset=utf-8" else ""
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type$charset\r\n" +
                   "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun streamMjpeg(out: OutputStream) {
        out.write(("HTTP/1.1 200 OK\r\n" +
                   "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                   "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
        streamClients.incrementAndGet()
        try {
            var last: ByteArray? = null
            // also drop the connection when sharing is switched off — the
            // request-time key check can't reach an already-open stream
            while (running && sharingEnabled) {
                val jpg = latestJpeg
                if (jpg == null || jpg === last) { Thread.sleep(30); continue }
                last = jpg
                out.write(("--frame\r\nContent-Type: image/jpeg\r\n" +
                           "Content-Length: ${jpg.size}\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                out.write(jpg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            // last watcher gone: clear the frame so the next client never
            // sees an arbitrarily stale image (encoding is gated on watchers)
            if (streamClients.decrementAndGet() == 0) latestJpeg = null
        }
    }

    private fun indexHtml(): String {
        val q = if (requireKey) "?key=$sessionKey" else ""
        val dev = if (debugEndpoints)
            """ · <a href="/log$q">app log</a> · <a href="/raw$q">raw frame</a> · <a href="/stats$q">usage</a>""" else ""
        return """
        <!doctype html><meta charset="utf-8"><title>ThermaSpace</title>
        <style>a{color:#8ab4ff;text-decoration:none} a:hover{text-decoration:underline}</style>
        <body style="margin:0;background:#000;color:#cfd8e3;font-family:sans-serif;display:flex;flex-direction:column;height:100vh">
        <div style="padding:9px 14px;background:#101418;font-size:14px">
          🔥 <b>ThermaSpace</b> &nbsp;&nbsp;
          <a href="/export$q">📦 export gallery (zip)</a> ·
          <a href="/config$q">⚙ image config</a> ·
          <a href="/status$q">camera status</a>$dev
        </div>
        <div style="flex:1;display:flex;justify-content:center;min-height:0">
          <img src="/stream$q" style="height:100%;image-rendering:pixelated">
        </div>
        </body>""".trimIndent()
    }
}
