# 第三方组件与许可

本仓库分发的 APK 里打包了以下第三方组件。它们各自的许可如下，分发时请保留本文件。

## sherpa-onnx 1.13.8

- 用途：离线 TTS 推理运行时（`app/src/main/jniLibs/arm64-v8a/`、`com/k2fsa/sherpa/onnx/Tts.kt`）
- 许可：Apache License 2.0
- 来源：https://github.com/k2-fsa/sherpa-onnx

## onnxruntime

- 用途：ONNX 推理后端（`libonnxruntime.so`，随 sherpa-onnx 官方 Android 预编译包分发）
- 许可：MIT
- 来源：https://github.com/microsoft/onnxruntime

## espeak-ng（音素数据）

- 用途：piper 模型的文本转音素（`assets/tts/espeak-ng-data/`，已裁剪到英语相关文件）
- 许可：GPL-3.0
- 来源：https://github.com/espeak-ng/espeak-ng

## piper 语音模型

- `en_US-lessac-medium`（int8）
  - 训练数据：Lessac Blizzard 2013
  - 许可：https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html
  - **注意：该数据集许可仅允许研究/非商业用途。** 本项目免费分发、不含广告与内购，属于非商业使用；
    若将来要商用，需要换成许可更宽松的模型（例如基于 LJSpeech 公有领域数据训练的 `en_US-ljspeech`）。
- `en_GB-alan-medium`（int8）
  - 训练数据：Mycroft mimic3-voices `apope_low`，并在 lessac 模型上微调
  - 许可：见上方 lessac 条款（派生自 lessac）

## 备注

如需替换模型，把新模型的 `.onnx` 放进 `app/src/main/assets/tts/`（美式 `us.onnx`、英式 `uk.onnx`，
`tokens.txt` 共用），把 `ModelStore.VERSION` +1，并同步更新本文件。