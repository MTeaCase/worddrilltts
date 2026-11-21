package com.worddrill.tts;

import android.speech.tts.Voice;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对外暴露的两个「声音」：美式 / 英式。
 *
 * 框架的 setLanguage 会先问 onIsLanguageAvailable，再拿 onGetDefaultVoiceNameFor
 * 找到这里的声音名去 loadVoice，最后还要用 getVoices() 把 Voice 对象查回来；
 * 查不回来就判定语言不可用。所以这个列表必须有真对象，不能返回空。
 *
 * 坑：compileSdk 36 的 android.jar 里 {@code Voice.Builder} 整个没了（javac、Kotlin
 * 都找不到），API 36 改成公开构造器 public Voice(name, locale, quality, latency,
 * requiresNetwork, features)，老系统上则只有 Builder。两边都要兼容，所以用反射：
 * 先试 Builder（Android 5.0～15 的公开 API），再试构造器（Android 16+）。
 */
public final class Voices {

    public static final String US = "en-US";
    public static final String UK = "en-GB";

    private static final int QUALITY_HIGH = 400;
    private static final int LATENCY_NORMAL = 300;

    private Voices() {
    }

    public static List<Voice> all() {
        List<Voice> voices = new ArrayList<>(2);
        Voice us = build(US, Locale.US);
        Voice uk = build(UK, Locale.UK);
        if (us != null) {
            voices.add(us);
        }
        if (uk != null) {
            voices.add(uk);
        }
        return voices;
    }

    private static Voice build(String name, Locale locale) {
        Voice voice = viaBuilder(name, locale);
        if (voice == null) {
            voice = viaConstructor(name, locale);
        }
        return voice;
    }

    /** Android 5.0 ~ 15：Voice.Builder 是公开 API。 */
    private static Voice viaBuilder(String name, Locale locale) {
        try {
            Class<?> builderClass = Class.forName("android.speech.tts.Voice$Builder");
            Object builder = builderClass.getConstructor(String.class).newInstance(name);
            builderClass.getMethod("setLocale", Locale.class).invoke(builder, locale);
            builderClass.getMethod("setQuality", int.class).invoke(builder, QUALITY_HIGH);
            builderClass.getMethod("setLatency", int.class).invoke(builder, LATENCY_NORMAL);
            builderClass.getMethod("setFeatures", Set.class).invoke(builder, new HashSet<String>());
            Object voice = builderClass.getMethod("build").invoke(builder);
            return voice instanceof Voice ? (Voice) voice : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Android 16 起 Builder 被移除，改用公开构造器。 */
    private static Voice viaConstructor(String name, Locale locale) {
        try {
            Constructor<Voice> constructor = Voice.class.getConstructor(
                    String.class, Locale.class, int.class, int.class, boolean.class, Set.class);
            return constructor.newInstance(name, locale, QUALITY_HIGH, LATENCY_NORMAL, false,
                    new HashSet<String>());
        } catch (Throwable ignored) {
            return null;
        }
    }
}