package vn.xocdiaoffline.app.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/*
 * Tìm người chơi và gửi lời mời qua Bluetooth năng lượng thấp (BLE).
 * Không cần ghép đôi, không cần bật chế độ "cho phép tìm thấy".
 *
 *  - Máy đang rảnh (mở app ở trang chủ / màn vào bàn) phát:
 *      gói chính : dịch vụ DATA  = [0x01, id(4 byte)]
 *      gói phụ   : dịch vụ NAME  = tên người chơi (UTF-8, tối đa 22 byte)
 *    và nghe lời mời.
 *  - Chủ bàn nghe các gói trên để liệt kê người ở gần. Khi bấm Mời, chủ bàn phát:
 *      gói chính : DATA = [0x10, mãGame, hostId(4), số người, id1(4), id2(4), id3(4)]
 *      gói phụ   : NAME = tên chủ bàn
 */
object BleConst {
    val DATA: ParcelUuid = ParcelUuid.fromString("0000fda9-0000-1000-8000-00805f9b34fb")
    val NAME: ParcelUuid = ParcelUuid.fromString("0000fdaa-0000-1000-8000-00805f9b34fb")
    const val PRESENCE: Byte = 0x01
    const val INVITE: Byte = 0x10

    /** 4 byte đầu của mã máy (8 ký tự hex). */
    fun shortId(deviceId: String): ByteArray {
        val hex = deviceId.filter { it.isLetterOrDigit() }.take(8).padEnd(8, '0')
        return ByteArray(4) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun hex(b: ByteArray, from: Int, len: Int = 4) = (from until from + len).joinToString("") { "%02x".format(b[it]) }

    fun nameBytes(name: String): ByteArray {
        var s = name
        while (s.toByteArray(Charsets.UTF_8).size > 22) s = s.dropLast(1)
        return s.toByteArray(Charsets.UTF_8)
    }
}

class BleInvite(val hostId: String, val hostName: String, val game: Int)
class BlePeer(val id: String, val name: String, var seen: Long)

/** Máy đang rảnh: báo có mặt + nghe lời mời. */
@SuppressLint("MissingPermission")
class BlePresence(
    private val adapter: BluetoothAdapter,
    deviceId: String,
    private val name: String,
    private val onInvite: (BleInvite) -> Unit,
) {
    private val myId = BleConst.shortId(deviceId)
    private val myHex = BleConst.hex(myId, 0)
    private var adv: AdvertiseCallback? = null
    private var scan: ScanCallback? = null
    private val shown = HashMap<String, Long>()

    fun start(): Boolean {
        if (!adapter.isEnabled) return false
        var ok = false
        try {
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val cb = object : AdvertiseCallback() {}
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false).setTimeout(0).build()
                val data = AdvertiseData.Builder()
                    .addServiceUuid(BleConst.DATA)
                    .addServiceData(BleConst.DATA, byteArrayOf(BleConst.PRESENCE) + myId).build()
                val resp = AdvertiseData.Builder().addServiceData(BleConst.NAME, BleConst.nameBytes(name)).build()
                advertiser.startAdvertising(settings, data, resp, cb)
                adv = cb; ok = true
            }
        } catch (_: Exception) { }
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
                    override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
                }
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(BleConst.DATA).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
                scan = cb; ok = true
            }
        } catch (_: Exception) { }
        return ok
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val d = rec.getServiceData(BleConst.DATA) ?: return
        if (d.size < 7 || d[0] != BleConst.INVITE) return
        val game = d[1].toInt()
        val hostId = BleConst.hex(d, 2)
        val n = d[6].toInt().coerceIn(0, 3)
        var forMe = false
        for (k in 0 until n) if (d.size >= 11 + k * 4 && BleConst.hex(d, 7 + k * 4) == myHex) forMe = true
        if (!forMe) return
        val hostName = rec.getServiceData(BleConst.NAME)?.let { String(it, Charsets.UTF_8) } ?: "Một người"
        mainHandler.post {
            val now = System.currentTimeMillis()
            if (now - (shown[hostId] ?: 0L) < 30_000) return@post
            shown[hostId] = now
            onInvite(BleInvite(hostId, hostName, game))
        }
    }

    fun stop() {
        try { adv?.let { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        try { scan?.let { adapter.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) { }
        adv = null; scan = null
    }
}

/** Chủ bàn: liệt kê người ở gần qua BLE và phát lời mời. */
@SuppressLint("MissingPermission")
class BleInviter(
    private val adapter: BluetoothAdapter,
    hostDeviceId: String,
    private val hostName: String,
    private val game: Int,
    private val onPeers: (List<BlePeer>) -> Unit,
) {
    private val hostId = BleConst.shortId(hostDeviceId)
    private val peers = LinkedHashMap<String, BlePeer>()
    private var scan: ScanCallback? = null
    private var adv: AdvertiseCallback? = null
    private val targets = ArrayDeque<String>()
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (peers.values.removeAll { now - it.seen > 15_000 }) onPeers(peers.values.toList())
            mainHandler.postDelayed(this, 2000)
        }
    }

    fun start(): Boolean {
        if (!adapter.isEnabled) return false
        try {
            val scanner = adapter.bluetoothLeScanner ?: return false
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
            }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(BleConst.DATA).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            scan = cb
            mainHandler.postDelayed(tick, 2000)
            return true
        } catch (_: Exception) { return false }
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val d = rec.getServiceData(BleConst.DATA) ?: return
        if (d.size < 5 || d[0] != BleConst.PRESENCE) return
        val id = BleConst.hex(d, 1)
        val name = rec.getServiceData(BleConst.NAME)?.let { String(it, Charsets.UTF_8) } ?: "Người chơi"
        mainHandler.post {
            val p = peers[id]
            if (p == null) { peers[id] = BlePeer(id, name, System.currentTimeMillis()); onPeers(peers.values.toList()) }
            else p.seen = System.currentTimeMillis()
        }
    }

    /** Mời một người (id 8 ký tự hex). Phát lời mời cho tối đa 3 người mới nhất trong 60 giây. */
    fun invite(id: String): Boolean {
        targets.remove(id); targets.addLast(id)
        while (targets.size > 3) targets.removeFirst()
        return advertise()
    }

    private fun advertise(): Boolean {
        try {
            val advertiser = adapter.bluetoothLeAdvertiser ?: return false
            adv?.let { try { advertiser.stopAdvertising(it) } catch (_: Exception) { } }
            var payload = byteArrayOf(BleConst.INVITE, game.toByte()) + hostId + byteArrayOf(targets.size.toByte())
            for (t in targets) payload += BleConst.shortId(t)
            val cb = object : AdvertiseCallback() {}
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false).setTimeout(60_000).build()
            val data = AdvertiseData.Builder().addServiceUuid(BleConst.DATA).addServiceData(BleConst.DATA, payload).build()
            val resp = AdvertiseData.Builder().addServiceData(BleConst.NAME, BleConst.nameBytes(hostName)).build()
            advertiser.startAdvertising(settings, data, resp, cb)
            adv = cb
            return true
        } catch (_: Exception) { return false }
    }

    fun stop() {
        mainHandler.removeCallbacks(tick)
        try { scan?.let { adapter.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) { }
        try { adv?.let { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        scan = null; adv = null
    }
}
