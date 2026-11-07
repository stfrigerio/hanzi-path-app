package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Android's own Chinese voice, at Settings' "Voice speed" (normal a little slow, slower
 *  slower still); started the first time something's said. */
final class Speech {
    static final float NORMAL = 0.85f, SLOWER = 0.6f;
    static TextToSpeech tts;
    static boolean ready;
    static String waiting;
    static float rate = NORMAL;   // the rate the voice was last given

    private Speech() {}

    /** The rate for a speed as Settings keeps it ("slower", "normal"). */
    static float rateOf(String speed) { return "slower".equals(speed) ? SLOWER : NORMAL; }

    /** Said at rate r: a character, a word, a sentence alike. */
    static void say(Context ctx, String text, float r) {
        rate = r;
        if (tts == null) {
            waiting = text;
            tts = new TextToSpeech(ctx.getApplicationContext(), st -> {
                ready = st == TextToSpeech.SUCCESS;
                if (!ready) return;
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setSpeechRate(rate);
                if (waiting != null) tts.speak(waiting, TextToSpeech.QUEUE_FLUSH, null, "say");
                waiting = null;
            });
            return;
        }
        if (ready) {
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
        } else waiting = text;
    }

    static void close() {
        if (tts != null) tts.shutdown();
        tts = null;
        ready = false;
    }
}
