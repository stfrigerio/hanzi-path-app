package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Real sentences to read (assets/sentences.json, made by tools/build.py from Tatoeba): every
 * character in them on the path, a few for each place on the path from where they become
 * readable, in that order. Read the first time they're wanted, not with the path: Main starts
 * reading them on a thread of its own as the app opens, and whoever wants them first waits
 * for it (load is synchronized), so home's Read line doesn't parse them on the main thread.
 *
 * A card (Learn's, a looked-up one, a question's answer) shows one it's in that you can read
 * now: every character met, or the card's own. The Read round takes the ones you can read
 * from what you know (Read).
 */
final class Sentences {
    /** One sentence: as written, its pinyin a syllable per character (punctuation stuck to
     *  them), the English, the path index it's readable from, its characters in order. */
    static final class S {
        String zh, en;
        String[] py, han;
        int at;
    }

    static final List<S> all = new ArrayList<>();
    static boolean loaded;

    private Sentences() {}

    static synchronized void load(Context ctx) {
        if (loaded) return;
        List<S> read = new ArrayList<>(5000);
        try (android.util.JsonReader r = PathData.reader(ctx, "sentences.json")) {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("s")) { r.skipValue(); continue; }
                r.beginArray();
                while (r.hasNext()) {
                    r.beginArray();
                    S s = new S();
                    s.zh = r.nextString();
                    s.py = r.nextString().split(" ");
                    s.en = r.nextString();
                    s.at = r.nextInt();
                    while (r.hasNext()) r.skipValue();
                    r.endArray();
                    s.han = han(s.zh);
                    if (s.han.length == s.py.length) read.add(s);
                }
                r.endArray();
            }
            r.endObject();
            all.addAll(read);
            loaded = true;
        } catch (Exception e) {
            all.clear();   // no sentences: the cards show none, there's no Read round (read again when next wanted)
        }
    }

    /** The characters of a sentence, its punctuation left out. */
    static String[] han(String zh) {
        List<String> out = new ArrayList<>();
        for (String c : Words.chars(zh)) if (isHan(c)) out.add(c);
        return out.toArray(new String[0]);
    }

    static boolean isHan(String c) { return Character.UnicodeScript.of(c.codePointAt(0)) == Character.UnicodeScript.HAN; }

    /** A sentence with h in it (a character, or a word) that you can read: every character
     *  met, or h's own. The one that came within reach last, so a card shows what's new to it. */
    static S forCard(Main m, String h) {
        load(m);
        Set<String> own = new HashSet<>(java.util.Arrays.asList(Words.chars(h)));
        S best = null;
        for (S s : all) {
            if ((best != null && s.at <= best.at) || !s.zh.contains(h)) continue;
            boolean ok = true;
            for (String c : s.han) if (!own.contains(c) && !m.prog.cards.containsKey(c)) { ok = false; break; }
            if (ok) best = s;
        }
        return best;
    }

    /** What's known now (Progress.KNOWN): the characters a Read round's sentences are made of. */
    static Set<String> known(Progress p, long now) {
        Set<String> out = new HashSet<>();
        for (String h : p.cards.keySet()) if (PathData.get(h) != null && p.strength(h, now) >= Progress.KNOWN) out.add(h);
        return out;
    }

    /** The sentences made only of what's known, in the path's order. */
    static List<S> readable(Context ctx, Set<String> known) {
        List<S> out = new ArrayList<>();
        if (known.size() < 2) return out;
        load(ctx);
        for (S s : all) {
            boolean ok = true;
            for (String c : s.han) if (!known.contains(c)) { ok = false; break; }
            if (ok) out.add(s);
        }
        return out;
    }

    /** The sentence in the hanzi face, h in it (a character, or a word) on the highlighter. */
    static SpannableStringBuilder marked(S s, String h) {
        SpannableStringBuilder b = new SpannableStringBuilder(s.zh);
        if (h == null || h.isEmpty()) return b;
        for (int at = s.zh.indexOf(h); at >= 0; at = s.zh.indexOf(h, at + h.length())) {
            b.setSpan(new BackgroundColorSpan(Ink.hl), at, at + h.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Ink.hlInk), at, at + h.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    /** The sentence with each of hs (characters, or words) on the highlighter: what's new in a Read round. */
    static SpannableStringBuilder marked(S s, java.util.Collection<String> hs) {
        SpannableStringBuilder b = new SpannableStringBuilder(s.zh);
        if (hs == null) return b;
        boolean[] on = new boolean[s.zh.length()];
        for (String h : hs)
            if (!h.isEmpty()) for (int at = s.zh.indexOf(h); at >= 0; at = s.zh.indexOf(h, at + h.length()))
                for (int k = at; k < at + h.length(); k++) on[k] = true;
        for (int i = 0; i < on.length; ) {   // a run marked once, so a word and its character don't stack
            if (!on[i]) { i++; continue; }
            int j = i;
            while (j < on.length && on[j]) j++;
            b.setSpan(new BackgroundColorSpan(Ink.hl), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Ink.hlInk), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = j;
        }
        return b;
    }

    /** Which characters of the sentence are h's (a character, or a word): their syllables. */
    static boolean[] marks(S s, String h) {
        boolean[] out = new boolean[s.han.length];
        if (h == null || h.isEmpty()) return out;
        String[] w = Words.chars(h);
        for (int i = 0; i + w.length <= s.han.length; i++) {
            boolean ok = true;
            for (int j = 0; j < w.length && ok; j++) ok = s.han[i + j].equals(w[j]);
            if (ok) for (int j = 0; j < w.length; j++) out[i + j] = true;
        }
        return out;
    }

    /** The pinyin in the tones' inks (Ui.pinyin), h's syllables in the medium face. */
    static SpannableStringBuilder pinyin(S s, String h) {
        SpannableStringBuilder b = Ui.pinyin(android.text.TextUtils.join(" ", s.py));
        boolean[] mk = marks(s, h);
        int at = 0;
        for (int i = 0; i < s.py.length; i++) {
            if (mk[i]) b.setSpan(new TypefaceSpan(Ink.monoMedium), at, at + s.py[i].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at += s.py[i].length() + 1;
        }
        return b;
    }

    /** A card's sentence, under its words: the sentence with h marked, its pinyin small,
     *  the English muted, the speaker saying all of it. Nothing while none is readable. */
    static View view(Main m, String h) {
        S s = forCard(m, h);
        if (s == null) return null;
        LinearLayout c = Ui.column(m, 8);
        c.addView(Ui.label(m, "In a sentence"));
        LinearLayout r = Ui.row(m, 12);
        r.setGravity(Gravity.TOP);
        LinearLayout t = Ui.column(m, 2);
        TextView zh = Ui.text(m, marked(s, h), 22, Ink.han, Ink.ink);
        zh.setLineSpacing(0, 1.1f);
        Taps.link(m, zh, h);   // every character opens its card, but the card's own
        t.addView(zh);
        t.addView(Ui.text(m, pinyin(s, h), 14, Ink.mono, Ink.ink));
        TextView en = Ui.text(m, s.en, 14, Ink.sans, Ink.ink2);
        en.setPadding(0, Ink.dp(2), 0, 0);
        t.addView(en);
        r.addView(t, Ui.weight(1));
        View say = Ui.speaker(m, v -> m.say(s.zh));
        say.setContentDescription("Say the sentence");
        r.addView(say);
        c.addView(r);
        return c;
    }
}
