package vn.xocdiaoffline.app.tienlen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.table.act
import vn.xocdiaoffline.app.ui.Ui

/** Màn hình bàn Tiến lên, vẽ hoàn toàn bằng Canvas. */
@SuppressLint("ViewConstructor")
class TLView(ctx: Context, private val onAction: (JSONObject) -> Unit, private val onLeave: () -> Unit) : View(ctx), vn.xocdiaoffline.app.ui.GameView {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var state: JSONObject? = null
    private var stateAt = 0L
    private val selected = linkedSetOf<Int>()
    private var lastEventId = -1
    private var eventText = ""
    private var eventAt = 0L
    private var showBoard = false

    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = mutableListOf<Hit>()
    private val cardHits = mutableListOf<Pair<RectF, Int>>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    override fun setState(s: JSONObject) {
        state = s
        stateAt = System.currentTimeMillis()
        val g = s.optJSONObject("g")
        if (g != null) {
            val hand = g.optJSONArray("hand").toIntList().toSet()
            selected.retainAll(hand)
            val eid = g.optInt("eventId", -1)
            if (eid != lastEventId) {
                lastEventId = eid
                val e = g.optString("event")
                if (e.isNotEmpty()) { eventText = e; eventAt = System.currentTimeMillis() }
            }
        }
        invalidate()
    }

