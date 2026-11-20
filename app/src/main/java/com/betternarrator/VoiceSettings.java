package com.betternarrator;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;

public final class VoiceSettings {
    private VoiceSettings() { }
    public static final String[] ENGINES = {"Kitten Nano · compact offline voice", "Kokoro · offline voice", "Android · installed offline voice"};
    public static final String[] ENGINE_IDS = {"kitten", "kokoro", "system"};
    private static final String[] KOKORO = {"Heart · American female", "Bella · American female", "Michael · American male", "Fenrir · American male"};
    private static final String[] KITTEN = {"Voice 2 · male", "Voice 2 · female", "Voice 3 · male", "Voice 3 · female",
            "Voice 4 · male", "Voice 4 · female", "Voice 5 · male", "Voice 5 · female"};
    private static final int[] KOKORO_IDS = {3, 2, 16, 14};
    public static String engine(SharedPreferences prefs) {
        String value = prefs.getString("voice_engine", "kitten");
        return Arrays.asList(ENGINE_IDS).contains(value) ? value : "kitten";
    }
    public static int engineIndex(SharedPreferences prefs) { return Arrays.asList(ENGINE_IDS).indexOf(engine(prefs)); }
    public static boolean isNeural(SharedPreferences prefs) { return !engine(prefs).equals("system"); }
    public static String[] voices(SharedPreferences prefs) { return engine(prefs).equals("kokoro") ? KOKORO : KITTEN; }
    public static String voiceKey(SharedPreferences prefs) { return engine(prefs).equals("kokoro") ? "kokoro_voice" : "kitten_voice"; }
    public static int voiceIndex(SharedPreferences prefs) {
        return Math.max(0, Math.min(voices(prefs).length - 1, prefs.getInt(voiceKey(prefs), engine(prefs).equals("kokoro") ? 0 : 1)));
    }
    public static int speaker(SharedPreferences prefs) {
        int index = voiceIndex(prefs);
        return engine(prefs).equals("kokoro") ? KOKORO_IDS[index] : index;
    }
    public static SpeechEngine create(Context context, SpeechEngine.Listener listener) {
        String kind = engine(context.getSharedPreferences("reader", Context.MODE_PRIVATE));
        return kind.equals("system") ? new SystemSpeechEngine(context, listener) : new OfflineSpeechEngine(context, kind, listener);
    }
}
