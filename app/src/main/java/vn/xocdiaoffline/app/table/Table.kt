package vn.xocdiaoffline.app.table

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.xocdiaoffline.app.Wallet
import vn.xocdiaoffline.app.cards3.BaCayEngine
import vn.xocdiaoffline.app.cards3.LiengEngine
import vn.xocdiaoffline.app.net.BtServer
import vn.xocdiaoffline.app.net.Conn
import vn.xocdiaoffline.app.net.NetConst
import vn.xocdiaoffline.app.net.WifiServer
import vn.xocdiaoffline.app.net.mainHandler
import vn.xocdiaoffline.app.tienlen.TLEngine
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
    val ALL = listOf("xocdia", "baucua", "tienlen", "phom", "lieng", "bacay")
    val READY = setOf("tienlen", "lieng", "bacay")
    fun title(id: String) = when (id) {
        "tienlen" -> "Tiến lên miền Nam"
        "phom" -> "Phỏm"
        "xocdia" -> "Xóc đĩa"
        "baucua" -> "Bầu cua tôm cá"
        "lieng" -> "Liêng"
        "bacay" -> "Ba cây"
        else -> id
    }
    fun maxSeats(id: String) = when (id) { "tienlen", "phom" -> 4; else -> 6 }
    fun players(id: String) = when (id) { "tienlen", "phom" -> "2 – 4 người"; else -> "2 – 6 người" }
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
) : TableSession, EngineHost {
    override val isHost = true
    override var onState: ((JSONObject) -> Unit)? = null
    override var onClosed: ((String) -> Unit)? = null
    override var onToast: ((String) -> Unit)? = null
    override var lastState: JSONObject? = null
        private set

    val seats = Array(GameInfo.maxSeats(gameId)) { Seat() }
    override var bet = 1_000L
        private set
    var phase = "lobby"
    private val pendingConns = mutableListOf<Conn>()
    override val rng = Random(System.nanoTime())
    override val persist = HashMap<String, Any>()

    var wifi: WifiServer? = null
        private set
    var bt: BtServer? = null
        private set
    /** Tên Bluetooth của máy chủ bàn, gửi trong tín hiệu Wi‑Fi để khách gộp hai đường làm một. */
    var btName: String? = null
    var btVisibleUntil = 0L

    private var engine: Engine? = null
    private var deadline = 0L
    private var result: JSONObject? = null
    private var gameNo = 0
    private var timerTask: Runnable? = null

    val hostId: String = vn.xocdiaoffline.app.Stats.shortId(ctx)

    private val botNames = listOf("Máy Tuấn", "Máy Lan", "Máy Hùng", "Máy Mai", "Máy Dũng", "Máy Hoa")

    init {
        seats[0].kind = SeatKind.LOCAL
        seats[0].name = Wallet.name(ctx)
        seats[0].xu = Wallet.xu(ctx)
        val myXu = seats[0].xu
        bet = GameInfo.BETS.lastOrNull { it * 10 <= myXu } ?: GameInfo.BETS[0]
        if (solo) for (i in 1 until minOf(seats.size, 4)) addBot(i)
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
            // Khách hỏi "máy này có phải bàn đang mời tôi không" (dò qua Bluetooth)
            "whois" -> {
                c.send(JSONObject().put("t", "iam").put("hostId", hostId).put("game", gameId))
                pendingConns.remove(c)
                mainHandler.postDelayed({ c.close() }, 1500)
            }
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
        val e = engine
        if (phase == "play" && e != null && i in e.players) {
            s.kind = SeatKind.BOT
            toastAll("${s.name} đã rời bàn, máy chơi thay")
            s.name = s.name + " (máy)"
            e.seatBecameBot(i)
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
            .put("bt", if (bt != null) (btName ?: "") else "")
            .put("hostId", hostId)
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
            else -> engine?.action(seat, a)
        }
    }

    override fun toast(seat: Int, msg: String) {
        if (seat == 0) onToast?.invoke(msg)
        else seats[seat].conn?.send(JSONObject().put("t", "toast").put("msg", msg))
    }

    override fun toastAll(msg: String) {
        onToast?.invoke(msg)
        for (s in seats) if (s.kind == SeatKind.REMOTE) s.conn?.send(JSONObject().put("t", "toast").put("msg", msg))
    }

    // ------------------------------------------------- dành cho game (EngineHost)
    override fun name(seat: Int) = seats[seat].name
    override fun xu(seat: Int) = seats[seat].xu
    override fun isBot(seat: Int) = seats[seat].kind == SeatKind.BOT
    override fun changed() = broadcast()

    override fun schedule(ms: Long, showCountdown: Boolean, task: () -> Unit) {
        cancelTimer()
        val r = Runnable { timerTask = null; deadline = 0; task() }
        timerTask = r
        deadline = if (showCountdown) System.currentTimeMillis() + ms else 0
        mainHandler.postDelayed(r, ms)
    }

    override fun cancelTimer() {
        timerTask?.let { mainHandler.removeCallbacks(it) }
        timerTask = null
        deadline = 0
    }

    override fun finish(delta: LongArray, result: JSONObject) {
        cancelTimer()
        val e = engine ?: return
        fun d(i: Int) = delta.getOrElse(i) { 0L }
        for (i in seats.indices) seats[i].xu = (seats[i].xu + d(i)).coerceAtLeast(0)
        Wallet.add(ctx, d(0))
        seats[0].xu = Wallet.xu(ctx)
        for (i in seats.indices) if (seats[i].kind == SeatKind.REMOTE && d(i) != 0L) {
            seats[i].conn?.send(JSONObject().put("t", "wallet").put("delta", d(i)))
        }
        for (s in e.players) {
            seats[s].played++
            seats[s].net += d(s)
            if (d(s) > 0) seats[s].wins++
        }
        this.result = result
        phase = "result"
        broadcast()
    }

    // ---------------------------------------------------------------- ván
    private fun startGame() {
        val occ = seats.indices.filter { seats[it].occupied }
        if (occ.size < 2) { onToast?.invoke("Cần ít nhất 2 người (thêm máy hoặc chờ bạn vào)"); return }
        val broke = occ.filter { seats[it].kind != SeatKind.BOT && seats[it].xu <= 0 }
        if (broke.isNotEmpty()) {
            toastAll("${broke.joinToString { seats[it].name }} đã hết xu, cần nhận xu miễn phí ở trang chủ")
            if (0 in broke) return
        }
        for (i in occ) if (seats[i].kind == SeatKind.BOT && seats[i].xu < bet * 20) seats[i].xu = 50_000L.coerceAtLeast(bet * 40)
        val players = occ.filter { it !in broke }.toIntArray()
        if (players.size < 2) { onToast?.invoke("Cần ít nhất 2 người còn xu"); return }
        gameNo++
        result = null
        phase = "play"
        val e = when (gameId) {
            "lieng" -> LiengEngine(this, players)
            "bacay" -> BaCayEngine(this, players)
            else -> TLEngine(this, players)
        }
        engine = e
        e.start()
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
        if (v == 0) o.put("wifi", wifi != null).put("bt", bt != null).put("btName", btName ?: "")
            .put("btVisible", System.currentTimeMillis() < btVisibleUntil)
        val e = engine
        if (e != null && phase != "lobby") {
            val gv = e.view(v)
            gv.put("left", if (deadline > 0) (deadline - System.currentTimeMillis()).coerceAtLeast(0) else -1)
            if (gv.has("eventId")) gv.put("eventId", gameNo * 10000 + gv.optInt("eventId"))
            result?.let { gv.put("result", it) }
            o.put("g", gv)
        }
        return o
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
        cancelTimer()
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
        // Rời bàn giữa ván: bị trừ 2 lần mức cược (máy sẽ chơi thay)
        val s = lastState
        if (s != null && s.optString("phase") == "play") {
            val bet = s.optLong("bet")
            Wallet.setXu(ctx, Wallet.xu(ctx) - bet * 2)
        }
        conn.close()
    }
}
