package com.worddrill.tts

import android.content.Context
import java.io.File

/** 朗读口音：决定用哪个 VITS 模型。 */
enum class Accent {
    US,
    UK;

    companion object {
        /** 系统传进来的 country 是 ISO-3166 alpha-3（USA / GBR），空值按美式处理。 */
        fun fromCountry(country: String?): Accent {
            val value = country?.trim()?.uppercase().orEmpty()
            return if (value == "GBR" || value == "GB" || value == "UK") UK else US
        }
    }
}

/**
 * 模型仓库：把 APK 里的 `assets/tts/` 解包到应用私有目录。
 *
 * 为什么必须落盘：sherpa-onnx 的 espeak-ng 音素数据只认文件系统路径，
 * AssetManager 里的压缩资源喂不进去；模型文件同理。
 *
 * 解包是幂等的：`.ready` 里记着版本号，和 [VERSION] 一致就直接跳过，
 * 模型换了版本（或者上次装到一半被杀）会整个目录重来一遍。
 */
object ModelStore {

    /** assets 结构的版本号。模型换了、目录结构变了都要 +1。 */
    const val VERSION = 1

    private const val ASSET_ROOT = "tts"
    private const val MARKER = ".ready"

    @Volatile
    private var ready = false

    private val lock = Any()

    fun root(context: Context): File = File(context.filesDir, "tts-models")

    fun modelPath(context: Context, accent: Accent): String =
        File(root(context), if (accent == Accent.UK) "uk.onnx" else "us.onnx").absolutePath

    fun tokensPath(context: Context): String = File(root(context), "tokens.txt").absolutePath

    fun espeakDir(context: Context): String = File(root(context), "espeak-ng-data").absolutePath

    /**
     * 后台预热。
     *
     * 服务被系统绑定时就会调用，等用户真的点朗读时，36MB 的解包基本已经做完了。
     * 失败不在这里抛：真正的合成路径还会再调一次 [ensure] 并拿到真实错误。
     */
    fun warmUp(context: Context) {
        if (ready || isPrepared(context)) {
            ready = true
            return
        }
        Thread({ runCatching { ensure(context) } }, "tts-extract").apply { isDaemon = true }.start()
    }

    /** 确保 assets 已完整落盘；可重复调用，多线程安全。 */
    fun ensure(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return

            val target = root(context)
            if (isPrepared(context)) {
                ready = true
                return
            }

            // 版本不一致或上次没装完：整目录重来，避免混着新旧两套文件
            if (target.exists() && !target.deleteRecursively()) {
                throw IllegalStateException("旧模型目录删不掉：${target.absolutePath}")
            }
            if (!target.mkdirs() && !target.isDirectory) {
                throw IllegalStateException("模型目录建不出来：${target.absolutePath}")
            }

            copyAssetDir(context, ASSET_ROOT, target)

            val marker = File(target, MARKER)
            marker.writeText(VERSION.toString())
            ready = true
        }
    }

    private fun isPrepared(context: Context): Boolean {
        val marker = File(root(context), MARKER)
        if (!marker.isFile) return false
        val version = runCatching { marker.readText().trim() }.getOrDefault("")
        if (version != VERSION.toString()) return false
        return File(root(context), "us.onnx").isFile &&
            File(root(context), "uk.onnx").isFile &&
            File(root(context), "tokens.txt").isFile &&
            File(root(context), "espeak-ng-data").isDirectory
    }

    /**
     * 递归复制 assets 目录。
     *
     * 单个文件先写 `.part` 再改名，进程被杀也不会留下半个模型文件骗过校验。
     */
    private fun copyAssetDir(context: Context, assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath).orEmpty().sorted()
        if (children.isEmpty()) return
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("目录建不出来：${targetDir.absolutePath}")
        }
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childTarget = File(targetDir, child)
            val grandChildren = context.assets.list(childAsset).orEmpty()
            if (grandChildren.isEmpty()) {
                copyAssetFile(context, childAsset, childTarget)
            } else {
                copyAssetDir(context, childAsset, childTarget)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, target: File) {
        val part = File(target.parentFile, "${target.name}.part")
        runCatching { if (part.exists()) part.delete() }
        context.assets.open(assetPath).use { input ->
            part.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE * 4)
            }
        }
        if (target.exists() && !target.delete()) throw IllegalStateException("旧文件删不掉：${target.name}")
        if (!part.renameTo(target)) throw IllegalStateException("文件改名失败：${target.name}")
    }

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024
}