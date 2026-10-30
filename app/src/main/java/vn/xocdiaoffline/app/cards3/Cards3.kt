package vn.xocdiaoffline.app.cards3

import kotlin.random.Random

/*
 * Bộ bài cho Liêng và Ba cây: card = rank * 4 + suit
 *   rank: A = 1, 2..10, J = 11, Q = 12, K = 13
 *   suit: 0 = bích ♠, 1 = chuồn ♣, 2 = rô ♦, 3 = cơ ♥
 * Điểm: A = 1, 2..9 theo số, 10/J/Q/K = 0; điểm 3 lá lấy hàng đơn vị của tổng.
 */
object C3 {
    fun rank(c: Int) = c / 4
    fun suit(c: Int) = c % 4
    fun isRed(c: Int) = suit(c) >= 2
    fun deck(): MutableList<Int> = (4..55).toMutableList()
    fun rankText(c: Int): String = when (val r = rank(c)) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> r.toString() }
    fun suitText(c: Int): String = when (suit(c)) { 0 -> "♠"; 1 -> "♣"; 2 -> "♦"; else -> "♥" }

    fun points(h: List<Int>): Int = h.sumOf { val r = rank(it); if (r >= 10) 0 else r } % 10

    private fun pointText(p: Int) = if (p == 0) "Bù (0 điểm)" else "$p điểm"

    // ------------------------------------------------------------ Ba cây
    fun isBaTien(h: List<Int>) = h.all { rank(it) in 11..13 }
    /** Ba tiên cao nhất, sau đó 9 điểm xuống 0 điểm. */
    fun baCayValue(h: List<Int>): Int = if (isBaTien(h)) 100 else points(h)
    fun baCayDesc(h: List<Int>): String = if (isBaTien(h)) "Ba tiên" else pointText(points(h))

    // ------------------------------------------------------------- Liêng
    /** Sáp (3 lá cùng số) > Liêng (3 lá liên tiếp) > Điểm. */
    fun liengValue(h: List<Int>): Int {
        val r = h.map { rank(it) }.sorted()
        if (r[0] == r[1] && r[1] == r[2]) return 300 + (if (r[0] == 1) 14 else r[0])
        if (r == listOf(1, 12, 13)) return 200 + 14                     // Q-K-A cao nhất
        if (r[1] == r[0] + 1 && r[2] == r[1] + 1) return 200 + r[2]     // A-2-3 thấp nhất
        return points(h)
    }

    fun liengDesc(h: List<Int>): String {
        val v = liengValue(h)
        return when {
            v >= 300 -> "Sáp " + (if (v == 314) "A" else rankText((v - 300) * 4))
            v >= 200 -> "Liêng"
            else -> pointText(v)
        }
    }
}

// ============================================================================
// Liêng: góp gà, tố không giới hạn (tối đa bằng số xu đang có), có tất tay
// ============================================================================
class LiengGame(val n: Int, val ante: Long, stacks: LongArray, starter: Int, rng: Random) {
    val hands = Array(n) { mutableListOf<Int>() }
    val stack = stacks.copyOf()
    val contrib = LongArray(n)
    val folded = BooleanArray(n)
    val allIn = BooleanArray(n)
    var currentBet = 0L
    var turn = -1
    var raises = 0
    val pending = linkedSetOf<Int>()
    var over = false
    var foldWin = false
    val lastAction = Array(n) { "" }
    /** Nhật ký hành động: (người, chữ hiển thị). */
    val log = mutableListOf<Pair<Int, String>>()
    private fun fmt(v: Long) = vn.xocdiaoffline.app.Wallet.fmt(v)
    private fun record(p: Int, s: String) { lastAction[p] = s; log += p to s }

    init {
        val deck = C3.deck(); deck.shuffle(rng)
        for (i in 0 until n) { hands[i].addAll(deck.subList(i * 3, i * 3 + 3)); hands[i].sort() }
        for (i in 0 until n) {
            val a = ante.coerceAtMost(stack[i])
            stack[i] -= a; contrib[i] += a
            if (stack[i] == 0L) allIn[i] = true
        }
        currentBet = contrib.maxOrNull() ?: 0
        for (k in 0 until n) { val i = (starter + k) % n; if (!allIn[i]) pending += i }
        turn = pending.firstOrNull() ?: -1
        checkEnd((starter + n - 1) % n)
    }

    val pot get() = contrib.sum()
    fun toCall(p: Int) = (currentBet - contrib[p]).coerceAtLeast(0)
    private fun active() = (0 until n).filter { !folded[it] }

    private fun put(p: Int, amount: Long) {
        val a = amount.coerceAtMost(stack[p])
        stack[p] -= a; contrib[p] += a
        if (stack[p] == 0L) allIn[p] = true
    }

    private fun reopen(p: Int) {
        pending.clear()
        for (k in 1 until n) { val i = (p + k) % n; if (!folded[i] && !allIn[i]) pending += i }
    }

    fun fold(p: Int): String? {
        if (over || p != turn) return "Chưa tới lượt bạn"
        folded[p] = true; record(p, "Úp bỏ")
        pending.remove(p)
        checkEnd(p); return null
    }

    /** Theo (hoặc Xem nếu không phải bỏ thêm). */
    fun call(p: Int): String? {
        if (over || p != turn) return "Chưa tới lượt bạn"
        val tc = toCall(p)
        val before = stack[p]
        put(p, tc)
        record(p, when { allIn[p] -> "Tất tay ${fmt(before)}"; tc == 0L -> "Xem"; else -> "Theo ${fmt(tc)}" })
        pending.remove(p)
        checkEnd(p); return null
    }

