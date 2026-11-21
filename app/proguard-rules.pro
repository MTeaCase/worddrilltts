# 引擎逻辑全在 Kotlin 和 JNI 里，release 也没开混淆；这个文件先留着占位，
# 以后真要开 minify 时，这里需要 keep com.k2fsa.sherpa.onnx 的 JNI 方法。
-keep class com.k2fsa.sherpa.onnx.** { *; }