package vn.xocdiaoffline.app.tienlen

import kotlin.random.Random

/** Một khoản chuyển xu giữa hai người chơi (chỉ số theo thứ tự người chơi trong ván). */
class Transfer(val from: Int, val to: Int, val amount: Long, val note: String)

/**
 * Luật Tiến lên miền Nam, chạy trên máy chủ bàn.
 *  - Ván đầu: ai có 3♠ (hoặc lá nhỏ nhất nếu không ai có) đi trước và phải đánh lá đó.
 *  - Các ván sau: người thắng ván trước đi trước.
 *  - Đã bỏ lượt thì không được đánh lại trong vòng đó.
 *  - Người đầu tiên hết bài thắng, ván kết thúc ngay.
 *  - Tính tiền đếm lá (mức cược = tiền 1 lá): thua trả số lá còn lại, cóng trả 26 lá,
 *    cộng thêm thối heo/hàng (heo đen 5, heo đỏ 10, 3 đôi thông 5, tứ quý 10, 4 đôi thông 15 lá).
 *  - Chặt heo/hàng: người bị chặt cuối cùng đền cho người chặt (chặt chồng thì cộng dồn).
 *  - Tới trắng: mỗi người còn lại trả 13 lá.
 */
class TLGame(val n: Int, val bet: Long, starterHint: Int, rng: Random = Random.Default) {
    val hands = Array(n) { mutableListOf<Int>() }
    var turn = 0
    var lastCombo: Combo? = null
    var lastBy = -1
    val passed = BooleanArray(n)
    val playedAny = BooleanArray(n)
    var mustInclude = -1
    var winner = -1
    var winReason = ""
    val transfers = mutableListOf<Transfer>()
    private var pending: Transfer? = null
    val notes = Array(n) { mutableListOf<String>() }

    /** Sự kiện gần nhất để hiện lên màn hình (chặt heo, vòng mới...). */
    var event = ""
    var eventId = 0
        private set

    private fun emit(s: String) { event = s; eventId++ }

    init {
        val deck = Cards.deck()
        deck.shuffle(rng)
        for (i in 0 until n) {
            hands[i].addAll(deck.subList(i * 13, i * 13 + 13))
            hands[i].sort()
        }
        // Tới trắng
        for (i in 0 until n) {
            val r = TLRules.toiTrang(hands[i])
            if (r != null) {
                winner = i
                winReason = "Tới trắng: $r"
                for (q in 0 until n) if (q != i) {
                    transfers += Transfer(q, i, bet * TLRules.TOI_TRANG_LA, "Thua tới trắng")
                    notes[q] += "Thua tới trắng"
                }
                notes[i] += "Tới trắng ($r)"
                break
            }
        }
        if (winner < 0) {
            if (starterHint in 0 until n) {
                turn = starterHint
            } else {
                var holder = (0 until n).firstOrNull { Cards.THREE_SPADES in hands[it] }
                if (holder == null) holder = (0 until n).minByOrNull { hands[it].first() }!!
                turn = holder
                mustInclude = hands[holder].first()
            }
        }
    }

    val isOver get() = winner >= 0
    val leading get() = lastCombo == null

    private fun nextNotPassed(p: Int): Int {
        var i = p
        repeat(n) {
            i = (i + 1) % n
            if (!passed[i]) return i
        }
        return p
    }

    /** Đánh bài. Trả về thông báo lỗi, hoặc null nếu hợp lệ. */
    fun play(p: Int, cards: List<Int>): String? {
        if (isOver) return "Ván đã kết thúc"
        if (p != turn) return "Chưa tới lượt bạn"
        if (cards.isEmpty()) return "Bạn chưa chọn lá nào"
        if (!hands[p].containsAll(cards) || cards.toSet().size != cards.size) return "Bài không hợp lệ"
        val combo = TLRules.classify(cards) ?: return "Các lá này không thành bộ"
        if (mustInclude >= 0 && mustInclude !in cards) return "Lượt đầu phải đánh lá ${Cards.name(mustInclude)}"
        val prev = lastCombo
        if (prev != null && !TLRules.canBeat(prev, combo)) return "Bộ này không chặn được ${prev.describe().lowercase()} trên bàn"

        if (prev != null && TLRules.isChop(prev, combo)) {
            val amount = TLRules.chopValue(prev, bet) + (pending?.amount ?: 0)
            pending = Transfer(lastBy, p, amount, "Bị chặt")
            emit("CHOP|$p|$lastBy|${prev.describe()}")
        }
        hands[p].removeAll(cards)
        playedAny[p] = true
        lastCombo = combo
        lastBy = p
        mustInclude = -1

        if (hands[p].isEmpty()) {
            winner = p
            finish()
            return null
        }
        val nx = nextNotPassed(p)
        if (nx == p) newRound(p) else turn = nx
        return null
    }

    fun pass(p: Int): String? {
        if (isOver) return "Ván đã kết thúc"
        if (p != turn) return "Chưa tới lượt bạn"
        if (lastCombo == null) return "Bạn đang đi đầu vòng, phải đánh"
        passed[p] = true
        val nx = nextNotPassed(p)
        if (nx == lastBy || nx == p) newRound(lastBy) else turn = nx
        return null
    }

    private fun settlePending() {
        pending?.let {
            transfers += it
            notes[it.from] += "Bị chặt"
            notes[it.to] += "Chặt được"
        }
        pending = null
    }

    private fun newRound(leader: Int) {
        settlePending()
        lastCombo = null
        lastBy = -1
        passed.fill(false)
        turn = leader
        emit("ROUND|$leader")
    }

    private fun finish() {
        settlePending()
        for (q in 0 until n) {
            if (q == winner) continue
            val cong = !playedAny[q]
            val la = if (cong) TLRules.CONG_LA else hands[q].size
            val base = bet * la
            val (thoi, thoiText) = TLRules.thoi(hands[q], bet)
            val note = buildString {
                append(if (cong) "Cóng ($la lá)" else "Thua, còn $la lá")
                if (thoiText.isNotEmpty()) append(", ").append(thoiText)
            }
            transfers += Transfer(q, winner, base + thoi, note)
            notes[q] += note
        }
        notes[winner] += "Về nhất"
    }
}
