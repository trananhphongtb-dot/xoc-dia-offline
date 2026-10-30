package vn.xocdiaoffline.app

import android.content.Context

/** Ví xu ảo lưu trên máy. Không có nạp tiền, không đổi thưởng. */
object Wallet {
    private const val PREF = "wallet"
    const val START_XU = 10_000L
    const val CLAIM_BELOW = 1_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun xu(ctx: Context): Long = p(ctx).getLong("xu", START_XU)
    fun setXu(ctx: Context, v: Long) = p(ctx).edit().putLong("xu", v.coerceAtLeast(0)).apply()
    fun add(ctx: Context, d: Long) = setXu(ctx, xu(ctx) + d)

    fun name(ctx: Context): String = p(ctx).getString("name", null) ?: "Người chơi"
    fun setName(ctx: Context, v: String) = p(ctx).edit().putString("name", v).apply()

    /** Thêm xu theo mức tự chọn, ghi vào lịch sử xu (giữ 100 lần gần nhất). */
    fun addXu(ctx: Context, v: Long) {
        add(ctx, v)
        val arr = try { org.json.JSONArray(p(ctx).getString("addLog", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        val list = mutableListOf(org.json.JSONObject().put("t", System.currentTimeMillis()).put("v", v).put("after", xu(ctx)))
        for (i in 0 until minOf(arr.length(), 99)) list += arr.getJSONObject(i)
        p(ctx).edit().putString("addLog", org.json.JSONArray(list).toString()).apply()
    }

    fun addLog(ctx: Context): List<org.json.JSONObject> {
        val arr = try { org.json.JSONArray(p(ctx).getString("addLog", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Nhận xu miễn phí khi còn dưới 1.000 xu. Trả về true nếu nhận được. */
    fun claim(ctx: Context): Boolean {
        if (xu(ctx) >= CLAIM_BELOW) return false
        setXu(ctx, START_XU)
        return true
    }

    fun fmt(v: Long): String {
        val s = "%,d".format(kotlin.math.abs(v)).replace(',', '.')
        return if (v < 0) "-$s" else s
    }
}
