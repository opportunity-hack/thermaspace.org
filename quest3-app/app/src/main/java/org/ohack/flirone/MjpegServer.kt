package org.ohack.flirone

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
            when {
                request.contains("/stream") -> streamMjpeg(out)
                request.contains("/status") -> respond(out, "application/json", latestStatus.toByteArray())
                request.contains("/log") -> respond(out, "text/plain", logText().toByteArray())
                request.contains("/raw") -> respond(out, "application/octet-stream",
                    latestThermalRaw ?: ByteArray(0))
                request.contains("/config") -> {
                    renderer?.applyConfig(request)
                    respond(out, "application/json",
                        (renderer?.configJson() ?: "{}").toByteArray())
                }
                else -> respond(out, "text/html", INDEX_HTML.toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    }

    private fun respond(out: OutputStream, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
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
        var last: ByteArray? = null
        while (running) {
            val jpg = latestJpeg
            if (jpg == null || jpg === last) { Thread.sleep(30); continue }
            last = jpg
            out.write(("--frame\r\nContent-Type: image/jpeg\r\n" +
                       "Content-Length: ${jpg.size}\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            out.write(jpg)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private val INDEX_HTML = """
        <!doctype html><title>FLIR One</title>
        <body style="margin:0;background:#000;display:flex;justify-content:center">
        <img src="/stream" style="height:100vh;image-rendering:pixelated">
        </body>""".trimIndent()
}
