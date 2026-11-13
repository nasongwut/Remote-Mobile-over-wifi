package com.mobileremote.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * เว็บเซิร์ฟเวอร์บนเครื่องถูกรีโมท (พอร์ต 8080)
 * เปิด http://<IP>:8080 จากเบราว์เซอร์บนคอมพิวเตอร์ในวง Wi-Fi เดียวกัน เพื่อดูและควบคุมหน้าจอ
 *
 *   GET  /                 หน้าเว็บควบคุม
 *   GET  /check?pin=       ตรวจ PIN
 *   GET  /stream?pin=      ภาพหน้าจอแบบ MJPEG
 *   POST /cmd?pin=         คำสั่ง (TAP/LONG/SWIPE/KEY) บรรทัดละคำสั่ง
 */
class WebServer(
    private val pin: () -> String,
    private val nextFrame: (Long) -> Pair<Long, ByteArray?>,
    private val onCommand: (String) -> Unit,
    private val viewerChanged: (Int) -> Unit,
    private val isAlive: () -> Boolean
) {
    @Volatile private var server: ServerSocket? = null

    fun start() = thread(name = "web", isDaemon = true) {
        try {
            val ss = ServerSocket(Protocol.WEB_PORT)
            server = ss
            while (!ss.isClosed) {
                val c = ss.accept()
                thread(name = "web-client", isDaemon = true) { handle(c) }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        runCatching { server?.close() }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15_000
            c.tcpNoDelay = true
            val input = BufferedInputStream(c.getInputStream())
            val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)

            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            when {
                path == "/" || path == "/index.html" ->
                    respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray(Charsets.UTF_8))
                query["pin"] != pin() ->
                    respond(out, 403, "text/plain; charset=utf-8", "PIN ไม่ถูกต้อง".toByteArray(Charsets.UTF_8))
                path == "/check" ->
                    respond(out, 200, "text/plain", "ok".toByteArray())
                path == "/stream" ->
                    stream(c, out)
                path == "/cmd" && method == "POST" -> {
                    val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 4096)
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(body, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    String(body, 0, read).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach(onCommand)
                    respond(out, 204, "text/plain", ByteArray(0))
                }
                else -> respond(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { c.close() }
        }
    }

    private fun stream(c: Socket, out: OutputStream) {
        c.soTimeout = 0
        c.sendBufferSize = 96 * 1024
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        viewerChanged(1)
        try {
            var seq = -1L
            while (isAlive() && !c.isClosed) {
                val (s, frame) = nextFrame(seq)
                if (frame == null) continue
                seq = s
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            viewerChanged(-1)
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    companion object {
        // หน้าเว็บควบคุม (ไม่ใช้เครื่องหมายดอลลาร์ใน JavaScript เพื่อไม่ชนกับ string template ของ Kotlin)
        private val PAGE = """
<!DOCTYPE html>
<html lang="th">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mobile Remote</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  html,body{height:100%;background:#0B0B0F;color:#E5E7EB;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans Thai",sans-serif}
  #app{display:flex;flex-direction:column;height:100%}
  header{display:flex;align-items:center;justify-content:space-between;padding:14px 20px;border-bottom:1px solid #1F2230}
  header h1{font-size:16px;font-weight:600;color:#fff}
  #status{font-size:13px;color:#9CA3AF;display:flex;align-items:center;gap:8px}
  #dot{width:8px;height:8px;border-radius:50%;background:#6B7280}
  main{flex:1;display:flex;align-items:center;justify-content:center;min-height:0;padding:16px}
  #screen{max-width:100%;max-height:100%;object-fit:contain;border-radius:14px;cursor:pointer;user-select:none;-webkit-user-drag:none;background:#111;box-shadow:0 0 0 1px #1F2230}
  nav{display:flex;justify-content:center;gap:8px;padding:12px 16px 18px;border-top:1px solid #1F2230;flex-wrap:wrap}
  nav button{background:#161925;color:#E5E7EB;border:1px solid #262A3A;border-radius:10px;padding:10px 16px;font-size:14px;cursor:pointer;font-family:inherit}
  nav button:hover{background:#1F2333}
  .hint{font-size:12px;color:#6B7280;text-align:center;padding-bottom:12px}
  #login{position:fixed;inset:0;background:#F5F6FA;display:flex;align-items:center;justify-content:center;color:#111827}
  .card{background:#fff;border:1px solid #E5E7EB;border-radius:20px;padding:32px;width:340px}
  .card h2{font-size:24px;margin-bottom:6px}
  .card p{font-size:14px;color:#6B7280;margin-bottom:22px}
  .card input{width:100%;font-size:18px;padding:14px 16px;border-radius:12px;border:none;background:#F3F4F6;outline:none;letter-spacing:4px;font-family:inherit}
  .card button{width:100%;margin-top:14px;background:#4F46E5;color:#fff;border:none;border-radius:12px;padding:15px;font-size:16px;font-weight:600;cursor:pointer;font-family:inherit}
  #err{color:#EF4444;font-size:13px;margin-top:10px;min-height:18px}
</style>
</head>
<body>
<div id="login">
  <form class="card" id="form">
    <h2>Mobile Remote</h2>
    <p>ใส่รหัส PIN ที่ตั้งไว้ในแอปบนมือถือ</p>
    <input id="pin" type="password" inputmode="numeric" placeholder="PIN" autofocus>
    <button type="submit">เชื่อมต่อ</button>
    <div id="err"></div>
  </form>
</div>
<div id="app" style="display:none">
  <header>
    <h1>Mobile Remote</h1>
    <div id="status"><span id="dot"></span><span id="stext">กำลังเชื่อมต่อ…</span></div>
  </header>
  <main><img id="screen" alt="" draggable="false"></main>
  <nav>
    <button data-k="BACK">กลับ</button>
    <button data-k="HOME">หน้าหลัก</button>
    <button data-k="RECENTS">แอปล่าสุด</button>
    <button data-k="NOTIFICATIONS">แจ้งเตือน</button>
    <button data-k="LOCK">ล็อกหน้าจอ</button>
  </nav>
  <div class="hint">คลิก = แตะ · คลิกค้าง = กดค้าง · ลาก = ปัด · ล้อเมาส์ = เลื่อน · คลิกขวา / Esc = กลับ · Home = หน้าหลัก</div>
</div>
<script>
var PIN = '';
var img = document.getElementById('screen');
var down = null;
var lastWheel = 0;

function q(){ return '?pin=' + encodeURIComponent(PIN); }
function send(cmd){ fetch('/cmd' + q(), {method:'POST', body:cmd}).catch(function(){}); }
function f(v){ return Math.min(1, Math.max(0, v)).toFixed(4); }
function setStatus(ok, t){
  document.getElementById('dot').style.background = ok ? '#10B981' : '#EF4444';
  document.getElementById('stext').textContent = t;
}

document.getElementById('form').addEventListener('submit', function(e){
  e.preventDefault();
  PIN = document.getElementById('pin').value.trim();
  fetch('/check' + q()).then(function(r){
    if (r.ok) {
      document.getElementById('login').style.display = 'none';
      document.getElementById('app').style.display = 'flex';
      startStream();
    } else {
      document.getElementById('err').textContent = 'PIN ไม่ถูกต้อง';
    }
  }).catch(function(){ document.getElementById('err').textContent = 'เชื่อมต่อไม่ได้'; });
});

function startStream(){ img.src = '/stream' + q() + '&t=' + Date.now(); }
img.addEventListener('load', function(){ setStatus(true, 'เชื่อมต่อแล้ว'); });
img.addEventListener('error', function(){
  setStatus(false, 'การเชื่อมต่อหลุด กำลังลองใหม่…');
  setTimeout(startStream, 1500);
});

/* แปลงตำแหน่งเมาส์เป็นสัดส่วน 0..1 ของหน้าจอมือถือ (คิด object-fit: contain) */
function norm(e){
  var r = img.getBoundingClientRect();
  var iw = img.naturalWidth || r.width, ih = img.naturalHeight || r.height;
  var s = Math.min(r.width / iw, r.height / ih);
  var w = iw * s, h = ih * s;
  var x0 = r.left + (r.width - w) / 2, y0 = r.top + (r.height - h) / 2;
  return [(e.clientX - x0) / w, (e.clientY - y0) / h];
}

img.addEventListener('pointerdown', function(e){
  if (e.button !== 0) return;
  e.preventDefault();
  var p = norm(e);
  down = {x:p[0], y:p[1], t:Date.now(), cx:e.clientX, cy:e.clientY};
});
window.addEventListener('pointerup', function(e){
  if (!down || e.button !== 0) return;
  var p = norm(e);
  var moved = Math.hypot(e.clientX - down.cx, e.clientY - down.cy);
  var dur = Date.now() - down.t;
  if (moved < 6) {
    send((dur >= 500 ? 'LONG ' : 'TAP ') + f(down.x) + ' ' + f(down.y));
  } else {
    send('SWIPE ' + f(down.x) + ' ' + f(down.y) + ' ' + f(p[0]) + ' ' + f(p[1]) + ' ' + Math.min(2000, Math.max(80, dur)));
  }
  down = null;
});
img.addEventListener('contextmenu', function(e){ e.preventDefault(); send('KEY BACK'); });
img.addEventListener('wheel', function(e){
  e.preventDefault();
  var now = Date.now();
  if (now - lastWheel < 350) return;
  lastWheel = now;
  var p = norm(e);
  var x = f(p[0]);
  if (e.deltaY > 0) send('SWIPE ' + x + ' 0.70 ' + x + ' 0.35 250');
  else send('SWIPE ' + x + ' 0.35 ' + x + ' 0.70 250');
}, {passive:false});

document.querySelectorAll('nav button').forEach(function(b){
  b.addEventListener('click', function(){ send('KEY ' + b.getAttribute('data-k')); });
});
document.addEventListener('keydown', function(e){
  if (document.getElementById('app').style.display === 'none') return;
  if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); send('KEY BACK'); }
  else if (e.key === 'Home') { e.preventDefault(); send('KEY HOME'); }
});
</script>
</body>
</html>
""".trimIndent()
    }
}
