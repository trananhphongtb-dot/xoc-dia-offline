package vn.xocdiaoffline.app.phom

import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.table.Engine
import vn.xocdiaoffline.app.table.EngineHost

class PhomEngine(host: EngineHost, players: IntArray) : Engine(host, players) {
    private lateinit var g: PhomGame
    private val TURN_MS = 25_000L
    private var done = false

    override fun start() {
        val lastWinner = host.persist["phomLastWinner"] as? Int ?: -1
        val starter = if (lastWinner in players) players.indexOf(lastWinner) else 0
        g = PhomGame(players.size, host.bet, starter, host.rng)
        // Chờ hiệu ứng chia bài rồi mới tính giờ
        host.schedule(1800, false) { step() }
        host.changed()
    }

    private fun step() {
        if (g.over) { finish(); return }
        val seat = players[g.turn]
        if (host.isBot(seat)) host.schedule(800L + host.rng.nextLong(700), false) { botMove() }
        else host.schedule(TURN_MS, true) { timeout() }
        host.changed()
    }

    private fun botMove() {
        if (g.over) return
        val p = g.turn
        if (g.step == "draw") {
            if (PhomBot.drawOrEat(g, p) == "eat") g.eat(p) else g.draw(p)
        } else g.discard(p, PhomBot.discard(g, p, host.rng))
        step()
    }

    /** Hết giờ: tự bốc, tự đánh lá rác lớn nhất. */
    private fun timeout() {
        if (g.over) return
        val p = g.turn
        if (g.step == "draw") g.draw(p)
        if (!g.over && g.turn == p && g.step == "discard") g.discard(p, PhomBot.discard(g, p, host.rng))
        step()
    }

    override fun seatBecameBot(seat: Int) {
        if (!g.over && players[g.turn] == seat) step()
    }

    override fun action(seat: Int, a: JSONObject) {
        if (g.over) return
        val p = players.indexOf(seat)
        if (p < 0) return
        val err = when (a.optString("a")) {
            "eat" -> g.eat(p)
            "draw" -> g.draw(p)
            "discard" -> g.discard(p, a.optLong("v").toInt())
            else -> return
        }
        if (err != null) host.toast(seat, err) else step()
    }

    private fun finish() {
        if (done) return
        done = true
        host.cancelTimer()
        // Thanh toán theo thứ tự, không ai trả quá số xu đang có
        val bal = LongArray(players.size) { host.xu(players[it]) }
        val d = LongArray(players.size)
        for (t in g.transfers) {
            val amt = t.amount.coerceAtMost((bal[t.from] + d[t.from]).coerceAtLeast(0))
            d[t.from] -= amt; d[t.to] += amt
        }
        val delta = LongArray(players.maxOrNull()!! + 1)
        val lines = JSONArray()
        for (p in g.ranking) {
            val s = players[p]
            delta[s] = d[p]
            lines.put(JSONObject().put("seat", s).put("name", host.name(s)).put("delta", d[p])
                .put("note", g.notes[p].distinct().joinToString(" · "))
                .put("phoms", JSONArray(g.finalPhoms[p].map { JSONArray(it) }))
                .put("cards", JSONArray(g.finalRest[p])))
        }
        val w = g.ranking[0]
        host.persist["phomLastWinner"] = players[w]
        val title = if (g.uWinner >= 0) "${host.name(players[w])} Ù!" else "${host.name(players[w])} về nhất"
        host.finish(delta, JSONObject().put("title", title).put("lines", lines).put("winner", players[w]))
    }

    override fun view(v: Int): JSONObject {
        val gv = JSONObject().put("kind", "phom")
        val me = players.indexOf(v)
        gv.put("players", JSONArray(players.toList()))
        gv.put("turn", if (g.over) -1 else players[g.turn])
        gv.put("step", g.step)
        gv.put("deck", g.deck.size)
        val info = JSONArray()
        for (p in players.indices) {
            info.put(JSONObject()
                .put("seat", players[p])
                .put("count", g.hands[p].size)
                .put("discards", JSONArray(g.discards[p]))
                .put("locked", JSONArray(g.locked[p].map { JSONArray(it) }))
                .put("played", g.discardCount[p]))
        }
        gv.put("info", info)
        gv.put("lastDiscard", g.lastDiscard)
        gv.put("lastBy", if (g.lastDiscarder >= 0) players[g.lastDiscarder] else -1)
        if (me >= 0) {
            gv.put("hand", JSONArray(g.hands[me]))
            gv.put("lockedCards", JSONArray(g.locked[me].flatten()))
            gv.put("canEat", g.canEat(me))
            if (g.turn == me && !g.over) {
                // Gợi ý xếp bài: các phỏm đang có trên tay
                val a = PR.best(g.free(me), g.locked[me])
                gv.put("suggest", JSONArray(a.phoms.map { JSONArray(it) }))
            }
        }
        gv.put("event", eventText(g.event)).put("eventId", g.eventId)
        return gv
    }

    private fun eventText(e: String): String {
        val p = e.split("|")
        return when (p.getOrNull(0)) {
            "EAT" -> "${host.name(players[p[1].toInt()])} ăn ${if (p[3] == "1") "cây chốt " else ""}của ${host.name(players[p[2].toInt()])}!"
            "U" -> "${host.name(players[p[1].toInt()])} Ù!"
            else -> ""
        }
    }
}
