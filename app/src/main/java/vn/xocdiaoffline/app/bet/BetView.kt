package vn.xocdiaoffline.app.bet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.table.act
import vn.xocdiaoffline.app.ui.GameView
import vn.xocdiaoffline.app.ui.Painter
import vn.xocdiaoffline.app.ui.ResultPanel
import vn.xocdiaoffline.app.ui.Ui
import kotlin.math.sin
import kotlin.random.Random

/** Màn chơi Xóc đĩa và Bầu cua. */
@SuppressLint("ViewConstructor")
class BetView(ctx: Context, private val onAction: (JSONObject) -> Unit, private val onLeave: () -> Unit) :
    View(ctx), GameView {

    private val P = Painter(ctx)
    private fun dp(v: Float) = P.dp(v)
    private var state: JSONObject? = null
    private var stateAt = 0L
    private var chip = 1          // số lần mức cược
    private var showBoard = false
    private val result = ResultPanel(P)
    private var lastShake = -1
    private var shakeAt = 0L
    private var revealAt = 0L
    private var lastPhase = ""
    private val CHIPS = intArrayOf(1, 2, 5, 10)

    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = mutableListOf<Hit>()

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    override fun setState(s: JSONObject) {
        val g = s.optJSONObject("g")
        val now = System.currentTimeMillis()
        if (g != null) {
            val sh = g.optInt("shakeId")
            if (sh != lastShake) { lastShake = sh; shakeAt = now }
            val ph = g.optString("phase")
            if (ph == "reveal" && lastPhase != "reveal") revealAt = now
            lastPhase = ph
        }
        state = s; stateAt = now
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat(); val h = height.toFloat()
        P.fill.shader = RadialGradient(w / 2, h * 0.5f, maxOf(w, h) * 0.7f, Color.parseColor("#13824F"), Ui.FELT_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, P.fill); P.fill.shader = null
        val s = state ?: return
        val g = s.optJSONObject("g") ?: return
        val kind = g.optString("kind")
        val me = s.optInt("me")
        val seats = s.optJSONArray("seats") ?: return
        val players = g.optJSONArray("players").ints()
        val phase = g.optString("phase")
        val dealer = g.optInt("dealer", -1)
        val res = g.optJSONObject("result")
        val now = System.currentTimeMillis()
        val leftMs = g.optLong("left", -1).let { if (it < 0) -1L else it - (now - stateAt) }
        val shaking = phase == "bet" && now - shakeAt < 1800
        val revealing = phase == "reveal" && now - revealAt < 1200
        if (leftMs >= 0 || shaking || revealing) postInvalidateDelayed(30)

        // Hàng người chơi phía trên
        val info = g.optJSONArray("info") ?: JSONArray()
        val others = players.filter { it != me }
        val x0 = dp(170f); val x1 = w - dp(170f)
        for ((k, seat) in others.withIndex()) {
            val x = if (others.size == 1) w / 2 else x0 + (x1 - x0) * k / (others.size - 1)
            val y = dp(32f)
            val sj = seats.getJSONObject(seat)
            P.avatar(c, x, y, dp(16f), sj.optString("name"), true, seat == dealer, -1f)
            P.label(c, sj.optString("name"), x, y + dp(29f), dp(11f), Color.WHITE, true)
            val myBet = (0 until info.length()).map { info.getJSONObject(it) }.firstOrNull { it.optInt("seat") == seat }?.optLong("bet") ?: 0
            P.label(c, if (seat == dealer) "Nhà cái" else if (myBet > 0) "Đặt ${Wallet.fmt(myBet)}" else "${Wallet.fmt(sj.optLong("xu"))} xu",
                x, y + dp(42f), dp(10f), if (seat == dealer) Color.parseColor("#FF8A80") else Ui.GOLD, seat == dealer)
        }

        val doors = g.optJSONArray("doors") ?: JSONArray()
        val resultVals = g.optJSONArray("result").ints()
        val showResult = (phase == "reveal" || phase == "done") && !revealing
        val canBet = phase == "bet" && dealer != me
        val top = dp(84f); val bottom = h - dp(66f)

        fun door(i: Int, r: RectF, big: Boolean, icon: String? = null) {
            val d = doors.optJSONObject(i) ?: return
            val win = showResult && d.optInt("win") > 0
            P.fill.color = if (win) Color.parseColor("#E6F2C14E") else Color.parseColor("#990A2A1C")
            c.drawRoundRect(r, dp(14f), dp(14f), P.fill)
            P.stroke.color = if (win) Color.WHITE else Color.parseColor("#66F2C14E"); P.stroke.strokeWidth = dp(if (win) 3f else 1.5f)
            c.drawRoundRect(r, dp(14f), dp(14f), P.stroke)
            val fg = if (win) Color.parseColor("#3A2A00") else Color.WHITE
            var ty = r.top + dp(if (big) 34f else 22f)
            if (icon != null) { P.label(c, icon, r.centerX(), r.top + dp(32f), dp(26f), Color.WHITE, false); ty = r.top + dp(52f) }
            P.label(c, d.optString("name"), r.centerX(), ty, dp(if (big) 22f else 13f), fg, true)
            if (kind == "xocdia") P.label(c, "ăn x${d.optInt("mult")}", r.centerX(), ty + dp(15f), dp(11f), if (win) fg else Ui.MUTED, false)
            val total = d.optLong("total"); val mine = d.optLong("mine")
            if (total > 0) P.label(c, "Tổng ${Wallet.fmt(total)}", r.centerX(), r.bottom - dp(if (mine > 0) 20f else 8f), dp(11f), if (win) fg else Color.WHITE, false)
            if (mine > 0) {
                P.fill.color = Ui.RED
                c.drawCircle(r.right - dp(16f), r.top + dp(16f), dp(11f), P.fill)
                P.label(c, "Bạn ${Wallet.fmt(mine)}", r.centerX(), r.bottom - dp(6f), dp(11f), if (win) fg else Ui.GOLD, true)
            }
            if (win && kind == "baucua") P.label(c, "x${d.optInt("win")}", r.right - dp(16f), r.top + dp(20f), dp(12f), Color.WHITE, true)
            if (canBet) hits += Hit(r) { onAction(act("bet", "v" to i, "amt" to s.optLong("bet") * chip)) }
        }

        if (kind == "xocdia") {
            val cx = w / 2; val cy = top + (bottom - top) * 0.38f
            val sideW = w * 0.26f
            door(0, RectF(dp(14f), top, dp(14f) + sideW, top + (bottom - top) * 0.70f), true)
            door(1, RectF(w - dp(14f) - sideW, top, w - dp(14f), top + (bottom - top) * 0.70f), true)
            val rowTop = top + (bottom - top) * 0.74f
            val bw = (w - dp(28f) - dp(24f)) / 4
            for ((k, i) in listOf(2, 4, 5, 3).withIndex()) {
                val x = dp(14f) + k * (bw + dp(8f))
                door(i, RectF(x, rowTop, x + bw, bottom), false)
            }
            drawPlate(c, cx, cy, minOf((w - 2 * sideW) * 0.30f, (bottom - top) * 0.32f), resultVals, phase, now, showResult)
        } else {
            val gridR = w * 0.64f
            val cw = (gridR - dp(14f) - dp(16f)) / 3; val chh = (bottom - top - dp(8f)) / 2
            for (i in 0 until 6) {
                val col = i % 3; val row = i / 3
                val x = dp(14f) + col * (cw + dp(8f)); val y = top + row * (chh + dp(8f))
                door(i, RectF(x, y, x + cw, y + chh), false, BetRules.FACE_ICON[i])
            }
            drawDice(c, (gridR + w) / 2, (top + bottom) / 2, minOf(w - gridR, bottom - top) * 0.42f, resultVals, phase, now, showResult)
        }

        // Giữa: trạng thái / đồng hồ
        val status = when (phase) {
            "dealer" -> "Chọn nhà cái"
            "bet" -> if (shaking) (if (kind == "xocdia") "Đang xóc..." else "Đang lắc...") else "Đặt cược"
            "reveal" -> if (revealing) "Mở bát..." else (g.optString("resultText"))
            else -> g.optString("resultText")
        }
        val statusX = if (kind == "xocdia") w / 2 else w * 0.82f
        P.pill(c, status + if (leftMs >= 0 && phase != "reveal") "  ${leftMs / 1000 + 1}s" else "", statusX, top - dp(4f), dp(14f), Color.parseColor("#CC000000"), Ui.GOLD)

        // Thanh dưới: thông tin, chip, nút
        val meJ = seats.getJSONObject(me)
        P.label(c, meJ.optString("name") + if (dealer == me) " (Nhà cái)" else "", dp(14f), h - dp(38f), dp(14f), if (dealer == me) Ui.GOLD else Color.WHITE, true, Paint.Align.LEFT)
        val myBet = g.optLong("myBet")
        P.label(c, "${Wallet.fmt(meJ.optLong("xu"))} xu" + if (myBet > 0) " · đã đặt ${Wallet.fmt(myBet)}" else "", dp(14f), h - dp(20f), dp(12f), Ui.GOLD, false, Paint.Align.LEFT)
        if (canBet && res == null) {
            val bet = s.optLong("bet")
            val r0 = dp(19f)
            var x = w * 0.40f
            for (m in CHIPS) {
                val cy = h - dp(32f)
                val sel = m == chip
                P.fill.color = when (m) { 1 -> Color.parseColor("#1565C0"); 2 -> Color.parseColor("#2E7D32"); 5 -> Color.parseColor("#C62828"); else -> Color.parseColor("#6A1B9A") }
                c.drawCircle(x, cy, r0, P.fill)
                P.stroke.color = if (sel) Ui.GOLD else Color.WHITE; P.stroke.strokeWidth = dp(if (sel) 4f else 2f)
                c.drawCircle(x, cy, r0 - dp(1f), P.stroke)
                P.label(c, shortXu(bet * m), x, cy + dp(4f), dp(11f), Color.WHITE, true)
                val mm = m
                hits += Hit(RectF(x - r0, cy - r0, x + r0, cy + r0)) { chip = mm }
                x += r0 * 2 + dp(10f)
            }
        }
        val acts = g.optJSONArray("actions") ?: JSONArray()
        if (res == null) {
            var bx = w - dp(12f)
            for (i in 0 until acts.length()) {
                val a = acts.getJSONObject(i)
                val col = when (a.optString("style")) { "gold" -> Ui.GOLD; "red" -> Color.parseColor("#B71C1C"); else -> Ui.GREY }
                val r = RectF(bx - dp(104f), h - dp(52f), bx, h - dp(12f))
                P.button(c, r, a.optString("label"), col, if (col == Ui.GOLD) Color.parseColor("#3A2A00") else Color.WHITE)
                hits += Hit(r) { onAction(act(a.optString("a"))) }
                bx -= dp(112f)
            }
            val hint = g.optString("hint")
            if (hint.isNotEmpty() && !canBet) P.label(c, hint, w * 0.52f, h - dp(26f), dp(12f), Color.WHITE, false)
        }

        // Thanh trên cùng
        val leave = RectF(dp(10f), dp(10f), dp(100f), dp(40f))
        P.button(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(12f))
        hits += Hit(leave) { onLeave() }
        P.label(c, "Cược ${Wallet.fmt(s.optLong("bet"))}", w - dp(14f), dp(22f), dp(12f), Ui.GOLD, true, Paint.Align.RIGHT)
        val boardBtn = RectF(w - dp(112f), dp(30f), w - dp(10f), dp(58f))
        P.button(c, boardBtn, "📊 Bảng điểm", Color.parseColor("#66000000"), Color.WHITE, dp(12f))
        hits += Hit(boardBtn) { showBoard = !showBoard }

        if (res != null) result.draw(c, w, h, s, res, acts, { r, fn -> hits += Hit(r, fn) }, onAction)
        if (showBoard) { result.drawBoard(c, w, h, s); hits.clear(); hits += Hit(RectF(0f, 0f, w, h)) { showBoard = false } }
    }

    private fun shortXu(v: Long) = if (v >= 1000 && v % 1000 == 0L) "${v / 1000}K" else Wallet.fmt(v)

    /** Đĩa + bát + 4 quân vị. */
    private fun drawPlate(c: Canvas, cx: Float, cy: Float, r: Float, coins: List<Int>, phase: String, now: Long, show: Boolean) {
        var ox = 0f; var oy = 0f
        if (phase == "bet" && now - shakeAt < 1800) {
            val t = (now - shakeAt) / 1000f
            ox = sin(t * 38f) * dp(9f); oy = sin(t * 29f + 1f) * dp(6f)
        }
        P.fill.color = Color.WHITE
        c.drawCircle(cx + ox, cy + oy, r, P.fill)
        P.stroke.color = Color.parseColor("#BDBDBD"); P.stroke.strokeWidth = dp(3f)
        c.drawCircle(cx + ox, cy + oy, r * 0.82f, P.stroke)
        if (coins.size == 4 && (show || phase == "reveal")) {
            val rr = r * 0.2f
            val pos = listOf(-0.35f to -0.3f, 0.32f to -0.34f, -0.3f to 0.33f, 0.36f to 0.3f)
            for (i in 0 until 4) {
                val x = cx + pos[i].first * r; val y = cy + pos[i].second * r
                P.fill.color = if (coins[i] == 1) Color.parseColor("#D32F2F") else Color.parseColor("#FAFAFA")
                c.drawCircle(x, y, rr, P.fill)
                P.stroke.color = Color.parseColor("#9E9E9E"); P.stroke.strokeWidth = dp(1.5f)
                c.drawCircle(x, y, rr, P.stroke)
            }
        }
        // Bát úp: nhấc lên khi mở
        var lift = 0f
        if (phase == "reveal") lift = Painter.ease((now - revealAt) / 900f)
        if (phase == "done") lift = 1f
        if (lift < 1f) {
            val by = cy + oy - lift * r * 1.6f
            P.fill.shader = RadialGradient(cx + ox - r * 0.25f, by - r * 0.25f, r * 1.1f,
                Color.parseColor("#F5F5F5"), Color.parseColor("#9E9E9E"), Shader.TileMode.CLAMP)
            P.fill.alpha = ((1f - lift) * 255).toInt()
            c.drawCircle(cx + ox, by, r * 0.86f, P.fill)
            P.fill.shader = null; P.fill.alpha = 255
            P.stroke.color = Color.parseColor("#1565C0"); P.stroke.strokeWidth = dp(2f)
            c.drawCircle(cx + ox, by, r * 0.6f, P.stroke)
        }
    }

    /** Khay 3 xúc xắc bầu cua. */
    private fun drawDice(c: Canvas, cx: Float, cy: Float, r: Float, dice: List<Int>, phase: String, now: Long, show: Boolean) {
        var ox = 0f; var oy = 0f
        if (phase == "bet" && now - shakeAt < 1800) {
            val t = (now - shakeAt) / 1000f
            ox = sin(t * 38f) * dp(9f); oy = sin(t * 29f + 1f) * dp(6f)
        }
        P.fill.color = Color.parseColor("#FFF3E0")
        c.drawCircle(cx + ox, cy + oy, r, P.fill)
        P.stroke.color = Color.parseColor("#8D6E63"); P.stroke.strokeWidth = dp(3f)
        c.drawCircle(cx + ox, cy + oy, r, P.stroke)
        if (dice.size == 3 && (show || phase == "reveal")) {
            val rolling = phase == "reveal" && now - revealAt < 900
            val sz = r * 0.5f
            val pos = listOf(-0.42f to -0.2f, 0.42f to -0.2f, 0f to 0.42f)
            for (i in 0 until 3) {
                val x = cx + pos[i].first * r; val y = cy + pos[i].second * r
                val f = if (rolling) Random.nextInt(6) else dice[i]
                P.fill.color = Color.WHITE
                c.drawRoundRect(RectF(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2), sz * 0.18f, sz * 0.18f, P.fill)
                P.stroke.color = Color.parseColor("#9E9E9E"); P.stroke.strokeWidth = dp(1.5f)
                c.drawRoundRect(RectF(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2), sz * 0.18f, sz * 0.18f, P.stroke)
                P.label(c, BetRules.FACE_ICON[f], x, y + sz * 0.22f, sz * 0.6f, Color.BLACK, false)
            }
        }
        var lift = 0f
        if (phase == "reveal") lift = Painter.ease((now - revealAt) / 900f)
        if (phase == "done") lift = 1f
        if (lift < 1f) {
            val by = cy + oy - lift * r * 1.6f
            P.fill.shader = RadialGradient(cx + ox - r * 0.25f, by - r * 0.25f, r * 1.1f,
                Color.parseColor("#E57373"), Color.parseColor("#8E0000"), Shader.TileMode.CLAMP)
            P.fill.alpha = ((1f - lift) * 255).toInt()
            c.drawCircle(cx + ox, by, r * 0.92f, P.fill)
            P.fill.shader = null; P.fill.alpha = 255
            P.stroke.color = Ui.GOLD; P.stroke.strokeWidth = dp(2f)
            c.drawCircle(cx + ox, by, r * 0.62f, P.stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for (hh in hits.asReversed()) if (hh.rect.contains(e.x, e.y)) { hh.action(); invalidate(); return true }
        return true
    }
}
