package com.linghit.gdmusic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk 离线语音识别封装。
 *
 * 直接用 AudioRecord 喂 16kHz 单声道 PCM16 给 Recognizer（标准 Vosk Android 用法，
 * 比 SpeechService 更可控）。partialResult 实时回显、result 取定稿。
 */
class SttEngine(private val modelPath: String) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    fun start(
        scope: CoroutineScope,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            try {
                model = Model(modelPath)
                recognizer = Recognizer(model, 16000.0f)
                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                val buffer = ByteArray(minBuf)
                recorder?.startRecording()
                while (running) {
                    val n = recorder?.read(buffer, 0, buffer.size) ?: -1
                    if (n > 0) {
                        if (recognizer?.acceptWaveForm(buffer, n) == true) {
                            val text = JSONObject(recognizer?.result ?: "{}").optString("text")
                            if (text.isNotBlank()) withContext(Dispatchers.Main) { onFinal(text) }
                        } else {
                            val text = JSONObject(recognizer?.partialResult ?: "{}").optString("text")
                            if (text.isNotBlank()) withContext(Dispatchers.Main) { onPartial(text) }
                        }
                    }
                }
            } catch (t: Throwable) {
                running = false
                withContext(Dispatchers.Main) { onError(t.message ?: "STT 错误") }
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun stopInternal() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    fun release() {
        stop()
        job?.cancel()
        stopInternal()
    }
}
