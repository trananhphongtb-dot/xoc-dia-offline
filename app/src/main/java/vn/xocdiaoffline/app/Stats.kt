package vn.xocdiaoffline.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Thành tích cá nhân lưu trên máy: tính riêng từng game, theo tất cả và theo từng ngày. */
object Stats {
    private fun p(ctx: Context) = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)

    class Rec(val played: Int, val wins: Int, val net: Long, val bestWin: Long, val streak: Int, val bestStreak: Int)
    class Sum(val played: Int, val wins: Int, val net: Long)

    fun get(ctx: Context, game: String): Rec {
        val s = p(ctx)
        return Rec(s.getInt("$game.played", 0), s.getInt("$game.wins", 0), s.getLong("$game.net", 0),
            s.getLong("$game.best", 0), s.getInt("$game.streak", 0), s.getInt("$game.bestStreak", 0))
    }

    private fun dayKey(daysAgo: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)
    }

    /** Cộng dồn số liệu của game trong `days` ngày gần nhất (1 = hôm nay). */
    fun recent(ctx: Context, game: String, days: Int): Sum {
        val s = p(ctx)
        var played = 0; var wins = 0; var net = 0L
        for (d in 0 until days) {
            val k = "day.${dayKey(d)}.$game"
            played += s.getInt("$k.played", 0); wins += s.getInt("$k.wins", 0); net += s.getLong("$k.net", 0)
        }
        return Sum(played, wins, net)
    }

    fun record(ctx: Context, game: String, won: Boolean, delta: Long) {
        val r = get(ctx, game)
        val s = p(ctx)
        val streak = if (won) r.streak + 1 else 0
        val k = "day.${dayKey()}.$game"
        val e = s.edit()
            .putInt("$game.played", r.played + 1)
            .putInt("$game.wins", r.wins + if (won) 1 else 0)
            .putLong("$game.net", r.net + delta)
            .putLong("$game.best", maxOf(r.bestWin, delta))
            .putInt("$game.streak", streak)
            .putInt("$game.bestStreak", maxOf(r.bestStreak, streak))
            .putInt("$k.played", s.getInt("$k.played", 0) + 1)
            .putInt("$k.wins", s.getInt("$k.wins", 0) + if (won) 1 else 0)
            .putLong("$k.net", s.getLong("$k.net", 0) + delta)
        // Dọn số liệu theo ngày cũ hơn 60 ngày
        val oldest = dayKey(60)
        for (key in s.all.keys) if (key.startsWith("day.") && key.substring(4, 12) < oldest) e.remove(key)
        e.apply()
    }

    /** Mã riêng của máy để nhận lời mời đúng người. */
    fun deviceId(ctx: Context): String {
        val s = p(ctx)
        return s.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().take(12).also {
            s.edit().putString("deviceId", it).apply()
        }
    }
}

/** Lịch sử các bàn đã chơi (giữ 50 bàn gần nhất). */
object History {
    private const val MAX = 50
    private fun p(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<JSONObject> {
        val arr = try { JSONArray(p(ctx).getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * Lưu bàn vừa rời, dựa trên trạng thái cuối cùng của bàn.
     * Chỉ lưu khi mình đã chơi ít nhất 1 ván ở bàn đó.
     */
    fun saveTable(ctx: Context, st: JSONObject?, startedAt: Long) {
        if (st == null) return
        val seats = st.optJSONArray("seats") ?: return
        val me = st.optInt("me")
        val mine = seats.optJSONObject(me) ?: return
        if (mine.optInt("played") <= 0) return
        val players = JSONArray()
        var games = 0
        for (i in 0 until seats.length()) {
            val s = seats.getJSONObject(i)
            if (s.optInt("played") <= 0) continue
            games = maxOf(games, s.optInt("played"))
            players.put(JSONObject()
                .put("name", s.optString("name"))
                .put("me", i == me)
                .put("bot", s.optString("kind") == "bot")
                .put("played", s.optInt("played"))
                .put("wins", s.optInt("wins"))
                .put("net", s.optLong("net")))
        }
        val rec = JSONObject()
            .put("game", st.optString("game"))
            .put("host", seats.getJSONObject(0).optString("name"))
            .put("solo", st.optBoolean("solo"))
            .put("bet", st.optLong("bet"))
            .put("start", startedAt)
            .put("end", System.currentTimeMillis())
            .put("games", games)
            .put("players", players)
        val list = all(ctx).toMutableList()
        list.add(0, rec)
        val arr = JSONArray()
        for (r in list.take(MAX)) arr.put(r)
        p(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().remove("list").apply()

    fun timeText(ms: Long): String = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.US).format(Date(ms))
}
