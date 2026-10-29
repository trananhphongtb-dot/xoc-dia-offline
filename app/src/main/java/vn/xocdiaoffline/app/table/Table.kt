package vn.xocdiaoffline.app.table

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.net.BtServer
import vn.xocdiaoffline.app.net.Conn
import vn.xocdiaoffline.app.net.NetConst
import vn.xocdiaoffline.app.net.WifiServer
import vn.xocdiaoffline.app.net.mainHandler
import vn.xocdiaoffline.app.tienlen.TLBot
import vn.xocdiaoffline.app.tienlen.TLGame
import kotlin.random.Random

/** Giao diện chung để màn hình bàn chơi nói chuyện với bàn (dù mình là chủ bàn hay khách). */
interface TableSession {
    val isHost: Boolean
    var onState: ((JSONObject) -> Unit)?
    var onClosed: ((String) -> Unit)?
    var onToast: ((String) -> Unit)?
    val lastState: JSONObject?
    fun act(a: JSONObject)
    fun leave()
}

fun act(name: String, vararg kv: Pair<String, Any>) = JSONObject().apply {
    put("a", name); for ((k, v) in kv) put(k, v)
}

object GameInfo {
    fun title(id: String) = when (id) {
        "tienlen" -> "Tiến lên miền Nam"
        "phom" -> "Phỏm"
        "xocdia" -> "Xóc đĩa"
        "baucua" -> "Bầu cua tôm cá"
        else -> id
    }
    fun maxSeats(id: String) = when (id) { "tienlen", "phom" -> 4; else -> 6 }
    val BETS = longArrayOf(100, 500, 1_000, 2_000, 5_000, 10_000)
}

enum class SeatKind { EMPTY, LOCAL, REMOTE, BOT }

class Seat(var kind: SeatKind = SeatKind.EMPTY) {
    var name = ""
    var xu = 0L
    var conn: Conn? = null
    // Bảng điểm cả buổi (tính từ lúc người này ngồi vào ghế)
    var played = 0
    var wins = 0
    var net = 0L
    val occupied get() = kind != SeatKind.EMPTY
    fun resetScore() { played = 0; wins = 0; net = 0 }
}

/**
 * Bàn do máy này làm chủ. Mọi luật chơi chạy ở đây; các máy khách chỉ gửi thao tác
 * và nhận lại trạng thái (mỗi người chỉ thấy bài của mình).
 */
