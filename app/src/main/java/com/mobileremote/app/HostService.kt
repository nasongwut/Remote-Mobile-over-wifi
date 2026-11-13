package com.mobileremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * ทำงานบนเครื่องถูกรีโมท
 *  - จับภาพหน้าจอด้วย MediaProjection แล้วบีบอัดเป็น JPEG
 *  - เปิด TCP server รอเครื่องควบคุมเชื่อมต่อ
 *  - รับคำสั่งแตะ/ปัด/ปุ่ม แล้วส่งต่อให้ RemoteAccessibilityService
 */
class HostService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PIN = "pin"
        private const val TAG = "HostService"
        private const val CHANNEL = "remote_host"
        private const val MAX_SIDE = 1080      // ด้านยาวสุดของภาพที่ส่ง (ลดลงถ้า Wi-Fi ช้า)
        private const val JPEG_QUALITY = 60
        private const val FRAME_INTERVAL_MS = 33L // ~30 fps

        @Volatile var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var handler: Handler
    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pin: String = ""
    private var nsdListener: NsdManager.RegistrationListener? = null

    private var capW = 0
    private var capH = 0
    private var capDpi = 0

    // เฟรมล่าสุด (แชร์ระหว่างเธรดจับภาพกับเธรดส่ง)
    private val frameLock = Object()
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var frameSeq = 0L
    @Volatile private var clientConnected = false
    private val webViewers = java.util.concurrent.atomic.AtomicInteger(0)
    private var web: WebServer? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || isRunning) return START_NOT_STICKY

        startForegroundNow()
        FileServer.start(this)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        captureThread = HandlerThread("capture").also { it.start() }
        handler = Handler(captureThread.looper)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        projection = mp
        // Android 14+ บังคับให้ลงทะเบียน callback ก่อนสร้าง VirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        startCapture()
        startServer()
        registerNsd()
        web = WebServer({ pin }, { sent -> nextFrame(sent) }, { handleCommand(it) },
            { d -> onWebViewer(d) }, { isRunning }).also { it.start() }
        isRunning = true
        return START_NOT_STICKY
    }

    // ---------------- จับภาพหน้าจอ ----------------

    private fun computeCaptureSize() {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val realW = bounds.width()
        val realH = bounds.height()
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(realW, realH))
        // ให้เป็นเลขคู่ เพื่อความเข้ากันได้ของ encoder
        capW = ((realW * scale).roundToInt() / 2) * 2
        capH = ((realH * scale).roundToInt() / 2) * 2
        capDpi = (resources.displayMetrics.densityDpi * scale).roundToInt()
    }

    private fun newImageReader(): ImageReader {
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        return reader
    }

    private fun startCapture() {
        computeCaptureSize()
        val reader = newImageReader()
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "MobileRemote", capW, capH, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    /** เมื่อหมุนจอ ปรับขนาดภาพตาม (ไม่สร้าง VirtualDisplay ใหม่ เพราะ Android 14+ อนุญาตครั้งเดียว) */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isRunning) return
        handler.post {
            val oldW = capW; val oldH = capH
            computeCaptureSize()
            if (oldW == capW && oldH == capH) return@post
            val old = imageReader
            val reader = newImageReader()
            imageReader = reader
            virtualDisplay?.resize(capW, capH, capDpi)
            virtualDisplay?.surface = reader.surface
            old?.close()
        }
    }

    private var padBmp: Bitmap? = null
    private var cropBmp: Bitmap? = null
    private val jpegOut = ByteArrayOutputStream(256 * 1024)
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val pendingFrame = Runnable { imageReader?.let { processImage(it) } }

    private fun onImage(reader: ImageReader) {
        val interval = if (clientConnected || webViewers.get() > 0) FRAME_INTERVAL_MS else 1000L
        val wait = interval - (System.currentTimeMillis() - lastFrameAt)
        handler.removeCallbacks(pendingFrame)
        if (wait > 0) handler.postDelayed(pendingFrame, wait) else processImage(reader)
    }

    /** รอเฟรมใหม่ (สูงสุด 0.5 วินาที) สำหรับผู้ชมผ่านเว็บ */
    private fun nextFrame(sent: Long): Pair<Long, ByteArray?> = synchronized(frameLock) {
        if (frameSeq == sent && isRunning) frameLock.wait(500)
        if (frameSeq == sent) sent to null else frameSeq to latestFrame
    }

    private fun onWebViewer(delta: Int) {
        val n = webViewers.addAndGet(delta)
        if (n > 0) {
            acquireWakeLock()
            updateNotification("มีผู้ควบคุมผ่านเบราว์เซอร์")
        } else if (!clientConnected) {
            releaseWakeLock()
            updateNotification("รอการเชื่อมต่อที่พอร์ต ${Protocol.PORT}")
        }
    }

    private fun processImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            lastFrameAt = System.currentTimeMillis()

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            val paddedW = rowStride / pixelStride

            var padded = padBmp
            if (padded == null || padded.width != paddedW || padded.height != h) {
                padded?.recycle()
                padded = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
                padBmp = padded
            }
            plane.buffer.rewind()
            padded!!.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (paddedW != w) {
                var c = cropBmp
                if (c == null || c.width != w || c.height != h) {
                    c?.recycle()
                    c = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    cropBmp = c
                }
                android.graphics.Canvas(c!!).drawBitmap(padded, 0f, 0f, null)
                c
            } else padded

            val out = jpegOut
            out.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

            synchronized(frameLock) {
                latestFrame = out.toByteArray()
                frameSeq++
                frameLock.notifyAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame error", e)
        } finally {
            image.close()
        }
    }

    // ---------------- เซิร์ฟเวอร์ ----------------

    private fun startServer() = thread(name = "server") {
        try {
            val ss = ServerSocket(Protocol.PORT)
            serverSocket = ss
            while (!ss.isClosed) {
                val s = ss.accept()
                // รองรับทีละ 1 เครื่อง: ตัดเครื่องเก่าทิ้ง
                client?.let { runCatching { it.close() } }
                client = s
                thread(name = "client") { handleClient(s) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "server stopped: ${e.message}")
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.sendBufferSize = 96 * 1024
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = DataOutputStream(s.getOutputStream().buffered(64 * 1024))

            // 1) ตรวจ PIN
            s.soTimeout = 10_000
            val auth = reader.readLine() ?: return
            if (auth != "AUTH $pin") {
                out.writeInt(Protocol.AUTH_FAILED); out.flush()
                return
            }
            s.soTimeout = 0
            out.writeInt(capW); out.writeInt(capH); out.flush()

            clientConnected = true
            acquireWakeLock()
            updateNotification("เครื่องรีโมทเชื่อมต่อจาก ${s.inetAddress.hostAddress}")

            // 2) เธรดรับคำสั่ง
            thread(name = "commands") {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleCommand(line)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { s.close() }
                }
            }

            // 3) ส่งภาพเฟรมล่าสุดไปเรื่อย ๆ
            var sentSeq = -1L
            while (!s.isClosed) {
                val frame: ByteArray? = synchronized(frameLock) {
                    while (frameSeq == sentSeq && !s.isClosed && isRunning) frameLock.wait(500)
                    sentSeq = frameSeq
                    latestFrame
                }
                if (!isRunning) break
                if (frame == null) continue
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
            }
        } catch (e: Exception) {
            Log.i(TAG, "client disconnected: ${e.message}")
        } finally {
            runCatching { s.close() }
            if (client === s) {
                clientConnected = false
                if (webViewers.get() == 0) releaseWakeLock()
                updateNotification("รอการเชื่อมต่อที่พอร์ต ${Protocol.PORT}")
            }
        }
    }

    private fun handleCommand(line: String) {
        val p = line.trim().split(' ')
        val acc = RemoteAccessibilityService.instance
        if (acc == null) {
            Log.w(TAG, "Accessibility ยังไม่เปิด — ข้ามคำสั่ง $line")
            return
        }
        try {
            when (p[0]) {
                "TAP" -> acc.tap(p[1].toFloat(), p[2].toFloat())
                "LONG" -> acc.longPress(p[1].toFloat(), p[2].toFloat())
                "SWIPE" -> acc.swipe(
                    p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toLong()
                )
                "KEY" -> acc.key(p[1])
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad command: $line", e)
        }
    }

    // ---------------- ประกาศตัวในเครือข่าย (mDNS) ----------------

    private fun registerNsd() {
        val name = (Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL).take(24)
        val info = NsdServiceInfo().apply {
            serviceName = "MobileRemote $name"
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
            setAttribute("name", name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "NSD registered ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "NSD failed $errorCode") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        nsdListener = l
        runCatching { getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    // ---------------- Wake lock (ให้จอไม่ดับระหว่างถูกควบคุม) ----------------

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MobileRemote:lowlatency")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun acquireWakeLock() {
        acquireWifiLock()
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MobileRemote:session"
        ).apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---------------- Notification ----------------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Mobile Remote กำลังแชร์หน้าจอ")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Remote host", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification("รอการเชื่อมต่อที่พอร์ต ${Protocol.PORT}")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        isRunning = false
        clientConnected = false
        nsdListener?.let { l -> runCatching { getSystemService(NsdManager::class.java).unregisterService(l) } }
        nsdListener = null
        runCatching { serverSocket?.close() }
        web?.stop()
        runCatching { client?.close() }
        synchronized(frameLock) { frameLock.notifyAll() }
        releaseWakeLock()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        if (::captureThread.isInitialized) captureThread.quitSafely()
        super.onDestroy()
    }
}
