package vn.xocdiaoffline.app.cards3

import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.table.Engine
import vn.xocdiaoffline.app.table.EngineHost

/*
 * Trạng thái gửi cho màn bàn 3 lá (ThreeCardView) có dạng chung:
 *  g = { kind, players:[ghế], info:[{seat, cards, hidden, badge, line, desc, active}],
 *        center, centerSub, turn, actions:[{label, a, v, style}], hint }
 * Mỗi game tự quyết định hiện gì, màn hình chỉ việc vẽ.
 */
private fun actionJ(label: String, a: String, v: Long = 0, style: String = "grey") =
    JSONObject().put("label", label).put("a", a).put("v", v).put("style", style)

// ============================================================================
// LIÊNG
// ============================================================================
class LiengEngine(host: EngineHost, players: IntArray) : Engine(host, players) {
    private lateinit var g: LiengGame
    private val TURN_MS = 25_000L
    private var done = false

    override fun start() {
        val last = host.persist["liengStarter"] as? Int ?: -1
        val starter = if (last in players) (players.indexOf(last) + 1) % players.size else 0
        host.persist["liengStarter"] = players[starter]
        val stacks = LongArray(players.size) { host.xu(players[it]) }
        g = LiengGame(players.size, host.bet, stacks, starter, host.rng)
        step()
    }

    private fun step() {
        if (g.over) { finish(); return }
        val seat = players[g.turn]
        if (host.isBot(seat)) host.schedule(900L + host.rng.nextLong(900), false) { botMove() }
        else host.schedule(TURN_MS, true) { timeout() }
        host.changed()
    }

    private fun botMove() {
        if (g.over) return
        val p = g.turn
        val d = LiengBot.decide(g, p, host.bet, host.rng)
        val err = when {
            d == "fold" -> g.fold(p)
            d == "call" -> g.call(p)
            d == "allin" -> g.allInMove(p)
            else -> g.raise(p, d.substringAfter(":").toLong())
        }
        if (err != null) g.call(p)
        step()
    }

