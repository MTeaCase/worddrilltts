# WordDrill 离线语音

[WordDrill](https://github.com/SpeakerC/worddrill) 的可选「本地语音包」。装上它之后，主 App 用
sherpa-onnx + piper 模型在本机合成英文发音，不再依赖系统 TTS —— 适合系统里没装英语语音数据、
或者「文字转语音」被 ROM 精简掉的机器。

它是标准的 Android TTS 引擎：主 App 通过 `TextToSpeech(context, listener, "com.worddrill.tts")`
指名调用，不需要 JNI、不需要跨应用共享文件、也不用改系统默认引擎。

## 特性

- 纯离线，运行时不联网，不申请任何权限
- 两种口音各一个模型：美式 `en_US-lessac-medium`（int8），英式 `en_GB-alan-medium`（int8）
- 语速直接跟随 `speak(..., rate)`，口音跟随 `setLanguage(Locale.US / Locale.UK)`
- 模型放 APK assets，首次合成时解包到私有目录（约 36MB，一次性）
- 引擎空闲 90 秒释放内存，不常驻吃 RAM

## 体积

| 项 | 大小 |
|---|---|
| 原生库（arm64-v8a，4 个 .so） | 30.4MB |
| 模型 + 裁剪版 espeak 数据 | 36.4MB |
| 产物 APK | ≈47MB |

只带 arm64-v8a：32 位设备由主 App 直接禁用入口，避免装了用不了。

## 构建

```bash
./gradlew :app:assembleDebug
```

- JDK 21、Android SDK platform 36
- `app/src/main/jniLibs/arm64-v8a/` 里是 sherpa-onnx 1.13.8 官方预编译库
- `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` 是官方 Apache-2.0 源码，原样 vendor，别手改
- 签名用仓库里的 `keystore/worddrill-debug.p12`（和主 App 同一把），这样每个新版本都能直接覆盖安装

## 发版检查表

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. CI 绿了以后取 `app-debug.apk`，改名 `WordDrill-tts-engine-<版本>.apk`
3. 传到 Gitee / GitHub 的 release（同一个 tag）
4. 更新清单 `tts-engine.json`（主 App 读它判断有没有新版）：

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "url": "https://gitee.com/MTeaCase/worddrilltts/releases/download/v1.0.0/WordDrill-tts-engine-1.0.0.apk",
  "size": 49000000,
  "md5": "...",
  "content": "1. 首个版本"
}
```

## 许可

本项目沿用主仓库的 GPL-3.0。内置的第三方组件（sherpa-onnx、onnxruntime、espeak-ng、piper 语音模型）
许可见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。