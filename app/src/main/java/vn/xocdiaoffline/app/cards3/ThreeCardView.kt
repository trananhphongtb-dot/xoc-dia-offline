package vn.xocdiaoffline.app.cards3

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
import vn.xocdiaoffline.app.table.GameInfo
import vn.xocdiaoffline.app.table.act
import vn.xocdiaoffline.app.ui.GameView
import vn.xocdiaoffline.app.ui.Ui

/** Màn bàn chơi cho các game 3 lá (Liêng, Ba cây), tối đa 6 người. */
@SuppressLint("ViewConstructor")
class ThreeCardView(ctx: Context, private val onAction: (JSONObject) -> Unit, private val onLeave: () -> Unit) :
    View(ctx), GameView {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var state: JSONObject? = null
    private var stateAt = 0L
    private var showBoard = false

    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = mutableListOf<Hit>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    // Hiệu ứng chia bài: chạy một lần mỗi ván, khi bài vừa được chia
    private var dealtGame = -1
    private var dealAt = 0L
    // Lật bài: nhớ ai đã ngửa bài để lật có hiệu ứng
    private val revealedAt = HashMap<Int, Long>()

    override fun setState(s: JSONObject) {
        val now = System.currentTimeMillis()
        val g = s.optJSONObject("g")
        val gameNo = s.optInt("gameNo")
        if (g != null) {
            val info = g.optJSONArray("info") ?: JSONArray()
            val hasCards = (0 until info.length()).any { val o = info.getJSONObject(it); o.optBoolean("hidden") || (o.optJSONArray("cards")?.length() ?: 0) > 0 }
            if (hasCards && dealtGame != gameNo) { dealtGame = gameNo; dealAt = now; revealedAt.clear() }
            if (dealtGame == gameNo) for (i in 0 until info.length()) {
                val o = info.getJSONObject(i)
                val seat = o.optInt("seat")
                if ((o.optJSONArray("cards")?.length() ?: 0) == 3 && seat != s.optInt("me") && seat !in revealedAt) revealedAt[seat] = now
            }
        }
        state = s
        stateAt = now
        invalidate()
    }

    private val dealing get() = System.currentTimeMillis() - dealAt < 1100

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat(); val h = height.toFloat()
        drawTable(c, w, h)
        val s = state ?: return
        val g = s.optJSONObject("g") ?: return
        val me = s.optInt("me")
        val seats = s.optJSONArray("seats") ?: return
        val players = g.optJSONArray("players").ints()
        val info = g.optJSONArray("info") ?: JSONArray()
        val turn = g.optInt("turn", -1)
        val result = g.optJSONObject("result")
        val leftMs = g.optLong("left", -1).let { if (it < 0) -1L else it - (System.currentTimeMillis() - stateAt) }
        if (leftMs >= 0) postInvalidateDelayed(100)

        // Vị trí các đối thủ quanh bàn (mình ở dưới giữa)
        val n = players.size
        val myIdx = players.indexOf(me).let { if (it < 0) 0 else it }
        val all = listOf(
            w - dp(84f) to h * 0.45f,   // 0 phải dưới
            w - dp(200f) to h * 0.17f,  // 1 phải trên
            w / 2f to dp(52f),          // 2 trên giữa
            dp(200f) to h * 0.17f,      // 3 trái trên
            dp(84f) to h * 0.45f,       // 4 trái dưới
        )
        val slots = when (n) { 2 -> listOf(2); 3 -> listOf(1, 3); 4 -> listOf(0, 2, 4); 5 -> listOf(0, 1, 3, 4); else -> listOf(0, 1, 2, 3, 4) }
        val deal = dealing
        if (deal) postInvalidateDelayed(16)
        val targets = mutableListOf<Pair<Float, Float>>()
        for (k in 1 until n) {
            val p = (myIdx + k) % n
            val seat = players[p]
            val inf = info.optJSONObject(p) ?: continue
            val (x, y) = all[slots[k - 1]]
            targets += x to y
            drawOpponent(c, x, y, seats.getJSONObject(seat), inf, seat == turn, if (seat == turn) leftMs else -1, slots[k - 1], deal,
                revealedAt[seat])
        }
        targets += w * 0.40f to h - h * 0.15f

        // Giữa bàn
        label(c, g.optString("center"), w / 2f, h * 0.43f, dp(20f), Ui.GOLD, true)
        val sub = g.optString("centerSub")
        if (sub.isNotEmpty()) label(c, sub, w / 2f, h * 0.43f + dp(22f), dp(13f), Ui.MUTED, false)
        if (turn < 0 && leftMs >= 0 && result == null) {
            label(c, "${(leftMs / 1000) + 1} giây", w / 2f, h * 0.43f + dp(44f), dp(15f), Color.WHITE, true)
        }

        // Bài của mình
        val myInf = info.optJSONObject(myIdx)
        val ch = h * 0.27f; val cw = ch * 0.7f
        val gap = cw * 0.78f
        val baseY = h - ch - dp(8f)
        val myCx = w * 0.40f
        val startX = myCx - (cw + gap * 2) / 2f
        if (myInf != null) {
            val cards = myInf.optJSONArray("cards").ints()
            if (!deal) for (i in 0 until 3) {
                val x = startX + gap * i
                if (cards.size == 3) drawFace(c, x, baseY, cw, ch, cards[i], !myInf.optBoolean("active", true))
                else if (myInf.optBoolean("hidden")) drawBack(c, x, baseY, cw, ch)
            }
            val desc = myInf.optString("desc").ifEmpty { if (cards.size == 3) g.optString("myLine") else "" }
            if (desc.isNotEmpty()) {
                fill.color = Color.parseColor("#CC000000")
                txt.textSize = dp(14f); txt.typeface = bold
                val tw = txt.measureText(desc)
                val cy = baseY - dp(14f)
                c.drawRoundRect(RectF(myCx - tw / 2 - dp(10f), cy - dp(15f), myCx + tw / 2 + dp(10f), cy + dp(6f)), dp(10f), dp(10f), fill)
                label(c, desc, myCx, cy, dp(14f), Ui.GOLD, true)
            }
        }

        // Thông tin của mình
        val meJ = seats.getJSONObject(me)
        val myTurn = turn == me && result == null
        txt.textAlign = Paint.Align.LEFT
        val infoY = h - dp(74f)
        txt.textSize = dp(14f); txt.typeface = bold; txt.color = if (myTurn) Ui.GOLD else Color.WHITE
        c.drawText((if (myTurn) "▶ " else "") + meJ.optString("name"), dp(14f), infoY, txt)
        txt.typeface = Typeface.DEFAULT; txt.color = Ui.GOLD; txt.textSize = dp(13f)
        c.drawText("${Wallet.fmt(meJ.optLong("xu"))} xu", dp(14f), infoY + dp(17f), txt)
        if (myInf != null) {
            val badge = myInf.optString("badge")
            val line = listOf(badge, myInf.optString("line")).filter { it.isNotEmpty() }.joinToString(" · ")
            txt.color = Ui.MUTED; txt.textSize = dp(12f)
            if (line.isNotEmpty()) c.drawText(line, dp(14f), infoY + dp(33f), txt)
            if (g.optString("kind") == "lieng" && g.has("myLine")) c.drawText(g.optString("myLine"), dp(14f), infoY + dp(48f), txt)
        }
        if (myTurn && leftMs >= 0) {
            val frac = (leftMs / 25000f).coerceIn(0f, 1f)
            fill.color = Color.parseColor("#55000000")
            c.drawRoundRect(RectF(dp(14f), infoY - dp(30f), dp(134f), infoY - dp(24f)), dp(3f), dp(3f), fill)
            fill.color = if (frac < 0.3f) Ui.RED else Ui.GOLD
            c.drawRoundRect(RectF(dp(14f), infoY - dp(30f), dp(14f) + dp(120f) * frac, infoY - dp(24f)), dp(3f), dp(3f), fill)
        }

        // Chia bài: 3 vòng, mỗi người 1 lá mỗi vòng
        if (deal) {
            val el = System.currentTimeMillis() - dealAt
            var k = 0
            for (round in 0 until 3) for ((tx, ty) in targets) {
                val tt = (el - k * 40L) / 280f; k++
                if (tt < 0f || tt >= 1f) continue
                val e = vn.xocdiaoffline.app.ui.Painter.ease(tt)
                val x = w / 2f + (tx - w / 2f) * e; val y = h * 0.42f + (ty - h * 0.42f) * e
                drawBack(c, x - dp(14f), y - dp(20f), dp(28f), dp(40f))
            }
            drawBack(c, w / 2f - dp(20f), h * 0.42f - dp(28f), dp(40f), dp(56f))
        }

        // Nút thao tác (cột bên phải, xếp từ dưới lên)
        val acts = g.optJSONArray("actions") ?: JSONArray()
        if (result == null) {
            drawActions(c, acts, w - dp(12f), h - dp(12f))
            val hint = g.optString("hint")
            if (hint.isNotEmpty()) label(c, hint, w * 0.40f, baseY - dp(36f), dp(13f), Color.WHITE, false)
        }

        // Thanh trên cùng
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        drawButton(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(leave) { onLeave() }
        txt.textAlign = Paint.Align.RIGHT; txt.textSize = dp(13f); txt.color = Ui.GOLD; txt.typeface = bold
        c.drawText("${GameInfo.title(s.optString("game"))} · Cược ${Wallet.fmt(s.optLong("bet"))} xu", w - dp(14f), dp(26f), txt)
        val boardBtn = RectF(w - dp(126f), dp(36f), w - dp(10f), dp(66f))
        drawButton(c, boardBtn, "📊 Bảng điểm", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(boardBtn) { showBoard = !showBoard }

        if (result != null) drawResult(c, w, h, s, result, acts)
        if (showBoard) drawBoard(c, w, h, s)
    }

    private fun styleColor(st: String) = when (st) {
        "gold" -> Ui.GOLD; "blue" -> Ui.BLUE; "red" -> Color.parseColor("#B71C1C"); else -> Ui.GREY
    }

    /** Vẽ các nút từ góc dưới phải, xếp thành cột 2 nút một hàng nếu nhiều. */
    private fun drawActions(c: Canvas, acts: JSONArray, right: Float, bottom: Float) {
        val n = acts.length()
        if (n == 0) return
        val bh = dp(40f); val gapY = dp(8f); val bw = dp(112f); val gapX = dp(8f)
        val cols = minOf(3, n)
        for (i in 0 until n) {
            val a = acts.getJSONObject(i)
            // hàng dưới cùng là các nút đầu danh sách (quan trọng nhất), xếp từ phải sang trái
            val col = i % cols
            val row = i / cols
            val x2 = right - col * (bw + gapX)
            val y2 = bottom - row * (bh + gapY)
            val r = RectF(x2 - bw, y2 - bh, x2, y2)
            val stl = a.optString("style")
            drawButton(c, r, a.optString("label"), styleColor(stl), if (stl == "gold") Color.parseColor("#3A2A00") else Color.WHITE, dp(12f))
            hits += Hit(r) { onAction(act(a.optString("a"), "v" to a.optLong("v"))) }
        }
    }

    private fun drawTable(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.45f, maxOf(w, h) * 0.7f,
            Color.parseColor("#13824F"), Ui.FELT_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        stroke.color = Color.parseColor("#33F2C14E"); stroke.strokeWidth = dp(2f)
        c.drawRoundRect(RectF(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.70f), h * 0.28f, h * 0.28f, stroke)
    }

    private fun drawOpponent(c: Canvas, x: Float, y: Float, sj: JSONObject, inf: JSONObject,
                             isTurn: Boolean, leftMs: Long, slot: Int, deal: Boolean = false, revealAt: Long? = null) {
        val r = dp(22f)
        val active = inf.optBoolean("active", true)
        fill.color = if (active) Color.parseColor("#1E3A2C") else Color.parseColor("#33263238")
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = dp(3f)
        stroke.color = if (isTurn) Ui.GOLD else Color.parseColor("#55FFFFFF")
        c.drawCircle(x, y, r, stroke)
        if (isTurn && leftMs >= 0) {
            val frac = (leftMs / 25000f).coerceIn(0f, 1f)
            stroke.color = if (frac < 0.3f) Ui.RED else Color.WHITE
            c.drawArc(RectF(x - r - dp(5f), y - r - dp(5f), x + r + dp(5f), y + r + dp(5f)), -90f, 360f * frac, false, stroke)
        } else if (isTurn) postInvalidateDelayed(200)
        val name = sj.optString("name")
        label(c, name.take(1).uppercase(), x, y + dp(8f), dp(20f), if (active) Color.WHITE else Ui.MUTED, true)
        label(c, name, x, y + r + dp(15f), dp(12f), if (isTurn) Ui.GOLD else Color.WHITE, true)
        label(c, "${Wallet.fmt(sj.optLong("xu"))} xu", x, y + r + dp(29f), dp(11f), Ui.GOLD, false)
        val line = inf.optString("line")
        if (line.isNotEmpty()) label(c, line, x, y + r + dp(43f), dp(11f), Ui.MUTED, false)

        // Bài: bên trái nếu ngồi bên phải bàn, còn lại bên phải
        val cards = inf.optJSONArray("cards").ints()
        val chh = dp(42f); val cww = chh * 0.7f; val gp = cww * 0.55f
        val total = cww + gp * 2
        val cx = if (slot == 0 || slot == 1) x - r - dp(10f) - total else x + r + dp(10f)
        val cy = y - chh / 2
        if (deal) { /* đang chia bài */ }
        else if (cards.size == 3) {
            // Lật bài: co lá theo chiều ngang rồi mở ra mặt ngửa
            val t = revealAt?.let { (System.currentTimeMillis() - it) / 420f } ?: 1f
            if (t < 1f) postInvalidateDelayed(16)
            for (i in 0 until 3) {
                val sx = cx + gp * i
                if (t < 0.5f) { val k = 1f - t * 2; drawBack(c, sx + cww * (1 - k) / 2, cy, cww * k, chh) }
                else { val k = if (t >= 1f) 1f else (t - 0.5f) * 2; drawFace(c, sx + cww * (1 - k) / 2, cy, cww * k, chh, cards[i], false) }
            }
        }
        else if (inf.optBoolean("hidden")) for (i in 0 until 3) drawBack(c, cx + gp * i, cy, cww, chh)
        val desc = inf.optString("desc")
        if (desc.isNotEmpty()) {
            // Nhãn bài (Sáp, 7 điểm...) đặt đè lên phần dưới các lá để không lấn tên người chơi
            txt.textSize = dp(11f); txt.typeface = bold
            val tw = txt.measureText(desc)
            val mx = cx + total / 2; val by = cy + chh - dp(5f)
            fill.color = Color.parseColor("#E6000000")
            c.drawRoundRect(RectF(mx - tw / 2 - dp(6f), by - dp(12f), mx + tw / 2 + dp(6f), by + dp(4f)), dp(7f), dp(7f), fill)
            label(c, desc, mx, by, dp(11f), Ui.GOLD, true)
        }

        val badge = inf.optString("badge")
        if (badge.isNotEmpty()) {
            txt.textSize = dp(11f); txt.typeface = bold
            val bw = txt.measureText(badge) + dp(14f)
            fill.color = when (badge) {
                "Nhà cái" -> Color.parseColor("#E6B71C1C"); "Úp" -> Color.parseColor("#CC37474F")
                "Tất tay" -> Color.parseColor("#E6E65100"); else -> Color.parseColor("#CC0D47A1")
            }
            c.drawRoundRect(RectF(x - bw / 2, y - r - dp(20f), x + bw / 2, y - r - dp(3f)), dp(8f), dp(8f), fill)
            label(c, badge, x, y - r - dp(7f), dp(11f), Color.WHITE, true)
        }
    }

    private fun drawFace(c: Canvas, x: Float, y: Float, w: Float, h: Float, card: Int, dim: Boolean) {
        val r = RectF(x, y, x + w, y + h)
        fill.color = Color.parseColor("#40000000")
        c.drawRoundRect(RectF(x + dp(1.5f), y + dp(2f), x + w + dp(1.5f), y + h + dp(2f)), w * 0.1f, w * 0.1f, fill)
        fill.color = if (dim) Color.parseColor("#BDBDBD") else Color.WHITE
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, fill)
        stroke.strokeWidth = dp(1f); stroke.color = Color.parseColor("#9E9E9E")
        c.drawRoundRect(r, w * 0.1f, w * 0.1f, stroke)
        txt.color = if (C3.isRed(card)) Color.parseColor("#D32F2F") else Color.parseColor("#212121")
        txt.textAlign = Paint.Align.CENTER; txt.typeface = bold
        txt.textSize = h * 0.21f
        c.drawText(C3.rankText(card), x + w * 0.2f, y + h * 0.22f, txt)
        txt.textSize = h * 0.17f
        c.drawText(C3.suitText(card), x + w * 0.2f, y + h * 0.40f, txt)
        txt.textSize = h * 0.40f
        c.drawText(C3.suitText(card), x + w * 0.58f, y + h * 0.82f, txt)
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

    private fun drawResult(c: Canvas, w: Float, h: Float, s: JSONObject, res: JSONObject, acts: JSONArray) {
        fill.color = Color.parseColor("#B3000000")
        c.drawRect(0f, 0f, w, h, fill)
        val pw = minOf(w * 0.76f, dp(620f)); val ph = h * 0.88f
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), fill)
        stroke.color = Ui.GOLD; stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), stroke)
        label(c, "🏆 " + res.optString("title"), w / 2, panel.top + dp(30f), dp(18f), Ui.GOLD, true)

        val seats = s.getJSONArray("seats")
        val me = s.optInt("me")
        val lines = res.optJSONArray("lines") ?: JSONArray()
        val top = panel.top + dp(44f)
        val rowH = ((panel.bottom - dp(60f) - top) / maxOf(lines.length(), 1)).coerceAtMost(dp(56f))
        for (i in 0 until lines.length()) {
            val ln = lines.getJSONObject(i)
            val y = top + rowH * i
            val isMe = ln.optInt("seat") == me
            if (isMe) {
                fill.color = Color.parseColor("#22F2C14E")
                c.drawRoundRect(RectF(panel.left + dp(10f), y + dp(1f), panel.right - dp(10f), y + rowH - dp(1f)), dp(10f), dp(10f), fill)
            }
            txt.textAlign = Paint.Align.LEFT; txt.typeface = bold; txt.textSize = dp(14f); txt.color = Color.WHITE
            c.drawText(if (isMe) "Bạn" else ln.optString("name"), panel.left + dp(22f), y + rowH * 0.45f, txt)
            txt.typeface = Typeface.DEFAULT; txt.textSize = dp(11f); txt.color = Ui.MUTED
            c.drawText(ln.optString("note"), panel.left + dp(22f), y + rowH * 0.45f + dp(15f), txt)
            val cards = ln.optJSONArray("cards").ints()
            if (cards.isNotEmpty()) {
                val chh = (rowH - dp(8f)).coerceAtMost(dp(42f)); val cww = chh * 0.7f
                var x = panel.left + pw * 0.42f
                for (card in cards) { drawFace(c, x, y + (rowH - chh) / 2, cww, chh, card, false); x += cww * 0.8f }
            }
            val delta = ln.optLong("delta")
            txt.textAlign = Paint.Align.RIGHT; txt.typeface = bold; txt.textSize = dp(16f)
            txt.color = when { delta > 0 -> Color.parseColor("#69F0AE"); delta < 0 -> Color.parseColor("#FF8A80"); else -> Color.WHITE }
            c.drawText((if (delta > 0) "+" else "") + Wallet.fmt(delta) + " xu", panel.right - dp(22f), y + rowH * 0.45f, txt)
            val sj = seats.getJSONObject(ln.optInt("seat"))
            val sessNet = sj.optLong("net")
            txt.typeface = Typeface.DEFAULT; txt.textSize = dp(10f); txt.color = Ui.MUTED
            c.drawText("Cả buổi: thắng ${sj.optInt("wins")}/${sj.optInt("played")}, " + (if (sessNet > 0) "+" else "") + Wallet.fmt(sessNet),
                panel.right - dp(22f), y + rowH * 0.45f + dp(14f), txt)
        }

        val by = panel.bottom - dp(30f)
        val buttons = mutableListOf<Triple<String, Int, () -> Unit>>()
        if (s.optBoolean("host")) {
            buttons += Triple(if (s.optBoolean("solo")) "Đổi mức cược" else "Về phòng chờ", Ui.GREY) { onAction(act("lobby")) }
        }
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            buttons += Triple(a.optString("label"), styleColor(a.optString("style"))) { onAction(act(a.optString("a"), "v" to a.optLong("v"))) }
        }
        if (s.optBoolean("host")) buttons += Triple("Ván mới", Ui.GOLD) { onAction(act("next")) }
        if (buttons.isEmpty()) {
            label(c, "Chờ chủ bàn chia ván mới...", w / 2, by + dp(6f), dp(15f), Ui.MUTED, true)
        } else {
            val bw = dp(140f); val gapX = dp(12f)
            var x = w / 2 - (buttons.size * bw + (buttons.size - 1) * gapX) / 2
            for ((t, col, fn) in buttons) {
                val r = RectF(x, by - dp(20f), x + bw, by + dp(20f))
                drawButton(c, r, t, col, if (col == Ui.GOLD) Color.parseColor("#3A2A00") else Color.WHITE)
                hits += Hit(r, fn)
                x += bw + gapX
            }
            if (!s.optBoolean("host")) label(c, "Chờ chủ bàn chia ván mới...", w / 2, by - dp(28f), dp(12f), Ui.MUTED, false)
        }
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        drawButton(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
    }

    private fun drawBoard(c: Canvas, w: Float, h: Float, s: JSONObject) {
        val seats = s.optJSONArray("seats") ?: return
        val me = s.optInt("me")
        val rows = (0 until seats.length()).map { it to seats.getJSONObject(it) }
            .filter { it.second.optString("kind") != "empty" }
            .sortedByDescending { it.second.optLong("net") }
        fill.color = Color.parseColor("#99000000")
        c.drawRect(0f, 0f, w, h, fill)
        val pw = minOf(w * 0.7f, dp(520f)); val rowH = dp(36f)
        val ph = dp(90f) + rowH * rows.size
        val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        fill.color = Color.parseColor("#0E3B27")
        c.drawRoundRect(panel, dp(18f), dp(18f), fill)
        stroke.color = Ui.GOLD; stroke.strokeWidth = dp(2f)
        c.drawRoundRect(panel, dp(18f), dp(18f), stroke)
        label(c, "📊 Bảng điểm cả buổi", w / 2, panel.top + dp(30f), dp(18f), Ui.GOLD, true)
        val colWin = panel.left + pw * 0.58f
        val colNet = panel.right - dp(20f)
        txt.textSize = dp(12f); txt.color = Ui.MUTED; txt.typeface = Typeface.DEFAULT
        txt.textAlign = Paint.Align.CENTER; c.drawText("Thắng / Ván", colWin, panel.top + dp(52f), txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText("Xu thắng thua", colNet, panel.top + dp(52f), txt)
        for ((k, pair) in rows.withIndex()) {
            val (i, sj) = pair
            val y = panel.top + dp(60f) + rowH * k
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
        label(c, "Chạm để đóng", w / 2, panel.bottom - dp(10f), dp(11f), Ui.MUTED, false)
        hits.clear()
        hits += Hit(RectF(0f, 0f, w, h)) { showBoard = false }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for (hh in hits.asReversed()) if (hh.rect.contains(e.x, e.y)) { hh.action(); invalidate(); return true }
        return true
    }
}
