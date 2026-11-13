package com.mobileremote.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * ทำงานบนเครื่องถูกรีโมท
 * รับพิกัดแบบสัดส่วน (0..1) แล้วแปลงเป็นพิกเซลจริง เพื่อจำลองการแตะ/ปัด/กดปุ่มระบบ
 * ผู้ใช้ต้องเปิดเองที่ Settings > Accessibility > Installed apps > Mobile Remote
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ขนาดหน้าจอจริงปัจจุบัน (เปลี่ยนตามการหมุนจอ)
    private fun screen(): Pair<Float, Float> {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width().toFloat() to b.height().toFloat()
    }

    private fun px(nx: Float, ny: Float): Pair<Float, Float> {
        val (w, h) = screen()
        return (nx.coerceIn(0f, 1f) * (w - 1)) to (ny.coerceIn(0f, 1f) * (h - 1))
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(nx: Float, ny: Float) {
        val (x, y) = px(nx, ny)
        dispatch(Path().apply { moveTo(x, y) }, 50)
    }

    fun longPress(nx: Float, ny: Float) {
        val (x, y) = px(nx, ny)
        dispatch(Path().apply { moveTo(x, y) }, 800)
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long) {
        val (x1, y1) = px(nx1, ny1)
        val (x2, y2) = px(nx2, ny2)
        dispatch(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)
    }

    fun key(name: String) {
        val action = when (name) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "LOCK" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return
        }
        performGlobalAction(action)
    }
}
