package com.mobileremote.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * หน้าแรก: เลือกบทบาทของเครื่อง
 *  - "เครื่องรีโมท"     (Controller) ใช้ควบคุม
 *  - "เครื่องถูกรีโมท"  (Host) ถูกควบคุม
 */
class MainActivity : Activity() {

    // ---- โทนสี (Modern Minimal) ----
    private val cBg = Color.parseColor("#F5F6FA")
    private val cCard = Color.WHITE
    private val cText = Color.parseColor("#111827")
    private val cMuted = Color.parseColor("#6B7280")
    private val cLine = Color.parseColor("#E5E7EB")
    private val cField = Color.parseColor("#F3F4F6")
    private val cPrimary = Color.parseColor("#4F46E5")
    private val cGreen = Color.parseColor("#10B981")
    private val cGray = Color.parseColor("#D1D5DB")
    private val cDanger = Color.parseColor("#EF4444")

    private lateinit var pinInput: EditText
    private lateinit var ipInput: EditText
    private lateinit var segRemote: TextView
    private lateinit var segHost: TextView
    private lateinit var remoteCard: View
    private lateinit var hostCard: View
    private lateinit var accDot: View
    private lateinit var accText: TextView
    private lateinit var shareDot: View
    private lateinit var shareText: TextView
    private lateinit var ipValue: TextView
    private lateinit var accButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var webUrl: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var listStatus: TextView

    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ต้องสร้างหน้าจอก่อน จึงจะตั้งค่าสีแถบสถานะได้
        setContentView(buildUi())
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        FileServer.start(this)
        selectRole(prefs.getString("role", "remote") == "remote")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshHostStatus()
        if (remoteCard.visibility == View.VISIBLE) startDiscovery()
    }

    override fun onPause() {
        resumed = false
        stopDiscovery()
        super.onPause()
    }

    // ======================= UI =======================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        // หัวเรื่อง
        root.addView(text("Mobile Remote", 30f, cText, bold = true))
        root.addView(text("ควบคุมมือถืออีกเครื่องผ่าน Wi-Fi วงเดียวกัน", 15f, cMuted).apply {
            setPadding(0, dp(6), 0, dp(28))
        })

        // ตัวเลือกบทบาท (segmented control)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#E9EBF2"), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segRemote = segItem("เครื่องรีโมท") { selectRole(true) }
        segHost = segItem("เครื่องถูกรีโมท") { selectRole(false) }
        seg.addView(segRemote, LinearLayout.LayoutParams(0, dp(44), 1f))
        seg.addView(segHost, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(seg)
        root.addView(space(16))

        // การ์ด PIN (ใช้ร่วมกันทั้งสองบทบาท)
        root.addView(card().apply {
            addView(fieldLabel("รหัส PIN", "ต้องตรงกันทั้งสองเครื่อง"))
            pinInput = field("1234", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            pinInput.setText(prefs.getString("pin", "1234"))
            addView(pinInput)
        })
        root.addView(space(16))

        // การ์ดเครื่องรีโมท
        remoteCard = card().apply {
            addView(sectionTitle("เครื่องในเครือข่าย"))
            listStatus = text("กำลังค้นหา…", 13f, cMuted).apply { setPadding(0, 0, 0, dp(10)) }
            addView(listStatus)
            listBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(listBox)
            addView(space(10))
            addView(divider())
            addView(space(18))
            addView(fieldLabel("หรือใส่ IP เอง", "ดูได้จากหน้าแอปบนเครื่องถูกรีโมท"))
            ipInput = field("เช่น 192.168.1.45", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            ipInput.setText(prefs.getString("ip", ""))
            addView(ipInput)
            addView(space(20))
            addView(primaryButton("เชื่อมต่อ") { startController() })
        }
        root.addView(remoteCard)

        // การ์ดเครื่องถูกรีโมท
        hostCard = card().apply {
            addView(sectionTitle("สถานะเครื่องนี้"))

            val accRow = statusRow("สิทธิ์ Accessibility")
            accDot = accRow.second; accText = accRow.third
            addView(accRow.first)
            addView(divider())
            val shareRow = statusRow("การแชร์หน้าจอ")
            shareDot = shareRow.second; shareText = shareRow.third
            addView(shareRow.first)
            addView(divider())

            addView(text("IP ของเครื่องนี้", 13f, cMuted).apply { setPadding(0, dp(14), 0, dp(4)) })
            ipValue = text("-", 26f, cText, bold = true).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.02f
            }
            addView(ipValue)
            addView(text("ใส่เลขนี้ที่เครื่องรีโมท", 13f, cMuted).apply { setPadding(0, dp(2), 0, dp(14)) })
            addView(text("ควบคุมผ่านเบราว์เซอร์บนคอมพิวเตอร์", 13f, cMuted))
            webUrl = text("-", 15f, cPrimary, bold = true).apply {
                setPadding(0, dp(2), 0, dp(20))
                setTextIsSelectable(true)
            }
            addView(webUrl)

            accButton = secondaryButton("เปิดสิทธิ์ Accessibility") { openAccessibilitySettings() }
            addView(accButton)
            addView(space(10))
            shareButton = primaryButton("เริ่มแชร์หน้าจอ") {
                if (HostService.isRunning) {
                    stopService(Intent(this@MainActivity, HostService::class.java))
                    shareButton.postDelayed({ refreshHostStatus() }, 300)
                } else startHost()
            }
            addView(shareButton)
        }
        root.addView(hostCard)
        root.addView(space(16))

        // การ์ดส่งไฟล์ (ใช้ได้ทั้งสองบทบาท)
        root.addView(card().apply {
            addView(sectionTitle("ส่งไฟล์และโฟลเดอร์"))
            addView(text("ส่งหากันได้ทั้งสองทาง เครื่องปลายทางต้องเปิดแอปไว้\nไฟล์ที่ได้รับจะอยู่ใน ${FileServer.SAVE_DIR}", 13f, cMuted).apply {
                setPadding(0, 0, 0, dp(16))
                setLineSpacing(0f, 1.2f)
            })
            addView(secondaryButton("ส่งไฟล์") {
                savePrefs()
                startActivity(Intent(this@MainActivity, FileTransferActivity::class.java).apply {
                    val ip = ipInput.text.toString().trim()
                    if (remoteCard.visibility == View.VISIBLE && ip.isNotEmpty()) putExtra(FileTransferActivity.EXTRA_IP, ip)
                })
            })
        })

        root.addView(text("ใช้งานในเครือข่ายที่ไว้ใจได้เท่านั้น", 12f, cMuted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        }
    }

    private fun selectRole(remote: Boolean) {
        prefs.edit().putString("role", if (remote) "remote" else "host").apply()
        styleSeg(segRemote, remote)
        styleSeg(segHost, !remote)
        remoteCard.visibility = if (remote) View.VISIBLE else View.GONE
        hostCard.visibility = if (remote) View.GONE else View.VISIBLE
        refreshHostStatus()
        if (remote && resumed) startDiscovery() else stopDiscovery()
    }

    // ======================= ค้นหาเครื่องในเครือข่าย (mDNS / NSD) =======================

    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, Pair<String, String>>() // serviceName -> (ชื่อเครื่อง, IP)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var resumed = false

    private fun startDiscovery() {
        if (discoveryListener != null) return
        found.clear(); resolveQueue.clear(); resolving = false
        renderList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { discoveryListener = null; listStatus.text = "ค้นหาไม่ได้ ตรวจสอบการเชื่อมต่อ Wi-Fi" }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                runOnUiThread { if (discoveryListener === this) { resolveQueue.add(info); nextResolve() } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                runOnUiThread { found.remove(info.serviceName); renderList() }
            }
        }
        discoveryListener = l
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            discoveryListener = null
        }
    }

    private fun stopDiscovery() {
        val l = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION")
    private fun nextResolve() {
        if (resolving || discoveryListener == null) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { resolving = false; nextResolve() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                val ip = hostIp(si)
                val name = si.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: si.serviceName
                runOnUiThread {
                    if (ip != null && ip != localIp()) found[si.serviceName] = name to ip
                    resolving = false
                    renderList()
                    nextResolve()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun hostIp(si: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) {
            (si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull())?.hostAddress
        } else si.host?.hostAddress

    private fun renderList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        if (found.isEmpty()) {
            listStatus.text = "กำลังค้นหาเครื่องที่เปิดแชร์หน้าจออยู่…"
        } else {
            listStatus.text = "พบ ${found.size} เครื่อง แตะเพื่อเชื่อมต่อ"
            found.values.forEach { (name, ip) -> listBox.addView(deviceItem(name, ip)) }
        }
    }

    private fun deviceItem(name: String, ip: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(rounded(cField, 12))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        val dot = View(this@MainActivity)
        setDot(dot, cGreen)
        addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })

        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(name, 15f, cText, bold = true))
        col.addView(text(ip, 12f, cMuted))
        addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(text("เชื่อมต่อ", 14f, cPrimary, bold = true))
        setOnClickListener {
            ipInput.setText(ip)
            startController()
        }
    }

    private fun styleSeg(v: TextView, active: Boolean) {
        v.background = if (active) rounded(Color.WHITE, 11) else null
        v.elevation = if (active) dp(1).toFloat() else 0f
        v.setTextColor(if (active) cText else cMuted)
        v.typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun refreshHostStatus() {
        if (!::accDot.isInitialized) return
        val acc = isAccessibilityEnabled()
        setDot(accDot, if (acc) cGreen else cGray)
        accText.text = if (acc) "เปิดแล้ว" else "ยังไม่เปิด"
        accButton.visibility = if (acc) View.GONE else View.VISIBLE

        val running = HostService.isRunning
        setDot(shareDot, if (running) cGreen else cGray)
        shareText.text = if (running) "กำลังแชร์" else "ยังไม่เริ่ม"
        if (running) {
            shareButton.text = "หยุดแชร์หน้าจอ"
            shareButton.setTextColor(cDanger)
            shareButton.background = ripple(rounded(Color.WHITE, 14, cDanger))
        } else {
            shareButton.text = "เริ่มแชร์หน้าจอ"
            shareButton.setTextColor(Color.WHITE)
            shareButton.background = ripple(rounded(cPrimary, 14))
        }
        val ip = localIp()
        ipValue.text = ip ?: "ยังไม่ได้ต่อ Wi-Fi"
        webUrl.text = when {
            ip == null -> "-"
            running -> "http://$ip:${Protocol.WEB_PORT}"
            else -> "http://$ip:${Protocol.WEB_PORT}  (กดเริ่มแชร์หน้าจอก่อน)"
        }
    }

    // ======================= Host =======================

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "เลือก Installed apps > Mobile Remote > เปิดใช้งาน", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startHost() {
        savePrefs()
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "ยังไม่ได้เปิดสิทธิ์ Accessibility จะแชร์ภาพได้แต่สั่งแตะไม่ได้", Toast.LENGTH_LONG).show()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ไม่ได้รับอนุญาตให้จับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, HostService::class.java).apply {
            putExtra(HostService.EXTRA_CODE, resultCode)
            putExtra(HostService.EXTRA_DATA, data)
            putExtra(HostService.EXTRA_PIN, pinInput.text.toString())
        }
        startForegroundService(svc)
        shareButton.postDelayed({ refreshHostStatus() }, 800)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, RemoteAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ======================= Remote =======================

    private fun startController() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "กรุณาใส่ IP ของเครื่องถูกรีโมท", Toast.LENGTH_SHORT).show()
            return
        }
        savePrefs()
        startActivity(Intent(this, ControllerActivity::class.java).apply {
            putExtra(ControllerActivity.EXTRA_IP, ip)
            putExtra(ControllerActivity.EXTRA_PIN, pinInput.text.toString())
        })
    }

    // ======================= helpers =======================

    private fun savePrefs() {
        prefs.edit()
            .putString("pin", pinInput.text.toString())
            .putString("ip", ipInput.text.toString().trim())
            .apply()
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(content: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), content, null)

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 20, cLine)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    private fun sectionTitle(t: String) = text(t, 17f, cText, bold = true).apply {
        setPadding(0, 0, 0, dp(14))
    }

    private fun fieldLabel(title: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(10))
        addView(text(title, 14f, cText, bold = true))
        addView(text(caption, 12f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        textSize = 16f
        setTextColor(cText)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = rounded(cField, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isSingleLine = true
    }

    private fun primaryButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ripple(rounded(cPrimary, 14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(cPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ripple(rounded(Color.WHITE, 14, cPrimary))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun segItem(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(cLine)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun setDot(v: View, color: Int) {
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    /** แถวสถานะ: ชื่อ ........ [จุดสี] ค่า */
    private fun statusRow(title: String): Triple<View, View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(text(title, 15f, cText), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val dot = View(this)
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        val value = text("-", 15f, cMuted)
        row.addView(value)
        return Triple(row, dot, value)
    }

    companion object {
        private const val REQ_CAPTURE = 100
    }
}
