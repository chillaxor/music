package com.linghit.gdmusic

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 端侧 LLM 推理封装（Google LiteRT-LM 0.18.x）。
 *
 * 定位：完全离线、零 Key、零费用——在手机上直接跑 .litertlm 模型
 * （Qwen3-0.6B / 1.7B / 4B），用于公众号/小红书文案加工。
 *
 * 设计要点（都是踩过的坑）：
 *  1. Engine.initialize() 会阻塞数秒（官方说可达 10s），必须在后台线程，且只在
 *     真正要用时才加载；Engine 加载一次后常驻复用，别反复建。
 *  2. Engine / Conversation 都是 AutoCloseable，且 close() 二次调用会抛
 *     IllegalStateException —— 这里做了幂等保护。
 *  3. Qwen3 默认开启思维链（thinking），会把上下文吃光导致正文一个字都吐不出来。
 *     必须显式 ThinkingConfig(enableThinking=false) 关掉。
 *  4. 上下文窗口是硬上限，超了不报错、**静默截断**。喂长文前要自己分段。
 *  5. 全部操作收敛到单线程 executor：Engine 非线程安全，且避免流式回调跨线程竞态。
 */
object AiEngine {

    private const val TAG = "AiEngine"

    /** 单线程执行器：串行化 Engine 的所有生命周期操作 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-llm").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedPath: String? = null

    /** 缓存目录（编译产物缓存），降级重建引擎时复用 */
    @Volatile
    private var cacheDirPath: String? = null

    /**
     * GPU 后端是否已被判定不可用。
     * 上游 issue #1860：OpenCL 缺失时 initialize() 会成功但推理期才炸，
     * 所以要等第一次真实推理才知道。判死后本进程内不再试 GPU。
     */
    @Volatile
    var gpuDisabled = false

