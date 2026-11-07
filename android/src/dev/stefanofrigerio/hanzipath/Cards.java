package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pieces of an item's card, shared by Learn (all of them) and Quiz (the answer). */
final class Cards {
    private Cards() {}

    static View tag(Main m, Item it) {
        LinearLayout r = Ui.row(m, 8);
        TextView kind = Ui.text(m, it.isPart() ? "PART" : "CHARACTER", 11, Ink.mono, Ink.ink2);
        kind.setLetterSpacing(0.1f);
        kind.setPadding(Ink.dp(10), Ink.dp(2), Ink.dp(10), Ink.dp(2));
        kind.setBackground(Ui.shape(0, Ink.ink2, 999, it.isPart()));
        r.addView(kind);
        r.addView(Ui.spacer(m));
        TextView pos = Ui.text(m, (it.strokes + (it.strokes == 1 ? " stroke" : " strokes") + " · no. " + (it.at + 1) + " of " + PathData.items.size()).toUpperCase(),
                11, Ink.mono, Ink.ink2);
        pos.setLetterSpacing(0.08f);
        r.addView(pos);
        return r;
    }

    static HanziView box(Main m, Item it) {
        HanziView hv = new HanziView(m);
        int w = m.getResources().getDisplayMetrics().widthPixels;
        int s = Math.min(Ink.dp(240), (int) (w * 0.64f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        hv.setLayoutParams(lp);
        hv.setGlyph(PathData.glyph(m, it.h));
        hv.setContentDescription("Stroke order of " + it.h);
        return hv;
    }

    static View reading(Main m, Item it) {
        LinearLayout c = Ui.column(m, 2);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout r = Ui.row(m, 10);
        r.setGravity(Gravity.CENTER);
        TextView py = Ui.text(m, it.py.length > 0 ? Ui.pinyin(it.py[0]) : "—", 26, Ink.monoMedium, Ink.ink);
        r.addView(py);
        if (it.py.length > 0) r.addView(Ui.speaker(m, v -> m.say(it.h)));
        if (it.py.length > 1) r.addView(Ui.text(m, "also " + it.py[1], 15, Ink.mono, Ink.ink2));
        c.addView(r);
        TextView en = Ui.text(m, it.en, 19, Ink.sans, Ink.ink);
        en.setGravity(Gravity.CENTER);
        c.addView(en);
        return c;
    }

    static View chip(Main m, String h) {
        Item p = PathData.get(h);
        LinearLayout r = Ui.row(m, 8);
        r.setPadding(Ink.dp(6), Ink.dp(4), Ink.dp(12), Ink.dp(4));
        Ui.touch(r, Ink.paper, m.prog.cards.containsKey(h) ? Ink.ink : Ink.ink2, 999, !m.prog.cards.containsKey(h));
        TextView han = Ui.text(m, h, 21, Ink.han, Ink.ink);
        han.setMinWidth(Ink.dp(28));
        han.setGravity(Gravity.CENTER);
        r.addView(han);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (p.py.length > 0) b.append(Ui.pinyin(p.py[0])).append(' ');
        int at = b.length();
        b.append(p.en);
        b.setSpan(new ForegroundColorSpan(Ink.ink2), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        r.addView(Ui.text(m, b, 14, Ink.sans, Ink.ink));
        r.setOnClickListener(v -> m.lookup(h));
        r.setContentDescription(h + " " + p.reading() + " " + p.en);
        return r;
    }

    static View chips(Main m, String title, String[] hs) {
        LinearLayout c = Ui.column(m, 8);
        c.addView(Ui.label(m, title));
        Flow f = new Flow(m, 8);
        for (String h : hs) if (PathData.get(h) != null) f.addView(chip(m, h));
        c.addView(f);
        return c;
    }

    static void parts(Main m, LinearLayout into, Item it) {
        if (it.parts.length > 0) into.addView(chips(m, "Made of", it.parts));
        if (it.used.length > 0) into.addView(chips(m, "Leads to", it.used));
    }

    /** Make Me a Hanzi's etymology, as tools/build.py words it: what the picture shows, which
     *  part gives the meaning, which the sound. */
    static View why(Main m, Item it) {
        if (it.why == null || it.why.isEmpty()) return null;
        LinearLayout c = Ui.column(m, 8);
        c.addView(Ui.label(m, "Why it looks like this"));
        c.addView(Ui.text(m, whyText(it.why), 15, Ink.sans, Ink.ink));
        return c;
    }

    /** A reading in brackets right after a character: " (zhǐ)", with a tone mark. */
    static final Pattern SOUND = Pattern.compile("^ \\(([a-zü]*[āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ][a-zü]*)\\)");

    /** The line with every character in the hanzi face; a sound part and its reading
     *  ("止 (zhǐ)") in its tone's ink, as pinyin is everywhere else. */
    static SpannableStringBuilder whyText(String why) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int i = 0;
        while (i < why.length()) {
            int cp = why.codePointAt(i), n = Character.charCount(cp);
            int at = b.length();
            b.append(why, i, i + n);
            i += n;
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) continue;
            b.setSpan(new TypefaceSpan(Ink.han), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Matcher s = SOUND.matcher(why.substring(i));
            if (!s.find()) continue;
            int tone = Ink.tone[Ink.toneOf(s.group(1))];
            b.setSpan(new ForegroundColorSpan(tone), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(" (");   // the reading stays on the character's line
            int py = b.length();
            b.append(s.group(1));
            b.setSpan(new ForegroundColorSpan(tone), py, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(")");
            i += s.end();
        }
        return b;
    }

    static View words(Main m, Item it) {
        if (it.words.length == 0) return null;
        LinearLayout c = Ui.column(m, 10);
        c.addView(Ui.label(m, "Words you can read now"));
        for (String[] w : it.words) {
            LinearLayout r = Ui.row(m, 12);
            r.setGravity(Gravity.TOP);
            r.addView(Ui.text(m, w[0], 22, Ink.han, Ink.ink));
            LinearLayout t = Ui.column(m, 0);
            SpannableStringBuilder b = Ui.pinyin(w[1]);
            if (!"0".equals(w[3])) {   // 0: a word from CC-CEDICT, beyond HSK: no level to show
                int at = b.length();
                b.append("  HSK ").append(w[3]);
                b.setSpan(new ForegroundColorSpan(Ink.ink2), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            t.addView(Ui.text(m, b, 14, Ink.mono, Ink.ink));
            t.addView(Ui.text(m, w[2], 13, Ink.sans, Ink.ink2));
            r.addView(t, Ui.weight(1));
            r.setOnClickListener(v -> m.say(w[0]));
            r.setContentDescription(w[0] + ", " + w[2] + ". Tap to hear it.");
            c.addView(r);
        }
        return c;
    }

    /** A word's tag: WORD, and its level on the right ("HSK 3", or nothing for CC-CEDICT's). */
    static View wordTag(Main m, String[] w) {
        LinearLayout r = Ui.row(m, 8);
        TextView kind = Ui.text(m, "WORD", 11, Ink.mono, Ink.ink2);
        kind.setLetterSpacing(0.1f);
        kind.setPadding(Ink.dp(10), Ink.dp(2), Ink.dp(10), Ink.dp(2));
        kind.setBackground(Ui.shape(0, Ink.ink2, 999, false));
        r.addView(kind);
        r.addView(Ui.spacer(m));
        String lv = Words.level(w);
        TextView pos = Ui.text(m, (Words.chars(w[0]).length + " characters" + (lv.isEmpty() ? "" : " · " + lv)).toUpperCase(), 11, Ink.mono, Ink.ink2);
        pos.setLetterSpacing(0.08f);
        r.addView(pos);
        return r;
    }

    /** A word, large, where a character has its square: four characters still on one line. */
    static TextView word(Main m, String h) {
        TextView t = Ui.text(m, h, Words.chars(h).length > 3 ? 56 : 64, Ink.han, Ink.ink);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setPadding(0, Ink.dp(12), 0, Ink.dp(4));
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        t.setContentDescription(h);
        return t;
    }

    /** A word's reading in the tones' inks with the speaker (says the word), its meaning under. */
    static View wordReading(Main m, String[] w) {
        LinearLayout c = Ui.column(m, 2);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout r = Ui.row(m, 10);
        r.setGravity(Gravity.CENTER);
        r.addView(Ui.text(m, Ui.pinyin(w[1]), 26, Ink.monoMedium, Ink.ink));
        r.addView(Ui.speaker(m, v -> m.say(w[0])));
        c.addView(r);
        TextView en = Ui.text(m, w[2], 19, Ink.sans, Ink.ink);
        en.setGravity(Gravity.CENTER);
        c.addView(en);
        return c;
    }

    /** The bar over a card: a count on the left; Undo and × on the right. */
    static LinearLayout bar(Main m, String count, View... right) {
        LinearLayout r = Ui.row(m, 8);
        TextView t = Ui.text(m, count, 13, Ink.mono, Ink.ink2);
        t.setLetterSpacing(0.06f);
        r.addView(t, Ui.weight(1));
        for (View v : right) if (v != null) r.addView(v);
        return r;
    }

    static void add(LinearLayout into, View v) { if (v != null) into.addView(v); }
}