    private fun JSONArray?.toIntList(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    // ------------------------------------------------------------------ vẽ
    override fun onDraw(c: Canvas) {
        hits.clear(); cardHits.clear()
        val w = width.toFloat(); val h = height.toFloat()
        drawTable(c, w, h)
        val s = state ?: return
        val g = s.optJSONObject("g") ?: return
        val me = s.optInt("me")
        val seats = s.optJSONArray("seats") ?: return
        val players = g.optJSONArray("players").toIntList()
        val counts = g.optJSONArray("counts").toIntList()
        val passed = g.optJSONArray("passed").toIntList().toSet()
        val turn = g.optInt("turn", -1)
        val result = g.optJSONObject("result")
        val leftMs = g.optLong("left", -1).let { if (it < 0) -1L else it - (System.currentTimeMillis() - stateAt) }

        // Vị trí các đối thủ quanh bàn (mình ở dưới, người kế tiếp ở bên phải)
        val n = players.size
        val myIdx = players.indexOf(me).coerceAtLeast(0)
        val slots = when (n) { 2 -> listOf(2); 3 -> listOf(1, 3); else -> listOf(1, 2, 3) }
        for (k in 1 until n) {
            val seat = players[(myIdx + k) % n]
            val (x, y) = when (slots[k - 1]) {
                1 -> w - dp(78f) to h * 0.40f
                2 -> w / 2f to dp(58f)
                else -> dp(78f) to h * 0.40f
            }
            val sj = seats.getJSONObject(seat)
            drawPlayer(c, x, y, sj.optString("name"), sj.optLong("xu"), counts[(myIdx + k) % n],
                seat == turn, seat in passed, if (seat == turn) leftMs else -1, result == null, slots[k - 1])
        }

        // Bộ bài trên bàn
        g.optJSONObject("last")?.let { last ->
            val cards = last.optJSONArray("cards").toIntList()
            val ch = h * 0.21f; val cw = ch * 0.7f
            val gap = minOf(cw * 0.55f, w * 0.5f / cards.size)
            val total = cw + gap * (cards.size - 1)
            var x = w / 2f - total / 2f
            val y = h * 0.47f - ch / 2f
            for (card in cards) { drawCard(c, x, y, cw, ch, card, false, false); x += gap }
            val by = seats.getJSONObject(last.optInt("by")).optString("name")
            label(c, "$by · ${last.optString("desc")}", w / 2f, y - dp(8f), dp(13f), Ui.MUTED, true)
        } ?: run {
            if (result == null && turn >= 0) {
                val who = if (turn == me) "Bạn" else seats.getJSONObject(turn).optString("name")
                label(c, "$who đi đầu vòng", w / 2f, h * 0.47f, dp(15f), Ui.MUTED, true)
            }
        }

        // Thông báo sự kiện (chặt heo, vòng mới)
        if (eventText.isNotEmpty() && System.currentTimeMillis() - eventAt < 2600) {
            val big = eventText.contains("chặt")
            val ty = h * 0.25f
            txt.textSize = dp(if (big) 22f else 15f); txt.typeface = bold
            val tw = txt.measureText(eventText)
            fill.color = if (big) Color.parseColor("#E6B71C1C") else Color.parseColor("#99000000")
            c.drawRoundRect(RectF(w / 2 - tw / 2 - dp(14f), ty - dp(if (big) 30f else 22f), w / 2 + tw / 2 + dp(14f), ty + dp(10f)), dp(12f), dp(12f), fill)
            label(c, eventText, w / 2f, ty, txt.textSize, if (big) Ui.GOLD else Color.WHITE, true)
            postInvalidateDelayed(100)
        }

        // Bài của mình
        val hand = g.optJSONArray("hand").toIntList()
        val must = g.optInt("must", -1)
        val myTurn = turn == me && result == null
        val ch = h * 0.25f; val cw = ch * 0.7f
        if (hand.isNotEmpty()) {
            val maxW = w * 0.9f
            val gap = if (hand.size > 1) minOf(cw * 0.66f, (maxW - cw) / (hand.size - 1)) else 0f
            val total = cw + gap * (hand.size - 1)
            var x = w / 2f - total / 2f
            val baseY = h - ch - dp(6f)
            for ((i, card) in hand.withIndex()) {
                val sel = card in selected
                val y = if (sel) baseY - ch * 0.2f else baseY
                drawCard(c, x, y, cw, ch, card, sel, card == must)
                val hitW = if (i == hand.size - 1) cw else gap
                cardHits += RectF(x, y, x + hitW, y + ch) to card
                x += gap
            }
        }

        // Thông tin của mình + nút
        val meJ = seats.getJSONObject(me)
        val infoY = h - ch - dp(22f) - ch * 0.2f
        txt.textAlign = Paint.Align.LEFT
        txt.textSize = dp(14f); txt.typeface = bold; txt.color = if (myTurn) Ui.GOLD else Color.WHITE
        c.drawText((if (myTurn) "▶ " else "") + meJ.optString("name"), dp(14f), infoY, txt)
        txt.typeface = Typeface.DEFAULT; txt.color = Ui.GOLD; txt.textSize = dp(13f)
        c.drawText("${Wallet.fmt(meJ.optLong("xu"))} xu", dp(14f), infoY + dp(17f), txt)
        if (myTurn && leftMs >= 0) {
            val frac = (leftMs / 25000f).coerceIn(0f, 1f)
            fill.color = Color.parseColor("#55000000")
            c.drawRoundRect(RectF(dp(14f), infoY + dp(24f), dp(134f), infoY + dp(30f)), dp(3f), dp(3f), fill)
            fill.color = if (frac < 0.3f) Ui.RED else Ui.GOLD
            c.drawRoundRect(RectF(dp(14f), infoY + dp(24f), dp(14f) + dp(120f) * frac, infoY + dp(30f)), dp(3f), dp(3f), fill)
            postInvalidateDelayed(100)
        }
        if (me in passed && result == null) label(c, "Bạn đã bỏ lượt", w / 2f, infoY, dp(13f), Ui.MUTED, false)

        if (result == null) {
            var bx = w - dp(12f)
            val by = infoY - dp(14f)
            fun btn(text: String, color: Int, fg: Int, action: () -> Unit) {
                txt.textSize = dp(15f); txt.typeface = bold
                val bw = txt.measureText(text) + dp(32f)
                val r = RectF(bx - bw, by - dp(20f), bx, by + dp(20f))
                drawButton(c, r, text, color, fg)
                hits += Hit(r, action)
                bx -= bw + dp(10f)
            }
            if (myTurn) {
                btn("Đánh", Ui.GOLD, Color.parseColor("#3A2A00")) {
                    if (selected.isEmpty()) toast("Chọn lá bài muốn đánh trước đã")
                    else onAction(act("play", "cards" to JSONArray(selected.sorted())))
                }
                if (!g.optBoolean("leading")) btn("Bỏ lượt", Ui.GREY, Color.WHITE) { selected.clear(); onAction(act("pass")) }
            }
            if (selected.isNotEmpty()) btn("Bỏ chọn", Color.parseColor("#33FFFFFF"), Color.WHITE) { selected.clear(); invalidate() }
            if (myTurn && must >= 0) label(c, "Lượt đầu phải đánh lá ${Cards.name(must)}", w / 2f, h * 0.60f, dp(13f), Ui.GOLD, true)
        }

        // Thanh trên cùng
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        drawButton(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(leave) { onLeave() }
        txt.textAlign = Paint.Align.RIGHT; txt.textSize = dp(13f); txt.color = Ui.GOLD; txt.typeface = bold
        c.drawText("Cược ${Wallet.fmt(s.optLong("bet"))} xu", w - dp(14f), dp(30f), txt)
        val boardBtn = RectF(w - dp(126f), dp(40f), w - dp(10f), dp(72f))
        drawButton(c, boardBtn, "📊 Bảng điểm", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(boardBtn) { showBoard = !showBoard }

        if (result != null) drawResult(c, w, h, s, result)
        if (showBoard) drawBoard(c, w, h, s)
    }

    /** Bảng điểm cả buổi của mọi người trong bàn. */
    private fun drawBoard(c: Canvas, w: Float, h: Float, s: JSONObject) {
        val seats = s.optJSONArray("seats") ?: return
        val me = s.optInt("me")
        val rows = (0 until seats.length()).map { it to seats.getJSONObject(it) }
            .filter { it.second.optString("kind") != "empty" }
            .sortedByDescending { it.second.optLong("net") }
        fill.color = Color.parseColor("#99000000")
        c.drawRect(0f, 0f, w, h, fill)
        val pw = minOf(w * 0.7f, dp(520f)); val rowH = dp(40f)
        val ph = dp(96f) + rowH * rows.size
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), fill)
        stroke.color = Ui.GOLD; stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), stroke)
        label(c, "📊 Bảng điểm cả buổi", w / 2, panel.top + dp(32f), dp(18f), Ui.GOLD, true)
        val colWin = panel.left + pw * 0.58f
        val colNet = panel.right - dp(20f)
        txt.textSize = dp(12f); txt.color = Ui.MUTED; txt.typeface = Typeface.DEFAULT
        txt.textAlign = Paint.Align.CENTER; c.drawText("Thắng / Ván", colWin, panel.top + dp(56f), txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText("Xu thắng thua", colNet, panel.top + dp(56f), txt)
        for ((k, pair) in rows.withIndex()) {
            val (i, sj) = pair
            val y = panel.top + dp(66f) + rowH * k
            if (i == me) {
                fill.color = Color.parseColor("#22F2C14E")
                c.drawRoundRect(RectF(panel.left + dp(10f), y, panel.right - dp(10f), y + rowH - dp(4f)), dp(8f), dp(8f), fill)
            }
            val base = y + rowH * 0.62f
            txt.textAlign = Paint.Align.LEFT; txt.textSize = dp(15f); txt.typeface = bold; txt.color = Color.WHITE
            c.drawText("${k + 1}. " + (if (i == me) "Bạn" else sj.optString("name")), panel.left + dp(22f), base, txt)
            txt.textAlign = Paint.Align.CENTER; txt.typeface = Typeface.DEFAULT
            c.drawText("${sj.optInt("wins")} / ${sj.optInt("played")}", colWin, base, txt)
            val net = sj.optLong("net")
            txt.textAlign = Paint.Align.RIGHT; txt.typeface = bold
            txt.color = when { net > 0 -> Color.parseColor("#69F0AE"); net < 0 -> Color.parseColor("#FF8A80"); else -> Color.WHITE }
            c.drawText((if (net > 0) "+" else "") + Wallet.fmt(net), colNet, base, txt)
        }
        label(c, "Chạm để đóng", w / 2, panel.bottom - dp(12f), dp(11f), Ui.MUTED, false)
        hits.clear()
        hits += Hit(RectF(0f, 0f, w, h)) { showBoard = false }
    }

