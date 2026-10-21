package com.linghit.gdmusic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DLNA 投屏控制器（AVTransport SOAP，自研）。
 *
 * 关键点：
 *  - SetAVTransportURI 必须带 DIDL-Lite metadata，部分电视（小米/索尼/JBL 条形音箱）
 *    对空 metadata 直接报 402/605，protocolInfo 按扩展名猜（mp4/m3u8/其他）；
 *  - SOAPAction 头固定 `urn:schemas-upnp-org:service:AVTransport:1#Action`；
 *  - GetPositionInfo 返回的 RelTime/TrackDuration 是 "hh:mm:ss" 格式，
 *    NOT_IMPLEMENTED 也出现在这里，解析失败按未知处理；
 *  - 切源（换集）必须先 Stop 再 SetAVTransportURI，部分设备旧 URI 未停时新 URI 报 705。
 */
class DlnaController(private val device: DlnaDevice) {

    companion object {
        private const val TAG = "DlnaCtrl"
        private const val NS = "urn:schemas-upnp-org:service:AVTransport:1"
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()
        private const val INSTANCE = "0"
    }

    /** 位置/状态回调（主线程）；state 见 getTransportInfo */
    fun interface PosCallback {
        fun onPos(posMs: Long, durMs: Long, state: String)
    }

