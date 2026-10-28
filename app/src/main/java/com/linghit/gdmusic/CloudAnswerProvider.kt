package com.linghit.gdmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 升级到云端 Provider：检索仍端侧跑（模型自己打不开网页），最后改用云端大模型读材料。
 *
 * 接口形态：OpenAI 兼容的 /chat/completions（流式 SSE）。这意味着填好 endpoint 后，
 * 通义/DeepSeek/硅基流动/OpenAI 等任意兼容服务都能直接用，无需改代码。
 *
 * 未配置时 ask 直接返回清晰的错误引导——这就是「留一个云端接口」的入口：
 * 现在它是可用的桩，填入 Key 即生效。
 *
 * ⚠️ 同 LocalAnswerProvider，必须用 channelFlow：检索 IO 回调与 SSE 流都在 IO 上吐字，
 * flow 的跨调度器 emit 会崩，channelFlow.send 才安全。
 */
object CloudAnswerProvider : QnaProvider {
    override val id = "cloud"
    override val label = "升级到云端（OpenAI 兼容）"
    override val kind = ProviderKind.CLOUD

    override fun isReady(ctx: Context): Boolean = CloudConfigStore.load(ctx).isValid()

    override suspend fun prepare(ctx: Context) {
        // 云端无需本地模型；无副作用
    }

    override fun ask(ctx: Context, question: String): Flow<QnaEvent> = channelFlow {
        val cfg = CloudConfigStore.load(ctx)
        if (!cfg.isValid()) {
            send(QnaEvent.Error(
                "云端通道未配置：点右上角「云端设置」填写\n" +
                "· endpoint（如 https://api.siliconflow.cn/v1）\n" +
                "· API Key\n· 模型名（如 deepseek-chat / Qwen/Qwen2.5-72B-Instruct）\n\n" +
                "填好后即用云端大模型回答，且仍走端侧三段式检索拿最新资料。"
            ))
            return@channelFlow
        }
        val r = QnaRetrieval.retrieve(question) { send(QnaEvent.Stage(it)) }
        if (r.note.isNotBlank()) send(QnaEvent.Stage(r.note))
        if (r.sources.isNotEmpty()) send(QnaEvent.Sources(r.sources))
        send(QnaEvent.Stage("云端模型 ${cfg.model} 回答中…"))
        val sys = QnaPrompts.system(useCloud = true)
        val usr = QnaPrompts.userWithContext(question, r.context)
        withContext(Dispatchers.IO) {
            streamAnswer(cfg, sys, usr) { send(QnaEvent.Delta(it)) }
        }
        send(QnaEvent.Done)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 调用 OpenAI 兼容的 /chat/completions（流式 SSE） */
    private suspend fun streamAnswer(
        cfg: CloudConfig,
        sys: String,
        usr: String,
        emit: suspend (String) -> Unit
    ) {
        val url = cfg.endpoint.removeSuffix("/") + "/chat/completions"
        val bodyJson = JSONObject().apply {
            put("model", cfg.model)
            put("stream", true)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", usr))
            })
        }.toString()

        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = resp.body?.string()?.take(400) ?: ""
                throw IllegalStateException("云端返回 ${resp.code}：$msg")
            }
            val source = resp.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                runCatching {
                    val o = JSONObject(data)
                    val arr = o.optJSONArray("choices") ?: return@runCatching
                    if (arr.length() > 0) {
                        val delta = arr.getJSONObject(0).optJSONObject("delta") ?: return@runCatching
                        val c = delta.optString("content")
                        if (c.isNotEmpty()) emit(c)
                    }
                }
            }
        }
    }
}
