package vn.xocdiaoffline.app.phom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

/** Màn bàn Phỏm (2–4 người). */
@SuppressLint("ViewConstructor")
class PhomView(ctx: Context, private val onAction: (JSONObject) -> Unit, private val onLeave: () -> Unit) :
    View(ctx), GameView {

    private val P = Painter(ctx)
    private fun dp(v: Float) = P.dp(v)

    private var state: JSONObject? = null
    private var stateAt = 0L
    private var selected = -1
    private var sortByPhom = true
    private var showBoard = false
    private val result = ResultPanel(P)

    // Hiệu ứng
    private var lastGameNo = -1
    private var dealAt = 0L
    private class Fly(val kind: String, val seat: Int, val card: Int, val start: Long, val dur: Long)
    private val flies = mutableListOf<Fly>()
    private var prevDiscards = HashMap<Int, Int>()
    private var prevHandSize = -1
    private var lastEventId = -1
    private var eventText = ""
    private var eventAt = 0L

    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = mutableListOf<Hit>()
    private val cardHits = mutableListOf<Pair<RectF, Int>>()

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    override fun setState(s: JSONObject) {
        val g = s.optJSONObject("g")
        val now = System.currentTimeMillis()
        val gameNo = s.optInt("gameNo")
        if (g != null) {
            if (gameNo != lastGameNo) {
                lastGameNo = gameNo; dealAt = now; flies.clear(); prevDiscards.clear(); prevHandSize = -1; selected = -1
            } else {
                // Ai vừa đánh bài thì cho lá bay ra
                val info = g.optJSONArray("info") ?: JSONArray()
                for (i in 0 until info.length()) {
                    val inf = info.getJSONObject(i)
                    val seat = inf.optInt("seat")
                    val ds = inf.optJSONArray("discards").ints()
                    val old = prevDiscards[seat] ?: 0
                    if (ds.size > old && ds.isNotEmpty()) flies += Fly("discard", seat, ds.last(), now, 380)
                }
                val hs = g.optJSONArray("hand").ints().size
                if (prevHandSize in 0 until hs && g.optInt("turn") == s.optInt("me")) flies += Fly("draw", s.optInt("me"), -1, now, 350)
            }
            val info = g.optJSONArray("info") ?: JSONArray()
            for (i in 0 until info.length()) {
                val inf = info.getJSONObject(i)
                prevDiscards[inf.optInt("seat")] = inf.optJSONArray("discards").ints().size
            }
            prevHandSize = g.optJSONArray("hand").ints().size
            if (selected !in g.optJSONArray("hand").ints()) selected = -1
            val eid = g.optInt("eventId", -1)
            if (eid != lastEventId) {
                lastEventId = eid
                val e = g.optString("event")
                if (e.isNotEmpty()) { eventText = e; eventAt = now }
            }
        }
        state = s; stateAt = now
        invalidate()
    }

    // ------------------------------------------------------------------ vẽ
    override fun onDraw(c: Canvas) {
        hits.clear(); cardHits.clear()
        val w = width.toFloat(); val h = height.toFloat()
        P.table(c, w, h)
        val s = state ?: return
        val g = s.optJSONObject("g") ?: return
        val me = s.optInt("me")
        val seats = s.optJSONArray("seats") ?: return
        val players = g.optJSONArray("players").ints()
        val info = g.optJSONArray("info") ?: JSONArray()
        val turn = g.optInt("turn", -1)
        val res = g.optJSONObject("result")
        val now = System.currentTimeMillis()
        val leftMs = g.optLong("left", -1).let { if (it < 0) -1L else it - (now - stateAt) }
        val dealing = now - dealAt < 1600
        if (leftMs >= 0 || dealing || flies.isNotEmpty()) postInvalidateDelayed(30)

        val n = players.size
        val myIdx = players.indexOf(me).let { if (it < 0) 0 else it }
        val slotsFor = when (n) { 2 -> listOf(2); 3 -> listOf(1, 3); else -> listOf(1, 2, 3) }
        val seatPos = HashMap<Int, Pair<Float, Float>>()
        val discPos = HashMap<Int, Pair<Float, Float>>()
        val myHandY = h - h * 0.25f - dp(6f)
        seatPos[me] = w * 0.5f to myHandY
        // Bài đã đánh: to, đặt ngay bên phải người đánh (người ngồi bên phải màn hình thì đặt phía trong bàn)
        val DISC = dp(50f)
        val discW = DISC * 0.7f + DISC * 0.7f * 0.62f * 3
        discPos[me] = w * 0.5f + dp(40f) to myHandY - dp(48f) - DISC
        for (k in 1 until n) {
            val seat = players[(myIdx + k) % n]
            when (slotsFor[k - 1]) {
                1 -> { seatPos[seat] = w - dp(70f) to h * 0.36f; discPos[seat] = w - dp(70f) - dp(64f) - discW to h * 0.36f - DISC / 2 }
                2 -> { seatPos[seat] = w * 0.5f to dp(48f); discPos[seat] = w * 0.5f + dp(64f) to dp(48f) - DISC / 2 }
                else -> { seatPos[seat] = dp(70f) to h * 0.36f; discPos[seat] = dp(70f) + dp(64f) to h * 0.36f - DISC / 2 }
            }
        }
        val deckX = w * 0.5f; val deckY = h * 0.43f

        // Đối thủ
        for (k in 1 until n) {
            val p = (myIdx + k) % n
            val seat = players[p]
            val inf = info.optJSONObject(p) ?: continue
            val (x, y) = seatPos[seat]!!
            val sj = seats.getJSONObject(seat)
            val isTurn = seat == turn
            P.avatar(c, x, y, dp(20f), sj.optString("name"), true, isTurn, if (isTurn && leftMs >= 0) leftMs / 25000f else -1f)
            P.label(c, sj.optString("name"), x, y + dp(34f), dp(12f), if (isTurn) Ui.GOLD else Color.WHITE, true)
            P.label(c, "${Wallet.fmt(sj.optLong("xu"))} xu", x, y + dp(47f), dp(10f), Ui.GOLD, false)
            if (!dealing && res == null) {
                val cx = if (slotsFor[k - 1] == 1) x - dp(52f) else x + dp(30f)
                P.back(c, cx, y - dp(20f), dp(24f), dp(34f))
                P.label(c, inf.optInt("count").toString(), cx + dp(12f), y + dp(3f), dp(14f), Color.WHITE, true)
            }
            // Phỏm đã ăn (ngửa)
            val locked = inf.optJSONArray("locked")
            if (locked != null && locked.length() > 0 && res == null) {
                var lx = x - dp(40f); val ly = y + dp(52f)
                for (i in 0 until locked.length()) {
                    for (card in locked.getJSONArray(i).ints()) { P.faceA13(c, lx, ly, dp(18f), dp(26f), card, mark = true); lx += dp(10f) }
                    lx += dp(10f)
                }
            }
            drawDiscards(c, inf.optJSONArray("discards").ints(), discPos[seat]!!, DISC, g.optInt("lastBy") == seat, flyingCard(seat))
        }

        // Nọc bài
        if (res == null) {
            val deck = g.optInt("deck")
            if (deck > 0) {
                P.back(c, deckX - dp(20f), deckY - dp(28f), dp(40f), dp(56f))
                P.label(c, deck.toString(), deckX, deckY + dp(6f), dp(16f), Color.WHITE, true)
                P.label(c, "Nọc", deckX, deckY + dp(44f), dp(11f), Ui.MUTED, false)
            }
        }

        // Bài đã đánh của mình
        val myInf = info.optJSONObject(myIdx)
        if (myInf != null) drawDiscards(c, myInf.optJSONArray("discards").ints(), discPos[me]!!, DISC, g.optInt("lastBy") == me, flyingCard(me))

        // Bài trên tay
        var hand = g.optJSONArray("hand").ints()
        val lockedMine = g.optJSONArray("lockedCards").ints().toSet()
        val suggest = g.optJSONArray("suggest")
        val inSuggest = HashSet<Int>()
        if (suggest != null) for (i in 0 until suggest.length()) inSuggest += suggest.getJSONArray(i).ints()
        hand = if (sortByPhom) {
            val groups = mutableListOf<Int>()
            if (suggest != null) for (i in 0 until suggest.length()) groups += suggest.getJSONArray(i).ints().sorted()
            groups + hand.filter { it !in groups }.sortedWith(compareBy({ it / 4 }, { it % 4 }))
        } else hand.sortedWith(compareBy({ it / 4 }, { it % 4 }))
        val ch = h * 0.25f; val cw = ch * 0.7f
        if (hand.isNotEmpty() && !dealing) {
            val maxW = w * 0.62f
            val gap = if (hand.size > 1) minOf(cw * 0.72f, (maxW - cw) / (hand.size - 1)) else 0f
            val total = cw + gap * (hand.size - 1)
            var x = w * 0.5f - total / 2f
            for ((i, card) in hand.withIndex()) {
                val sel = card == selected
                val y = if (sel) myHandY - ch * 0.2f else myHandY
                P.faceA13(c, x, y, cw, ch, card, sel = sel, mark = card in lockedMine || card in inSuggest)
                cardHits += RectF(x, y, x + (if (i == hand.size - 1) cw else gap), y + ch) to card
                x += gap
            }
        }

        // Thông tin của mình
        val meJ = seats.getJSONObject(me)
        val myTurn = turn == me && res == null
        P.label(c, (if (myTurn) "▶ " else "") + meJ.optString("name"), dp(14f), h - dp(46f), dp(14f), if (myTurn) Ui.GOLD else Color.WHITE, true, Paint.Align.LEFT)
        P.label(c, "${Wallet.fmt(meJ.optLong("xu"))} xu", dp(14f), h - dp(29f), dp(13f), Ui.GOLD, false, Paint.Align.LEFT)
        if (myTurn && leftMs >= 0) {
            val frac = (leftMs / 25000f).coerceIn(0f, 1f)
            P.fill.color = Color.parseColor("#55000000")
            c.drawRoundRect(RectF(dp(14f), h - dp(20f), dp(134f), h - dp(14f)), dp(3f), dp(3f), P.fill)
            P.fill.color = if (frac < 0.3f) Ui.RED else Ui.GOLD
            c.drawRoundRect(RectF(dp(14f), h - dp(20f), dp(14f) + dp(120f) * frac, h - dp(14f)), dp(3f), dp(3f), P.fill)
        }

        // Nút
        if (res == null && !dealing) {
            var by = h - dp(12f)
            val bx2 = w - dp(12f); val bw = dp(96f); val bh = dp(40f)
            fun btn(t: String, col: Int, fg: Int, fn: () -> Unit) {
                val r = RectF(bx2 - bw, by - bh, bx2, by)
                P.button(c, r, t, col, fg); hits += Hit(r, fn); by -= bh + dp(8f)
            }
            val dark = Color.parseColor("#3A2A00")
            if (myTurn && g.optString("step") == "draw") {
                btn("Bốc bài", Ui.GOLD, dark) { onAction(act("draw")) }
                if (g.optBoolean("canEat")) btn("Ăn", Ui.BLUE, Color.WHITE) { onAction(act("eat")) }
                P.label(c, if (g.optBoolean("canEat")) "Bạn có thể ăn lá vừa đánh hoặc bốc" else "Đến lượt bạn: bốc bài",
                    w * 0.5f, myHandY - dp(30f), dp(13f), Color.WHITE, false)
            } else if (myTurn) {
                btn("Đánh", Ui.GOLD, dark) {
                    if (selected < 0) toast("Chọn 1 lá muốn đánh") else onAction(act("discard", "v" to selected))
                }
                P.label(c, "Chọn 1 lá rác rồi bấm Đánh (lá viền xanh đang thành phỏm)", w * 0.5f, myHandY - dp(30f), dp(13f), Color.WHITE, false)
            }
            btn(if (sortByPhom) "Xếp theo số" else "Xếp theo phỏm", Color.parseColor("#33FFFFFF"), Color.WHITE) { sortByPhom = !sortByPhom }
        }

        // Hiệu ứng bay
        drawFlies(c, now, seatPos, discPos, deckX, deckY, me)
        if (dealing) drawDeal(c, now, players, seatPos, deckX, deckY, w, h)

        // Thông báo
        if (eventText.isNotEmpty() && now - eventAt < 2600) {
            P.pill(c, eventText, w / 2f, h * 0.27f, dp(18f), Color.parseColor("#E6B71C1C"), Ui.GOLD)
            postInvalidateDelayed(100)
        }

        // Thanh trên cùng
        val leave = RectF(dp(10f), dp(10f), dp(110f), dp(44f))
        P.button(c, leave, "← Rời bàn", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(leave) { onLeave() }
        P.label(c, "Phỏm · Cược ${Wallet.fmt(s.optLong("bet"))} xu", w - dp(14f), dp(26f), dp(13f), Ui.GOLD, true, Paint.Align.RIGHT)
        val boardBtn = RectF(w - dp(126f), dp(36f), w - dp(10f), dp(66f))
        P.button(c, boardBtn, "📊 Bảng điểm", Color.parseColor("#66000000"), Color.WHITE, dp(13f))
        hits += Hit(boardBtn) { showBoard = !showBoard }

        if (res != null) result.draw(c, w, h, s, res, JSONArray(), { r, fn -> hits += Hit(r, fn) }, onAction)
        if (showBoard) { result.drawBoard(c, w, h, s); hits.clear(); hits += Hit(RectF(0f, 0f, w, h)) { showBoard = false } }
    }

    private fun flyingCard(seat: Int): Int = flies.firstOrNull { it.kind == "discard" && it.seat == seat }?.card ?: -99

    private fun drawDiscards(c: Canvas, ds: List<Int>, pos: Pair<Float, Float>, ch: Float, lastIsHere: Boolean, flying: Int) {
        if (ds.isEmpty()) return
        val cw = ch * 0.7f
        var x = pos.first
        for ((i, card) in ds.withIndex()) {
            if (card == flying && i == ds.size - 1) continue
            val isLast = lastIsHere && i == ds.size - 1
            P.faceA13(c, x, pos.second - (if (isLast) dp(8f) else 0f), cw, ch, card, sel = isLast)
            x += cw * 0.62f
        }
    }

    private fun drawFlies(c: Canvas, now: Long, seatPos: Map<Int, Pair<Float, Float>>, discPos: Map<Int, Pair<Float, Float>>,
                          deckX: Float, deckY: Float, me: Int) {
        val it = flies.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (now - f.start).toFloat() / f.dur
            if (t >= 1f) { it.remove(); continue }
            val e = Painter.ease(t)
            val (fx, fy) = when (f.kind) { "draw" -> deckX to deckY; else -> seatPos[f.seat] ?: (deckX to deckY) }
            val (tx, ty) = when (f.kind) { "draw" -> seatPos[me] ?: (deckX to deckY); else -> discPos[f.seat] ?: (deckX to deckY) }
            val x = fx + (tx - fx) * e; val y = fy + (ty - fy) * e
            val ch = dp(42f); val cw = ch * 0.7f
            if (f.card >= 0) P.faceA13(c, x - cw / 2, y - ch / 2, cw, ch, f.card) else P.back(c, x - cw / 2, y - ch / 2, cw, ch)
        }
    }

    /** Chia bài: từng lá bay từ giữa bàn tới từng người. */
    private fun drawDeal(c: Canvas, now: Long, players: List<Int>, seatPos: Map<Int, Pair<Float, Float>>, dx: Float, dy: Float, w: Float, h: Float) {
        val el = now - dealAt
        val per = 45L
        var k = 0
        for (round in 0 until 9) for (seat in players) {
            val start = k * per; k++
            val t = (el - start).toFloat() / 260f
            if (t < 0f || t >= 1f) continue
            val (tx, ty) = seatPos[seat] ?: continue
            val e = Painter.ease(t)
            val x = dx + (tx - dx) * e; val y = dy + (ty - dy) * e
            P.back(c, x - dp(14f), y - dp(20f), dp(28f), dp(40f))
        }
        P.back(c, dx - dp(20f), dy - dp(28f), dp(40f), dp(56f))
        P.label(c, "Đang chia bài...", w / 2, h * 0.62f, dp(14f), Ui.GOLD, true)
    }

    private fun toast(m: String) = android.widget.Toast.makeText(context, m, android.widget.Toast.LENGTH_SHORT).show()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for (hh in hits.asReversed()) if (hh.rect.contains(e.x, e.y)) { hh.action(); invalidate(); return true }
        if (state?.optJSONObject("g")?.optJSONObject("result") != null) return true
        for ((r, card) in cardHits.asReversed()) if (r.contains(e.x, e.y)) {
            selected = if (selected == card) -1 else card
            invalidate(); return true
        }
        return true
    }
}
