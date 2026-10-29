package vn.xocdiaoffline.app.tienlen

import kotlin.random.Random

/** Máy đánh Tiến lên: đơn giản nhưng biết giữ heo, giữ hàng, chặt heo và chặn người sắp hết bài. */
object TLBot {

    /** Trả về các lá sẽ đánh, hoặc null nếu bỏ lượt. */
    fun choose(g: TLGame, p: Int, rng: Random = Random.Default): List<Int>? {
        val hand = g.hands[p]
        val prev = g.lastCombo
        val must = g.mustInclude
        val cands = TLRules.allCombos(hand).filter {
            TLRules.canBeat(prev, it) && (must < 0 || must in it.cards)
        }
        if (cands.isEmpty()) return null

        // Đánh hết bài trong một lần nếu được
        cands.firstOrNull { it.size == hand.size }?.let { return it.cards }

        val minOpp = (0 until g.n).filter { it != p }.minOf { g.hands[it].size }
        val cnt = IntArray(16).also { a -> hand.forEach { a[Cards.rank(it)]++ } }

        // Phạt khi phải xé bộ đôi / sám để đánh
        fun breakCost(c: Combo): Int {
            var cost = 0
            val used = c.cards.groupBy { Cards.rank(it) }
            for ((r, cs) in used) {
                val left = cnt[r] - cs.size
                if (left == 1 && cnt[r] >= 2) cost += 12
                if (left == 2 && cnt[r] >= 3) cost += 6
            }
            if (c.type != CType.QUAD && cnt.any { it == 4 } && c.cards.any { cnt[Cards.rank(it)] == 4 }) cost += 40
            return cost
        }

        if (prev == null) {
            // Đi đầu vòng: tránh đánh heo/hàng, ưu tiên bộ dài chứa lá nhỏ nhất
            val normal = cands.filter { !it.isHang && !it.hasHeo }
            val pool = if (normal.isNotEmpty()) normal else cands
            val lowest = pool.minOf { it.cards.first() }
            var withLowest = pool.filter { lowest in it.cards }
            if (minOpp == 1) {
                // Đối thủ còn 1 lá: tránh đánh rác lẻ
                val multi = pool.filter { it.size >= 2 }
                if (multi.isNotEmpty()) withLowest = multi
                else return listOf(pool.maxOf { it.top })
            }
            val best = withLowest.minByOrNull { c ->
                val typeScore = when (c.type) {
                    CType.STRAIGHT -> 0; CType.PAIRSEQ -> 1; CType.TRIPLE -> 2
                    CType.PAIR -> 3; CType.SINGLE -> 4; CType.QUAD -> 5
                }
                typeScore * 10 - c.size + breakCost(c) / 3
            } ?: pool.first()
            return best.cards
        }

        val urgent = minOpp <= 2 || hand.size <= 4
        // Có heo trên bàn mà mình có hàng chặt được thì chặt luôn
        if (prev.isHeoCombo || prev.isHang) {
            val chops = cands.filter { it.isHang }
            if (chops.isNotEmpty()) return chops.minBy { it.top }.cards
        }
        fun cost(c: Combo): Int {
            var v = c.top
            if (c.hasHeo && !prev.hasHeo) v += 60
            if (c.isHang) v += 120
            return v + breakCost(c)
        }
        val best = cands.minBy { cost(it) }
        val expensive = (best.hasHeo && !prev.hasHeo) || best.isHang
        if (expensive && !urgent) {
            // Chưa gấp thì thường giữ heo lại
            if (hand.size > 8 || rng.nextInt(100) < 60) return null
        }
        if (!urgent && breakCost(best) >= 12 && rng.nextInt(100) < 50) return null
        return best.cards
    }

    /** Khi người chơi hết giờ: đi đầu thì đánh lá nhỏ nhất (hoặc lá bắt buộc), còn lại thì bỏ lượt. */
    fun autoMove(g: TLGame, p: Int): List<Int>? {
        if (g.lastCombo != null) return null
        val c = if (g.mustInclude >= 0) g.mustInclude else g.hands[p].first()
        return listOf(c)
    }
}
