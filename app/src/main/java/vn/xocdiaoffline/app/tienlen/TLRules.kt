package vn.xocdiaoffline.app.tienlen

/*
 * Lá bài mã hoá bằng số nguyên: card = rank * 4 + suit
 *   rank: 3..10, J=11, Q=12, K=13, A=14, 2 (heo)=15
 *   suit (Tiến lên miền Nam, nhỏ -> lớn): 0 = bích ♠, 1 = chuồn ♣, 2 = rô ♦, 3 = cơ ♥
 * Như vậy so sánh lá bài chỉ cần so sánh số: 3♠ = 12 là nhỏ nhất, 2♥ = 63 là lớn nhất.
 */
object Cards {
    const val THREE_SPADES = 12
    fun rank(c: Int) = c / 4
    fun suit(c: Int) = c % 4
    fun isHeo(c: Int) = rank(c) == 15
    fun isRed(c: Int) = suit(c) >= 2
    fun rankText(c: Int): String = when (val r = rank(c)) {
        11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; 15 -> "2"
        else -> r.toString()
    }
    fun suitText(c: Int): String = when (suit(c)) { 0 -> "♠"; 1 -> "♣"; 2 -> "♦"; else -> "♥" }
    fun name(c: Int) = rankText(c) + suitText(c)
    fun deck(): MutableList<Int> = (12..63).toMutableList()
}

enum class CType { SINGLE, PAIR, TRIPLE, STRAIGHT, QUAD, PAIRSEQ }

class Combo(val type: CType, cards: List<Int>) {
    val cards: List<Int> = cards.sorted()
    val size get() = cards.size
    val top get() = cards.last()
    val hasHeo get() = cards.any { Cards.isHeo(it) }
    /** Hàng = tứ quý hoặc đôi thông (3 đôi thông trở lên). */
    val isHang get() = type == CType.QUAD || type == CType.PAIRSEQ
    val isHeoCombo get() = (type == CType.SINGLE || type == CType.PAIR) && Cards.isHeo(top)

    fun describe(): String = when (type) {
        CType.SINGLE -> if (isHeoCombo) "Heo" else "Rác"
        CType.PAIR -> if (isHeoCombo) "Đôi heo" else "Đôi"
        CType.TRIPLE -> "Sám cô"
        CType.STRAIGHT -> "Sảnh ${size} lá"
        CType.QUAD -> "Tứ quý"
        CType.PAIRSEQ -> "${size / 2} đôi thông"
    }
}

object TLRules {

    fun classify(cs: List<Int>): Combo? {
        if (cs.isEmpty() || cs.toSet().size != cs.size) return null
        val s = cs.sorted()
        val r = s.map { Cards.rank(it) }
        val n = s.size
        if (n == 1) return Combo(CType.SINGLE, s)
        if (r.all { it == r[0] }) {
            return when (n) {
                2 -> Combo(CType.PAIR, s)
                3 -> Combo(CType.TRIPLE, s)
                4 -> Combo(CType.QUAD, s)
                else -> null
            }
        }
        // Sảnh: từ 3 lá, các lá liên tiếp, không có heo
        if (n >= 3 && r.last() <= 14 && (1 until n).all { r[it] == r[it - 1] + 1 }) {
            return Combo(CType.STRAIGHT, s)
        }
        // Đôi thông: từ 3 đôi liên tiếp, không có heo
        if (n >= 6 && n % 2 == 0 && r.last() <= 14) {
            var ok = true
            for (i in 0 until n step 2) if (r[i] != r[i + 1]) ok = false
            for (i in 2 until n step 2) if (r[i] != r[i - 2] + 1) ok = false
            if (ok) return Combo(CType.PAIRSEQ, s)
        }
        return null
    }

    /** nw có chặn được prev không (prev == null nghĩa là đang đi đầu vòng). */
    fun canBeat(prev: Combo?, nw: Combo): Boolean {
        if (prev == null) return true
        if (prev.type == nw.type && prev.size == nw.size) return nw.top > prev.top
        return when {
            // 1 heo: chặt bằng tứ quý, 3 đôi thông trở lên
            prev.type == CType.SINGLE && prev.isHeoCombo ->
                nw.type == CType.QUAD || (nw.type == CType.PAIRSEQ && nw.size >= 6)
            // đôi heo: chặt bằng tứ quý, 4 đôi thông
            prev.type == CType.PAIR && prev.isHeoCombo ->
                nw.type == CType.QUAD || (nw.type == CType.PAIRSEQ && nw.size >= 8)
            // 3 đôi thông: chặt bằng tứ quý, 4 đôi thông
            prev.type == CType.PAIRSEQ && prev.size == 6 ->
                nw.type == CType.QUAD || (nw.type == CType.PAIRSEQ && nw.size >= 8)
            // tứ quý: chặt bằng 4 đôi thông
            prev.type == CType.QUAD -> nw.type == CType.PAIRSEQ && nw.size >= 8
            else -> false
        }
    }