    private fun drawTable(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.45f, maxOf(w, h) * 0.7f,
            Color.parseColor("#13824F"), Ui.FELT_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        stroke.color = Color.parseColor("#33F2C14E"); stroke.strokeWidth = dp(2f)
        c.drawRoundRect(RectF(w * 0.12f, h * 0.16f, w * 0.88f, h * 0.70f), h * 0.27f, h * 0.27f, stroke)
    }

    private fun drawPlayer(c: Canvas, x: Float, y: Float, name: String, xu: Long, count: Int,
                           isTurn: Boolean, isPassed: Boolean, leftMs: Long, showCards: Boolean, slot: Int) {
        val r = dp(24f)
        fill.color = Color.parseColor("#1E3A2C")
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = dp(3f)
        stroke.color = if (isTurn) Ui.GOLD else Color.parseColor("#55FFFFFF")
        c.drawCircle(x, y, r, stroke)
        if (isTurn && leftMs >= 0) {
            val frac = (leftMs / 25000f).coerceIn(0f, 1f)
            stroke.color = if (frac < 0.3f) Ui.RED else Color.WHITE
            c.drawArc(RectF(x - r - dp(5f), y - r - dp(5f), x + r + dp(5f), y + r + dp(5f)), -90f, 360f * frac, false, stroke)
            postInvalidateDelayed(100)
        } else if (isTurn) postInvalidateDelayed(200)
        label(c, name.take(1).uppercase(), x, y + dp(8f), dp(22f), Color.WHITE, true)
        label(c, name, x, y + r + dp(16f), dp(13f), if (isTurn) Ui.GOLD else Color.WHITE, true)
        label(c, "${Wallet.fmt(xu)} xu", x, y + r + dp(31f), dp(11f), Ui.GOLD, false)
        if (isPassed) {
            fill.color = Color.parseColor("#CC37474F")
            c.drawRoundRect(RectF(x - dp(30f), y - r - dp(20f), x + dp(30f), y - r - dp(3f)), dp(8f), dp(8f), fill)
            label(c, "Bỏ lượt", x, y - r - dp(7f), dp(11f), Color.WHITE, true)
        }
        if (showCards && count > 0) {
            val ch = dp(44f); val cw = ch * 0.7f
            val cx = when (slot) { 1 -> x - r - dp(14f) - cw; 3 -> x + r + dp(14f); else -> x + r + dp(14f) }
            val cy = y - ch / 2
            drawBack(c, cx, cy, cw, ch)
            label(c, count.toString(), cx + cw / 2, cy + ch / 2 + dp(7f), dp(18f), Color.WHITE, true)
        }
    }

