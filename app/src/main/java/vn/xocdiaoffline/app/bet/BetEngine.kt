package vn.xocdiaoffline.app.bet

import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.table.Engine
import vn.xocdiaoffline.app.table.EngineHost
import kotlin.random.Random

/** Cửa cược: tên và số lần ăn. */
class Door(val name: String, val mult: Int)

object BetRules {
    val XOC = listOf(Door("Chẵn", 1), Door("Lẻ", 1), Door("4 Đỏ", 12), Door("4 Trắng", 12), Door("3 Đỏ 1 Trắng", 3), Door("3 Trắng 1 Đỏ", 3))
    val BAU = listOf(Door("Bầu", 1), Door("Cua", 1), Door("Tôm", 1), Door("Cá", 1), Door("Gà", 1), Door("Nai", 1))
    val FACE_ICON = listOf("🍐", "🦀", "🦐", "🐟", "🐓", "🦌")

    fun doors(kind: String) = if (kind == "xocdia") XOC else BAU

    /** Xóc đĩa: 4 quân vị (true = đỏ). Bầu cua: 3 xúc xắc (0..5). */
    fun roll(kind: String, rng: Random): List<Int> =
        if (kind == "xocdia") List(4) { rng.nextInt(2) } else List(3) { rng.nextInt(6) }

    /** Số lần ăn của cửa d với kết quả r (0 = thua). */
    fun hits(kind: String, d: Int, r: List<Int>): Int {
        if (kind == "baucua") return r.count { it == d }
        val red = r.sum()
        return when (d) {
            0 -> if (red % 2 == 0) 1 else 0
            1 -> if (red % 2 == 1) 1 else 0
            2 -> if (red == 4) 12 else 0
            3 -> if (red == 0) 12 else 0
            4 -> if (red == 3) 3 else 0
            else -> if (red == 1) 3 else 0
        }
    }

    fun resultText(kind: String, r: List<Int>): String {
        if (kind == "baucua") return r.joinToString(" · ") { BAU[it].name }
        val red = r.sum()
        val pattern = when (red) { 4 -> "4 Đỏ"; 0 -> "4 Trắng"; 3 -> "3 Đỏ 1 Trắng"; 1 -> "3 Trắng 1 Đỏ"; else -> "2 Đỏ 2 Trắng" }
        return "$pattern · ${if (red % 2 == 0) "Chẵn" else "Lẻ"}"
    }

    /**
     * Thanh toán với nhà cái. bets[p][d] = xu đặt; stacks = xu đang có.
     * Nhà con thua trả trước cho cái, sau đó cái trả nhà con thắng (cái hết xu thì thôi).
     */
    fun settle(kind: String, r: List<Int>, bets: Array<LongArray>, dealer: Int, stacks: LongArray): LongArray {
        val n = bets.size
        val net = LongArray(n)
        for (p in 0 until n) {
            if (p == dealer) continue
            for (d in bets[p].indices) {
                val b = bets[p][d]; if (b == 0L) continue
                val k = hits(kind, d, r)
                net[p] += if (k > 0) b * k else -b
            }
        }
        val delta = LongArray(n)
        var funds = stacks[dealer]
        for (p in 0 until n) if (net[p] < 0) {
            val a = (-net[p]).coerceAtMost(stacks[p])
            delta[p] -= a; delta[dealer] += a; funds += a
        }
        for (p in 0 until n) if (net[p] > 0) {
            val a = net[p].coerceAtMost(funds)
            funds -= a
            delta[p] += a; delta[dealer] -= a
        }
        return delta
    }
}

/** Xóc đĩa và Bầu cua: có nhà cái, mọi người đặt cửa, cái mở bát. */
class BetEngine(host: EngineHost, players: IntArray, private val kind: String) : Engine(host, players) {
    private val doors = BetRules.doors(kind)
    private var phase = "dealer"         // dealer → bet → reveal → done
    private var dealer = -1
    private val bets = Array(players.size) { LongArray(doors.size) }
    private var result: List<Int> = emptyList()
    private var phaseEnd = 0L
    private var betStart = 0L
    private var shakeId = 0
    private val BET_MS = 20_000L
    private val key = "dealer_$kind"
    /** Các lần đặt gần nhất (ghế, cửa, xu) để máy khách vẽ chip bay vào ô. */
    private val chipLog = mutableListOf<Triple<Int, Int, Long>>()
    private var chipSeq = 0