    private fun timeout() {
        if (g.over) return
        val p = g.turn
        if (g.toCall(p) == 0L) g.call(p) else g.fold(p)
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
            "fold" -> g.fold(p)
            "call" -> g.call(p)
            "raise" -> g.raise(p, a.optLong("v"))
            "allin" -> g.allInMove(p)
            else -> return
        }
        if (err != null) host.toast(seat, err) else step()
    }

    private fun finish() {
        if (done) return
        done = true
        host.cancelTimer()
        val d = g.settle()
        val delta = LongArray(players.maxOrNull()!! + 1)
        val lines = JSONArray()
        val best = (0 until players.size).filter { !g.folded[it] }.maxOf { g.value(it) }
        for (p in players.indices) {
            val s = players[p]
            delta[s] = d[p]
            val show = !g.folded[p] && !g.foldWin
            val note = when {
                g.folded[p] -> "Úp bỏ"
                g.foldWin -> "Mọi người úp hết"
                else -> C3.liengDesc(g.hands[p]) + if (g.value(p) == best) " · thắng" else ""
            }
            lines.put(JSONObject().put("seat", s).put("name", host.name(s)).put("delta", d[p])
                .put("note", note).put("cards", if (show) JSONArray(g.hands[p]) else JSONArray()))
        }
        val top = players.indices.maxBy { d[it] }
        val title = "${host.name(players[top])} ăn gà ${Wallet.fmt(g.pot)} xu"
        host.finish(delta, JSONObject().put("title", title).put("lines", lines).put("winner", players[top]))
    }

    override fun view(v: Int): JSONObject {
        val gv = JSONObject().put("kind", "lieng")
        val me = players.indexOf(v)
        gv.put("players", JSONArray(players.toList()))
        val info = JSONArray()
        for (p in players.indices) {
            val reveal = (p == me) || (g.over && !g.foldWin && !g.folded[p])
            val badge = g.lastAction[p]
            info.put(JSONObject()
                .put("seat", players[p])
                .put("cards", if (reveal) JSONArray(g.hands[p]) else JSONArray())
                .put("hidden", !reveal)
                .put("badge", badge)
                .put("line", "Đã bỏ ${Wallet.fmt(g.contrib[p])}")
                .put("desc", if (reveal && (g.over || p == me)) C3.liengDesc(g.hands[p]) else "")
                .put("active", !g.folded[p]))
        }
        gv.put("info", info)
        gv.put("center", "Gà: ${Wallet.fmt(g.pot)} xu")
        gv.put("centerSub", "Mức tố hiện tại: ${Wallet.fmt(g.currentBet)} xu")
        gv.put("turn", if (g.over) -1 else players[g.turn])
        val acts = JSONArray()
        if (!g.over && me >= 0 && g.turn == me) {
            val tc = g.toCall(me)
            val st = g.stack[me]
            if (tc >= st) {
                acts.put(actionJ("Theo tất tay ${Wallet.fmt(st)}", "allin", 0, "gold"))
                acts.put(actionJ("Úp", "fold", 0, "grey"))
            } else {
                acts.put(actionJ(if (tc == 0L) "Xem" else "Theo ${Wallet.fmt(tc)}", "call", 0, "gold"))
                acts.put(actionJ("Úp", "fold", 0, "grey"))
                val b = host.bet
                for (m in longArrayOf(1, 2, 5)) if (tc + b * m < st) acts.put(actionJ("Tố +${Wallet.fmt(b * m)}", "raise", b * m, "blue"))
                acts.put(actionJ("Tất tay ${Wallet.fmt(st)}", "allin", 0, "red"))
            }
            gv.put("hint", if (tc > 0) "Cần bỏ thêm ${Wallet.fmt(tc)} xu để theo" else "Bạn có thể xem miễn phí hoặc tố")
        }
        gv.put("actions", acts)
        if (me >= 0) gv.put("myLine", "Còn ${Wallet.fmt(g.stack[me])} xu trên tay")
        // Hành động mới nhất hiện chữ to giữa bàn + nhật ký 5 dòng
        g.log.lastOrNull()?.let { (p, t) ->
            gv.put("event", "${host.name(players[p])}: ${t.uppercase()}").put("eventId", g.log.size)
        }
        val lg = JSONArray()
        for ((p, t) in g.log.takeLast(5)) lg.put("${host.name(players[p])}: $t")
        gv.put("log", lg)
        return gv
    }
}

// ============================================================================
// BA CÂY (có nhà cái)
// ============================================================================
class BaCayEngine(host: EngineHost, players: IntArray) : Engine(host, players) {
    private var phase = "dealer"      // dealer → bet → look → done
    private var dealer = -1           // chỉ số trong players
    private val mult = IntArray(players.size)
    private val flipped = BooleanArray(players.size)
    private var hands: List<List<Int>> = emptyList()
    private val MULTS = intArrayOf(1, 2, 3, 5)

    override fun start() {
        val d = host.persist["bcDealer"] as? Int ?: -1
        val ok = d in players && host.xu(d) > 0 && !(host.isBot(d) && host.xu(d) < host.bet * 10)
        if (ok) { dealer = players.indexOf(d); enterBet() }
        else {
            host.persist.remove("bcDealer")
            phase = "dealer"
            host.schedule(15_000, true) { pickRandomDealer() }
            host.changed()
        }
    }

    private fun pickRandomDealer() {
        if (phase != "dealer") return
        val cands = players.indices.filter { host.xu(players[it]) > 0 }
        setDealer(if (cands.isEmpty()) 0 else cands[host.rng.nextInt(cands.size)], random = true)
    }

    private fun setDealer(p: Int, random: Boolean = false) {
        dealer = p
        host.persist["bcDealer"] = players[p]
        host.toastAll("${host.name(players[p])} làm cái" + if (random) " (chọn ngẫu nhiên)" else "")
        enterBet()
    }

