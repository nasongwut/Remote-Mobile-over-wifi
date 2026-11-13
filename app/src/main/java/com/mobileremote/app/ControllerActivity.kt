package com.mobileremote.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * ทำงานบนเครื่องรีโมท
 * แสดงภาพหน้าจอของเครื่องถูกรีโมท และส่งการแตะ/ปัด/ปุ่ม กลับไป
 */
class ControllerActivity : Activity() {

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PIN = "pin"
    }

    private lateinit var remoteView: RemoteScreenView
    private lateinit var status: TextView
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closing = false
    private val frameLock = Object()
    private var pendingFrame: ByteArray? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onResume() {
        super.onResume()
        // ปิดโหมดประหยัดพลังงาน Wi-Fi ขณะควบคุม (ลดการหน่วงได้มาก)
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MobileRemote:controller")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onPause() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileServer.start(this)
        setContentView(buildUi())
        connect(intent.getStringExtra(EXTRA_IP)!!, intent.getStringExtra(EXTRA_PIN) ?: "")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val screenFrame = FrameLayout(this)
        remoteView = RemoteScreenView(this) { cmd -> send(cmd) }
        screenFrame.addView(remoteView)
        val d = resources.displayMetrics.density
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC111827")); cornerRadius = 100 * d
            }
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            text = "กำลังเชื่อมต่อ…"
        }
        screenFrame.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = (16 * d).toInt() })
        root.addView(screenFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // แถบปุ่มด้านล่าง (minimal, ไม่มีไอคอน)
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
        }
        fun key(label: String, cmd: String) = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E5E7EB"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12 * density }
            )
            isClickable = true
            setOnClickListener { send("KEY $cmd") }
        }
        listOf(
            "กลับ" to "BACK", "หน้าหลัก" to "HOME", "แอปล่าสุด" to "RECENTS",
            "แจ้งเตือน" to "NOTIFICATIONS", "ล็อก" to "LOCK"
        ).forEach { (l, c) ->
            bar.addView(key(l, c), LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
        }
        // ปุ่มส่งไฟล์ไปยังเครื่องที่กำลังรีโมทอยู่
        bar.addView(key("ส่งไฟล์", "").apply {
            setTextColor(Color.parseColor("#A5B4FC"))
            setOnClickListener {
                startActivity(Intent(this@ControllerActivity, FileTransferActivity::class.java)
                    .putExtra(FileTransferActivity.EXTRA_IP, intent.getStringExtra(EXTRA_IP)))
            }
        }, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
        root.addView(bar)
        return root
    }

    private fun connect(ip: String, pin: String) = thread(name = "receiver") {
        try {
            val s = Socket()
            s.receiveBufferSize = 192 * 1024   // บัฟเฟอร์เล็ก = ไม่มีภาพเก่าค้างคิว
            s.connect(InetSocketAddress(ip, Protocol.PORT), 5000)
            s.tcpNoDelay = true
            socket = s
            val out = s.getOutputStream()
            output = out
            out.write("AUTH $pin\n".toByteArray()); out.flush()

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            val w = input.readInt()
            if (w == Protocol.AUTH_FAILED) {
                setStatus("PIN ไม่ถูกต้อง")
                s.close(); return@thread
            }
            val h = input.readInt()
            setStatus("เชื่อมต่อแล้ว ${ip} (${w}×${h})", hideAfterMs = 2500)

            // เธรดถอดรหัสภาพ: ทำเฉพาะเฟรมล่าสุด ข้ามเฟรมที่ค้างอยู่ (ลดการหน่วง)
            thread(name = "decoder", isDaemon = true) {
                while (!closing) {
                    val data: ByteArray = synchronized(frameLock) {
                        while (pendingFrame == null && !closing) frameLock.wait(500)
                        val d = pendingFrame
                        pendingFrame = null
                        d
                    } ?: continue
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                    remoteView.post { remoteView.setFrame(bmp) }
                }
            }

            // เธรดรับข้อมูล: อ่านให้เร็วที่สุด ไม่ให้ข้อมูลกองในเครือข่าย
            while (!closing) {
                val len = input.readInt()
                if (len <= 0 || len > 20_000_000) throw IllegalStateException("bad frame $len")
                val buf = ByteArray(len)
                input.readFully(buf, 0, len)
                synchronized(frameLock) {
                    pendingFrame = buf
                    frameLock.notifyAll()
                }
            }
        } catch (e: Exception) {
            if (!closing) setStatus("การเชื่อมต่อหลุด: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun send(cmd: String) {
        val out = output ?: return
        sender.execute {
            try {
                out.write((cmd + "\n").toByteArray()); out.flush()
            } catch (_: Exception) { }
        }
    }

    private fun setStatus(text: String, hideAfterMs: Long = 0) {
        runOnUiThread {
            status.visibility = View.VISIBLE
            status.text = text
            if (hideAfterMs > 0) status.postDelayed({ status.visibility = View.GONE }, hideAfterMs)
        }
    }

    override fun onDestroy() {
        closing = true
        synchronized(frameLock) { frameLock.notifyAll() }
        runCatching { socket?.close() }
        sender.shutdownNow()
        super.onDestroy()
    }
}

/**
 * แสดงภาพหน้าจอระยะไกลแบบ fit-center และแปลงการสัมผัสเป็นคำสั่ง
 *  - แตะสั้น       -> TAP
 *  - แตะค้าง ≥0.5s -> LONG
 *  - ลากนิ้ว        -> SWIPE (ระยะเวลาเท่ากับที่ลากจริง)
 */
@SuppressLint("ViewConstructor")
class RemoteScreenView(
    context: Context,
    private val onCommand: (String) -> Unit
) : View(context) {

    private var frame: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val moveSlop = 24f * resources.displayMetrics.density / 2

    fun setFrame(bmp: Bitmap) {
        val old = frame
        frame = bmp
        if (old != null && old !== bmp) old.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frame ?: return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        dst.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun nx(x: Float) = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun ny(y: Float) = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
    private fun f(v: Float) = String.format(java.util.Locale.US, "%.4f", v)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (frame == null || dst.width() <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!dst.contains(e.x, e.y)) return false
                downX = e.x; downY = e.y; downTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dur = System.currentTimeMillis() - downTime
                val moved = hypot(e.x - downX, e.y - downY)
                val cmd = when {
                    moved < moveSlop && dur >= 500 -> "LONG ${f(nx(downX))} ${f(ny(downY))}"
                    moved < moveSlop -> "TAP ${f(nx(downX))} ${f(ny(downY))}"
                    else -> "SWIPE ${f(nx(downX))} ${f(ny(downY))} ${f(nx(e.x))} ${f(ny(e.y))} " +
                            dur.coerceIn(80, 2000)
                }
                onCommand(cmd)
            }
        }
        return true
    }
}
