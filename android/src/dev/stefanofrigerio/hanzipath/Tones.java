package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * Quiz's tone question: the character is said aloud and shown, and you pick its tone from
 * five pills, 1 ā, 2 á, 3 ǎ, 4 à and neutral, each in its tone's ink. Only in a review, only
 * while the voice is on, only for a character (not a part, not a word) whose main reading has
 * a tone and whose tone is worth asking: missed before (a lapse), or answered slowly last
 * time (unsure, 2). Then about one question in EVERY. Logged as "tone".
 */
final class Tones {
    static final int EVERY = 6;
    static final List<String> OPTIONS = Arrays.asList("1", "2", "3", "4", "5");
    static final String[] MARK = {"", "ā", "á", "ǎ", "à", "a"};
    static final String[] NAME = {"", "first tone", "second tone", "third tone", "fourth tone", "neutral tone"};

    private Tones() {}

    /** Whether h's tone may be asked: a character with a toned main reading, met, missed or slow. */
    static boolean fits(Progress p, String h) {
        if (!p.say || Words.is(h)) return false;
        Item it = PathData.get(h);
        if (it == null || it.isPart() || it.py.length == 0 || answer(h) == 5) return false;
        Progress.Card c = p.cards.get(h);
        return c != null && (c.lapses >= 1 || c.grade == 2);
    }

    /** h's tone, 1-4, 5 for neutral. */
    static int answer(String h) { return Ink.toneOf(PathData.get(h).py[0]); }

    static boolean right(String option, String h) { return option.equals(String.valueOf(answer(h))); }

    /** The question: the character large (tap: its strokes), the speaker under it to hear it again. */
    static void question(Quiz q, LinearLayout card, Item it) {
        Main m = q.m;
        q.hv = Cards.box(m, it);
        card.addView(q.hv);
        q.hv.show();
        q.hv.setOnClickListener(v -> q.hv.animateStrokes());
        if (q.picked == null) {
            LinearLayout r = Ui.row(m, 8);
            r.setGravity(Gravity.CENTER);
            r.addView(Ui.speaker(m, v -> m.say(it.h)));
            r.addView(Ui.text(m, "tap to hear it again", 13, Ink.serif, Ink.ink2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(r, lp);
        }
        card.addView(pills(q, it.h), new LinearLayout.LayoutParams(-1, -2));
    }

    /** The five, side by side in equal widths: the vowel with its mark large in the tone's ink,
     *  the tone's number (or "neutral") small under it. After a tap, as Quiz.mark: the right one
     *  ringed green, a wrong pick pink, the rest fade. */
    static View pills(Quiz q, String h) {
        Main m = q.m;
        LinearLayout row = Ui.row(m, 8);
        for (String o : OPTIONS) {
            int t = Integer.parseInt(o);
            SpannableStringBuilder b = new SpannableStringBuilder(MARK[t]);
            b.setSpan(new ForegroundColorSpan(Ink.tone[t]), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new TypefaceSpan(Ink.serifBold), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append('\n');
            int at = b.length();
            b.append(t == 5 ? "neutral" : o);
            b.setSpan(new AbsoluteSizeSpan(11, true), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Ink.ink2), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView v = Ui.text(m, b, 26, Ink.serif, Ink.ink);
            v.setGravity(Gravity.CENTER);
            v.setLineSpacing(0, 0.9f);
            v.setMinHeight(Ink.dp(72));
            v.setPadding(0, Ink.dp(6), 0, Ink.dp(8));
            v.setContentDescription(NAME[t] + (t == 5 ? "" : ", " + MARK[t]));
            if (q.picked == null) {
                Ui.touch(v, Ink.surface, Ink.line, 999);
                v.setOnClickListener(x -> q.pick(o));
            } else q.mark(v, right(o, h), o.equals(q.picked), 999);
            row.addView(v, Ui.weight(1));
        }
        return row;
    }
}