class HostTable(
    private val ctx: Context,
    val gameId: String,
    val solo: Boolean,
) : TableSession {
    override val isHost = true
    override var onState: ((JSONObject) -> Unit)? = null
    override var onClosed: ((String) -> Unit)? = null
    override var onToast: ((String) -> Unit)? = null
    override var lastState: JSONObject? = null
        private set

    val seats = Array(GameInfo.maxSeats(gameId)) { Seat() }
    var bet = 1_000L
    var phase = "lobby"
    private val pendingConns = mutableListOf<Conn>()
    private val rng = Random(System.nanoTime())

    var wifi: WifiServer? = null
        private set
    var bt: BtServer? = null
        private set

    // --- Tiến lên ---
    private var tl: TLGame? = null
    private var players = IntArray(0)
    private var firstGame = true
    private var lastWinnerSeat = -1
    private var deadline = 0L
    private var result: JSONObject? = null
    private var gameNo = 0
    private val TURN_MS = 25_000L

    private val botNames = listOf("Máy Tuấn", "Máy Lan", "Máy Hùng", "Máy Mai", "Máy Dũng", "Máy Hoa")

    init {
        seats[0].kind = SeatKind.LOCAL
        seats[0].name = Wallet.name(ctx)
        seats[0].xu = Wallet.xu(ctx)
        val myXu = seats[0].xu
        bet = GameInfo.BETS.lastOrNull { it * 10 <= myXu } ?: GameInfo.BETS[0]
        if (solo) for (i in 1 until seats.size) addBot(i)
    }

    // ------------------------------------------------------------------ mạng
    fun startWifi(): Boolean {
        if (wifi == null) {
            val w = WifiServer { onNewConn(it) }
            if (!w.start()) return false
            wifi = w
            updateBeacon()
        }
        return true
    }

    fun startBt(adapter: android.bluetooth.BluetoothAdapter): Boolean {
        if (bt == null) {
            val b = BtServer(adapter) { onNewConn(it) }
            if (!b.start()) return false
            bt = b
        }
        broadcast()
        return true
    }

    private fun onNewConn(c: Conn) {
        pendingConns += c
        c.onMessage = { handleRemote(c, it) }
        c.onClose = { onConnClosed(c) }
        c.start()
    }

    private fun seatOf(c: Conn) = seats.indexOfFirst { it.conn === c }

    private fun handleRemote(c: Conn, o: JSONObject) {
        when (o.optString("t")) {
            "hello" -> {
                if (seatOf(c) >= 0) return
                if (o.optString("game") != gameId) {
                    reject(c, "Bàn này đang chơi ${GameInfo.title(gameId)}"); return
                }
                if (phase == "play") { reject(c, "Bàn đang chơi dở ván, đợi hết ván rồi vào lại nhé"); return }
                var idx = (1 until seats.size).firstOrNull { seats[it].kind == SeatKind.EMPTY }
                if (idx == null) idx = (1 until seats.size).firstOrNull { seats[it].kind == SeatKind.BOT }
                if (idx == null) { reject(c, "Bàn đã đủ người"); return }
                pendingConns.remove(c)
                val s = seats[idx]
                s.kind = SeatKind.REMOTE
                s.name = o.optString("name").take(15).ifBlank { "Khách" }
                s.xu = o.optLong("xu").coerceAtLeast(0)
                s.conn = c
                s.resetScore()
                c.send(JSONObject().put("t", "welcome").put("seat", idx))
                toastAll("${s.name} đã vào bàn")
                broadcast()
            }
            "act" -> {
                val i = seatOf(c)
                if (i >= 0) handleAction(i, o.optJSONObject("a") ?: return)
            }
        }
    }

    private fun reject(c: Conn, msg: String) {
        c.send(JSONObject().put("t", "reject").put("msg", msg))
        mainHandler.postDelayed({ c.close() }, 800)
    }

    private fun onConnClosed(c: Conn) {
        pendingConns.remove(c)
        val i = seatOf(c)
        if (i < 0) return
        val s = seats[i]
        s.conn = null
        if (phase == "play" && i in players) {
            s.kind = SeatKind.BOT
            toastAll("${s.name} đã rời bàn, máy đánh thay")
            s.name = s.name + " (máy)"
            if (tl?.let { !it.isOver && players[it.turn] == i } == true) scheduleTurn()
        } else {
            s.kind = SeatKind.EMPTY
            toastAll("${s.name} đã rời bàn")
        }
        broadcast()
    }

    private fun updateBeacon() {
        wifi?.beacon = JSONObject()
            .put("app", NetConst.APP_TAG)
            .put("name", "Bàn của ${seats[0].name}")
            .put("game", gameId)
            .put("players", seats.count { it.kind == SeatKind.LOCAL || it.kind == SeatKind.REMOTE })
            .put("max", seats.size)
            .toString()
    }

    // ------------------------------------------------------------- thao tác
    override fun act(a: JSONObject) = handleAction(0, a)

    private fun addBot(i: Int) {
        val s = seats[i]
        s.kind = SeatKind.BOT
        s.resetScore()
        val used = seats.map { it.name }.toSet()
        s.name = botNames.firstOrNull { it !in used } ?: "Máy $i"
        s.xu = 50_000
    }

    private fun handleAction(seat: Int, a: JSONObject) {
        val host = seat == 0
        when (a.optString("a")) {
            "bet" -> if (host && phase != "play") {
                bet = a.optLong("v", bet); broadcast()
            }
            "addBot" -> if (host && phase != "play") {
                val i = a.optInt("seat", -1)
                if (i in 1 until seats.size && seats[i].kind == SeatKind.EMPTY) { addBot(i); broadcast() }
            }
            "kick" -> if (host && phase != "play") {
                val i = a.optInt("seat", -1)
                if (i in 1 until seats.size) {
                    val s = seats[i]
                    if (s.kind == SeatKind.REMOTE) {
                        val c = s.conn
                        s.conn = null; s.kind = SeatKind.EMPTY
                        c?.send(JSONObject().put("t", "kicked"))
                        mainHandler.postDelayed({ c?.close() }, 500)
                    } else s.kind = SeatKind.EMPTY
                    broadcast()
                }
            }
            "start", "next" -> if (host && phase != "play") startGame()
            "lobby" -> if (host && phase == "result") { phase = "lobby"; broadcast() }
            "play" -> {
                val g = tl ?: return
                if (phase != "play") return
                val p = players.indexOf(seat)
                if (p < 0) return
                val arr = a.optJSONArray("cards") ?: return
                val cards = (0 until arr.length()).map { arr.getInt(it) }
                val err = g.play(p, cards)
                if (err != null) toastSeat(seat, err) else afterMove()
            }
            "pass" -> {
                val g = tl ?: return
                if (phase != "play") return
                val p = players.indexOf(seat)
                if (p < 0) return
                val err = g.pass(p)
                if (err != null) toastSeat(seat, err) else afterMove()
            }
        }
    }

    private fun toastSeat(seat: Int, msg: String) {
        if (seat == 0) onToast?.invoke(msg)
        else seats[seat].conn?.send(JSONObject().put("t", "toast").put("msg", msg))
    }

    private fun toastAll(msg: String) {
        onToast?.invoke(msg)
        for (s in seats) if (s.kind == SeatKind.REMOTE) s.conn?.send(JSONObject().put("t", "toast").put("msg", msg))
    }

    // ---------------------------------------------------------------- ván
    private fun startGame() {
        val occ = seats.indices.filter { seats[it].occupied }
        if (occ.size < 2) { onToast?.invoke("Cần ít nhất 2 người (thêm máy hoặc chờ bạn vào)"); return }
        for (i in occ) if (seats[i].kind == SeatKind.BOT && seats[i].xu < bet * 20) seats[i].xu = 50_000.coerceAtLeast(bet * 40)
        players = occ.toIntArray()
        val hint = if (firstGame || lastWinnerSeat !in players) -1 else players.indexOf(lastWinnerSeat)
        tl = TLGame(players.size, bet, hint, rng)
        firstGame = false
        gameNo++
        result = null
        phase = "play"
        afterMove()
    }

    private val turnTask = Runnable { onTurnTimer() }

    private fun afterMove() {
        val g = tl ?: return
        mainHandler.removeCallbacks(turnTask)
        if (g.isOver) { finishGame(); return }
        scheduleTurn()
        broadcast()
    }

    private fun scheduleTurn() {
        val g = tl ?: return
        mainHandler.removeCallbacks(turnTask)
        val seat = players[g.turn]
        if (seats[seat].kind == SeatKind.BOT) {
            deadline = 0
            mainHandler.postDelayed(turnTask, 700L + rng.nextLong(900))
        } else {
            deadline = System.currentTimeMillis() + TURN_MS
            mainHandler.postDelayed(turnTask, TURN_MS)
        }
    }

    private fun onTurnTimer() {
        val g = tl ?: return
        if (phase != "play" || g.isOver) return
        val p = g.turn
        val isBot = seats[players[p]].kind == SeatKind.BOT
        val cards = if (isBot) TLBot.choose(g, p, rng) else TLBot.autoMove(g, p)
        var err = if (cards == null) g.pass(p) else g.play(p, cards)
        if (err != null) {
            // Phòng hờ: không bao giờ để bàn bị kẹt
            err = if (g.leading) g.play(p, listOf(if (g.mustInclude >= 0) g.mustInclude else g.hands[p].first())) else g.pass(p)
        }
        afterMove()
    }

    private fun finishGame() {
        val g = tl ?: return
        mainHandler.removeCallbacks(turnTask)
        val delta = LongArray(seats.size)
        for (t in g.transfers) {
            val from = players[t.from]; val to = players[t.to]
            val amt = t.amount.coerceAtMost(seats[from].xu.coerceAtLeast(0))
            seats[from].xu -= amt; seats[to].xu += amt
            delta[from] -= amt; delta[to] += amt
        }
        Wallet.add(ctx, delta[0])
        seats[0].xu = Wallet.xu(ctx)
        for (i in seats.indices) if (seats[i].kind == SeatKind.REMOTE && delta[i] != 0L) {
            seats[i].conn?.send(JSONObject().put("t", "wallet").put("delta", delta[i]))
        }
        lastWinnerSeat = players[g.winner]
        for (s in players) { seats[s].played++; seats[s].net += delta[s] }
        seats[lastWinnerSeat].wins++
        val lines = JSONArray()
        for (p in players.indices) {
            val s = players[p]
            lines.put(JSONObject()
                .put("seat", s)
                .put("name", seats[s].name)
                .put("delta", delta[s])
                .put("note", g.notes[p].joinToString(" · "))
                .put("cards", JSONArray(g.hands[p])))
        }
        result = JSONObject().put("winner", lastWinnerSeat).put("reason", g.winReason).put("lines", lines)
        phase = "result"
        broadcast()
    }

    // ------------------------------------------------------------ trạng thái
    private fun viewFor(v: Int): JSONObject {
        val o = JSONObject()
        o.put("game", gameId).put("phase", phase).put("bet", bet).put("me", v)
            .put("host", v == 0).put("solo", solo).put("gameNo", gameNo)
        val sa = JSONArray()
        for (s in seats) sa.put(JSONObject()
            .put("kind", s.kind.name.lowercase())
            .put("name", s.name)
            .put("xu", s.xu)
            .put("played", s.played)
            .put("wins", s.wins)
            .put("net", s.net))
        o.put("seats", sa)
        if (v == 0) {
            o.put("wifi", wifi != null).put("bt", bt != null)
        }
        val g = tl
        if (g != null && phase != "lobby") {
            val gv = JSONObject()
            gv.put("players", JSONArray(players.toList()))
            gv.put("counts", JSONArray(g.hands.map { it.size }))
            gv.put("turn", if (g.isOver) -1 else players[g.turn])
            gv.put("left", if (deadline > 0 && !g.isOver) (deadline - System.currentTimeMillis()).coerceAtLeast(0) else -1)
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
            gv.put("must", if (myP >= 0 && g.turn == myP) g.mustInclude else -1)
            gv.put("event", eventText(g.event)).put("eventId", gameNo * 10000 + g.eventId)
            result?.let { gv.put("result", it) }
            o.put("g", gv)
        }
        return o
    }

    private fun eventText(e: String): String {
        val p = e.split("|")
        return when (p.getOrNull(0)) {
            "CHOP" -> "${seats[players[p[1].toInt()]].name} chặt ${p[3].lowercase()} của ${seats[players[p[2].toInt()]].name}!"
            "ROUND" -> "Vòng mới: ${seats[players[p[1].toInt()]].name} đi trước"
            else -> ""
        }
    }

    fun broadcast() {
        updateBeacon()
        for (i in seats.indices) {
            val s = seats[i]
            if (s.kind == SeatKind.REMOTE) s.conn?.send(JSONObject().put("t", "state").put("s", viewFor(i)))
        }
        val mine = viewFor(0)
        lastState = mine
        onState?.invoke(mine)
    }

    override fun leave() {
        mainHandler.removeCallbacks(turnTask)
        onState = null; onClosed = null; onToast = null
        for (s in seats) s.conn?.let { c -> c.onClose = null; c.send(JSONObject().put("t", "bye")); mainHandler.postDelayed({ c.close() }, 300) }
        for (c in pendingConns) c.close()
        wifi?.stop(); bt?.stop()
    }
}

