package com.worddrill.tts

import android.media.AudioFormat
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * 离线 TTS 引擎（sherpa-onnx + piper 模型）。
 *
 * 对系统而言就是一个普通 TTS 引擎：主 App 用
 * `TextToSpeech(ctx, listener, "com.worddrill.tts")` 就能指名调用它。
 *
 * 几个关键取舍：
 * - 冷启动要建图（1～2 秒），所以引擎单例常驻，空闲 90 秒才释放内存；
 * - 合成是阻塞的，`generate` 和释放都在同一把锁里，绝不出现「生成到一半模型被释放」；
 * - 语速直接映射 `speak(..., rate)`，口音由 `setLanguage` 的国家码决定；
 * - 失败一律 `callback.error()` 并返回 ERROR，让上层能回退系统 TTS。
 */
class TtsEngineService : TextToSpeechService() {

    private val engineLock = Any()
    private var engine: OfflineTts? = null
    private var engineAccent: Accent? = null

    /** onStop 之后正在写的分块要尽快停下，别把过时的句子念完。 */
    @Volatile
    private var stopRequested = false

    private val idleThread by lazy {
        HandlerThread("tts-idle").apply { start() }
    }

    private val idleHandler by lazy { Handler(idleThread.looper) }

    /** 十分钟没人用就释放模型；被系统杀掉进程更省事，这里只是给常驻场景兜底。 */
    private val releaseTask = Runnable { releaseEngine() }

    override fun onCreate() {
        super.onCreate()
        ModelStore.warmUp(applicationContext)
    }

    override fun onDestroy() {
        runCatching { idleHandler.removeCallbacksAndMessages(null) }
        runCatching { idleThread.quitSafely() }
        releaseEngine()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 语言能力

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    /**
     * 对外暴露两个「声音」。
     *
     * 现代 Android 的 TextToSpeech.setLanguage 是走 Voice 匹配的，不给声音列表时
     * 部分 ROM 会直接判定语言不可用；美式放第一个，让默认声音就是美音。
     */
    override fun onGetVoices(): List<Voice> = listOf(
        voice("en-US", Locale.US),
        voice("en-GB", Locale.UK),
    )

    private fun voice(name: String, locale: Locale): Voice = Voice.Builder(name)
        .setLocale(locale)
        .setQuality(Voice.QUALITY_HIGH)
        .setLatency(Voice.LATENCY_NORMAL)
        .setFeatures(emptySet<String>())
        .build()

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val language = lang?.trim()?.lowercase().orEmpty()
        if (language.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        // 只要英语：eng / en / en-US 这些写法都放行
        if (!language.startsWith("en") && language != "eng") {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        return when (country?.trim()?.uppercase().orEmpty()) {
            "" -> TextToSpeech.LANG_AVAILABLE
            "USA", "GBR", "GB", "UK", "US" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int = onIsLanguageAvailable(lang, country, variant)

    override fun onStop(): Int {
        stopRequested = true
        return TextToSpeech.SUCCESS
    }

    // ---------------------------------------------------------------- 合成

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback): Int {
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return TextToSpeech.SUCCESS

        stopRequested = false
        val accent = Accent.fromCountry(request.country)
        val speed = request.speechRate.takeIf { it.isFinite() && it >= 0.1f } ?: 1.0f

        try {
            ModelStore.ensure(applicationContext)

            // 取引擎和生成必须在同一把锁里：中间的获释任务不能把模型抽走
            val audio = synchronized(engineLock) {
                val tts = obtainLocked(accent)
                tts.generate(text, sid = 0, speed = speed)
            }

            val samples = audio.samples
            if (samples.isEmpty()) {
                runCatching { callback.error() }
                return TextToSpeech.ERROR
            }

            val sampleRate = audio.sampleRate
            val started = callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (started != TextToSpeech.SUCCESS) {
                // 上层已经不要这段音频了（比如用户又点了一次）
                return TextToSpeech.ERROR
            }

            writeChunks(samples, callback)
            if (!stopRequested) callback.done()
            scheduleRelease()
            return TextToSpeech.SUCCESS
        } catch (error: Throwable) {
            runCatching { callback.error() }
            return TextToSpeech.ERROR
        }
    }

    /** 把 [-1,1] 的浮点采样转成 16bit 小端 PCM，按回调允许的最大块长分批送。 */
    private fun writeChunks(samples: FloatArray, callback: SynthesisCallback) {
        val maxBytes = (callback.maxBufferSize.takeIf { it >= 2048 } ?: 4096) / 2 * 2
        val samplesPerChunk = maxBytes / 2
        var index = 0
        while (index < samples.size) {
            if (stopRequested) return
            val count = minOf(samples.size - index, samplesPerChunk)
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val sample = samples[index + i].coerceIn(-1.0f, 1.0f)
                val value = (sample * 32767.0f).toInt()
                bytes[2 * i] = (value and 0xFF).toByte()
                bytes[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
            }
            callback.audioAvailable(bytes, 0, bytes.size)
            index += count
        }
    }

    // ---------------------------------------------------------------- 引擎生命周期

    private fun obtainLocked(accent: Accent): OfflineTts {
        val current = engine
        if (current != null && engineAccent == accent) return current

        releaseLocked()

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = ModelStore.modelPath(this, accent),
                    tokens = ModelStore.tokensPath(this),
                    dataDir = ModelStore.espeakDir(this),
                ),
                numThreads = Threads.count(),
            ),
        )
        return OfflineTts(config = config).also {
            engine = it
            engineAccent = accent
        }
    }

    private fun scheduleRelease() {
        runCatching {
            idleHandler.removeCallbacks(releaseTask)
            idleHandler.postDelayed(releaseTask, IDLE_RELEASE_MS)
        }
    }

    private fun releaseEngine() {
        synchronized(engineLock) { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { engine?.free() }
        engine = null
        engineAccent = null
    }

    private object Threads {
        /** 2～4 线程在手机上性价比最好，再多会跟 UI 抢核。 */
        fun count(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }

    private companion object {
        const val IDLE_RELEASE_MS = 90_000L
    }
}