    override fun start() {
        val d = host.persist[key] as? Int ?: -1
        val ok = d in players && host.xu(d) > 0 && !(host.isBot(d) && host.xu(d) < host.bet * 20)
        if (ok) { dealer = players.indexOf(d); enterBet() }
        else {
            host.persist.remove(key)
            phase = "dealer"
            phaseEnd = host.now() + 15_000
            tick()
        }
    }

    private fun tick() {
        val now = host.now()
        when (phase) {
            "dealer" -> if (now >= phaseEnd) {
                // Không ai nhận cái: để máy làm cái (người thật được đặt cược); không có máy thì chọn ngẫu nhiên
                val bots = players.indices.filter { host.isBot(players[it]) }
                val rich = players.indices.filter { host.xu(players[it]) > 0 }
                val cands = bots.ifEmpty { rich.ifEmpty { players.indices.toList() } }
                setDealer(cands[host.rng.nextInt(cands.size)], random = true); return
            }
            "bet" -> {
                botsBet(now)
                if (now >= phaseEnd) { reveal(); return }
                // Máy làm cái: mở sớm khi mọi người thật đã đặt và đã qua 8 giây
                if (host.isBot(players[dealer]) && now - betStart > 8_000 && allHumansBet() && host.rng.nextInt(3) == 0) { reveal(); return }
            }
        }
        host.schedule(700, false) { tick() }
        host.changed()
    }

    private fun allHumansBet() = players.indices.all { it == dealer || host.isBot(players[it]) || bets[it].sum() > 0 }

    private fun setDealer(p: Int, random: Boolean = false) {
        dealer = p
        host.persist[key] = players[p]
        host.toastAll("${host.name(players[p])} làm cái" + if (random) " (chọn ngẫu nhiên)" else "")
        enterBet()
    }

    private fun enterBet() {
        phase = "bet"
        for (b in bets) b.fill(0)
        chipLog.clear()
        result = BetRules.roll(kind, host.rng)   // đã xóc xong, úp bát chờ mở
        betStart = host.now()
        phaseEnd = betStart + BET_MS
        shakeId++
        tick()
    }

    private fun botsBet(now: Long) {
        if (now - betStart < 2000) return
        for (p in players.indices) {
            if (p == dealer || !host.isBot(players[p])) continue
            if (bets[p].sum() >= host.bet * 8 || host.rng.nextInt(100) > 22) continue
            val d = if (kind == "xocdia") {
                if (host.rng.nextInt(100) < 80) host.rng.nextInt(2) else 2 + host.rng.nextInt(4)
            } else host.rng.nextInt(6)
            val amt = host.bet * listOf(1L, 1L, 2L, 5L)[host.rng.nextInt(4)]
            place(p, d, amt)
        }
    }

    private fun place(p: Int, d: Int, amt: Long): String? {
        if (phase != "bet") return "Chưa đến lúc đặt cược"
        if (p == dealer) return "Nhà cái không đặt cược"
        if (d !in doors.indices || amt <= 0) return "Cửa không hợp lệ"
        val left = host.xu(players[p]) - bets[p].sum()
        if (left < amt) return "Không đủ xu để đặt thêm"
        bets[p][d] += amt
        chipLog += Triple(players[p], d, amt)
        if (chipLog.size > 40) chipLog.removeAt(0)
        chipSeq++
        return null
    }

    private fun reveal() {
        if (phase != "bet") return
        phase = "reveal"
        host.schedule(3200, false) { finish() }
        host.changed()
    }

    private fun finish() {
        phase = "done"
        val stacks = LongArray(players.size) { host.xu(players[it]) }
        val d = BetRules.settle(kind, result, bets, dealer, stacks)
        val delta = LongArray(players.maxOrNull()!! + 1)
        val lines = JSONArray()
        val order = players.indices.sortedByDescending { d[it] }
        for (p in order) {
            val s = players[p]
            delta[s] = d[p]
            val note = if (p == dealer) "Nhà cái" else {
                val parts = doors.indices.filter { bets[p][it] > 0 }.map { k ->
                    val hit = BetRules.hits(kind, k, result)
                    "${doors[k].name} ${Wallet.fmt(bets[p][k])}" + if (hit > 0) " ✓" else ""
                }
                if (parts.isEmpty()) "Không đặt" else parts.joinToString(", ")
            }
            lines.put(JSONObject().put("seat", s).put("name", host.name(s)).put("delta", d[p]).put("note", note).put("cards", JSONArray()))
        }
        val dd = d[dealer]
        val dName = host.name(players[dealer])
        val title = "Nhà cái $dName " + when { dd > 0 -> "ăn ${Wallet.fmt(dd)} xu"; dd < 0 -> "đền ${Wallet.fmt(-dd)} xu"; else -> "hòa vốn" }
        host.finish(delta, JSONObject().put("title", title).put("sub", "Kết quả: " + BetRules.resultText(kind, result))
            .put("lines", lines).put("winner", players[dealer]))
    }