    /** 构建并初始化一个引擎（不写全局状态） */
    private fun buildEngine(modelPath: String, backend: Backend, cacheDir: String): Engine {
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            // 缓存编译产物：第二次加载快很多
            cacheDir = cacheDir
        )
        val e = Engine(cfg)
        e.initialize()
        if (!e.isInitialized()) {
            runCatching { e.close() }
            error("引擎初始化后 isInitialized=false")
        }
        return e
    }

    /** 当前是否已就绪（UI 用它决定按钮状态） */
    val isReady: Boolean get() = engine?.isInitialized() == true

    /** 已加载的模型绝对路径（UI 显示用） */
    val currentModelPath: String? get() = loadedPath

    /**
     * 加载模型（若已加载同一模型则直接复用）。
     *
     * @param modelPath .litertlm 文件绝对路径
     * @param useGpu     是否尝试 GPU 后端。GPU 更快但部分机型/驱动不支持，
     *                   失败时自动回落到 CPU。
     * @return 实际使用的后端描述（给 UI 显示）
     */
    suspend fun load(
        context: Context,
        modelPath: String,
        useGpu: Boolean = true
    ): String = withContext(Dispatchers.Default) {
        if (engine?.isInitialized() == true && loadedPath == modelPath) {
            return@withContext "已就绪（复用）"
        }
        // 换了模型或引擎异常 → 先彻底释放旧的
        releaseInternal()

        val appCtx = context.applicationContext
        cacheDirPath = appCtx.cacheDir.absolutePath
        val result = runCatching {
            // 先试 GPU，失败回落 CPU。GPU 后端对部分骁龙/联发科机型更友好，
            // 但驱动不兼容时会抛异常，不能让用户看到崩溃。
            val backends = buildList {
                if (useGpu && !gpuDisabled) add(Backend.GPU())
                add(Backend.CPU(threadCount = null, numOfThreads = null))
            }
            var lastErr: Throwable? = null
            for (be in backends) {
                try {
                    val e = buildEngine(modelPath, be, appCtx.cacheDir.absolutePath)
                    engine = e
                    loadedPath = modelPath
                    return@runCatching be.name
                } catch (t: Throwable) {
                    lastErr = t
                    Log.w(TAG, "后端 ${be.name} 加载失败，尝试下一个", t)
                    // GPU 在加载期就失败 → 直接标记不可用，别再试了
                    if (be is Backend.GPU) gpuDisabled = true
                }
            }
            throw lastErr ?: IllegalStateException("所有后端都加载失败")
        }

        result.getOrElse { t ->
            Log.e(TAG, "模型加载失败: ${t.message}", t)
            throw t
        }
    }

    /**
     * 流式生成。
     *
     * ⚠️ **CPU 兜底必须包住整个生成过程，不能只包引擎构建**。
     * 上游已知问题（LiteRT-LM issue #1860）：OpenCL 缺失时
     * `Engine.initialize()` **会成功**，但第一条消息才报
     * "Can not find OpenCL library on this device" —— 也就是说
     * 「加载成功」不代表「后端真的能用」。所以这里：
     *   1. 生成中途若抛 OpenCL 类异常 → 重建 CPU 引擎并重跑一次；
     *   2. 一旦降级过，本进程内不再尝试 GPU（记住结果，避免每次生成都白跑一遍）。
     *
     * @param systemPrompt 系统提示词（角色设定、去 AI 味规则等）
     * @param userText     用户输入
     * @return Flow，每个元素是一段增量文本
     */
    /**
     * 流式生成。
     *
     * ⚠️ 用 channelFlow 而非 flow：库内部的 `conv.sendMessageAsync` 在自己的调度器/线程上
     * 发射 token，我们在其 collect 回调里 `send`，跨调度器发射在 flow 构建器里会触发
     * "Flow invariant is violated"，channelFlow 的 send 允许跨调度器安全发射。
     */
    fun generate(
        context: Context,
        systemPrompt: String,
        userText: String
    ): Flow<String> = channelFlow {
        val e = engine
        if (e == null || !e.isInitialized()) {
            throw IllegalStateException("模型未加载，请先调用 load()")
        }
        try {
            generateWith(e, systemPrompt, userText).collect { send(it) }
        } catch (t: Throwable) {
            // GPU 后端在推理期才暴露的问题（OpenCL 缺失等）→ 降级 CPU 重试
            if (!gpuDisabled && isGpuFatal(t)) {
                Log.w(TAG, "GPU 后端推理失败，降级 CPU：${t.message}")
                gpuDisabled = true
                val path = loadedPath ?: throw t
                val cpu = buildEngine(
                    path,
                    Backend.CPU(threadCount = null, numOfThreads = null),
                    cacheDirPath ?: context.cacheDir.absolutePath
                )
                releaseInternal()
                engine = cpu
                loadedPath = path
                generateWith(cpu, systemPrompt, userText).collect { send(it) }
            } else {
                throw t
            }
        }
    }

    /** 判断是不是「GPU 后端不可用」这类可降级的错误 */
    private fun isGpuFatal(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        val all = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return msg.contains("opencl") || all.contains("opencl") ||
                msg.contains("gpu") || all.contains("cannot find") && all.contains("library")
    }

    /** 用指定引擎跑一次生成 */
    private fun generateWith(
        e: Engine,
        systemPrompt: String,
        userText: String
    ): kotlinx.coroutines.flow.Flow<String> = channelFlow {
        // 会话级配置：关思维链 + 采样参数
        // temperature 0.7：文案要有点变化但不能发散；topK/topP 收窄候选词
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = emptyList(),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7, seed = 0),
            // ⚠️ Qwen3 默认开思维链，会把 2048 上下文吃光，必须显式关
            thinkingConfig = ThinkingConfig(enableThinking = false),
            // ⚠️ 上下文窗口 2048 是**硬上限，超了不报错而是静默截断输入**。
            // 留 768 给回答，输入就有 ~1280 可用；系统提示词约 200 字 + 参考资料 600 字
            // 刚好放得下。maxOutputToken 给太大反而会挤掉输入（实测导致回答为空）。
            maxOutputToken = 768
        )
        val conv = e.createConversation(cfg)
        try {
            // Flow 版 sendMessageAsync：按消息增量吐出，结束时最后一条是完整结果。
            // 库在自己线程上发射，这里用 channelFlow 的 send 跨调度器安全转发。
            conv.sendMessageAsync(userText).collect { msg ->
                val text = extractText(msg)
                if (text.isNotEmpty()) send(text)
            }
        } finally {
            // 会话必须关，否则 native 侧资源泄漏；close 幂等已由引擎侧保证
            runCatching { conv.close() }
        }
    }

    /** 从 Message 里取纯文本（contents 是 List<Content>，文本型是 Content.Text） */
    private fun extractText(msg: Message): String {
        val sb = StringBuilder()
        for (c in msg.contents.contents) {
            if (c is com.google.ai.edge.litertlm.Content.Text) sb.append(c.text)
        }
        return sb.toString()
    }

    /** 释放引擎，回收 native 内存。切模型 / 退出页面时调用。 */
    suspend fun release() = withContext(Dispatchers.Default) {
        releaseInternal()
    }

    private fun releaseInternal() {
        val e = engine
        engine = null
        loadedPath = null
        if (e != null) {
            // close 二次调用会抛，这里吞掉
            runCatching { e.close() }
                .onFailure { Log.w(TAG, "引擎释放异常（可忽略）", it) }
        }
    }

    /** 同步释放，供 Activity.onDestroy 调用 */
    fun releaseBlocking() {
        val e = engine
        engine = null
        loadedPath = null
        if (e != null) runCatching { e.close() }
    }
}
