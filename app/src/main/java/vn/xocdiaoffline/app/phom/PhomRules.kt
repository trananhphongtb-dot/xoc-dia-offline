package vn.xocdiaoffline.app.phom

import kotlin.random.Random

/*
 * Phỏm (tá lả), dùng cùng mã bài với Liêng/Ba cây: card = rank * 4 + suit,
 * rank A = 1 … K = 13; suit 0 ♠, 1 ♣, 2 ♦, 3 ♥.
 * Điểm rác: A = 1 … K = 13.
 * Phỏm: 3–4 lá cùng số, hoặc 3+ lá liên tiếp cùng chất (A chỉ đứng đầu, không vòng).
 */
object PR {
    fun rank(c: Int) = c / 4
    fun suit(c: Int) = c % 4
    fun points(cards: Collection<Int>) = cards.sumOf { rank(it) }

    fun isPhom(cs: Collection<Int>): Boolean {
        if (cs.size < 3) return false
        val l = cs.sorted()
        if (l.all { rank(it) == rank(l[0]) }) return cs.size <= 4
        if (l.all { suit(it) == suit(l[0]) }) {
            val r = l.map { rank(it) }
            return (1 until r.size).all { r[it] == r[it - 1] + 1 }
        }
        return false
    }

    /** Mọi phỏm có thể tạo từ các lá này. */
    fun allPhoms(cards: Collection<Int>): List<List<Int>> {
        val res = mutableListOf<List<Int>>()
        val byRank = cards.groupBy { rank(it) }
        for ((_, cs) in byRank) {
            if (cs.size >= 3) {
                if (cs.size == 4) res += cs.sorted()
                for (i in cs.indices) for (j in i + 1 until cs.size) for (k in j + 1 until cs.size) res += listOf(cs[i], cs[j], cs[k]).sorted()
            }
        }
        val bySuit = cards.groupBy { suit(it) }
        for ((_, cs) in bySuit) {
            val s = cs.sortedBy { rank(it) }
            for (i in s.indices) {
                var j = i
                while (j + 1 < s.size && rank(s[j + 1]) == rank(s[j]) + 1) {
                    j++
                    if (j - i + 1 >= 3) res += s.subList(i, j + 1).toList()
                }
            }
        }
        return res
    }

    class Arrangement(val phoms: List<List<Int>>, val rest: List<Int>) {
        val restPoints get() = points(rest)
    }

    /**
     * Xếp bài tốt nhất: chọn các phỏm rời nhau để phần rác còn lại có ít lá nhất,
     * sau đó ít điểm nhất. fixed = các phỏm đã khóa (đã ăn), luôn giữ nguyên.
     */
    fun best(free: Collection<Int>, fixed: List<List<Int>> = emptyList()): Arrangement {
        val cands = allPhoms(free)
        val freeSet = free.toSet()
        var bestPh: List<List<Int>> = emptyList()
        var bestRestN = Int.MAX_VALUE
        var bestRestP = Int.MAX_VALUE
        fun rec(start: Int, used: Set<Int>, chosen: MutableList<List<Int>>) {
            val rest = freeSet - used
            val rn = rest.size; val rp = points(rest)
            if (rn < bestRestN || (rn == bestRestN && rp < bestRestP)) {
                bestRestN = rn; bestRestP = rp; bestPh = chosen.toList()
            }
            for (i in start until cands.size) {
                val p = cands[i]
                if (p.any { it in used }) continue
                chosen += p
                rec(i + 1, used + p, chosen)
                chosen.removeAt(chosen.size - 1)
            }
        }
        rec(0, emptySet(), mutableListOf())
        val used = bestPh.flatten().toSet()
        return Arrangement(fixed + bestPh, (freeSet - used).sorted())
    }

    /** Có thể gửi lá c vào phỏm ph không. */
    fun canAttach(ph: List<Int>, c: Int) = isPhom(ph + c)

    /** Tìm một phỏm chứa lá c, dùng các lá trong pool (ít lá nhất). */
    fun phomWith(c: Int, pool: Collection<Int>): List<Int>? =
        allPhoms(pool + c).filter { c in it }.minByOrNull { it.size }

    /** Điểm "tiềm năng" của tay bài cho máy: phỏm, cạ (2 lá gần thành phỏm), ít điểm rác. */
    fun handValue(free: Collection<Int>, fixed: List<List<Int>>): Int {
        val a = best(free, fixed)
        var v = (free.size - a.rest.size) * 100
        val rest = a.rest
        for (i in rest.indices) for (j in i + 1 until rest.size) {
            val x = rest[i]; val y = rest[j]
            if (rank(x) == rank(y)) v += 18
            else if (suit(x) == suit(y) && kotlin.math.abs(rank(x) - rank(y)) <= 2) v += 14
        }
        return v - a.restPoints
    }
}