    /** 简单成败回调（主线程）；msg 为失败原因，成功为空串 */
    fun interface ResultCallback {
        fun onDone(ok: Boolean, msg: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @Volatile
    private var lastOk = true

    /** 投送并播放一条直链 */
    fun cast(url: String, title: String, cb: ResultCallback) {
        scope.launch {
            stop { _, _ ->
                setUri(url, title) { ok, msg ->
                    if (!ok) {
                        lastOk = false
                        cb.onDone(false, msg)
                        return@setUri
                    }
                    // SetAVTransportURI 成功后设备进入 TRANSITIONING（正在加载源），
                    // 立刻发 Play 会被小米/索尼等设备以 705 拒绝——而电视其实已经自己
                    // 起播了，误判失败会把用户踢回本地。等 400ms 离开过渡态再 Play，
                    // 仍报 705 就再重试一次，两次都挂才当真失败。
                    scope.launch {
                        delay(400)
                        play { ok2, msg2 ->
                            if (!ok2 && msg2.contains("705")) {
                                Log.w(TAG, "Play 705（设备过渡中），500ms 后重试")
                                scope.launch {
                                    delay(500)
                                    play { ok3, msg3 ->
                                        lastOk = ok3
                                        cb.onDone(ok3, msg3)
                                    }
                                }
                            } else {
                                lastOk = ok2
                                cb.onDone(ok2, msg2)
                            }
                        }
                    }
                }
            }
        }
    }

    fun play(cb: ResultCallback) = action("Play", cb) { b ->
        b.append("<InstanceID>$INSTANCE</InstanceID><Speed>1</Speed>")
    }

    fun pause(cb: ResultCallback) = action("Pause", cb) { b ->
        b.append("<InstanceID>$INSTANCE</InstanceID>")
    }

    fun stop(cb: ResultCallback) = action("Stop", cb) { b ->
        b.append("<InstanceID>$INSTANCE</InstanceID>")
    }

    /** 跳转到指定位置（毫秒） */
    fun seekTo(posMs: Long, cb: ResultCallback) = action("Seek", cb) { b ->
        b.append("<InstanceID>$INSTANCE</InstanceID>")
        b.append("<Unit>REL_TIME</Unit>")
        b.append("<Target>${fmtTime(posMs)}</Target>")
    }

    /**
     * 轮询播放位置。注意：GetPositionInfo 的响应**不带** TransportState 字段
     * （那是 GetTransportInfo 才有的），所以不能拿它判活——以前每次轮询都解析出
     * "ERR"，castFailCount 每秒 +1，第 5 次就把正常投屏误踢回本地。
     * 现在只有 SOAP 无响应或返回 fault 才算 "ERR"；正常响应即使位置字段缺失
     * （NOT_IMPLEMENTED，解析为 -1 → 0）也算成功。
     */
    fun getPositionInfo(cb: PosCallback) {
        scope.launch {
            var pos = 0L; var dur = 0L; var state = "ERR"
            runCatching {
                val body = soap("GetPositionInfo") {
                    it.append("<InstanceID>$INSTANCE</InstanceID>")
                }
                if (body == null || body.contains("fault", true)) return@runCatching
                pos = parseTime(body, "RelTime")
                dur = parseTime(body, "TrackDuration")
                state = "OK"
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                cb.onPos(if (pos < 0) 0 else pos, if (dur < 0) 0 else dur, state)
            }
        }
    }

    /** 当前传输状态：PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIUM_PRESENT */
    fun getTransportInfo(cb: (String) -> Unit) {
        scope.launch {
            var state = "ERR"
            runCatching {
                val body = soap("GetTransportInfo") {
                    it.append("<InstanceID>$INSTANCE</InstanceID>")
                }
                state = body?.let { parseState(it) } ?: "ERR"
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { cb(state) }
        }
    }

    private fun setUri(url: String, title: String, cb: ResultCallback) = action("SetAVTransportURI", cb) { b ->
        b.append("<InstanceID>$INSTANCE</InstanceID>")
        b.append("<CurrentURI>${escapeXml(url)}</CurrentURI>")
        b.append("<CurrentURIMetaData>${escapeXml(didlLite(url, title))}</CurrentURIMetaData>")
    }

    // ===================== SOAP 基础设施 =====================

    private fun action(
        name: String,
        cb: ResultCallback,
        args: (StringBuilder) -> Unit
    ) {
        scope.launch {
            var ok = false
            var msg = ""
            runCatching {
                val resp = soap(name, args)
                if (resp == null) {
                    msg = "设备无响应"
                } else if (resp.contains("fault", true) || resp.contains("errorCode", true)) {
                    msg = faultMsg(resp) ?: "设备返回错误"
                } else {
                    ok = true
                }
            }.onFailure { msg = it.message ?: "请求失败" }
            lastOk = ok
            kotlinx.coroutines.withContext(Dispatchers.Main) { cb.onDone(ok, msg) }
        }
    }

    /** 发 SOAP 动作，返回响应体 XML；HTTP 非 200 抛异常由调用方兜 */
    private fun soap(action: String, args: (StringBuilder) -> Unit): String? {
        val sb = StringBuilder()
        args(sb)
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.upnp.org/soap/envelope/" s:encodingStyle="http://schemas.upnp.org/soap/encoding/">
            <s:Body><u:$action xmlns:u="$NS">${sb}</u:$action></s:Body></s:Envelope>
        """.trimIndent()
        Log.d(TAG, "SOAP $action -> ${device.controlUrl}")
        val req = Request.Builder()
            .url(device.controlUrl)
            .post(envelope.toRequestBody(XML))
            .header("SOAPAction", "\"$NS#$action\"")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.w(TAG, "$action HTTP ${resp.code}: ${body.take(200)}")
                if (body.contains("<", true)) return body   // UPnP 错误：HTTP 500 + fault 体
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return body
        }
    }

    /** DIDL-Lite metadata：严格校验的设备没有它直接 402 */
    private fun didlLite(url: String, title: String): String {
        val proto = when {
            url.contains(".m3u8", true) -> "http-get:*:application/vnd.apple.mpegurl:*"
            url.contains(".mpd", true) -> "http-get:*:application/dash+xml:*"
            url.contains(".mp4", true) -> "http-get:*:video/mp4:*"
            url.contains(".flv", true) -> "http-get:*:video/x-flv:*"
            else -> "http-get:*:video/*:*"
        }
        val t = escapeXml(title.ifBlank { "视频" })
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>$t</dc:title><dc:creator>未知</dc:creator>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="$proto">${escapeXml(url)}</res></item></DIDL-Lite>"""
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 从 SOAP fault 里抠错误描述；不是 fault 返回 null */
    private fun faultMsg(xml: String): String? {
        if (!xml.contains("fault", true)) return null
        val code = Regex("<errorCode>(\\d+)</errorCode>").find(xml)?.groupValues?.get(1)
        val desc = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
        return buildString {
            if (code != null) append("错误 $code")
            if (!desc.isNullOrBlank()) append("：$desc")
        }.ifBlank { "设备拒绝操作" }
    }

    /** "hh:mm:ss" / "mm:ss" → 毫秒；NOT_IMPLEMENTED 等返回 -1 */
    private fun parseTime(xml: String, tag: String): Long {
        val m = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: return -1
        return toMs(m.groupValues[1].trim())
    }

    private fun parseState(xml: String): String {
        // GetTransportInfo 用 CurrentTransportState；GetPositionInfo 失败时也可能带
        Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(xml)
            ?.let { return it.groupValues[1].trim() }
        Regex("<TransportState>(.*?)</TransportState>").find(xml)
            ?.let { return it.groupValues[1].trim() }
        return "ERR"
    }

    private fun fmtTime(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun toMs(t: String): Long {
        if (t.isBlank() || !t.first().isDigit()) return -1
        val parts = t.split(":")
        if (parts.size !in 2..3) return -1
        val nums = parts.map { it.trim().toLongOrNull() ?: return -1 }
        return when (nums.size) {
            2 -> (nums[0] * 60 + nums[1]) * 1000
            else -> (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000
        }
    }
}
