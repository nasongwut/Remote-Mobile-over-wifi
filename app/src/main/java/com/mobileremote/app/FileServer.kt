package com.mobileremote.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ตัวรับไฟล์ (ทำงานตลอดเวลาที่แอปเปิดอยู่ ทั้งเครื่องรีโมทและเครื่องถูกรีโมท)
 * ไฟล์ที่ได้รับจะถูกบันทึกที่ Download/MobileRemote/ (โฟลเดอร์จะคงโครงสร้างเดิม)
 *
 * โปรโตคอล (พอร์ต 8889):
 *   C->S  UTF pin            S->C  byte 1=ok / 0=PIN ผิด
 *   C->S  UTF ชื่อผู้ส่ง
 *   วนซ้ำ: C->S UTF relativePath, long size, bytes[size]
 *   C->S  UTF ""  (จบ)       S->C  int จำนวนไฟล์ที่บันทึกได้
 */
object FileServer {
    private const val TAG = "FileServer"
    private const val CHANNEL = "files"
    const val SAVE_DIR = "Download/MobileRemote"

    @Volatile private var started = false
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun deviceName(ctx: Context): String =
        (Settings.Global.getString(ctx.contentResolver, "device_name") ?: Build.MODEL).take(24)

    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        thread(name = "file-server", isDaemon = true) {
            try {
                val ss = ServerSocket(Protocol.FILE_PORT)
                registerNsd(ctx)
                while (true) {
                    val s = ss.accept()
                    thread(name = "file-client", isDaemon = true) { handle(ctx, s) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "file server stopped", e)
                started = false
            }
        }
    }

    private fun registerNsd(ctx: Context) {
        val name = deviceName(ctx)
        val info = NsdServiceInfo().apply {
            serviceName = "MobileRemote Files $name"
            serviceType = Protocol.FILE_SERVICE_TYPE
            port = Protocol.FILE_PORT
            setAttribute("name", name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {}
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        nsdListener = l
        runCatching {
            ctx.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        try {
            s.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            val out = DataOutputStream(s.getOutputStream())

            val pin = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("pin", "1234")
            if (input.readUTF() != pin) {
                out.writeByte(0); out.flush(); return
            }
            out.writeByte(1); out.flush()
            val sender = input.readUTF()

            var count = 0
            var lastName = ""
            val buf = ByteArray(256 * 1024)
            while (true) {
                val rel = input.readUTF()
                if (rel.isEmpty()) break
                val size = input.readLong()
                if (saveFile(ctx, rel, size, input, buf)) {
                    count++
                    lastName = rel.substringBefore('/').ifEmpty { rel }
                }
            }
            out.writeInt(count); out.flush()
            notifyReceived(ctx, sender, count, lastName)
        } catch (e: Exception) {
            Log.w(TAG, "receive failed", e)
        } finally {
            runCatching { s.close() }
        }
    }

    /** ตัด path อันตราย (.., /) ออก แล้วแยกเป็น (โฟลเดอร์ย่อย, ชื่อไฟล์) */
    private fun sanitize(rel: String): Pair<String, String> {
        val parts = rel.replace('\\', '/').split('/')
            .map { it.trim().replace(Regex("[:*?\"<>|]"), "_") }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val name = parts.lastOrNull() ?: "file"
        val dir = parts.dropLast(1).joinToString("/")
        return dir to name
    }

    private fun saveFile(ctx: Context, rel: String, size: Long, input: DataInputStream, buf: ByteArray): Boolean {
        val (dir, name) = sanitize(rel)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/MobileRemote" + (if (dir.isEmpty()) "" else "/$dir"))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        var remaining = size
        if (uri == null) {
            // บันทึกไม่ได้: อ่านทิ้งให้ครบเพื่อไปไฟล์ถัดไป
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw java.io.EOFException()
                remaining -= n
            }
            return false
        }
        resolver.openOutputStream(uri)!!.use { os ->
            val bos = BufferedOutputStream(os, 256 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw java.io.EOFException()
                bos.write(buf, 0, n)
                remaining -= n
            }
            bos.flush()
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    private fun notifyReceived(ctx: Context, sender: String, count: Int, name: String) {
        if (count == 0) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ไฟล์ที่ได้รับ", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (count == 1) "ได้รับ $name จาก $sender" else "ได้รับ $count ไฟล์ จาก $sender"
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ได้รับไฟล์แล้ว")
            .setContentText("$text  ·  บันทึกที่ $SAVE_DIR")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}
