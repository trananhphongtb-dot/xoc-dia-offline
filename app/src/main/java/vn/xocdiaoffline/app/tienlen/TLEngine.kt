package vn.xocdiaoffline.app.tienlen

import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.table.Engine
import vn.xocdiaoffline.app.table.EngineHost

/** Tiến lên miền Nam trên bàn chơi (bọc TLGame). */
class TLEngine(host: EngineHost, players: IntArray) : Engine(host, players) {
    private lateinit var g: TLGame
    private val TURN_MS = 25_000L

    override fun start() {
        val first = host.persist["tlFirstDone"] != true
        val lastWinner = host.persist["tlLastWinner"] as? Int ?: -1
        val hint = if (first || lastWinner !in players) -1 else players.indexOf(lastWinner)
        g = TLGame(players.size, host.bet, hint, host.rng)
        host.persist["tlFirstDone"] = true
        afterMove()
    }

    override fun action(seat: Int, a: JSONObject) {
        if (g.isOver) return
        val p = players.indexOf(seat)
        if (p < 0) return
        val err = when (a.optString("a")) {
            "play" -> {
                val arr = a.optJSONArray("cards") ?: return
                g.play(p, (0 until arr.length()).map { arr.getInt(it) })
            }
            "pass" -> g.pass(p)
            else -> return
        }
        if (err != null) host.toast(seat, err) else afterMove()
    }

    override fun seatBecameBot(seat: Int) {
        if (!g.isOver && players[g.turn] == seat) scheduleTurn()
    }

    private fun afterMove() {
        if (g.isOver) { finish(); return }
        scheduleTurn()
        host.changed()
    }

    private fun scheduleTurn() {
        val seat = players[g.turn]
        if (host.isBot(seat)) host.schedule(700L + host.rng.nextLong(900), false) { onTimer() }
        else host.schedule(TURN_MS, true) { onTimer() }
    }

    private fun onTimer() {
        if (g.isOver) return
        val p = g.turn
        val cards = if (host.isBot(players[p])) TLBot.choose(g, p, host.rng) else TLBot.autoMove(g, p)
        val err = if (cards == null) g.pass(p) else g.play(p, cards)
        if (err != null) {
            // Phòng hờ: không để bàn bị kẹt
            if (g.leading) g.play(p, listOf(if (g.mustInclude >= 0) g.mustInclude else g.hands[p].first())) else g.pass(p)
        }
        afterMove()
    }

    private fun finish() {
        host.cancelTimer()
        val bal = HashMap<Int, Long>()
        for (s in players) bal[s] = host.xu(s)
        val delta = LongArray(players.maxOrNull()!! + 1)
        for (t in g.transfers) {
            val from = players[t.from]; val to = players[t.to]
            val amt = t.amount.coerceAtMost(bal[from]!!.coerceAtLeast(0))
            bal[from] = bal[from]!! - amt; bal[to] = bal[to]!! + amt
            delta[from] -= amt; delta[to] += amt
        }
        val winnerSeat = players[g.winner]
        host.persist["tlLastWinner"] = winnerSeat
        val lines = JSONArray()
        for (p in players.indices) {
            val s = players[p]
            lines.put(JSONObject()
                .put("seat", s).put("name", host.name(s))
                .put("delta", delta[s])
                .put("note", g.notes[p].joinToString(" · "))
                .put("cards", JSONArray(g.hands[p])))
        }
        val res = JSONObject().put("winner", winnerSeat).put("reason", g.winReason).put("lines", lines)
        host.finish(delta, res)
    }

    override fun view(v: Int): JSONObject {
        val gv = JSONObject()
        gv.put("players", JSONArray(players.toList()))
        gv.put("counts", JSONArray(g.hands.map { it.size }))
        gv.put("turn", if (g.isOver) -1 else players[g.turn])
        gv.put("leading", g.leading)
        g.lastCombo?.let { c ->
            gv.put("last", JSONObject()
                .put("cards", JSONArray(c.cards))
                .put("by", players[g.lastBy])
                .put("desc", c.describe()))
        }
        val passedSeats = JSONArray()
        for (p in players.indices) if (g.passed[p]) passedSeats.put(players[p])
        gv.put("passed", passedSeats)
        val myP = players.indexOf(v)
        if (myP >= 0) gv.put("hand", JSONArray(g.hands[myP]))
        gv.put("must", if (myP >= 0 && g.turn == myP && !g.isOver) g.mustInclude else -1)
        gv.put("event", eventText(g.event)).put("eventId", g.eventId)
        return gv
    }

    private fun eventText(e: String): String {
        val p = e.split("|")
        return when (p.getOrNull(0)) {
            "CHOP" -> "${host.name(players[p[1].toInt()])} chặt ${p[3].lowercase()} của ${host.name(players[p[2].toInt()])}!"
            "ROUND" -> "Vòng mới: ${host.name(players[p[1].toInt()])} đi trước"
            else -> ""
        }
    }
}
