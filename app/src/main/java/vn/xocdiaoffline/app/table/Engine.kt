package vn.xocdiaoffline.app.table

import org.json.JSONObject
import kotlin.random.Random

/** Những gì một game được phép dùng từ bàn chơi. */
interface EngineHost {
    val bet: Long
    val rng: Random
    /** Dữ liệu giữ qua các ván của cùng một bàn (người làm cái, người thắng ván trước...). */
    val persist: MutableMap<String, Any>
    fun name(seat: Int): String
    fun xu(seat: Int): Long
    fun isBot(seat: Int): Boolean
    /** Trạng thái đã đổi, gửi lại cho mọi người. */
    fun changed()
    /** Hẹn giờ (thay cho hẹn giờ trước đó). showCountdown = hiện đồng hồ đếm ngược. */
    fun schedule(ms: Long, showCountdown: Boolean, task: () -> Unit)
    fun cancelTimer()
    fun toast(seat: Int, msg: String)
    fun toastAll(msg: String)
    /** Ván kết thúc: delta xu theo chỉ số ghế (tổng bằng 0), kèm bảng kết quả. */
    fun finish(delta: LongArray, result: JSONObject)
    /** Đồng hồ của bàn (để kiểm thử có thể tua nhanh). */
    fun now(): Long = System.currentTimeMillis()
}

/** Một ván của một game, chạy trên máy chủ bàn. players = các ghế tham gia ván. */
abstract class Engine(protected val host: EngineHost, val players: IntArray) {
    abstract fun start()
    /** Thao tác của người ngồi ghế seat (cả khi ván đã xong, ví dụ "bỏ cái"). */
    abstract fun action(seat: Int, a: JSONObject)
    /** Phần "g" của trạng thái gửi cho người ngồi ghế v. */
    abstract fun view(v: Int): JSONObject
    /** Một người rời bàn giữa ván, máy chơi thay. */
    open fun seatBecameBot(seat: Int) {}
}