    /** Tố thêm `amount` trên mức đang có. */
    fun raise(p: Int, amount: Long): String? {
        if (over || p != turn) return "Chưa tới lượt bạn"
        if (amount <= 0) return "Mức tố không hợp lệ"
        val need = toCall(p) + amount
        if (need >= stack[p]) return allInMove(p)
        put(p, need)
        currentBet = contrib[p]
        raises++
        record(p, "Tố +${fmt(amount)}")
        reopen(p)
        checkEnd(p); return null
    }

    fun allInMove(p: Int): String? {
        if (over || p != turn) return "Chưa tới lượt bạn"
        val all = stack[p]
        put(p, all)
        record(p, "Tất tay ${fmt(all)}")
        if (contrib[p] > currentBet) { currentBet = contrib[p]; raises++; reopen(p) } else pending.remove(p)
        checkEnd(p); return null
    }

    private fun checkEnd(last: Int) {
        val act = active()
        if (act.size <= 1) { over = true; foldWin = true; turn = -1; return }
        pending.removeAll { folded[it] || allIn[it] }
        val canBet = act.filter { !allIn[it] }
        if (pending.isEmpty() || (canBet.size <= 1 && pending.all { toCall(it) == 0L })) {
            over = true; turn = -1; return
        }
        // Người kế tiếp (theo vòng) còn phải hành động
        for (k in 1..n) { val i = (last + k) % n; if (i in pending) { turn = i; return } }
        turn = pending.first()
    }

    fun value(p: Int) = C3.liengValue(hands[p])

    /** Chia gà (có gà phụ khi có người tất tay). Trả về số xu thắng/thua của từng người. */
    fun settle(): LongArray {
        val win = LongArray(n)
        val act = active()
        if (act.size == 1) {
            win[act[0]] += pot
        } else {
            val levels = contrib.filter { it > 0 }.distinct().sorted()
            var prev = 0L
            for (lv in levels) {
                var part = 0L
                for (i in 0 until n) part += (minOf(contrib[i], lv) - minOf(contrib[i], prev)).coerceAtLeast(0)
                prev = lv
                if (part == 0L) continue
                val eligible = act.filter { contrib[it] >= lv }
                val pool = if (eligible.isEmpty()) act else eligible
                val best = pool.maxOf { value(it) }
                val ws = pool.filter { value(it) == best }
                val each = part / ws.size
                for (w in ws) win[w] += each
                win[ws[0]] += part - each * ws.size
            }
        }
        return LongArray(n) { win[it] - contrib[it] }
    }
}

// ============================================================================
// Ba cây có nhà cái: nhà con so với cái, Ba tiên cao nhất, bằng điểm thì hòa
// ============================================================================
object BaCay {
    class Outcome(val delta: LongArray, val notes: Array<String>)

    /**
     * dealer: chỉ số người làm cái; stakes[i]: số xu nhà con i đặt; stacks: xu đang có.
     * Nhà con thua trả trước cho cái, sau đó cái trả cho nhà con thắng (hết xu thì thôi).
     */
    fun settle(hands: List<List<Int>>, dealer: Int, stakes: LongArray, stacks: LongArray): Outcome {
        val n = hands.size
        val delta = LongArray(n)
        val notes = Array(n) { "" }
        val dv = C3.baCayValue(hands[dealer])
        var dealerFunds = stacks[dealer]
        val winners = mutableListOf<Int>()
        for (i in 0 until n) {
            if (i == dealer) continue
            val v = C3.baCayValue(hands[i])
            when {
                v < dv -> {
                    val a = stakes[i].coerceAtMost(stacks[i])
                    delta[i] -= a; delta[dealer] += a; dealerFunds += a
                    notes[i] = "${C3.baCayDesc(hands[i])} · thua cái"
                }
                v > dv -> winners += i
                else -> notes[i] = "${C3.baCayDesc(hands[i])} · hòa"
            }
        }
        for (i in winners) {
            val a = stakes[i].coerceAtMost(dealerFunds)
            dealerFunds -= a
            delta[i] += a; delta[dealer] -= a
            notes[i] = "${C3.baCayDesc(hands[i])} · ăn cái" + if (a < stakes[i]) " (cái hết xu)" else ""
        }
        notes[dealer] = "${C3.baCayDesc(hands[dealer])} · làm cái"
        return Outcome(delta, notes)
    }
}

/** Máy chơi Liêng. */
object LiengBot {
    /** Trả về "fold" | "call" | "raise:<số xu>" | "allin". */
    fun decide(g: LiengGame, p: Int, bet: Long, rng: Random): String {
        val v = g.value(p)
        val tc = g.toCall(p)
        val st = g.stack[p]
        val r = rng.nextInt(100)
        val capRaise = g.raises < 8
        return when {
            v >= 300 -> if (capRaise && r < 75) "raise:${bet * (2 + rng.nextInt(4))}" else "call"
            v >= 200 -> if (capRaise && r < 60) "raise:${bet * (1 + rng.nextInt(3))}" else "call"
            v >= 7 -> when {
                tc > st / 2 && r < 50 -> "fold"
                capRaise && tc <= bet * 3 && r < 30 -> "raise:$bet"
                else -> "call"
            }
            v >= 4 -> when {
                tc == 0L -> if (capRaise && r < 15) "raise:$bet" else "call"
                tc <= bet * 2 && r < 70 -> "call"
                else -> "fold"
            }
            else -> when {
                tc == 0L -> if (capRaise && r < 8) "raise:$bet" else "call"
                tc <= bet && r < 35 -> "call"
                else -> "fold"
            }
        }
    }
}