    private fun enterBet() {
        phase = "bet"
        mult.fill(0)
        for (p in players.indices) if (p != dealer && host.isBot(players[p])) mult[p] = MULTS[host.rng.nextInt(MULTS.size)]
        if (allBet()) { enterLook(); return }
        host.schedule(12_000, true) {
            for (p in players.indices) if (p != dealer && mult[p] == 0) mult[p] = 1
            enterLook()
        }
        host.changed()
    }

    private fun allBet() = players.indices.all { it == dealer || mult[it] > 0 }

    private var lookEnd = 0L
    private val botFlipAt = LongArray(players.size)
    private var allFlippedAt = 0L

    /** Chia bài: ai cũng úp, chỉ thấy bài mình. Máy lật vào lúc ngẫu nhiên; hết giờ ai chưa lật thì tự lật. */
    private fun enterLook() {
        phase = "look"
        val deck = C3.deck(); deck.shuffle(host.rng)
        hands = players.indices.map { deck.subList(it * 3, it * 3 + 3).sorted() }
        flipped.fill(false)
        val now = host.now()
        lookEnd = now + 15_000
        allFlippedAt = 0
        for (p in players.indices) botFlipAt[p] = now + 3_000 + host.rng.nextLong(9_000)
        lookTick()
    }

    private fun lookTick() {
        if (phase != "look") return
        val now = host.now()
        for (p in players.indices) if (!flipped[p] && host.isBot(players[p]) && now >= botFlipAt[p]) flipped[p] = true
        if (flipped.all { it } && allFlippedAt == 0L) allFlippedAt = now
        if (now >= lookEnd || (allFlippedAt > 0 && now - allFlippedAt >= 1_500)) { showdown(); return }
        host.schedule(500, false) { lookTick() }
        host.changed()
    }

    private fun showdown() {
        if (phase != "look") return
        phase = "done"
        host.cancelTimer()
        val stakes = LongArray(players.size) { if (it == dealer) 0 else host.bet * mult[it] }
        val stacks = LongArray(players.size) { host.xu(players[it]) }
        val o = BaCay.settle(hands, dealer, stakes, stacks)
        val delta = LongArray(players.maxOrNull()!! + 1)
        val lines = JSONArray()
        for (p in players.indices) {
            val s = players[p]
            delta[s] = o.delta[p]
            val note = o.notes[p] + if (p != dealer) " (cược x${mult[p]})" else ""
            lines.put(JSONObject().put("seat", s).put("name", host.name(s)).put("delta", o.delta[p])
                .put("note", note).put("cards", JSONArray(hands[p])))
        }
        val dName = host.name(players[dealer])
        val dd = o.delta[dealer]
        val title = "Nhà cái $dName " + when { dd > 0 -> "ăn ${Wallet.fmt(dd)} xu"; dd < 0 -> "đền ${Wallet.fmt(-dd)} xu"; else -> "hòa vốn" }
        host.finish(delta, JSONObject().put("title", title).put("lines", lines).put("winner", players[dealer]))
    }

    override fun seatBecameBot(seat: Int) {
        val p = players.indexOf(seat)
        if (p < 0) return
        if (phase == "bet" && p != dealer && mult[p] == 0) { mult[p] = 1; if (allBet()) enterLook() }
        else if (phase == "look" && !flipped[p]) botFlipAt[p] = minOf(botFlipAt[p], host.now() + 1_000)
    }

