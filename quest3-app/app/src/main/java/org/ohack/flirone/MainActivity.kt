package org.ohack.flirone

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.SurfaceView
import android.widget.TextView
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private companion object { const val ACTION_USB = "org.ohack.flirone.USB_PERMISSION" }

    private lateinit var surface: SurfaceView
    private lateinit var statusView: TextView
    private var flir: FlirOne? = null
    private val server = MjpegServer(8080)

    private val renderer = ThermalRenderer()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE; textSize = 28f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = SurfaceView(this)
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 24, 24, 24)
            text = "Waiting for FLIR One…  (plug it into the Quest USB-C port)"
        }
        setContentView(FrameLayout(this).apply {
            addView(surface); addView(statusView)
        })
        server.renderer = renderer
        server.start()
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB), RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (flir == null) findAndOpen()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        flir?.stop()
        server.stop()
    }

    private fun findAndOpen() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull {
            it.vendorId == FlirOne.VID && it.productId == FlirOne.PID
        } ?: run { setStatus("FLIR One not found — plug it in and reopen the app"); return }

        if (!usb.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
            return
        }
        open(usb, dev)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB) return
            val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null)
                open(getSystemService(Context.USB_SERVICE) as UsbManager, dev)
            else setStatus("USB permission denied")
        }
    }

    private fun open(usb: UsbManager, dev: UsbDevice) {
        val conn = usb.openDevice(dev) ?: run { setStatus("openDevice failed"); return }
        setStatus("FLIR One connected — starting stream  |  Mac view: http://${localIp()}:8080")
        val recent = ArrayDeque<String>()
        flir = FlirOne(dev, conn, ::onFrame,
            onError = { msg -> setStatus("error: $msg  |  log: http://${localIp()}:8080/log") },
            onLog = { line ->
                server.log(line)
                synchronized(recent) {
                    recent.addLast(line); while (recent.size > 4) recent.removeFirst()
                    setStatus(recent.joinToString("\n"))
                }
            }).also { it.start() }
    }

    private fun onFrame(f: FlirFrame) {
        f.statusJson?.let { server.latestStatus = it }
        f.thermalRaw?.let { server.latestThermalRaw = it }
        if (f.thermal == null) {
            // unknown thermal geometry: at least show the visible camera
            f.visibleJpeg?.let { server.latestJpeg = it }
            return
        }
        if (!renderer.process(f)) return
        if (statusView.text.isNotEmpty()) setStatus("")   // frames flowing: clear debug text
        val out = renderer.output ?: return
        drawToSurface(out)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        server.latestJpeg = bos.toByteArray()
    }

    private fun drawToSurface(bmp: Bitmap) {
        val holder = surface.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val vw = canvas.width.toFloat(); val vh = canvas.height.toFloat()
            val scale = minOf(vw / bmp.width, vh / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            val dst = Rect(((vw - dw) / 2).toInt(), ((vh - dh) / 2).toInt(),
                           ((vw + dw) / 2).toInt(), ((vh + dh) / 2).toInt())
            canvas.drawBitmap(bmp, null, dst, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { statusView.text = msg }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "<quest-ip>"
}