    override fun seatBecameBot(seat: Int) {}

    override fun action(seat: Int, a: JSONObject) {
        val p = players.indexOf(seat)
        if (p < 0) return
        when (a.optString("a")) {
            "takeDealer" -> if (phase == "dealer") {
                if (host.xu(seat) <= 0) host.toast(seat, "Bạn hết xu, không làm cái được") else setDealer(p)
            }
            "place" -> {
                val err = place(p, a.optLong("v").toInt(), a.optLong("amt"))
                if (err != null) host.toast(seat, err) else host.changed()
            }
            "clearBet" -> if (phase == "bet" && p != dealer) { bets[p].fill(0); host.changed() }
            "open" -> if (phase == "bet" && p == dealer) {
                if (host.now() - betStart < 5_000) host.toast(seat, "Chờ thêm vài giây cho mọi người đặt cược")
                else reveal()
            }
            "quitDealer" -> if (phase == "done" && p == dealer && host.persist[key] == seat) {
                host.persist.remove(key)
                host.toastAll("${host.name(seat)} đã bỏ cái, ván sau chọn cái mới")
                host.changed()
            }
        }
    }

    override fun view(v: Int): JSONObject {
        val gv = JSONObject().put("kind", kind)
        val me = players.indexOf(v)
        val now = host.now()
        gv.put("players", JSONArray(players.toList()))
        gv.put("phase", phase)
        gv.put("dealer", if (dealer >= 0) players[dealer] else -1)
        gv.put("shakeId", shakeId)
        if (phase == "dealer" || phase == "bet") gv.put("left", (phaseEnd - now).coerceAtLeast(0))
        val ds = JSONArray()
        for (d in doors.indices) {
            ds.put(JSONObject().put("name", doors[d].name).put("mult", doors[d].mult)
                .put("total", bets.sumOf { it[d] })
                .put("mine", if (me >= 0) bets[me][d] else 0)
                .put("win", if (phase == "reveal" || phase == "done") BetRules.hits(kind, d, result) else 0))
        }
        gv.put("doors", ds)
        gv.put("chipSeq", chipSeq)
        val cl = JSONArray()
        for ((seat, d, amt) in chipLog) cl.put(JSONArray().put(seat).put(d).put(amt))
        gv.put("chips", cl)
        if (phase == "reveal" || phase == "done") { gv.put("result", JSONArray(result)); gv.put("resultText", BetRules.resultText(kind, result)) }
        val info = JSONArray()
        for (p in players.indices) info.put(JSONObject().put("seat", players[p]).put("bet", bets[p].sum()))
        gv.put("info", info)
        val acts = JSONArray()
        fun a(label: String, act: String, style: String) = acts.put(JSONObject().put("label", label).put("a", act).put("style", style))
        if (me >= 0) when (phase) {
            "dealer" -> { a("Làm cái", "takeDealer", "gold"); gv.put("hint", "Chưa có nhà cái. Bấm \"Làm cái\" để nhận cái") }
            "bet" -> if (me == dealer) {
                if (now - betStart >= 5_000) a("Mở bát", "open", "gold")
                gv.put("hint", "Bạn làm cái. Chờ mọi người đặt rồi mở bát")
            } else {
                if (bets[me].sum() > 0) a("Hủy cược", "clearBet", "grey")
                gv.put("hint", "Chọn mức chip rồi chạm vào cửa để đặt")
            }
            "done" -> if (me == dealer && host.persist[key] == v) a("Bỏ cái", "quitDealer", "red")
        }
        gv.put("actions", acts)
        if (me >= 0) gv.put("myBet", bets[me].sum())
        return gv
    }
}