/** Một khoản chuyển xu giữa hai người (chỉ số theo thứ tự trong ván). */
class PTransfer(val from: Int, val to: Int, val amount: Long)

/**
 * Luật Phỏm phổ thông:
 *  - Chia mỗi người 9 lá, người đi đầu 10 lá và đánh trước.
 *  - Đến lượt: ăn lá vừa đánh của người trước (nếu thành phỏm) hoặc bốc, rồi đánh 1 lá.
 *  - Lá ăn được khóa cùng phỏm của nó, không đánh ra được; một phỏm chỉ có 1 lá ăn.
 *  - Ăn cây: người bị ăn trả 1, 2, 3 cược (cây thứ 1, 2, 3 của người ăn); ăn cây chốt trả 4 cược.
 *  - Mỗi người đánh đủ 4 lá thì hạ: tự xếp phỏm tốt nhất, gửi rác vào phỏm người khác.
 *  - Ít điểm nhất về nhất; nhì trả 1, ba trả 2, bét trả 3 cược; móm (không phỏm) trả 4 cược.
 *  - Ù (toàn bộ bài, trừ tối đa 1 lá, thành phỏm): mỗi người trả 5 cược;
 *    ù bằng lá ăn thì người cho ăn đền cả làng.
 */
class PhomGame(val n: Int, val bet: Long, val starter: Int, rng: Random) {
    val hands = Array(n) { mutableListOf<Int>() }
    val locked = Array(n) { mutableListOf<List<Int>>() }
    val discards = Array(n) { mutableListOf<Int>() }
    val deck = mutableListOf<Int>()
    val discardCount = IntArray(n)
    val eatCount = IntArray(n)
    var turn = starter
    /** "draw" = phải ăn hoặc bốc, "discard" = phải đánh 1 lá. */
    var step = "discard"
    var lastDiscard = -1
    var lastDiscarder = -1
    val transfers = mutableListOf<PTransfer>()
    var over = false
    var uWinner = -1
    var uFeeder = -1
    /** Kết quả khi hạ: phỏm và rác của từng người, xếp hạng. */
    val finalPhoms = Array(n) { listOf<List<Int>>() }
    val finalRest = Array(n) { listOf<Int>() }
    val mom = BooleanArray(n)
    var ranking: List<Int> = emptyList()
    val notes = Array(n) { mutableListOf<String>() }
    var event = ""
    var eventId = 0
        private set

    private fun emit(s: String) { event = s; eventId++ }

    init {
        val d = (4..55).toMutableList(); d.shuffle(rng)
        for (i in 0 until n) {
            val k = if (i == starter) 10 else 9
            repeat(k) { hands[i] += d.removeAt(d.size - 1) }
        }
        deck.addAll(d)
        checkU(starter, viaEat = false)
    }

    fun free(p: Int): List<Int> {
        val l = locked[p].flatten().toSet()
        return hands[p].filter { it !in l }
    }

    private fun prev(p: Int) = (p - 1 + n) % n

    fun canEat(p: Int): Boolean {
        if (over || p != turn || step != "draw" || lastDiscard < 0 || lastDiscarder != prev(p)) return false
        return PR.phomWith(lastDiscard, free(p)) != null
    }

    fun eat(p: Int): String? {
        if (!canEat(p)) return "Không ăn được lá này"
        val c = lastDiscard
        val ph = PR.phomWith(c, free(p))!!
        val feeder = lastDiscarder
        discards[feeder].remove(c)
        hands[p] += c
        locked[p] += ph
        eatCount[p]++
        val chot = discardCount[feeder] >= 4
        val mult = if (chot) 4 else eatCount[p].coerceAtMost(3)
        transfers += PTransfer(feeder, p, bet * mult)
        notes[feeder] += if (chot) "Bị ăn cây chốt" else "Bị ăn cây"
        notes[p] += if (chot) "Ăn cây chốt" else "Ăn cây"
        emit("EAT|$p|$feeder|${if (chot) 1 else 0}")
        lastDiscard = -1
        step = "discard"
        checkU(p, viaEat = true, feeder = feeder)
        return null
    }