    private fun drawCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, card: Int, sel: Boolean, mark: Boolean) {
        val r = RectF(x, y, x + w, y + h)
        fill.color = Color.parseColor("#40000000")
        c.drawRoundRect(RectF(x + dp(1.5f), y + dp(2f), x + w + dp(1.5f), y + h + dp(2f)), w * 0.1f, w * 0.1f, fill)
        fill.color = if (sel) Color.parseColor("#FFF8E1") else Color.WHITE
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, fill)
        stroke.strokeWidth = if (sel || mark) dp(2.5f) else dp(1f)
        stroke.color = if (sel || mark) Ui.GOLD else Color.parseColor("#9E9E9E")
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, stroke)
        val color = if (Cards.isRed(card)) Color.parseColor("#D32F2F") else Color.parseColor("#212121")
        txt.color = color; txt.textAlign = Paint.Align.CENTER; txt.typeface = bold
        txt.textSize = h * 0.21f
        val cx = x + w * 0.2f
        c.drawText(Cards.rankText(card), cx, y + h * 0.22f, txt)
        txt.textSize = h * 0.17f
        c.drawText(Cards.suitText(card), cx, y + h * 0.40f, txt)
        txt.textSize = h * 0.40f
        c.drawText(Cards.suitText(card), x + w * 0.58f, y + h * 0.82f, txt)
    }

    private fun drawBack(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = RectF(x, y, x + w, y + h)
        fill.shader = LinearGradient(x, y, x + w, y + h, Color.parseColor("#C62828"), Color.parseColor("#7F0000"), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, fill)
        fill.shader = null
        stroke.color = Color.WHITE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, stroke)
        stroke.color = Color.parseColor("#88F2C14E"); stroke.strokeWidth = dp(1f)
        c.drawRoundRect(RectF(x + w * 0.14f, y + w * 0.14f, x + w * 0.86f, y + h - w * 0.14f), w * 0.08f, w * 0.08f, stroke)
    }

    private fun drawButton(c: Canvas, r: RectF, text: String, bg: Int, fg: Int, size: Float = dp(15f)) {
        fill.color = bg
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        label(c, text, r.centerX(), r.centerY() + size * 0.35f, size, fg, true)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, isBold: Boolean) {
        txt.textAlign = Paint.Align.CENTER; txt.textSize = size; txt.color = color
        txt.typeface = if (isBold) bold else Typeface.DEFAULT
        c.drawText(s, x, y, txt)
    }

    private fun drawResult(c: Canvas, w: Float, h: Float, s: JSONObject, res: JSONObject) {
        fill.color = Color.parseColor("#B3000000")
        c.drawRect(0f, 0f, w, h, fill)
        val pw = minOf(w * 0.78f, dp(620f)); val ph = h * 0.86f
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), fill)
        stroke.color = Ui.GOLD; stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), stroke)

        val seats = s.getJSONArray("seats")
        val me = s.optInt("me")
        val winner = res.optInt("winner")
        val winName = if (winner == me) "Bạn" else seats.getJSONObject(winner).optString("name")
        label(c, "🏆 $winName thắng ván này!", w / 2, panel.top + dp(34f), dp(20f), Ui.GOLD, true)
        val reason = res.optString("reason")
        if (reason.isNotEmpty()) label(c, reason, w / 2, panel.top + dp(56f), dp(14f), Color.WHITE, true)

        val lines = res.optJSONArray("lines") ?: JSONArray()
        val top = panel.top + dp(if (reason.isNotEmpty()) 74f else 62f)
        val bottomArea = dp(64f)
        val rowH = ((panel.bottom - bottomArea - top) / maxOf(lines.length(), 1)).coerceAtMost(dp(62f))
        for (i in 0 until lines.length()) {
            val ln = lines.getJSONObject(i)
            val y = top + rowH * i
            val isMe = ln.optInt("seat") == me
            if (isMe) {
                fill.color = Color.parseColor("#22F2C14E")
                c.drawRoundRect(RectF(panel.left + dp(10f), y + dp(2f), panel.right - dp(10f), y + rowH - dp(2f)), dp(10f), dp(10f), fill)
            }
            txt.textAlign = Paint.Align.LEFT; txt.typeface = bold; txt.textSize = dp(15f); txt.color = Color.WHITE
            c.drawText(if (isMe) "Bạn" else ln.optString("name"), panel.left + dp(22f), y + rowH * 0.42f, txt)
            txt.typeface = Typeface.DEFAULT; txt.textSize = dp(12f); txt.color = Ui.MUTED
            c.drawText(ln.optString("note"), panel.left + dp(22f), y + rowH * 0.42f + dp(17f), txt)
            val delta = ln.optLong("delta")
            txt.textAlign = Paint.Align.RIGHT; txt.typeface = bold; txt.textSize = dp(17f)
            txt.color = when { delta > 0 -> Color.parseColor("#69F0AE"); delta < 0 -> Color.parseColor("#FF8A80"); else -> Color.WHITE }
            c.drawText((if (delta > 0) "+" else "") + Wallet.fmt(delta) + " xu", panel.right - dp(22f), y + rowH * 0.45f, txt)
            val sj = seats.getJSONObject(ln.optInt("seat"))
            val sessNet = sj.optLong("net")
            txt.typeface = Typeface.DEFAULT; txt.textSize = dp(11f); txt.color = Ui.MUTED
            c.drawText("Cả buổi: thắng ${sj.optInt("wins")}/${sj.optInt("played")}, " + (if (sessNet > 0) "+" else "") + Wallet.fmt(sessNet),
                panel.right - dp(22f), y + rowH * 0.45f + dp(15f), txt)
            // Bài còn lại
            val cards = ln.optJSONArray("cards").toIntList()
            if (cards.isNotEmpty()) {
                val ch = (rowH - dp(10f)).coerceAtMost(dp(44f)); val cw = ch * 0.7f
                val gap = minOf(cw * 0.5f, pw * 0.26f / cards.size)
                var x = panel.left + pw * 0.36f
                for (card in cards) { drawCard(c, x, y + (rowH - ch) / 2, cw, ch, card, false, false); x += gap }
            }
        }

        val by = panel.bottom - dp(34f)
        if (s.optBoolean("host")) {
            val r1 = RectF(w / 2 + dp(8f), by - dp(22f), w / 2 + dp(158f), by + dp(22f))
            drawButton(c, r1, "Ván mới", Ui.GOLD, Color.parseColor("#3A2A00"))
            hits += Hit(r1) { onAction(act("next")) }
            if (!s.optBoolean("solo")) {
                val r2 = RectF(w / 2 - dp(158f), by - dp(22f), w / 2 - dp(8f), by + dp(22f))
                drawButton(c, r2, "Về phòng chờ", Ui.GREY, Color.WHITE)
                hits += Hit(r2) { onAction(act("lobby")) }
            } else {
                val r2 = RectF(w / 2 - dp(158f), by - dp(22f), w / 2 - dp(8f), by + dp(22f))
                drawButton(c, r2, "Đổi mức cược", Ui.GREY, Color.WHITE)
                hits += Hit(r2) { onAction(act("lobby")) }
            }
        } else {
            label(c, "Chờ chủ bàn chia ván mới...", w / 2, by + dp(6f), dp(15f), Ui.MUTED, true)
        }
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        drawButton(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
    }

    private fun toast(m: String) = android.widget.Toast.makeText(context, m, android.widget.Toast.LENGTH_SHORT).show()

    // --------------------------------------------------------------- chạm
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x; val y = e.y
        // Nút ưu tiên trước (xét từ nút vẽ sau cùng)
        for (hh in hits.asReversed()) if (hh.rect.contains(x, y)) { hh.action(); invalidate(); return true }
        val result = state?.optJSONObject("g")?.optJSONObject("result")
        if (result != null) return true
        for ((r, card) in cardHits.asReversed()) {
            if (r.contains(x, y)) {
                if (!selected.remove(card)) selected += card
                invalidate()
                return true
            }
        }
        return true
    }
}
