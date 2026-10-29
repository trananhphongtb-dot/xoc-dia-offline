package vn.xocdiaoffline.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.table.act

/** Khung kết quả cuối ván và bảng điểm cả buổi, dùng chung cho các game mới. */
class ResultPanel(private val P: Painter) {
    private fun dp(v: Float) = P.dp(v)
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    /**
     * lines[i]: seat, name, delta, note, cards (lá rác/bài), phoms (tùy chọn: các phỏm),
     *           extra (tùy chọn: chữ thay cho bài, ví dụ "Cửa Chẵn 2.000").
     * cardKind: "a13" (A..K) — mặc định.
     */
    fun draw(c: Canvas, w: Float, h: Float, s: JSONObject, res: JSONObject, acts: JSONArray,
             addHit: (RectF, () -> Unit) -> Unit, onAction: (JSONObject) -> Unit) {
        P.fill.color = Color.parseColor("#B3000000")
        c.drawRect(0f, 0f, w, h, P.fill)
        val pw = minOf(w * 0.76f, dp(640f)); val ph = h * 0.88f
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        P.fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), P.fill)
        P.stroke.color = Ui.GOLD; P.stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), P.stroke)
        P.label(c, "🏆 " + res.optString("title"), w / 2, panel.top + dp(30f), dp(18f), Ui.GOLD, true)
        val sub = res.optString("sub")
        val seats = s.getJSONArray("seats")
        val me = s.optInt("me")
        val lines = res.optJSONArray("lines") ?: JSONArray()
        var top = panel.top + dp(42f)
        if (sub.isNotEmpty()) { P.label(c, sub, w / 2, panel.top + dp(50f), dp(13f), Color.WHITE, false); top += dp(14f) }
        val rowH = ((panel.bottom - dp(60f) - top) / maxOf(lines.length(), 1)).coerceAtMost(dp(56f))
        for (i in 0 until lines.length()) {
            val ln = lines.getJSONObject(i)
            val y = top + rowH * i
            val isMe = ln.optInt("seat") == me
            if (isMe) {
                P.fill.color = Color.parseColor("#22F2C14E")
                c.drawRoundRect(RectF(panel.left + dp(10f), y + dp(1f), panel.right - dp(10f), y + rowH - dp(1f)), dp(10f), dp(10f), P.fill)
            }
            P.label(c, if (isMe) "Bạn" else ln.optString("name"), panel.left + dp(22f), y + rowH * 0.45f, dp(14f), Color.WHITE, true, Paint.Align.LEFT)
            P.label(c, ln.optString("note"), panel.left + dp(22f), y + rowH * 0.45f + dp(15f), dp(11f), Ui.MUTED, false, Paint.Align.LEFT)
            // Bài: phỏm (viền xanh) rồi rác
            val chh = (rowH - dp(8f)).coerceAtMost(dp(40f)); val cww = chh * 0.7f
            var x = panel.left + pw * 0.36f
            val phoms = ln.optJSONArray("phoms")
            if (phoms != null) for (k in 0 until phoms.length()) {
                for (card in phoms.getJSONArray(k).ints()) { P.faceA13(c, x, y + (rowH - chh) / 2, cww, chh, card, mark = true); x += cww * 0.45f }
                x += cww * 0.7f
            }
            for (card in ln.optJSONArray("cards").ints()) { P.faceA13(c, x, y + (rowH - chh) / 2, cww, chh, card); x += cww * 0.45f }
            val extra = ln.optString("extra")
            if (extra.isNotEmpty()) P.label(c, extra, panel.left + pw * 0.36f, y + rowH * 0.55f, dp(12f), Color.WHITE, false, Paint.Align.LEFT)
            val delta = ln.optLong("delta")
            val col = when { delta > 0 -> Color.parseColor("#69F0AE"); delta < 0 -> Color.parseColor("#FF8A80"); else -> Color.WHITE }
            P.label(c, (if (delta > 0) "+" else "") + Wallet.fmt(delta) + " xu", panel.right - dp(22f), y + rowH * 0.45f, dp(16f), col, true, Paint.Align.RIGHT)
            val sj = seats.getJSONObject(ln.optInt("seat"))
            val sessNet = sj.optLong("net")
            P.label(c, "Cả buổi: thắng ${sj.optInt("wins")}/${sj.optInt("played")}, " + (if (sessNet > 0) "+" else "") + Wallet.fmt(sessNet),
                panel.right - dp(22f), y + rowH * 0.45f + dp(14f), dp(10f), Ui.MUTED, false, Paint.Align.RIGHT)
        }

        val by = panel.bottom - dp(30f)
        val buttons = mutableListOf<Triple<String, Int, () -> Unit>>()
        if (s.optBoolean("host")) buttons += Triple(if (s.optBoolean("solo")) "Đổi mức cược" else "Về phòng chờ", Ui.GREY) { onAction(act("lobby")) }
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val col = when (a.optString("style")) { "gold" -> Ui.GOLD; "blue" -> Ui.BLUE; "red" -> Color.parseColor("#B71C1C"); else -> Ui.GREY }
            buttons += Triple(a.optString("label"), col) { onAction(act(a.optString("a"), "v" to a.optLong("v"))) }
        }
        if (s.optBoolean("host")) buttons += Triple("Ván mới", Ui.GOLD) { onAction(act("next")) }
        if (buttons.isEmpty()) {
            P.label(c, "Chờ chủ bàn chia ván mới...", w / 2, by + dp(6f), dp(15f), Ui.MUTED, true)
        } else {
            val bw = dp(140f); val gapX = dp(12f)
            var x = w / 2 - (buttons.size * bw + (buttons.size - 1) * gapX) / 2
            for ((t, col, fn) in buttons) {
                val r = RectF(x, by - dp(20f), x + bw, by + dp(20f))
                P.button(c, r, t, col, if (col == Ui.GOLD) Color.parseColor("#3A2A00") else Color.WHITE, dp(14f))
                addHit(r, fn)
                x += bw + gapX
            }
            if (!s.optBoolean("host")) P.label(c, "Chờ chủ bàn chia ván mới...", w / 2, by - dp(28f), dp(12f), Ui.MUTED, false)
        }
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        P.button(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
    }

    fun drawBoard(c: Canvas, w: Float, h: Float, s: JSONObject) {
        val seats = s.optJSONArray("seats") ?: return
        val me = s.optInt("me")
        val rows = (0 until seats.length()).map { it to seats.getJSONObject(it) }
            .filter { it.second.optString("kind") != "empty" }
            .sortedByDescending { it.second.optLong("net") }
        P.fill.color = Color.parseColor("#99000000")
        c.drawRect(0f, 0f, w, h, P.fill)
        val pw = minOf(w * 0.7f, dp(520f)); val rowH = dp(36f)
        val ph = dp(90f) + rowH * rows.size
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        P.fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), P.fill)
        P.stroke.color = Ui.GOLD; P.stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), P.stroke)
        P.label(c, "📊 Bảng điểm cả buổi", w / 2, panel.top + dp(30f), dp(18f), Ui.GOLD, true)
        val colWin = panel.left + pw * 0.58f
        val colNet = panel.right - dp(20f)
        P.label(c, "Thắng / Ván", colWin, panel.top + dp(52f), dp(12f), Ui.MUTED, false)
        P.label(c, "Xu thắng thua", colNet, panel.top + dp(52f), dp(12f), Ui.MUTED, false, Paint.Align.RIGHT)
        for ((k, pair) in rows.withIndex()) {
            val (i, sj) = pair
            val y = panel.top + dp(60f) + rowH * k
            if (i == me) {
                P.fill.color = Color.parseColor("#22F2C14E")
                c.drawRoundRect(RectF(panel.left + dp(10f), y, panel.right - dp(10f), y + rowH - dp(4f)), dp(8f), dp(8f), P.fill)
            }
            val base = y + rowH * 0.62f
            P.label(c, "${k + 1}. " + (if (i == me) "Bạn" else sj.optString("name")), panel.left + dp(22f), base, dp(15f), Color.WHITE, true, Paint.Align.LEFT)
            P.label(c, "${sj.optInt("wins")} / ${sj.optInt("played")}", colWin, base, dp(15f), Color.WHITE, false)
            val net = sj.optLong("net")
            val col = when { net > 0 -> Color.parseColor("#69F0AE"); net < 0 -> Color.parseColor("#FF8A80"); else -> Color.WHITE }
            P.label(c, (if (net > 0) "+" else "") + Wallet.fmt(net), colNet, base, dp(15f), col, true, Paint.Align.RIGHT)
        }
        P.label(c, "Chạm để đóng", w / 2, panel.bottom - dp(10f), dp(11f), Ui.MUTED, false)
    }
}