    fun draw(p: Int): String? {
        if (over || p != turn || step != "draw") return "Chưa tới lượt bốc"
        if (deck.isEmpty()) { finish(); return null }
        hands[p] += deck.removeAt(deck.size - 1)
        step = "discard"
        checkU(p, viaEat = false)
        return null
    }

    fun discard(p: Int, c: Int): String? {
        if (over || p != turn || step != "discard") return "Chưa tới lượt đánh"
        if (c !in free(p)) return "Không đánh được lá này (đã nằm trong phỏm ăn)"
        hands[p].remove(c)
        discards[p] += c
        discardCount[p]++
        lastDiscard = c
        lastDiscarder = p
        if ((0 until n).all { discardCount[it] >= 4 }) { finish(); return null }
        turn = (p + 1) % n
        step = "draw"
        return null
    }

    /** Ù khi toàn bộ bài (trừ tối đa 1 lá) nằm trong phỏm. */
    private fun checkU(p: Int, viaEat: Boolean, feeder: Int = -1) {
        val a = PR.best(free(p), locked[p])
        if (a.phoms.isNotEmpty() && a.rest.size <= 1 && hands[p].size >= 9) {
            over = true
            uWinner = p
            uFeeder = if (viaEat) feeder else -1
            for (q in 0 until n) if (q != p) {
                val payer = if (uFeeder >= 0) uFeeder else q
                transfers += PTransfer(payer, p, bet * 5)
            }
            for (q in 0 until n) {
                finalPhoms[q] = if (q == p) a.phoms else PR.best(free(q), locked[q]).phoms
                finalRest[q] = if (q == p) a.rest else PR.best(free(q), locked[q]).rest
            }
            notes[p] += "Ù!"
            if (uFeeder >= 0) notes[uFeeder] += "Đền làng (cho ăn để ù)"
            ranking = listOf(p) + (0 until n).filter { it != p }
            emit("U|$p")
        }
    }

    private fun finish() {
        over = true
        // Hạ theo thứ tự từ người đi đầu
        val order = (0 until n).map { (starter + it) % n }
        val ph = Array(n) { mutableListOf<MutableList<Int>>() }
        val rest = Array(n) { mutableListOf<Int>() }
        for (p in order) {
            val a = PR.best(free(p), locked[p])
            a.phoms.forEach { ph[p] += it.toMutableList() }
            rest[p].addAll(a.rest)
            mom[p] = a.phoms.isEmpty()
        }
        // Gửi rác vào phỏm đã hạ (người móm không được gửi)
        for (p in order) {
            if (mom[p]) continue
            var changed = true
            while (changed) {
                changed = false
                for (c in rest[p].sortedByDescending { PR.rank(it) }) {
                    val target = order.flatMap { q -> ph[q] }.firstOrNull { PR.canAttach(it, c) }
                    if (target != null) { target += c; rest[p].remove(c); changed = true; break }
                }
            }
        }
        for (p in 0 until n) { finalPhoms[p] = ph[p].map { it.sorted() }; finalRest[p] = rest[p].sorted() }
        val okPlayers = order.filter { !mom[it] }.sortedBy { PR.points(rest[it]) }   // sortedBy ổn định: hòa điểm thì ai hạ trước hơn
        val momPlayers = order.filter { mom[it] }.sortedBy { PR.points(rest[it]) }
        ranking = okPlayers + momPlayers
        val winner = ranking[0]
        for ((pos, p) in ranking.withIndex()) {
            if (p == winner) { notes[p] += "Về nhất (${PR.points(rest[p])} điểm)"; continue }
            val mult = if (mom[p]) 4 else pos.toLong().toInt()
            transfers += PTransfer(p, winner, bet * mult)
            notes[p] += if (mom[p]) "Móm" else "Về ${listOf("", "nhì", "ba", "bét")[pos.coerceAtMost(3)]} (${PR.points(rest[p])} điểm)"
        }
        emit("END")
    }
}

/** Máy chơi Phỏm. */
object PhomBot {
    /** "eat" hoặc "draw". */
    fun drawOrEat(g: PhomGame, p: Int): String = if (g.canEat(p)) "eat" else "draw"

    fun discard(g: PhomGame, p: Int, rng: Random): Int {
        val free = g.free(p)
        var best = free.first(); var bestV = Int.MIN_VALUE
        for (c in free) {
            var v = PR.handValue(free - c, g.locked[p]) * 10 + PR.rank(c) * 3
            v += rng.nextInt(3)
            if (v > bestV) { bestV = v; best = c }
        }
        return best
    }
}
