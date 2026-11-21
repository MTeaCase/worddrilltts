package com.worddrill.tts

import android.media.AudioFormat
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
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
 * - 语速是百分比（100 = 正常），口音由 setLanguage 选中的 Voice 决定；
 * - 失败一律 `callback.error()`，让上层能判断这次朗读没成。
 */
class TtsEngineService : TextToSpeechService() {

    private val engineLock = Any()
    private var engine: OfflineTts? = null
    private var engineAccent: Accent? = null

    /** 当前被 loadVoice / loadLanguage 选中的口音，合成时优先用它。 */
    @Volatile
    private var currentAccent: Accent = Accent.US

    /** onStop 之后正在写的分块要尽快停下，别把过时的句子念完。 */
    @Volatile
    private var stopRequested = false

    private val idleThread by lazy {
        HandlerThread("tts-idle").apply { start() }
    }

    private val idleHandler by lazy { Handler(idleThread.looper) }

    /** 90 秒没人用就释放模型；被系统杀掉进程更省事，这里只是给常驻场景兜底。 */
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

    // ---------------------------------------------------------------- 语言与声音

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onGetVoices(): List<Voice> = Voices.all()

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (onIsLanguageAvailable(lang, country, variant) < TextToSpeech.LANG_AVAILABLE) return null
        return if (Accent.fromCountry(country) == Accent.UK) Voices.UK else Voices.US
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == Voices.US || voiceName == Voices.UK) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int = when (voiceName) {
        Voices.US -> {
            currentAccent = Accent.US
            TextToSpeech.SUCCESS
        }
        Voices.UK -> {
            currentAccent = Accent.UK
            TextToSpeech.SUCCESS
        }
        else -> TextToSpeech.ERROR
    }

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
    ): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            currentAccent = Accent.fromCountry(country)
        }
        return result
    }

    override fun onStop() {
        stopRequested = true
    }

    // ---------------------------------------------------------------- 合成

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        stopRequested = false
        val accent = if (request.country.isNullOrBlank()) {
            currentAccent
        } else {
            Accent.fromCountry(request.country)
        }

        // 框架传的是百分比：100 = 正常语速
        val rawRate = request.speechRate
        val speed = if (rawRate in 10..400) rawRate / 100f else 1.0f

        try {
            ModelStore.ensure(applicationContext)

            // 取引擎和生成必须在同一把锁里：中间的释放任务不能把模型抽走
            val audio = synchronized(engineLock) {
                val tts = obtainLocked(accent)
                tts.generate(text, sid = 0, speed = speed)
            }

            val samples = audio.samples
            if (samples.isEmpty()) {
                runCatching { callback.error() }
                return
            }

            val started = callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (started != TextToSpeech.SUCCESS) {
                // 上层已经不要这段音频了（比如用户又点了一次）
                return
            }

            writeChunks(samples, callback)
            if (!stopRequested) callback.done()
            scheduleRelease()
        } catch (error: Throwable) {
            runCatching { callback.error() }
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
                numThreads = threads(),
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

    /** 2～4 线程在手机上性价比最好，再多会跟 UI 抢核。 */
    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private companion object {
        const val IDLE_RELEASE_MS = 90_000L
    }
}