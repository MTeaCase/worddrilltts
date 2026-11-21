package com.worddrill.tts;

import android.speech.tts.Voice;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 对外暴露的两个「声音」：美式 / 英式。
 *
 * 单独放在 Java 里是因为 {@code Voice.Builder} 在 Kotlin 里解析不到（SDK 36 的
 * android.jar 里这个嵌套类对 Kotlin 不可见），Java 里 new 一下没问题。
 * 语言能力最终以这份列表为准：框架的 setLanguage 会先问
 * onIsLanguageAvailable，再拿 onGetDefaultVoiceNameFor 找到这里的声音名去 loadVoice。
 */
public final class Voices {

    public static final String US = "en-US";
    public static final String UK = "en-GB";

    private Voices() {
    }

    public static List<Voice> all() {
        return Arrays.asList(build(US, Locale.US), build(UK, Locale.UK));
    }

    private static Voice build(String name, Locale locale) {
        return new Voice.Builder(name)
                .setLocale(locale)
                .setQuality(Voice.QUALITY_HIGH)
                .setLatency(Voice.LATENCY_NORMAL)
                .setFeatures(new HashSet<String>())
                .build();
    }
}