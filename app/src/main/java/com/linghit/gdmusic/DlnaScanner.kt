package com.linghit.gdmusic

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** 一台可投屏的 DLNA 媒体渲染器（电视/盒子） */
data class DlnaDevice(
    /** friendlyName，如「客厅小米电视」 */
    val name: String,
    val ip: String,
    val port: Int,
    /** AVTransport 控制地址（绝对 URL，含 http://ip:port 前缀） */
    val controlUrl: String
)

/**
 * DLNA 设备发现（SSDP 协议，自研无第三方库）。
 *
 * 流程：
 *  1. MulticastSocket 向 239.255.255.250:1900 组播 M-SEARCH（ST: ssdp:all），
 *     收 3 秒内的单播响应，从 LOCATION 头拿 description.xml 地址；
 *  2. HTTP 拉 description.xml，只保留带 AVTransport:1 服务的设备（媒体渲染器），
 *     过滤打印机/路由器等无关 SSDP 设备；
 *  3. 去重后回调完整列表。
 *
 * 注意：
 *  - 响应里 LOCATION 常带 http://ip:port/... 前缀，controlURL 则可能是相对路径，
 *    统一用 description 的 base 补全；
 *  - M-SEARCH 发两遍（MX=2，设备响应有快有慢），总窗口约 3.5s；
 *  - 手机与电视必须在同一网段，路由器 AP 隔离会搜不到（搜索页有文案提示）。
 */
class DlnaScanner(private val appCtx: Context) {

    companion object {
        private const val TAG = "DlnaScan"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val TOTAL_TIMEOUT_MS = 3500L
    }

    /** 扫描结果回调（主线程） */
    fun interface Callback {
        fun onFound(devices: List<DlnaDevice>)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @Volatile
    private var scanning = false

    /** 开始一次扫描；结果（含空列表）在 TOTAL_TIMEOUT 后回调一次 */
    fun scan(cb: Callback) {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = CopyOnWriteArrayList<DlnaDevice>()
            val locations = LinkedHashSet<String>()
            val lock = acquireMulticastLock("dlna-scan")
            try {
                MulticastSocket(0).use { sock ->
                    sock.soTimeout = 1200
                    runCatching { sock.joinGroup(InetAddress.getByName(SSDP_ADDR)) }
                    val groupAddr = InetAddress.getByName(SSDP_ADDR)
                    // 发两遍：MX=2 表示设备最迟 2s 内响应，慢设备第二遍兜底
                    repeat(2) {
                        if (locations.isNotEmpty() && it == 1) return@repeat
                        val query = buildString {
                            append("M-SEARCH * HTTP/1.1\r\n")
                            append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                            append("MAN: \"ssdp:discover\"\r\n")
                            append("MX: 2\r\n")
                            append("ST: ssdp:all\r\n")
                            append("\r\n")
                        }
                        val bytes = query.toByteArray()
                        sock.send(DatagramPacket(bytes, bytes.size, groupAddr, SSDP_PORT))
                        Log.d(TAG, "M-SEARCH 已发送（第 ${it + 1} 遍）")
                        // 每遍接收窗口 ~1.4s
                        val roundEnd = System.currentTimeMillis() + 1400
                        while (System.currentTimeMillis() < roundEnd) {
                            val buf = ByteArray(2048)
                            val pkt = DatagramPacket(buf, buf.size)
                            try {
                                sock.receive(pkt)
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                            val resp = String(pkt.data, pkt.offset, pkt.length)
                            val loc = resp.lineSequence()
                                .firstOrNull { it.startsWith("LOCATION:", true) }
                                ?.substringAfter(':')?.trim()
                            if (!loc.isNullOrBlank() && locations.add(loc)) {
                                Log.d(TAG, "发现 LOCATION: $loc")
                            }
                        }
                    }
                }
                // 拉 description 过滤出带 AVTransport 的渲染器
                locations.forEach { loc ->
                    runCatching { fetchDevice(loc) }
                        .onSuccess { it?.let { d -> found.add(d) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "扫描异常: ${e.message}")
            } finally {
                releaseMulticastLock(lock)
                scanning = false
                withContext(Dispatchers.Main) { cb.onFound(found.toList()) }
            }
        }
    }

    /** 拉 description.xml，只认带 AVTransport:1 的设备；否则返回 null */
    private fun fetchDevice(location: String): DlnaDevice? {
        val resp = http.newCall(Request.Builder().url(location).build()).execute()
        if (!resp.isSuccessful) return null
        val xml = resp.body?.string() ?: return null
        resp.close()

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var friendly = ""
        var base = location.substringBeforeLast('/') + "/"
        var udn = ""
        // 当前 service 块内的字段
        var inService = false
        var svcType = ""
        var svcCtrl = ""
        var avTransportCtrl: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "friendlyName" -> friendly = parser.nextText().trim()
                    "UDN" -> udn = parser.nextText().trim()
                    "URLBase" -> base = parser.nextText().trim().let {
                        if (it.endsWith("/")) it else "$it/"
                    }
                    "service" -> {
                        inService = true; svcType = ""; svcCtrl = ""
                    }
                    "serviceType" -> if (inService) svcType = parser.nextText().trim()
                    "controlURL" -> if (inService) svcCtrl = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "service") {
                    if (svcType == "urn:schemas-upnp-org:service:AVTransport:1" && avTransportCtrl == null) {
                        avTransportCtrl = svcCtrl
                    }
                    inService = false
                }
            }
            event = parser.next()
        }

        val ctrl = avTransportCtrl ?: return null
        val host = location.removePrefix("http://").substringBefore('/')
        val name = friendly.ifBlank { "DLNA 设备 ${hashOf(udn, host)}" }
        Log.d(TAG, "渲染器就绪: $name @ $host ctrl=$ctrl")
        return DlnaDevice(
            name = name,
            ip = host.substringBefore(':'),
            port = host.substringAfter(':', "80").toIntOrNull() ?: 80,
            controlUrl = if (ctrl.startsWith("http")) ctrl else base.removeSuffix("/") + ctrl
        )
    }

    private fun hashOf(udn: String, host: String) =
        ((udn + host).hashCode().toLong() and 0xFFFF).toString(16).uppercase()

    private fun acquireMulticastLock(tag: String): WifiManager.MulticastLock? = runCatching {
        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock(tag)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) = runCatching {
        if (lock?.isHeld == true) lock.release()
    }.getOrDefault(Unit)
}
