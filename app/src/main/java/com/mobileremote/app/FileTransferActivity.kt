package com.mobileremote.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ส่งไฟล์หรือโฟลเดอร์ไปยังอีกเครื่องที่เปิดแอป Mobile Remote อยู่ (ส่งได้ทั้งสองทิศทาง)
 */
class FileTransferActivity : Activity() {

    companion object {
        const val EXTRA_IP = "ip"
        private const val REQ_FILES = 201
        private const val REQ_FOLDER = 202
    }

    private data class Item(val uri: Uri, val path: String, var size: Long)

    // โทนสีเดียวกับหน้าแรก
    private val cBg = Color.parseColor("#F5F6FA")
    private val cText = Color.parseColor("#111827")
    private val cMuted = Color.parseColor("#6B7280")
    private val cLine = Color.parseColor("#E5E7EB")
    private val cField = Color.parseColor("#F3F4F6")
    private val cPrimary = Color.parseColor("#4F46E5")
    private val cGreen = Color.parseColor("#10B981")
    private val cDanger = Color.parseColor("#EF4444")

    private val items = mutableListOf<Item>()
    private lateinit var summary: TextView
    private lateinit var listStatus: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    @Volatile private var sending = false

    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }
    private lateinit var browser: NsdBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ต้องสร้างหน้าจอก่อน จึงจะตั้งค่าสีแถบสถานะได้
        setContentView(buildUi())
        FileServer.start(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        intent.getStringExtra(EXTRA_IP)?.let { ipInput.setText(it) }
        browser = NsdBrowser(this, Protocol.FILE_SERVICE_TYPE) { renderDevices(it) }
    }

    override fun onResume() { super.onResume(); browser.start() }
    override fun onPause() { browser.stop(); super.onPause() }

    // ======================= UI =======================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
        root.addView(text("ส่งไฟล์", 30f, cText, bold = true))
        root.addView(text("ไฟล์ที่ได้รับจะอยู่ใน ${FileServer.SAVE_DIR}", 14f, cMuted).apply {
            setPadding(0, dp(6), 0, dp(24))
        })

        // สิ่งที่จะส่ง
        root.addView(card().apply {
            addView(sectionTitle("สิ่งที่จะส่ง"))
            val row = LinearLayout(this@FileTransferActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(secondaryButton("เลือกไฟล์") { pickFiles() },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
            row.addView(secondaryButton("เลือกโฟลเดอร์") { pickFolder() },
                LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(row)
            summary = text("ยังไม่ได้เลือก", 14f, cMuted).apply { setPadding(0, dp(14), 0, 0) }
            addView(summary)
        })
        root.addView(space(16))

        // ส่งไปที่
        root.addView(card().apply {
            addView(sectionTitle("ส่งไปที่"))
            listStatus = text("กำลังค้นหา…", 13f, cMuted).apply { setPadding(0, 0, 0, dp(10)) }
            addView(listStatus)
            listBox = LinearLayout(this@FileTransferActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(listBox)
            addView(space(10)); addView(divider()); addView(space(16))
            addView(text("หรือใส่ IP เอง", 14f, cText, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
            ipInput = EditText(this@FileTransferActivity).apply {
                hint = "เช่น 192.168.1.45"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                textSize = 16f
                setTextColor(cText)
                setHintTextColor(Color.parseColor("#9CA3AF"))
                background = rounded(cField, 12)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isSingleLine = true
            }
            addView(ipInput)
        })
        root.addView(space(20))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(cPrimary)
            progressBackgroundTintList = ColorStateList.valueOf(cLine)
            visibility = View.GONE
        }
        root.addView(progress)
        progressText = text("", 13f, cMuted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(12)); visibility = View.GONE
        }
        root.addView(progressText)

        sendButton = TextView(this).apply {
            text = "ส่ง"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ripple(rounded(cPrimary, 14))
            isClickable = true
            setOnClickListener { startSend() }
        }
        root.addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

        return ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(root) }
    }

    private fun renderDevices(found: Map<String, Pair<String, String>>) {
        listBox.removeAllViews()
        listStatus.text = if (found.isEmpty()) "กำลังค้นหาเครื่องที่เปิดแอปอยู่…"
        else "พบ ${found.size} เครื่อง แตะเพื่อเลือก"
        val selected = ipInput.text.toString().trim()
        found.values.forEach { (name, ip) ->
            val active = ip == selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(if (active) rounded(Color.parseColor("#EEF2FF"), 12, cPrimary) else rounded(cField, 12))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { ipInput.setText(ip); renderDevices(found) }
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cGreen) }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(name, 15f, cText, bold = true))
            col.addView(text(ip, 12f, cMuted))
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(if (active) "เลือกแล้ว" else "เลือก", 14f, cPrimary, bold = true))
            listBox.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun updateSummary() {
        if (items.isEmpty()) { summary.text = "ยังไม่ได้เลือก"; summary.setTextColor(cMuted); return }
        val total = items.sumOf { maxOf(it.size, 0) }
        val folder = items.first().path.substringBefore('/', "")
        val what = if (folder.isNotEmpty() && items.all { it.path.startsWith("$folder/") })
            "โฟลเดอร์ $folder (${items.size} ไฟล์)" else "${items.size} ไฟล์"
        summary.text = "$what  ·  ${human(total)}"
        summary.setTextColor(cText)
    }

    // ======================= เลือกไฟล์ / โฟลเดอร์ =======================

    @Suppress("DEPRECATION")
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_FILES)
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        summary.text = "กำลังอ่านรายการ…"
        thread {
            val list = mutableListOf<Item>()
            try {
                if (requestCode == REQ_FILES) {
                    val uris = mutableListOf<Uri>()
                    data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
                    data.data?.let { if (uris.isEmpty()) uris.add(it) }
                    uris.forEach { u -> list.add(queryFile(u)) }
                } else if (requestCode == REQ_FOLDER) {
                    val tree = data.data!!
                    val rootId = DocumentsContract.getTreeDocumentId(tree)
                    val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "Folder"
                    walk(tree, rootId, rootName, list)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "อ่านไฟล์ไม่ได้: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            runOnUiThread { items.clear(); items.addAll(list); updateSummary() }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun queryFile(uri: Uri): Item {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Item(uri, name, size)
    }

    /** ไล่อ่านทุกไฟล์ในโฟลเดอร์ (รวมโฟลเดอร์ย่อย) */
    private fun walk(tree: Uri, docId: String, prefix: String, out: MutableList<Item>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, "$prefix/$name", out)
                } else {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    out.add(Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$prefix/$name", size))
                }
            }
        }
    }

    // ======================= ส่ง =======================

    private fun startSend() {
        if (sending) return
        val ip = ipInput.text.toString().trim()
        if (items.isEmpty()) { toast("เลือกไฟล์หรือโฟลเดอร์ก่อน"); return }
        if (ip.isEmpty()) { toast("เลือกเครื่องปลายทาง หรือใส่ IP"); return }
        val pin = prefs.getString("pin", "1234") ?: "1234"
        sending = true
        setSending(true)
        thread { send(ip, pin, items.toList()) }
    }

    private fun send(ip: String, pin: String, list: List<Item>) {
        try {
            // หาขนาดไฟล์ที่ระบบไม่บอก
            list.filter { it.size < 0 }.forEach { item ->
                item.size = contentResolver.openInputStream(item.uri)!!.use { s ->
                    val b = ByteArray(64 * 1024); var t = 0L
                    while (true) { val n = s.read(b); if (n < 0) break; t += n }
                    t
                }
            }
            val total = list.sumOf { it.size }.coerceAtLeast(1)

            Socket().use { s ->
                s.connect(InetSocketAddress(ip, Protocol.FILE_PORT), 5000)
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
                val input = DataInputStream(s.getInputStream())
                out.writeUTF(pin); out.flush()
                if (input.readByte().toInt() != 1) { finishSend("PIN ไม่ตรงกับเครื่องปลายทาง", false); return }
                out.writeUTF(FileServer.deviceName(this))

                var sent = 0L
                var lastUi = 0L
                val buf = ByteArray(256 * 1024)
                list.forEachIndexed { idx, item ->
                    out.writeUTF(item.path)
                    out.writeLong(item.size)
                    var remaining = item.size
                    contentResolver.openInputStream(item.uri)!!.use { src ->
                        while (remaining > 0) {
                            val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw java.io.IOException("ไฟล์ ${item.path} สั้นกว่าที่ระบุ")
                            out.write(buf, 0, n)
                            remaining -= n
                            sent += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 150) {
                                lastUi = now
                                val pct = (sent * 1000 / total).toInt()
                                runOnUiThread {
                                    progress.progress = pct
                                    progressText.text = "กำลังส่ง ${idx + 1}/${list.size}  ·  ${pct / 10}%  ·  ${human(sent)} / ${human(total)}"
                                }
                            }
                        }
                    }
                }
                out.writeUTF(""); out.flush()
                val saved = input.readInt()
                finishSend("ส่งแล้ว $saved ไฟล์  ·  ${human(total)}", true)
            }
        } catch (e: Exception) {
            finishSend("ส่งไม่สำเร็จ: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun finishSend(msg: String, ok: Boolean) = runOnUiThread {
        sending = false
        setSending(false)
        progress.visibility = if (ok) View.VISIBLE else View.GONE
        if (ok) progress.progress = 1000
        progressText.visibility = View.VISIBLE
        progressText.text = msg
        progressText.setTextColor(if (ok) cGreen else cDanger)
    }

    private fun setSending(on: Boolean) {
        sendButton.text = if (on) "กำลังส่ง…" else "ส่ง"
        sendButton.alpha = if (on) 0.6f else 1f
        if (on) {
            progress.visibility = View.VISIBLE; progress.progress = 0
            progressText.visibility = View.VISIBLE; progressText.setTextColor(cMuted)
            progressText.text = "กำลังเชื่อมต่อ…"
        }
    }

    // ======================= helpers =======================

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun ripple(d: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), d, null)
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun divider() = View(this).apply {
        setBackgroundColor(cLine)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 20, cLine)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
    private fun sectionTitle(t: String) = text(t, 17f, cText, bold = true).apply { setPadding(0, 0, 0, dp(14)) }
    private fun secondaryButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(cPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ripple(rounded(Color.WHITE, 14, cPrimary))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/**
 * ค้นหาเครื่องที่เปิดแอปในเครือข่ายด้วย mDNS (ไม่รวมเครื่องตัวเอง)
 * ผลลัพธ์: serviceName -> (ชื่อเครื่อง, IP)
 */
class NsdBrowser(
    private val activity: Activity,
    private val type: String,
    private val onChange: (Map<String, Pair<String, String>>) -> Unit
) {
    private val nsd = activity.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, Pair<String, String>>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        found.clear(); queue.clear(); resolving = false
        onChange(found)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                activity.runOnUiThread { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                activity.runOnUiThread { if (listener === this) { queue.add(info); next() } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                activity.runOnUiThread { found.remove(info.serviceName); onChange(found) }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                activity.runOnUiThread { resolving = false; next() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                val ip = if (Build.VERSION.SDK_INT >= 34)
                    (si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull())?.hostAddress
                else si.host?.hostAddress
                val name = si.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: si.serviceName
                activity.runOnUiThread {
                    if (ip != null && ip != localIp()) found[si.serviceName] = name to ip
                    resolving = false
                    onChange(found)
                    next()
                }
            }
        })
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }
}
