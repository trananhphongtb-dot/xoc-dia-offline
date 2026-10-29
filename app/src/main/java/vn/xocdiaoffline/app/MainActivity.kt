package vn.xocdiaoffline.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import vn.xocdiaoffline.app.net.FoundTable
import vn.xocdiaoffline.app.net.Invite
import vn.xocdiaoffline.app.net.NearbyScanner
import vn.xocdiaoffline.app.net.Peer
import vn.xocdiaoffline.app.net.Presence
import vn.xocdiaoffline.app.net.WifiFinder
import vn.xocdiaoffline.app.net.connectBt
import vn.xocdiaoffline.app.net.connectWifi
import vn.xocdiaoffline.app.net.localIps
import vn.xocdiaoffline.app.table.ClientTable
import vn.xocdiaoffline.app.table.GameInfo
import vn.xocdiaoffline.app.table.HostTable
import vn.xocdiaoffline.app.table.TableSession
import vn.xocdiaoffline.app.table.act
import vn.xocdiaoffline.app.tienlen.TLView
import vn.xocdiaoffline.app.ui.Ui

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var session: TableSession? = null
    private var screen = "home"
    private var wifiFinder: WifiFinder? = null
    private var btReceiver: BroadcastReceiver? = null
    private var progress: AlertDialog? = null
    private var presence: Presence? = null
    private var scanner: NearbyScanner? = null
    private var peers: List<Peer> = emptyList()
    private var nearbyBox: LinearLayout? = null
    private val invited = HashMap<String, Long>()
    private var inviteDialog: AlertDialog? = null
    private var lastRecorded = ""

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        showHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun feltBackground(v: View) {
        v.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#13824F"), Ui.FELT_DARK))
    }

    // =================================================================== QUYỀN
    private var reqCode = 100
    private val permCallbacks = HashMap<Int, () -> Unit>()
    private val resultCallbacks = HashMap<Int, (Int) -> Unit>()

    private fun withPerms(perms: Array<String>, then: () -> Unit) {
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { then(); return }
        val code = reqCode++
        permCallbacks[code] = then
        requestPermissions(need.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cb = permCallbacks.remove(requestCode) ?: return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) cb()
        else toast("Cần cho phép quyền Bluetooth / vị trí lân cận để chơi qua Bluetooth")
    }

    private fun startForResult(i: Intent, cb: (Int) -> Unit) {
        val code = reqCode++
        resultCallbacks[code] = cb
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, code)
        } catch (e: Exception) {
            resultCallbacks.remove(code); toast("Máy này không hỗ trợ Bluetooth")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        resultCallbacks.remove(requestCode)?.invoke(resultCode)
    }

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Xin quyền và bật Bluetooth, xong thì gọi then(adapter). */
    private fun withBluetooth(then: (BluetoothAdapter) -> Unit) {
        val ad = btAdapter ?: run { toast("Máy này không có Bluetooth"); return }
        withPerms(btPerms()) {
            if (ad.isEnabled) then(ad)
            else startForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) {
                if (ad.isEnabled || it == RESULT_OK) then(ad) else toast("Bạn chưa bật Bluetooth")
            }
        }
    }

    // ============================================================== TRANG CHỦ
    private fun showHome() {
        stopFinders()
        screen = "home"
        startPresence()
        val root = Ui.row(this)
        feltBackground(root)
        val pad = Ui.dp(this, 20)
        root.setPadding(pad, pad, pad, pad)

        // Cột trái: tên, xu
        val left = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(Ui.text(this, "XÓC ĐĨA", 34f, Ui.GOLD, true).apply { gravity = Gravity.CENTER })
        left.addView(Ui.text(this, "OFFLINE", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.4f })
        left.addView(Ui.text(this, "Chơi cùng bạn bè qua Wi‑Fi hoặc Bluetooth, không cần mạng", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 14))
        })
        left.addView(Ui.text(this, "Tên của bạn", 13f, Ui.MUTED))
        val nameEdit = EditText(this).apply {
            setText(Wallet.name(this@MainActivity)); setTextColor(Color.WHITE); textSize = 17f
            filters = arrayOf(InputFilter.LengthFilter(15)); setSingleLine()
            background = Ui.rounded(Color.parseColor("#33000000"), Ui.dp(this@MainActivity, 10).toFloat())
            val p = Ui.dp(this@MainActivity, 10); setPadding(p, p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().trim(); if (v.isNotEmpty()) Wallet.setName(this@MainActivity, v)
                }
            })
        }
        left.addView(nameEdit, Ui.lp(Ui.MATCH, Ui.WRAP))
        left.addView(Ui.spacer(this, 14))
        val xuText = Ui.text(this, "", 22f, Ui.GOLD, true).apply { gravity = Gravity.CENTER }
        fun refreshXu() { xuText.text = "💰 ${Wallet.fmt(Wallet.xu(this))} xu" }
        refreshXu()
        left.addView(xuText, Ui.lp(Ui.MATCH, Ui.WRAP))
        left.addView(Ui.spacer(this, 8))
        left.addView(Ui.button(this, "Nhận xu miễn phí", Color.parseColor("#2E7D32"), Color.WHITE, 15f) {
            if (Wallet.claim(this)) { toast("Đã nhận, ví của bạn có ${Wallet.fmt(Wallet.START_XU)} xu"); refreshXu() }
            else toast("Khi còn dưới ${Wallet.fmt(Wallet.CLAIM_BELOW)} xu mới nhận được")
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        left.addView(Ui.spacer(this, 8))
        left.addView(Ui.button(this, "📊 Thành tích của bạn", Color.parseColor("#33FFFFFF"), Color.WHITE, 15f) { showStats() }, Ui.lp(Ui.MATCH, Ui.WRAP))
        left.addView(Ui.spacer(this, 10))
        left.addView(Ui.text(this, "Xu chỉ để chơi cho vui, không có giá trị quy đổi.", 11f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        root.addView(left, Ui.lp(0, Ui.MATCH, 0.9f))

        // Cột phải: 4 game
        val grid = Ui.col(this)
        val games = listOf(
            listOf("xocdia", "⚪🔴", "Xóc đĩa", "Sắp có"),
            listOf("baucua", "🦀🐟", "Bầu cua tôm cá", "Sắp có"),
            listOf("tienlen", "🂡🃁", "Tiến lên miền Nam", "2 – 4 người"),
            listOf("phom", "🃏", "Phỏm", "Sắp có"),
        )
        for (r in 0 until 2) {
            val row = Ui.row(this)
            for (k in 0 until 2) {
                val gm = games[r * 2 + k]
                val ready = gm[0] == "tienlen"
                val tile = Ui.col(this).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(if (ready) Color.parseColor("#E60A3D27") else Color.parseColor("#80092A1B"),
                        Ui.dp(this@MainActivity, 18).toFloat(), Ui.dp(this@MainActivity, 2), if (ready) Ui.GOLD else Color.parseColor("#447FA08E"))
                    addView(Ui.text(this@MainActivity, gm[1], 30f).apply { gravity = Gravity.CENTER })
                    addView(Ui.text(this@MainActivity, gm[2], 18f, if (ready) Color.WHITE else Ui.MUTED, true).apply { gravity = Gravity.CENTER })
                    addView(Ui.text(this@MainActivity, gm[3], 12f, if (ready) Ui.GOLD else Ui.MUTED).apply { gravity = Gravity.CENTER })
                    setOnClickListener {
                        if (ready) chooseMode(gm[0]) else toast("${gm[2]} đang được làm, sẽ có trong bản sau")
                    }
                }
                row.addView(tile, Ui.lp(0, Ui.MATCH, 1f, Ui.dp(this, 7)))
            }
            grid.addView(row, Ui.lp(Ui.MATCH, 0, 1f))
        }
        root.addView(grid, Ui.lp(0, Ui.MATCH, 1.6f).apply { leftMargin = Ui.dp(this@MainActivity, 18) })
        setContentView(root)
    }

    private fun showStats() {
        val sb = StringBuilder()
        for (g in listOf("tienlen", "phom", "xocdia", "baucua")) {
            val r = Stats.get(this, g)
            sb.append("■ ").append(GameInfo.title(g)).append("\n")
            if (r.played == 0) { sb.append("   Chưa chơi ván nào\n\n"); continue }
            val rate = r.wins * 100 / r.played
            sb.append("   Số ván: ${r.played}   ·   Thắng: ${r.wins} ($rate%)\n")
            sb.append("   Tổng xu: ${if (r.net > 0) "+" else ""}${Wallet.fmt(r.net)}   ·   Ván ăn đậm nhất: +${Wallet.fmt(r.bestWin)}\n")
            sb.append("   Chuỗi thắng dài nhất: ${r.bestStreak} ván\n\n")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("📊 Thành tích của ${Wallet.name(this)}")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("Đóng", null).show()
    }

    // ======================================================= NGƯỜI CHƠI Ở GẦN
    private fun startPresence() {
        presence?.stop()
        presence = Presence(this, Stats.deviceId(this),
            { JSONObject().put("name", Wallet.name(this)).put("xu", Wallet.xu(this)) }) { inv -> onInvite(inv) }
            .also { it.start() }
    }

    private fun onInvite(inv: Invite) {
        if (screen == "table") return
        inviteDialog?.dismiss()
        inviteDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Lời mời chơi")
            .setMessage("${inv.hostName} mời bạn vào bàn ${GameInfo.title(inv.game)}.")
            .setPositiveButton("Vào bàn") { _, _ ->
                showProgress("Đang vào bàn...")
                connectWifi(inv.ip) { c, err -> hideProgress(); if (c != null) joinWith(c, inv.game) else toast(err ?: "Lỗi") }
            }
            .setNegativeButton("Để sau", null).show()
    }

    private fun startScanner() {
        scanner?.stop()
        peers = emptyList()
        scanner = NearbyScanner(this) { list -> peers = list; renderNearby() }.also { it.start() }
    }

    private fun renderNearby() {
        val box = nearbyBox ?: return
        val h = session as? HostTable ?: return
        box.removeAllViews()
        if (peers.isEmpty()) {
            box.addView(Ui.text(this, "Chưa thấy ai. Người ở gần mở app (cùng Wi‑Fi hoặc bắt điểm phát sóng của bạn) sẽ hiện ở đây.", 12f, Ui.MUTED))
            return
        }
        val now = System.currentTimeMillis()
        for (p in peers) {
            val row = Ui.row(this).apply {
                background = Ui.rounded(Color.parseColor("#22FFFFFF"), Ui.dp(this@MainActivity, 10).toFloat())
                val pd = Ui.dp(this@MainActivity, 8); setPadding(pd * 2, pd, pd, pd)
            }
            val info = Ui.col(this)
            info.addView(Ui.text(this, "🙂 " + p.name, 15f, Color.WHITE, true))
            info.addView(Ui.text(this, "${Wallet.fmt(p.xu)} xu", 12f, Ui.GOLD))
            row.addView(info, Ui.lp(0, Ui.WRAP, 1f))
            val justInvited = now - (invited[p.id] ?: 0L) < 15_000
            row.addView(Ui.button(this, if (justInvited) "Đã mời" else "Mời", if (justInvited) Ui.GREY else Ui.GOLD,
                if (justInvited) Color.WHITE else Color.parseColor("#3A2A00"), 13f) {
                if (System.currentTimeMillis() - (invited[p.id] ?: 0L) >= 15_000) {
                    invited[p.id] = System.currentTimeMillis()
                    scanner?.invite(p, Wallet.name(this), h.gameId)
                    toast("Đã gửi lời mời tới ${p.name}")
                    renderNearby()
                }
            })
            box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }
    }

    private fun chooseMode(gameId: String) {
        val items = arrayOf("🤖  Chơi với máy", "📶  Tạo bàn (bạn bè vào qua Wi‑Fi / Bluetooth)", "🔍  Vào bàn của bạn")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(GameInfo.title(gameId))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openHost(gameId, solo = true)
                    1 -> openHost(gameId, solo = false)
                    else -> showJoin(gameId)
                }
            }.show()
    }

    private fun openHost(gameId: String, solo: Boolean) {
        val h = HostTable(this, gameId, solo)
        if (!solo && !h.startWifi()) toast("Không mở được cổng Wi‑Fi, bạn vẫn có thể mở bàn bằng Bluetooth")
        enterTable(h)
        if (solo) h.act(act("start")) else h.broadcast()
    }

    // ================================================================ VÀO BÀN
    private fun showJoin(gameId: String) {
        stopFinders()
        screen = "join"
        startPresence()
        val scroll = ScrollView(this)
        feltBackground(scroll)
        val root = Ui.col(this)
        val pad = Ui.dp(this, 18)
        root.setPadding(pad, pad, pad, pad)
        scroll.addView(root)

        val header = Ui.row(this)
        header.addView(Ui.button(this, "← Quay lại", Color.parseColor("#55000000"), Color.WHITE, 14f) { showHome() })
        header.addView(Ui.text(this, "  Vào bàn ${GameInfo.title(gameId)}", 20f, Ui.GOLD, true))
        root.addView(header)
        root.addView(Ui.spacer(this, 12))

        val cols = Ui.row(this).apply { gravity = Gravity.TOP }
        root.addView(cols, Ui.lp(Ui.MATCH, Ui.WRAP))

        // --- Wi‑Fi
        val wifiCol = Ui.col(this).apply {
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 14).toFloat())
            setPadding(pad, pad, pad, pad)
        }
        wifiCol.addView(Ui.text(this, "📶 Qua Wi‑Fi", 18f, Color.WHITE, true))
        wifiCol.addView(Ui.text(this, "Cùng một mạng Wi‑Fi, hoặc bắt Wi‑Fi điểm phát sóng của máy chủ bàn. Bàn sẽ tự hiện ở đây.", 12f, Ui.MUTED))
        wifiCol.addView(Ui.spacer(this, 8))
        val wifiList = Ui.col(this)
        val wifiEmpty = Ui.text(this, "Đang tìm bàn...", 14f, Ui.MUTED)
        wifiCol.addView(wifiEmpty)
        wifiCol.addView(wifiList)
        wifiCol.addView(Ui.spacer(this, 8))
        wifiCol.addView(Ui.button(this, "Nhập địa chỉ IP", Color.parseColor("#33FFFFFF"), Color.WHITE, 14f) { askIp(gameId) })
        cols.addView(wifiCol, Ui.lp(0, Ui.WRAP, 1f, Ui.dp(this, 6)))

        wifiFinder = WifiFinder(this) { list: List<FoundTable> ->
            wifiList.removeAllViews()
            val shown = list.filter { it.game == gameId }
            wifiEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            for (t in shown) {
                wifiList.addView(listRow("${t.name}", "${t.players}/${t.max} người · ${t.ip}") {
                    showProgress("Đang vào bàn...")
                    connectWifi(t.ip) { c, err -> hideProgress(); if (c != null) joinWith(c, gameId) else toast(err ?: "Lỗi") }
                })
            }
        }.also { it.start() }

        // --- Bluetooth
        val btCol = Ui.col(this).apply {
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 14).toFloat())
            setPadding(pad, pad, pad, pad)
        }
        btCol.addView(Ui.text(this, "🔵 Qua Bluetooth", 18f, Color.WHITE, true))
        btCol.addView(Ui.text(this, "Máy chủ bàn bấm \"Mở Bluetooth\" trong phòng chờ, rồi bạn bấm Dò tìm và chọn tên máy đó.", 12f, Ui.MUTED))
        btCol.addView(Ui.spacer(this, 8))
        val btList = Ui.col(this)
        val btStatus = Ui.text(this, "", 13f, Ui.MUTED)
        btCol.addView(Ui.button(this, "Dò tìm thiết bị Bluetooth", Ui.BLUE, Color.WHITE, 14f) {
            withBluetooth { ad -> startBtScan(ad, btList, btStatus, gameId) }
        })
        btCol.addView(btStatus)
        btCol.addView(btList)
        cols.addView(btCol, Ui.lp(0, Ui.WRAP, 1f, Ui.dp(this, 6)))

        setContentView(scroll)
    }

    private fun listRow(title: String, sub: String, onClick: () -> Unit): View {
        val v = Ui.col(this).apply {
            background = Ui.rounded(Color.parseColor("#33FFFFFF"), Ui.dp(this@MainActivity, 10).toFloat())
            val p = Ui.dp(this@MainActivity, 10); setPadding(p, p, p, p)
            addView(Ui.text(this@MainActivity, title, 16f, Color.WHITE, true))
            addView(Ui.text(this@MainActivity, sub, 12f, Ui.MUTED))
            setOnClickListener { onClick() }
        }
        v.layoutParams = Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(this@MainActivity, 6) }
        return v
    }

    private fun askIp(gameId: String) {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; hint = "Ví dụ 192.168.43.1" }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Địa chỉ IP của máy chủ bàn")
            .setMessage("Xem ở phòng chờ trên máy chủ bàn")
            .setView(e)
            .setPositiveButton("Vào") { _, _ ->
                val ip = e.text.toString().trim()
                if (ip.isNotEmpty()) {
                    showProgress("Đang vào bàn...")
                    connectWifi(ip) { c, err -> hideProgress(); if (c != null) joinWith(c, gameId) else toast(err ?: "Lỗi") }
                }
            }.setNegativeButton("Hủy", null).show()
    }

    private fun startBtScan(ad: BluetoothAdapter, list: LinearLayout, status: TextView, gameId: String) {
        stopBtScan()
        list.removeAllViews()
        val seen = HashSet<String>()
        fun addDevice(d: BluetoothDevice) {
            if (!seen.add(d.address)) return
            val name = try { d.name } catch (_: Exception) { null } ?: return
            list.addView(listRow(name, d.address) {
                stopBtScan()
                showProgress("Đang kết nối tới $name...")
                connectBt(ad, d) { c, err -> hideProgress(); if (c != null) joinWith(c, gameId) else toast(err ?: "Lỗi") }
            })
        }
        try { ad.bondedDevices?.forEach { addDevice(it) } } catch (_: Exception) { }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null) addDevice(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> status.text = "Đã dò xong. Không thấy máy chủ bàn thì bấm Dò tìm lại."
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        btReceiver = r
        status.text = "Đang dò tìm (khoảng 12 giây)..."
        try { ad.cancelDiscovery(); ad.startDiscovery() } catch (_: Exception) { status.text = "Không dò được, kiểm tra quyền và vị trí" }
    }

    private fun stopBtScan() {
        btReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        btReceiver = null
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) { }
    }

    private fun stopFinders() {
        wifiFinder?.stop(); wifiFinder = null
        presence?.stop(); presence = null
        scanner?.stop(); scanner = null
        nearbyBox = null
        stopBtScan()
    }

    private fun showProgress(msg: String) {
        hideProgress()
        progress = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(msg).setCancelable(false).show()
    }

    private fun hideProgress() { progress?.dismiss(); progress = null }

    private fun joinWith(c: vn.xocdiaoffline.app.net.Conn, gameId: String) {
        stopFinders()
        val s = ClientTable(this, c, gameId)
        enterTable(s)
        showProgress("Đang chờ chủ bàn nhận...")
    }

    // ============================================================== BÀN CHƠI
    private var tableRoot: FrameLayout? = null
    private var lobbyView: LinearLayout? = null
    private var gameView: TLView? = null

    private fun enterTable(s: TableSession) {
        stopFinders()
        session = s
        screen = "table"
        val root = FrameLayout(this)
        feltBackground(root)
        val lobby = Ui.col(this)
        val gv = TLView(this, { a -> s.act(a) }, { confirmLeave() })
        root.addView(gv, FrameLayout.LayoutParams(-1, -1))
        root.addView(ScrollView(this).apply { addView(lobby) }, FrameLayout.LayoutParams(-1, -1))
        tableRoot = root; lobbyView = lobby; gameView = gv
        setContentView(root)

        s.onToast = { toast(it) }
        s.onClosed = { msg ->
            hideProgress()
            session = null
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(msg).setCancelable(false)
                .setPositiveButton("Về trang chủ") { _, _ -> showHome() }.show()
        }
        s.onState = { st -> hideProgress(); renderState(st) }
        s.lastState?.let { renderState(it) }
        if (s is HostTable && !s.solo) startScanner()
    }

    private fun renderState(st: JSONObject) {
        val phase = st.optString("phase")
        recordStats(st)
        val scroll = lobbyView?.parent as? View
        if (phase == "lobby") {
            scroll?.visibility = View.VISIBLE
            gameView?.visibility = View.GONE
            buildLobby(st)
        } else {
            scroll?.visibility = View.GONE
            gameView?.visibility = View.VISIBLE
            gameView?.setState(st)
        }
    }

    /** Ghi thành tích cá nhân mỗi khi một ván kết thúc. */
    private fun recordStats(st: JSONObject) {
        val res = st.optJSONObject("g")?.optJSONObject("result") ?: return
        val key = st.optString("game") + "#" + st.optInt("gameNo") + "#" + System.identityHashCode(session)
        if (key == lastRecorded) return
        lastRecorded = key
        val me = st.optInt("me")
        val lines = res.optJSONArray("lines") ?: return
        for (i in 0 until lines.length()) {
            val ln = lines.getJSONObject(i)
            if (ln.optInt("seat") == me) {
                Stats.record(this, st.optString("game"), res.optInt("winner") == me, ln.optLong("delta"))
            }
        }
    }

    private fun buildLobby(st: JSONObject) {
        val lobby = lobbyView ?: return
        val s = session ?: return
        lobby.removeAllViews()
        val pad = Ui.dp(this, 16)
        lobby.setPadding(pad, pad, pad, pad)
        val host = st.optBoolean("host")
        val game = st.optString("game")

        val header = Ui.row(this)
        header.addView(Ui.button(this, "← Rời bàn", Color.parseColor("#55000000"), Color.WHITE, 14f) { confirmLeave() })
        header.addView(Ui.text(this, "  Phòng chờ · ${GameInfo.title(game)}", 20f, Ui.GOLD, true))
        lobby.addView(header)
        lobby.addView(Ui.spacer(this, 10))

        val cols = Ui.row(this).apply { gravity = Gravity.TOP }
        lobby.addView(cols, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Ghế ngồi
        val seatCol = Ui.col(this).apply {
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 14).toFloat()); setPadding(pad, pad, pad, pad)
        }
        seatCol.addView(Ui.text(this, "Người chơi", 16f, Color.WHITE, true))
        val seats = st.getJSONArray("seats")
        val me = st.optInt("me")
        for (i in 0 until seats.length()) {
            val sj = seats.getJSONObject(i)
            val kind = sj.optString("kind")
            val row = Ui.row(this).apply {
                background = Ui.rounded(Color.parseColor("#22FFFFFF"), Ui.dp(this@MainActivity, 10).toFloat())
                val p = Ui.dp(this@MainActivity, 8); setPadding(p * 2, p, p, p)
            }
            val label = when (kind) {
                "empty" -> "Ghế trống"
                "bot" -> "🤖 " + sj.optString("name")
                else -> (if (i == 0) "👑 " else "🙂 ") + sj.optString("name") + (if (i == me) " (bạn)" else "")
            }
            val info = Ui.col(this)
            info.addView(Ui.text(this, label, 15f, if (kind == "empty") Ui.MUTED else Color.WHITE, kind != "empty"))
            if (kind != "empty") {
                val played = sj.optInt("played"); val net = sj.optLong("net")
                val score = if (played > 0) "   ·   Thắng ${sj.optInt("wins")}/$played ván, ${if (net > 0) "+" else ""}${Wallet.fmt(net)}" else ""
                info.addView(Ui.text(this, "${Wallet.fmt(sj.optLong("xu"))} xu$score", 12f, Ui.GOLD))
            }
            row.addView(info, Ui.lp(0, Ui.WRAP, 1f))
            if (host && i > 0) {
                if (kind == "empty") row.addView(Ui.button(this, "+ Thêm máy", Color.parseColor("#33FFFFFF"), Color.WHITE, 13f) { s.act(act("addBot", "seat" to i)) })
                else row.addView(Ui.button(this, "Mời ra", Color.parseColor("#55C62828"), Color.WHITE, 13f) { s.act(act("kick", "seat" to i)) })
            }
            seatCol.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }
        if (host && !st.optBoolean("solo")) {
            seatCol.addView(Ui.spacer(this, 14))
            seatCol.addView(Ui.text(this, "👥 Người chơi ở gần", 16f, Color.WHITE, true))
            val box = Ui.col(this)
            seatCol.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
            nearbyBox = box
            renderNearby()
        } else nearbyBox = null
        cols.addView(seatCol, Ui.lp(0, Ui.WRAP, 1.2f, Ui.dp(this, 6)))

        // Cài đặt bàn
        val setCol = Ui.col(this).apply {
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 14).toFloat()); setPadding(pad, pad, pad, pad)
        }
        val bet = st.optLong("bet")
        setCol.addView(Ui.text(this, "Mức cược: ${Wallet.fmt(bet)} xu", 16f, Ui.GOLD, true))
        if (host) {
            val betRow1 = Ui.row(this); val betRow2 = Ui.row(this)
            for ((k, b) in GameInfo.BETS.withIndex()) {
                val chip = Ui.button(this, Wallet.fmt(b), if (b == bet) Ui.GOLD else Color.parseColor("#33FFFFFF"),
                    if (b == bet) Color.parseColor("#3A2A00") else Color.WHITE, 13f) { s.act(act("bet", "v" to b)) }
                (if (k < 3) betRow1 else betRow2).addView(chip, Ui.lp(0, Ui.WRAP, 1f, Ui.dp(this, 3)))
            }
            setCol.addView(betRow1, Ui.lp(Ui.MATCH, Ui.WRAP)); setCol.addView(betRow2, Ui.lp(Ui.MATCH, Ui.WRAP))
            setCol.addView(Ui.spacer(this, 10))
            if (!st.optBoolean("solo")) {
                val ips = localIps()
                setCol.addView(Ui.text(this, if (st.optBoolean("wifi")) "📶 Wi‑Fi: đang mở" else "📶 Wi‑Fi: chưa mở", 14f, Color.WHITE, true))
                setCol.addView(Ui.text(this, if (ips.isEmpty()) "Chưa có mạng Wi‑Fi. Hãy bật điểm phát sóng để bạn bè bắt vào." else "IP máy bạn: " + ips.joinToString(", "), 12f, Ui.MUTED))
                setCol.addView(Ui.spacer(this, 6))
                if (st.optBoolean("bt")) {
                    setCol.addView(Ui.text(this, "🔵 Bluetooth: đang mở", 14f, Color.WHITE, true))
                    val btName = try { btAdapter?.name } catch (_: Exception) { null }
                    if (btName != null) setCol.addView(Ui.text(this, "Bạn bè dò tìm và chọn: $btName", 12f, Ui.MUTED))
                } else {
                    setCol.addView(Ui.button(this, "🔵 Mở Bluetooth cho bàn", Ui.BLUE, Color.WHITE, 14f) { openBtHosting() })
                }
            }
            setCol.addView(Ui.spacer(this, 12))
            setCol.addView(Ui.button(this, "▶  Bắt đầu chơi", Ui.GOLD) { s.act(act("start")) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        } else {
            setCol.addView(Ui.spacer(this, 10))
            setCol.addView(Ui.text(this, "Đang chờ chủ bàn bắt đầu...", 15f, Color.WHITE))
        }
        cols.addView(setCol, Ui.lp(0, Ui.WRAP, 1f, Ui.dp(this, 6)))
    }

    private fun openBtHosting() {
        val h = session as? HostTable ?: return
        withBluetooth { ad ->
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startForResult(i) { code ->
                if (code == RESULT_CANCELED) toast("Cần cho phép hiện máy với thiết bị khác để bạn bè tìm thấy")
                if (!h.startBt(ad)) toast("Không mở được Bluetooth")
                else toast("Đã mở Bluetooth. Máy bạn hiện với người khác trong 5 phút")
            }
        }
    }

    private fun confirmLeave() {
        val s = session ?: run { showHome(); return }
        val st = s.lastState
        val inGame = st?.optString("phase") == "play"
        val msg = when {
            s.isHost && !(st?.optBoolean("solo") ?: true) -> "Bạn là chủ bàn, rời đi thì bàn sẽ đóng. Rời bàn?"
            !s.isHost && inGame -> "Đang giữa ván, rời bàn sẽ bị trừ ${Wallet.fmt((st?.optLong("bet") ?: 0) * 2)} xu. Rời bàn?"
            else -> "Rời bàn?"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(msg)
            .setPositiveButton("Rời bàn") { _, _ -> leaveTable() }
            .setNegativeButton("Ở lại", null).show()
    }

    private fun leaveTable() {
        scanner?.stop(); scanner = null
        session?.leave()
        session = null
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "table" -> confirmLeave()
            "join" -> showHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopFinders()
        session?.leave()
        session = null
        super.onDestroy()
    }
}