    /** Nước đánh có phải là "chặt" (bị chặt thì phải đền). */
    fun isChop(prev: Combo?, nw: Combo): Boolean =
        prev != null && (prev.isHeoCombo || prev.isHang) && nw.isHang

    /** Giá trị đền khi một bộ bị chặt, tính theo mức cược. */
    fun chopValue(c: Combo, bet: Long): Long = when {
        c.isHeoCombo -> c.cards.sumOf { if (Cards.isRed(it)) bet else bet / 2 }
        c.type == CType.PAIRSEQ && c.size == 6 -> bet
        c.type == CType.QUAD -> bet * 2
        c.type == CType.PAIRSEQ -> bet * 3
        else -> 0
    }

    private fun rankCounts(hand: List<Int>): IntArray {
        val cnt = IntArray(16)
        for (c in hand) cnt[Cards.rank(c)]++
        return cnt
    }

    /** Tới trắng (thắng ngay khi vừa chia bài). Trả về lý do, hoặc null. */
    fun toiTrang(hand: List<Int>): String? {
        val cnt = rankCounts(hand)
        if (cnt[15] == 4) return "Tứ quý heo"
        if ((3..14).all { cnt[it] >= 1 }) return "Sảnh rồng"
        var run = 0
        for (r in 3..14) {
            run = if (cnt[r] >= 2) run + 1 else 0
            if (run >= 5) return "5 đôi thông"
        }
        if ((3..15).sumOf { cnt[it] / 2 } >= 6) return "6 đôi"
        return null
    }

    /** Thối: bài còn lại trên tay người thua có heo hoặc hàng. Trả về (số xu, mô tả). */
    fun thoi(hand: List<Int>, bet: Long): Pair<Long, String> {
        var total = 0L
        val parts = mutableListOf<String>()
        val heo = hand.filter { Cards.isHeo(it) }
        if (heo.isNotEmpty()) {
            total += heo.sumOf { if (Cards.isRed(it)) bet else bet / 2 }
            parts += "${heo.size} heo"
        }
        val cnt = rankCounts(hand)
        val quads = (3..14).count { cnt[it] == 4 }
        if (quads > 0) { total += quads * bet * 2; parts += "$quads tứ quý" }
        var run = 0
        val runs = mutableListOf<Int>()
        for (r in 3..15) {
            if (r <= 14 && cnt[r] >= 2) run++ else { if (run >= 3) runs += run; run = 0 }
        }
        for (l in runs) {
            total += if (l >= 4) bet * 3 else bet
            parts += "$l đôi thông"
        }
        return total to (if (parts.isEmpty()) "" else "Thối " + parts.joinToString(", "))
    }

    // ------------------------------------------------------------------
    // Sinh tất cả các bộ có thể đánh từ một tay bài (dùng cho máy)
    // ------------------------------------------------------------------
    private fun <T> choose(list: List<T>, k: Int): List<List<T>> {
        if (k == 0) return listOf(emptyList())
        if (list.size < k) return emptyList()
        val res = mutableListOf<List<T>>()
        fun rec(start: Int, acc: MutableList<T>) {
            if (acc.size == k) { res += acc.toList(); return }
            for (i in start until list.size) { acc += list[i]; rec(i + 1, acc); acc.removeAt(acc.size - 1) }
        }
        rec(0, mutableListOf())
        return res
    }

    fun allCombos(hand: List<Int>): List<Combo> {
        val byRank = hand.sorted().groupBy { Cards.rank(it) }
        val res = mutableListOf<Combo>()
        for ((_, cs) in byRank) {
            for (c in cs) res += Combo(CType.SINGLE, listOf(c))
            for (k in 2..4) for (sub in choose(cs, k)) {
                res += Combo(if (k == 2) CType.PAIR else if (k == 3) CType.TRIPLE else CType.QUAD, sub)
            }
        }
        // Sảnh: các lá không phải lá cao nhất lấy chất nhỏ nhất, lá cao nhất thử mọi chất
        for (start in 3..12) {
            if (byRank[start] == null) continue
            var end = start
            while (end + 1 <= 14 && byRank[end + 1] != null) {
                end++
                if (end - start + 1 >= 3) {
                    val base = (start until end).map { byRank[it]!!.first() }
                    for (topCard in byRank[end]!!) res += Combo(CType.STRAIGHT, base + topCard)
                }
            }
        }
        // Đôi thông
        for (start in 3..12) {
            if ((byRank[start]?.size ?: 0) < 2) continue
            var end = start
            while (end + 1 <= 14 && (byRank[end + 1]?.size ?: 0) >= 2) {
                end++
                if (end - start + 1 >= 3) {
                    val base = (start until end).flatMap { byRank[it]!!.take(2) }
                    for (topPair in choose(byRank[end]!!, 2)) res += Combo(CType.PAIRSEQ, base + topPair)
                }
            }
        }
        return res
    }
}
