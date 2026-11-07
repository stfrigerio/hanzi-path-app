package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Tell them apart": two characters the learner has taken for each other (Progress.pairs), side by side.
 * Each large on its square (tap: its strokes), its reading in the tones' inks with the
 * speaker, its meaning and its parts; when they share parts, the ones that differ are on the
 * highlighter. Under them, one line on what tells them apart, made from the data: the parts
 * they share and the ones they don't, or that one is built on the other, and how each is said.
 * Not a question, not graded: Next goes on. A review round starts with one, once a week a
 * pair (Quiz); Stats' "Mixed up" opens any of them.
 */
final class Apart {
    final Main m;
    final Progress.Pair pair;
    final String from;          // review | stats: where Next (and back) goes
    final Runnable next;

    Apart(Main m, Progress.Pair pair, String from, Runnable next) {
        this.m = m;
        this.pair = pair;
        this.from = from;
        this.next = next;
    }

    /** The card, seen: the week before the pair comes again counts from now. */
    static void show(Main m, Progress.Pair pair, String from, Runnable next) {
        Apart a = new Apart(m, pair, from, next);
        m.apart = a;
        m.prog.apartSeen(pair, System.currentTimeMillis());
        a.show();
    }

    void show() {
        LinearLayout col = m.page("apart", pair.key(), this::show);
        col.addView(Cards.bar(m, (from.equals("review") ? "review · " : "") + "mixed up " + times(pair.n),
                Ui.close(m, v -> { if (from.equals("stats") && m.stats != null) m.stats.show(); else m.home(); })));

        LinearLayout card = Ui.card(m);
        TextView ask = Ui.label(m, "Tell them apart");
        ask.setGravity(Gravity.CENTER);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));

        Item a = PathData.get(pair.a), b = PathData.get(pair.b);
        List<String> shared = shared(a, b);
        LinearLayout both = Ui.row(m, 12);
        both.setGravity(Gravity.TOP);
        both.addView(side(a, shared), Ui.weight(1));
        both.addView(side(b, shared), Ui.weight(1));
        card.addView(both, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tell = Ui.column(m, 8);
        tell.setPadding(0, Ink.dp(16), 0, 0);
        tell.setBackground(Home.topRule());
        tell.addView(Ui.label(m, "What tells them apart"));
        TextView line = Ui.text(m, line(a, b), 15, Ink.sans, Ink.ink);
        line.setLineSpacing(0, 1.1f);
        Taps.link(m, line, a.h, b.h);   // the parts open their cards; the two are here already
        tell.addView(line);
        card.addView(tell, new LinearLayout.LayoutParams(-1, -2));

        card.addView(Ui.pill(m, from.equals("review") ? "Next" : "Back to Stats", Ui.SOLID, v -> next.run()),
                new LinearLayout.LayoutParams(-1, -2));
        col.addView(card);
    }

    /** One of the two: its square, its reading and the speaker, its meaning, its parts. */
    View side(Item it, List<String> shared) {
        LinearLayout c = Ui.column(m, 6);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        HanziView hv = new HanziView(m);
        hv.setGlyph(PathData.glyph(m, it.h));
        hv.show();
        hv.setOnClickListener(v -> hv.animateStrokes());
        hv.setContentDescription("Stroke order of " + it.h);
        c.addView(hv, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r = Ui.row(m, 8);
        r.setGravity(Gravity.CENTER);
        r.addView(Ui.text(m, it.py.length > 0 ? Ui.pinyin(it.py[0]) : "—", 22, Ink.monoMedium, Ink.ink));
        if (it.py.length > 0) r.addView(Ui.speaker(m, v -> m.say(it.h)));
        c.addView(r, new LinearLayout.LayoutParams(-1, -2));
        TextView en = Ui.text(m, it.en, 15, Ink.sans, Ink.ink);
        en.setGravity(Gravity.CENTER);
        c.addView(en, new LinearLayout.LayoutParams(-1, -2));

        if (it.parts.length == 0) {
            TextView one = Ui.text(m, "no smaller parts", 12, Ink.mono, Ink.ink2);
            one.setGravity(Gravity.CENTER);
            c.addView(one, new LinearLayout.LayoutParams(-1, -2));
        } else {
            // its parts, centred: the ones the other hasn't on the highlighter, when they share any
            LinearLayout ps = Ui.row(m, 6);
            ps.setGravity(Gravity.CENTER);
            for (String p : it.parts) ps.addView(part(p, !shared.isEmpty() && !shared.contains(p)));
            c.addView(ps, new LinearLayout.LayoutParams(-1, -2));
        }
        return c;
    }

    /** A part as a small pill: the part and its meaning; the highlighter when it's the difference. */
    View part(String p, boolean differs) {
        SpannableStringBuilder b = new SpannableStringBuilder(p);
        Ui.hanSpan(b, 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(1.35f), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String mean = meaning(p);
        if (!mean.isEmpty()) {
            b.append(' ');
            int at = b.length();
            b.append(mean);
            b.setSpan(new ForegroundColorSpan(differs ? Ink.hlInk : Ink.ink2), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView t = Ui.text(m, b, 12, Ink.sans, differs ? Ink.hlInk : Ink.ink);
        t.setMaxLines(1);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ink.dp(8), Ink.dp(1), Ink.dp(10), Ink.dp(1));
        t.setBackground(Ui.shape(differs ? Ink.hl : Ink.paper, differs ? Ink.hlInk : Ink.rule, 999, false));
        t.setContentDescription(p + " " + mean + (differs ? ", the difference" : ""));
        return t;
    }

    static String times(int n) { return n == 1 ? "once" : n == 2 ? "twice" : n + " times"; }

    /** The parts both have. */
    static List<String> shared(Item a, Item b) {
        List<String> out = new ArrayList<>();
        for (String p : a.parts) if (Arrays.asList(b.parts).contains(p) && !out.contains(p)) out.add(p);
        return out;
    }

    /** An item's first sense, short: "person; people" is "person"; "" for one off the path. */
    static String meaning(String h) {
        Item it = PathData.get(h);
        if (it == null || it.en == null) return "";
        int semi = it.en.indexOf(';');
        return (semi < 0 ? it.en : it.en.substring(0, semi)).trim();
    }

    /** What tells them apart, from the data: "Both have 舌 tongue: 活 has 氵 water, 话 has 讠
     *  speech.", "太 is built on 大 big.", "人 person, 入 to enter: no part in common.";
     *  then how each is said ("Said huó and huà.", or "Both said shì: …"). */
    static SpannableStringBuilder line(Item a, Item b) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        List<String> pa = Arrays.asList(a.parts), pb = Arrays.asList(b.parts);
        if (pb.contains(a.h) || pa.contains(b.h)) {
            Item in = pb.contains(a.h) ? a : b, out = in == a ? b : a;
            List<String> more = new ArrayList<>(Arrays.asList(out.parts));
            more.remove(in.h);
            han(s, out.h).append(" is built on ");
            named(s, in.h);
            if (!more.isEmpty()) { s.append(", with "); named(s, more); s.append(" beside it"); }
            s.append('.');
        } else {
            List<String> shared = shared(a, b), da = new ArrayList<>(pa), db = new ArrayList<>(pb);
            da.removeAll(shared);
            db.removeAll(shared);
            if (shared.isEmpty()) {
                named(s, a.h); s.append(", "); named(s, b.h); s.append(": no part in common.");
            } else if (da.isEmpty() && db.isEmpty()) {
                s.append("Both are made of "); named(s, shared); s.append(": only where they sit tells them apart.");
            } else {
                s.append("Both have "); named(s, shared); s.append(":");
                if (!da.isEmpty()) { s.append(" "); han(s, a.h).append(" has "); named(s, da); }
                if (!da.isEmpty() && !db.isEmpty()) s.append(",");
                if (!db.isEmpty()) { s.append(" "); han(s, b.h).append(" has "); named(s, db); }
                s.append('.');
            }
        }
        String ya = a.reading(), yb = b.reading();
        if (!ya.isEmpty() && !yb.isEmpty()) {
            s.append(' ');
            if (ya.equals(yb)) {
                s.append("Both said ").append(Ui.pinyin(ya)).append(": only the meaning tells them apart.");
            } else {
                s.append("Said ").append(Ui.pinyin(ya)).append(" and ").append(Ui.pinyin(yb)).append('.');
            }
        }
        return s;
    }

    static SpannableStringBuilder han(SpannableStringBuilder s, String h) {
        int at = s.length();
        s.append(h);
        s.setSpan(new TypefaceSpan(Ink.han), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** "氵 water", or "一 one and 木 tree". */
    static void named(SpannableStringBuilder s, String h) {
        han(s, h);
        String mean = meaning(h);
        if (!mean.isEmpty()) s.append('\u00a0').append(mean);   // the part and its meaning on one line
    }

    static void named(SpannableStringBuilder s, List<String> hs) {
        for (int i = 0; i < hs.size(); i++) {
            if (i > 0) s.append(i == hs.size() - 1 ? " and " : ", ");
            named(s, hs.get(i));
        }
    }
}
