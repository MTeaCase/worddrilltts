plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * WordDrill 离线语音引擎。
 *
 * 它是一个标准的 Android TTS 引擎（实现 TextToSpeechService），主 App 通过
 * `TextToSpeech(context, listener, "com.worddrill.tts")` 直接用，不需要 JNI、
 * 不需要共享文件，也不需要用户去系统设置里改默认引擎。
 *
 * 模型和原生库都打进包里，装完即可离线朗读；运行时不联网。
 */
android {
    namespace = "com.worddrill.tts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.worddrill.tts"
        minSdk = 24
        targetSdk = 36

        // 版本号要跟 Gitee 清单 tts-engine.json 里的一致，主 App 靠它判断「有没有新版」
        versionCode = 1
        versionName = "1.0.0"

        // 只带 arm64：32 位设备由主 App 直接禁用入口，避免装个用不了的包
        ndk { abiFilters += "arm64-v8a" }
    }

    /**
     * 和主 App 用同一把固定 debug 密钥。
     *
     * CI 每次都是全新 runner，AGP 会临时生成新密钥，那样每次打出来的包签名都不同，
     * 手机上无法覆盖安装（会提示「应用未安装」/签名冲突）。这把 p12 是仓库里的，
     * 保证 v1.0.0 之后每个版本都能直接升级安装。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/worddrill-debug.p12")
            storePassword = "android"
            keyAlias = "worddrill"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 语音包只以 APK 形式分发，release 也用同一把密钥，省得两个包不能互相覆盖
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /**
     * 原生库压缩进 APK（安装时由系统解压）。
     *
     * 30MB 的 .so 不压缩会让包从 47MB 涨到 66MB，下载体验差很多；
     * 代价是安装时多解压一次（一次性，几十 MB，很快）。
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 只有原生 TTS 逻辑，没有界面，不需要任何 androidx 依赖
}