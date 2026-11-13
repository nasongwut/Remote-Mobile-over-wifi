package com.mobileremote.app

/**
 * โปรโตคอลการสื่อสารผ่าน TCP (พอร์ต 8888)
 *
 * Controller -> Host (ข้อความทีละบรรทัด, พิกัดเป็นสัดส่วน 0.0–1.0 ของหน้าจอ):
 *   AUTH <pin>
 *   TAP x y
 *   LONG x y
 *   SWIPE x1 y1 x2 y2 durationMs
 *   KEY BACK | HOME | RECENTS | NOTIFICATIONS | LOCK
 *
 * Host -> Controller (ไบนารี):
 *   int width, int height        (หลัง AUTH ถูกต้อง; ถ้า PIN ผิดส่ง -1 แล้วตัดการเชื่อมต่อ)
 *   แล้ววนซ้ำ: int length, byte[length] (ภาพ JPEG หนึ่งเฟรม)
 */
object Protocol {
    const val PORT = 8888
    /** ชื่อบริการ mDNS ที่ใช้ประกาศ/ค้นหาเครื่องในเครือข่าย */
    const val SERVICE_TYPE = "_mobileremote._tcp"
    /** พอร์ตและชื่อบริการสำหรับรับ-ส่งไฟล์ */
    const val FILE_PORT = 8889
    /** พอร์ตหน้าเว็บสำหรับควบคุมผ่านเบราว์เซอร์ */
    const val WEB_PORT = 8080
    const val FILE_SERVICE_TYPE = "_mrfiles._tcp"
    const val AUTH_FAILED = -1
}
