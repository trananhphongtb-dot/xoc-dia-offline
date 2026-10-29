package vn.xocdiaoffline.app

import android.content.Context

/** Thành tích cá nhân lưu trên máy, tính riêng từng game. */
object Stats {
    private fun p(ctx: Context) = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)

    class Rec(val played: Int, val wins: Int, val net: Long, val bestWin: Long, val streak: Int, val bestStreak: Int)

    fun get(ctx: Context, game: String): Rec {
        val s = p(ctx)
        return Rec(s.getInt("$game.played", 0), s.getInt("$game.wins", 0), s.getLong("$game.net", 0),
            s.getLong("$game.best", 0), s.getInt("$game.streak", 0), s.getInt("$game.bestStreak", 0))
    }

    fun record(ctx: Context, game: String, won: Boolean, delta: Long) {
        val r = get(ctx, game)
        val streak = if (won) r.streak + 1 else 0
        p(ctx).edit()
            .putInt("$game.played", r.played + 1)
            .putInt("$game.wins", r.wins + if (won) 1 else 0)
            .putLong("$game.net", r.net + delta)
            .putLong("$game.best", maxOf(r.bestWin, delta))
            .putInt("$game.streak", streak)
            .putInt("$game.bestStreak", maxOf(r.bestStreak, streak))
            .apply()
    }

    /** Mã riêng của máy để nhận lời mời đúng người. */
    fun deviceId(ctx: Context): String {
        val s = p(ctx)
        return s.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().take(12).also {
            s.edit().putString("deviceId", it).apply()
        }
    }
}
