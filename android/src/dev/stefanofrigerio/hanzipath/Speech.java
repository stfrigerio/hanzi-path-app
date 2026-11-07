package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Android's own Chinese voice, a little slow; started the first time something's said, or
 * when the app opens with Say it aloud on (check), so it's known early whether there is one.
 * A phone without a Chinese voice (the engine fails to start, or has no data for Chinese or
 * doesn't support it) is remembered, here and in the app's preferences for the next start:
 * Settings says so with the way to Android's text-to-speech settings, and listen questions
 * aren't drawn (voice() false). Coming back to the app asks the engine again (recheck).
 */
final class Speech {
    static TextToSpeech tts;
    static boolean ready;
    static String waiting;
    /** null: not known yet; else whether the phone has a Chinese voice. */
    static Boolean voice;
    /** Run (on the main thread) when it turns out whether there's a voice: Settings' line. */
    static Runnable changed;

    private Speech() {}

    /** Whether to count on a voice: yes until the phone said there's none (now, or last time). */
    static boolean voice() { return voice == null || voice; }

    static void say(Context ctx, String text) {
        if (tts == null) {
            waiting = text;
            start(ctx);
            return;
        }
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
        else waiting = text;
    }

    /** The engine started without saying anything: to learn whether there's a voice. */
    static void check(Context ctx) {
        if (voice == null) voice = remembered(ctx);
        if (tts == null && !asked) start(ctx);
    }

    static boolean asked;   // an engine has answered in this run: check needn't start another

    /** Back in the app (maybe from installing a voice): without one, the engine asked again. */
    static void recheck(Context ctx) {
        if (voice != null && !voice) {
            close();
            asked = false;
            start(ctx);
        }
    }

    static int engine;    // which start this is: an engine closed since doesn't get a say

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (voice == null) voice = remembered(app);
        final int mine = ++engine;
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        tts = new TextToSpeech(app, st -> main.post(() -> {   // after the constructor has returned
            if (mine != engine || tts == null) return;
            int lang = TextToSpeech.LANG_NOT_SUPPORTED;
            if (st == TextToSpeech.SUCCESS) {
                lang = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setSpeechRate(0.85f);
            }
            heard(app, st, lang);
        }));
    }

    /** What the engine said when it started (st) and to Chinese (lang): ready to speak, or
     *  remembered as no voice. Also how a test plays a phone without one. */
    static void heard(Context ctx, int st, int lang) {
        boolean ok = st == TextToSpeech.SUCCESS && lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED;
        asked = true;
        ready = ok && tts != null;
        if (ready && waiting != null) tts.speak(waiting, TextToSpeech.QUEUE_FLUSH, null, "say");
        waiting = null;
        Boolean was = voice;
        voice = ok;
        ctx.getSharedPreferences("hanzi", Context.MODE_PRIVATE).edit().putBoolean("voice", ok).apply();
        if (!Boolean.valueOf(ok).equals(was) && changed != null) changed.run();
    }

    static boolean remembered(Context ctx) {
        return ctx.getSharedPreferences("hanzi", Context.MODE_PRIVATE).getBoolean("voice", true);
    }

    static void close() {
        if (tts != null) tts.shutdown();
        tts = null;
        engine++;
        ready = false;
    }
}
