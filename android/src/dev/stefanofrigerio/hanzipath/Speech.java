package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Android's own Chinese voice, a little slow; started the first time something's said. */
final class Speech {
    static TextToSpeech tts;
    static boolean ready;
    static String waiting;

    private Speech() {}

    static void say(Context ctx, String text) {
        if (tts == null) {
            waiting = text;
            tts = new TextToSpeech(ctx.getApplicationContext(), st -> {
                ready = st == TextToSpeech.SUCCESS;
                if (!ready) return;
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setSpeechRate(0.85f);
                if (waiting != null) tts.speak(waiting, TextToSpeech.QUEUE_FLUSH, null, "say");
                waiting = null;
            });
            return;
        }
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
        else waiting = text;
    }

    static void close() {
        if (tts != null) tts.shutdown();
        tts = null;
        ready = false;
    }
}