    override fun action(seat: Int, a: JSONObject) {
        val p = players.indexOf(seat)
        when (a.optString("a")) {
            "takeDealer" -> if (phase == "dealer" && p >= 0) {
                if (host.xu(seat) <= 0) host.toast(seat, "Bạn hết xu, không làm cái được") else setDealer(p)
            }
            "stake" -> if (phase == "bet" && p >= 0 && p != dealer && mult[p] == 0) {
                val m = a.optLong("v").toInt()
                if (m in MULTS) { mult[p] = m; if (allBet()) enterLook() else host.changed() }
            }
            "flip" -> if (phase == "look" && p >= 0 && !flipped[p]) {
                flipped[p] = true
                host.toastAll("${host.name(seat)} lật bài: ${C3.baCayDesc(hands[p])}")
                host.changed()
            }
            "quitDealer" -> if (phase == "done" && p >= 0 && p == dealer && host.persist["bcDealer"] == seat) {
                host.persist.remove("bcDealer")
                host.toastAll("${host.name(seat)} đã bỏ cái, ván sau chọn cái mới")
                host.changed()
            }
        }
    }

    override fun view(v: Int): JSONObject {
        val gv = JSONObject().put("kind", "bacay")
        val me = players.indexOf(v)
        gv.put("players", JSONArray(players.toList()))
        val info = JSONArray()
        for (p in players.indices) {
            val haveCards = hands.isNotEmpty()
            val reveal = haveCards && (p == me || flipped[p] || phase == "done")
            val badge = when {
                p == dealer -> "Nhà cái"
                phase == "dealer" -> ""
                mult[p] > 0 -> "Cược x${mult[p]}"
                else -> "Đang chọn cược"
            }
            info.put(JSONObject()
                .put("seat", players[p])
                .put("cards", if (reveal) JSONArray(hands[p]) else JSONArray())
                .put("hidden", haveCards && !reveal)
                .put("badge", badge)
                .put("line", if (p != dealer && mult[p] > 0) "Đặt ${Wallet.fmt(host.bet * mult[p])} xu" else "")
                .put("desc", if (reveal && (flipped[p] || phase == "done")) C3.baCayDesc(hands[p]) else "")
                .put("active", true))
        }
        gv.put("info", info)
        gv.put("turn", -1)
        val dName = if (dealer >= 0) host.name(players[dealer]) else ""
        when (phase) {
            "dealer" -> { gv.put("center", "Chọn nhà cái"); gv.put("centerSub", "Ai bấm \"Làm cái\" trước sẽ làm cái") }
            "bet" -> { gv.put("center", "Đặt cược"); gv.put("centerSub", "Nhà cái: $dName") }
            "look" -> {
                gv.put("center", "Xem bài · đã lật ${flipped.count { it }}/${players.size}")
                gv.put("centerSub", "Nhà cái: $dName · hết giờ sẽ tự lật")
                gv.put("left", (lookEnd - host.now()).coerceAtLeast(0))
            }
            else -> { gv.put("center", "Nhà cái: $dName"); gv.put("centerSub", "") }
        }
        val acts = JSONArray()
        if (me >= 0) when (phase) {
            "dealer" -> { acts.put(actionJ("Làm cái", "takeDealer", 0, "gold")); gv.put("hint", "Chưa có nhà cái. Bấm \"Làm cái\" để nhận cái") }
            "bet" -> if (me != dealer && mult[me] == 0) {
                for (m in MULTS) acts.put(actionJ("x$m · ${Wallet.fmt(host.bet * m)}", "stake", m.toLong(), if (m == 1) "gold" else "blue"))
                gv.put("hint", "Chọn mức cược của bạn")
            } else gv.put("hint", if (me == dealer) "Bạn làm cái, chờ nhà con đặt cược" else "Đã đặt cược, chờ chia bài")
            "look" -> if (!flipped[me]) { acts.put(actionJ("Lật bài", "flip", 0, "gold")); gv.put("hint", "Chỉ bạn thấy bài này. Bấm Lật bài để mọi người cùng xem") }
                      else gv.put("hint", "Chờ mọi người lật bài")
            "done" -> if (me == dealer && host.persist["bcDealer"] == v) acts.put(actionJ("Bỏ cái", "quitDealer", 0, "red"))
        }
        gv.put("actions", acts)
        if (me >= 0 && hands.isNotEmpty()) gv.put("myLine", C3.baCayDesc(hands[me]))
        return gv
    }
}