/** Bàn của người khác, mình vào làm khách. */
class ClientTable(private val ctx: Context, private val conn: Conn, gameId: String) : TableSession {
    override val isHost = false
    override var onState: ((JSONObject) -> Unit)? = null
    override var onClosed: ((String) -> Unit)? = null
    override var onToast: ((String) -> Unit)? = null
    override var lastState: JSONObject? = null
        private set
    private var ended = false
    private var welcomed = false

    init {
        conn.onMessage = { handle(it) }
        conn.onClose = { if (!ended) { ended = true; onClosed?.invoke(if (welcomed) "Mất kết nối với chủ bàn" else "Không vào được bàn") } }
        conn.start()
        conn.send(JSONObject().put("t", "hello").put("name", Wallet.name(ctx)).put("xu", Wallet.xu(ctx)).put("game", gameId))
    }

    private fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "welcome" -> welcomed = true
            "state" -> { val s = o.getJSONObject("s"); lastState = s; onState?.invoke(s) }
            "wallet" -> Wallet.add(ctx, o.optLong("delta"))
            "toast" -> onToast?.invoke(o.optString("msg"))
            "reject" -> end(o.optString("msg"))
            "kicked" -> end("Chủ bàn đã mời bạn ra khỏi bàn")
            "bye" -> end("Chủ bàn đã đóng bàn")
        }
    }

    private fun end(msg: String) {
        if (ended) return
        ended = true
        onClosed?.invoke(msg)
        conn.close()
    }

    override fun act(a: JSONObject) = conn.send(JSONObject().put("t", "act").put("a", a))

    override fun leave() {
        ended = true
        onState = null; onClosed = null; onToast = null
        // Rời bàn giữa ván: bị trừ 2 lần mức cược (máy sẽ đánh thay)
        val s = lastState
        if (s != null && s.optString("phase") == "play") {
            val bet = s.optLong("bet")
            Wallet.setXu(ctx, Wallet.xu(ctx) - bet * 2)
        }
        conn.close()
    }
}
