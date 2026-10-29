package vn.xocdiaoffline.app.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

val mainHandler = Handler(Looper.getMainLooper())

/**
 * Một kết nối tới máy khác (qua Wi‑Fi hoặc Bluetooth). Mỗi tin nhắn là một dòng JSON.
 * Mọi callback đều chạy trên luồng giao diện.
 */
class Conn(val label: String, private val input: InputStream, output: OutputStream, private val closer: Closeable) {
    var onMessage: ((JSONObject) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    @Volatile var closed = false
        private set
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
    private val exec = Executors.newSingleThreadExecutor()

    fun start() {
        Thread {
            try {
                val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = try { JSONObject(line) } catch (e: Exception) { null } ?: continue
                    mainHandler.post { if (!closed) onMessage?.invoke(o) }
                }
            } catch (_: Exception) { }
            close()
        }.apply { isDaemon = true }.start()
    }

    fun send(o: JSONObject) {
        if (closed) return
        val s = o.toString()
        try {
            exec.execute {
                try { writer.write(s); writer.write("\n"); writer.flush() } catch (_: Exception) { close() }
            }
        } catch (_: Exception) { }
    }

    fun close() {
        if (closed) return
        closed = true
        try { closer.close() } catch (_: Exception) { }
        exec.shutdown()
        mainHandler.post { onClose?.invoke() }
    }
}

object NetConst {
    const val TCP_PORT = 47321
    const val UDP_PORT = 47322
    const val APP_TAG = "xdo1"
    val BT_UUID: UUID = UUID.fromString("6b8f2a10-3c1e-4c55-9a7e-0d1f2c3b4a59")
}

fun localIps(): List<String> {
    val res = mutableListOf<String>()
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) res += a.hostAddress ?: continue
        }
    } catch (_: Exception) { }
    return res
}

private fun broadcastTargets(): List<InetAddress> {
    val res = mutableListOf<InetAddress>()
    try { res += InetAddress.getByName("255.255.255.255") } catch (_: Exception) { }
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) ia.broadcast?.let { res += it }
        }
    } catch (_: Exception) { }
    return res
}

// ---------------------------------------------------------------------------
// Wi‑Fi: máy chủ bàn mở cổng TCP và phát "đèn hiệu" UDP để máy khác tự thấy bàn
// ---------------------------------------------------------------------------
class WifiServer(private val onConn: (Conn) -> Unit) {
    @Volatile var beacon: String = ""
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null

    fun start(): Boolean {
        if (running) return true
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(NetConst.TCP_PORT)) }
        } catch (e: Exception) { return false }
        running = true
        Thread {
            while (running) {
                try {
                    val s = server!!.accept()
                    s.tcpNoDelay = true
                    val c = Conn(s.inetAddress?.hostAddress ?: "wifi", s.getInputStream(), s.getOutputStream(), s)
                    mainHandler.post { onConn(c) }
                } catch (_: Exception) { if (!running) break }
            }
        }.apply { isDaemon = true }.start()
        Thread {
            try {
                udp = DatagramSocket().apply { broadcast = true }
                while (running) {
                    val data = beacon.toByteArray(Charsets.UTF_8)
                    if (data.isNotEmpty()) for (t in broadcastTargets()) {
                        try { udp?.send(DatagramPacket(data, data.size, t, NetConst.UDP_PORT)) } catch (_: Exception) { }
                    }
                    Thread.sleep(1000)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        try { udp?.close() } catch (_: Exception) { }
    }
}

class FoundTable(val ip: String, val name: String, val game: String, val players: Int, val max: Int, var seen: Long)

/** Nghe đèn hiệu UDP để liệt kê các bàn trong cùng mạng Wi‑Fi / điểm phát sóng. */
class WifiFinder(ctx: Context, private val onUpdate: (List<FoundTable>) -> Unit) {
    @Volatile private var running = false
    private var sock: DatagramSocket? = null
    private val found = LinkedHashMap<String, FoundTable>()
    private val lock: WifiManager.MulticastLock? = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("xdo").apply { setReferenceCounted(false) }
    } catch (_: Exception) { null }

    fun start() {
        if (running) return
        running = true
        try { lock?.acquire() } catch (_: Exception) { }
        Thread {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true; broadcast = true; soTimeout = 1000
                    bind(InetSocketAddress(NetConst.UDP_PORT))
                }
                sock = s
                val buf = ByteArray(2048)
                while (running) {
                    try {
                        val pk = DatagramPacket(buf, buf.size)
                        s.receive(pk)
                        val o = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
                        if (o.optString("app") != NetConst.APP_TAG) continue
                        val ip = pk.address.hostAddress ?: continue
                        val t = FoundTable(ip, o.optString("name"), o.optString("game"),
                            o.optInt("players"), o.optInt("max"), System.currentTimeMillis())
                        mainHandler.post { found[ip] = t; publish() }
                    } catch (_: java.net.SocketTimeoutException) {
                        mainHandler.post { publish() }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        found.values.removeAll { now - it.seen > 4000 }
        if (running) onUpdate(found.values.toList())
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (_: Exception) { }
        try { lock?.release() } catch (_: Exception) { }
    }
}

fun connectWifi(ip: String, cb: (Conn?, String?) -> Unit) {
    Thread {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, NetConst.TCP_PORT), 6000)
            s.tcpNoDelay = true
            val c = Conn(ip, s.getInputStream(), s.getOutputStream(), s)
            mainHandler.post { cb(c, null) }
        } catch (e: Exception) {
            mainHandler.post { cb(null, "Không kết nối được tới $ip") }
        }
    }.apply { isDaemon = true }.start()
}

// ---------------------------------------------------------------------------
// Bluetooth (RFCOMM, không cần ghép đôi trước)
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class BtServer(private val adapter: BluetoothAdapter, private val onConn: (Conn) -> Unit) {
    @Volatile private var running = false
    private var server: BluetoothServerSocket? = null

    fun start(): Boolean {
        if (running) return true
        server = try {
            adapter.listenUsingInsecureRfcommWithServiceRecord("XocDiaOffline", NetConst.BT_UUID)
        } catch (e: Exception) { null } ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val s = server!!.accept()
                    val label = try { s.remoteDevice?.name ?: "Bluetooth" } catch (_: Exception) { "Bluetooth" }
                    val c = Conn(label, s.inputStream, s.outputStream, s)
                    mainHandler.post { onConn(c) }
                } catch (_: Exception) { if (!running) break; try { Thread.sleep(300) } catch (_: Exception) { } }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
    }
}

@SuppressLint("MissingPermission")
fun connectBt(adapter: BluetoothAdapter, device: BluetoothDevice, cb: (Conn?, String?) -> Unit) {
    Thread {
        try { adapter.cancelDiscovery() } catch (_: Exception) { }
        var err: String? = null
        for (secure in listOf(false, true)) {
            try {
                val s = if (secure) device.createRfcommSocketToServiceRecord(NetConst.BT_UUID)
                        else device.createInsecureRfcommSocketToServiceRecord(NetConst.BT_UUID)
                s.connect()
                val c = Conn(device.name ?: device.address, s.inputStream, s.outputStream, s)
                mainHandler.post { cb(c, null) }
                return@Thread
            } catch (e: Exception) {
                err = "Không kết nối được. Kiểm tra máy kia đã tạo bàn và bật Bluetooth chưa."
            }
        }
        mainHandler.post { cb(null, err) }
    }.apply { isDaemon = true }.start()
}
