package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A round of questions: the check after a batch (its first grade), or a review. The
 * character, and four answers, each a reading and a meaning: the other three are close
 * (characters sharing a part with it, else near it on the path, ones you've met first). A
 * tap grades it: right and quick is knew it (3), right after SLOW ms unsure (2), wrong
 * again (1), which puts it at the end of the round (up to three times). Then the answer in
 * full, and Next. Undo takes the last grade back.
 */
class Quiz {
    static final long SLOW = 8000;
    final Main m;
    final String kind;     // check | review
    List<String> queue;
    int done, total, misses;
    Map<String, Integer> tries = new HashMap<>();
    long shown;
    Last last;
    String optionsFor;     // the question the options were drawn for (a redraw keeps them)
    List<String> options;
    String picked;         // the answer tapped; null while it's open
    int grade;
    HanziView hv;
    final Random rnd = new Random();

    static class Last {
        Progress.Undo undo;
        List<String> queue;
        int done, misses;
        Map<String, Integer> tries;
    }

    Quiz(Main m, List<String> items, String kind) {
        this.m = m;
        this.kind = kind;
        this.queue = new ArrayList<>(items);
        this.total = items.size();
    }

    void show() {
        String h = queue.get(0);
        Item it = PathData.get(h);
        if (!h.equals(optionsFor)) {
            options = options(h);
            optionsFor = h;
            shown = System.currentTimeMillis();
        }
        LinearLayout col = m.page("quiz", this::show);
        TextView undo = last == null ? null : smallPill("Undo", v -> undo());
        col.addView(Cards.bar(m, (kind.equals("check") ? "check " : "review ") + Math.min(done + 1, total) + " / " + total,
                undo, Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        TextView ask = Ui.label(m, picked == null ? "Which one is it?" : picked.equals(h) ? "Right" : "Not that one");
        ask.setGravity(Gravity.CENTER);
        if (picked != null) ask.setTextColor(picked.equals(h) ? Ink.tone[2] : Ink.tone[4]);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        hv = Cards.box(m, it);
        card.addView(hv);
        hv.show();
        hv.setOnClickListener(v -> hv.animateStrokes());

        LinearLayout opts = Ui.column(m, 10);
        for (String o : options) opts.addView(option(o, h), new LinearLayout.LayoutParams(-1, -2));
        card.addView(opts);

        if (picked != null) {
            // Next right under the answers: the rest is there to read, not to scroll past
            card.addView(Ui.pill(m, queue.size() > 1 || grade == 1 && tries(h) < 3 ? "Next" : "Finish",
                    Ui.SOLID, v -> next()), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout answer = Ui.column(m, 16);
            answer.setPadding(0, Ink.dp(16), 0, 0);
            answer.setBackground(Home.topRule());
            answer.addView(Cards.reading(m, it));
            Cards.parts(m, answer, it);
            Cards.add(answer, Cards.why(m, it));
            card.addView(answer);
        }
        col.addView(card);
    }

    /** An answer: its reading in the tones' inks, then its meaning. After a tap, the right one
     *  is ringed green, a wrong pick pink, the rest fade. */
    View option(String o, String h) {
        Item it = PathData.get(o);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (it.py.length > 0) {
            b.append(Ui.pinyin(it.py[0]));
            b.setSpan(new TypefaceSpan(Ink.monoMedium), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append("   ");
        }
        b.append(it.en);
        TextView t = Ui.text(m, b, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setMinHeight(Ink.dp(52));
        t.setPadding(Ink.dp(20), Ink.dp(8), Ink.dp(20), Ink.dp(8));
        if (picked == null) {
            Ui.style(t, Ui.OUTLINE);
            t.setOnClickListener(v -> pick(o));
        } else {
            boolean right = o.equals(h), mine = o.equals(picked);
            android.graphics.drawable.GradientDrawable g = Ui.shape(Ink.paper, 0, 999, false);
            if (right || mine) g.setStroke(Ink.dp(2), right ? Ink.tone[2] : Ink.tone[4]);
            else g.setStroke(Math.max(1, Ink.dp(1)), Ink.rule);
            t.setBackground(g);
            if (!right && !mine) t.setAlpha(0.45f);
        }
        t.setContentDescription((it.py.length > 0 ? it.py[0] + ", " : "") + it.en);
        return t;
    }

    /** Three close wrong answers: siblings (sharing a part), then neighbours on the path; ones
     *  met first; each meaning once. */
    List<String> options(String h) {
        Item it = PathData.get(h);
        List<String> sib = new ArrayList<>(), near = new ArrayList<>();
        for (String p : it.parts) {
            Item pi = PathData.get(p);
            if (pi != null) for (String u : pi.used) sib.add(u);
        }
        for (String u : it.used) sib.add(u);
        for (int d = -40; d <= 40; d++) {
            int i = it.at + d;
            if (d != 0 && i >= 0 && i < PathData.items.size() && PathData.items.get(i).isPart() == it.isPart())
                near.add(PathData.items.get(i).h);
        }
        Collections.shuffle(sib, rnd);
        Collections.shuffle(near, rnd);
        List<String> pool = new ArrayList<>();
        for (List<String> l : Arrays.asList(sib, near)) {
            for (String s : l) if (m.prog.cards.containsKey(s)) pool.add(s);      // met first
            for (String s : l) if (!m.prog.cards.containsKey(s)) pool.add(s);
        }
        List<String> out = new ArrayList<>();
        Set<String> meanings = new HashSet<>();
        out.add(h);
        meanings.add(it.en);
        for (String s : pool) {
            if (out.size() >= 4) break;
            Item si = PathData.get(s);
            if (si == null || out.contains(s) || meanings.contains(si.en)
                    || (si.py.length > 0 && it.py.length > 0 && si.py[0].equals(it.py[0]) && si.en.equals(it.en))) continue;
            out.add(s);
            meanings.add(si.en);
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    TextView smallPill(String s, View.OnClickListener on) {
        TextView t = Ui.pill(m, s, Ui.OUTLINE, on);
        t.setMinHeight(Ink.dp(36));
        t.setTextSize(14);
        t.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        return t;
    }

    int tries(String h) { return tries.containsKey(h) ? tries.get(h) : 0; }

    /** A tap: the grade, from right or wrong and how long it took. */
    void pick(String o) {
        if (picked != null) return;
        String h = queue.get(0);
        long ms = System.currentTimeMillis() - shown;
        grade = !o.equals(h) ? 1 : ms <= SLOW ? 3 : 2;
        Last l = new Last();
        l.undo = m.prog.grade(h, grade, "choose", ms);
        l.queue = new ArrayList<>(queue);
        l.done = done;
        l.misses = misses;
        l.tries = new HashMap<>(tries);
        last = l;
        picked = o;
        Item it = PathData.get(h);
        if (m.prog.say && it.py.length > 0) m.say(h);
        show();
    }

    void next() {
        String h = queue.remove(0);
        int t = tries(h) + 1;
        tries.put(h, t);
        if (grade == 1) misses++;
        if (grade == 1 && t < 3) queue.add(h);   // once more at the end of the round
        else done++;
        picked = null;
        optionsFor = null;
        if (queue.isEmpty()) finish();
        else show();
    }

    /** The last grade taken back: the question open again, as it was. */
    void undo() {
        if (last == null) return;
        Last l = last;
        m.prog.ungrade(l.undo);
        queue = l.queue;
        done = l.done;
        misses = l.misses;
        tries = l.tries;
        last = null;
        picked = null;
        optionsFor = null;
        m.quiz = this;
        show();
    }

    /** The round's end: a seal (好 clean, 行 fine, 练 practise), what happened, the way back. */
    void finish() {
        LinearLayout col = m.page("done", this::finish);
        LinearLayout card = Ui.card(m);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView seal = Ui.text(m, misses == 0 ? "好" : misses <= total / 3.0 ? "行" : "练", 60, Ink.brush, Ink.tone[4]);
        android.graphics.drawable.GradientDrawable ring = Ui.shape(0, 0, 12, false);
        ring.setStroke(Ink.dp(2), Ink.tone[4]);
        seal.setBackground(ring);
        seal.setPadding(Ink.dp(14), Ink.dp(4), Ink.dp(14), Ink.dp(4));
        seal.setRotation(-4);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ink.dp(8);
        card.addView(seal, sp);
        String said = kind.equals("check")
                ? total + " new on the path. They come back in a few days, or in minutes if you missed them."
                : total + " reviewed" + (misses > 0 ? ", " + misses + " to go over again." : ", all of them held.");
        TextView t = Ui.text(m, said, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        LinearLayout acts = Ui.row(m, 10);
        acts.setGravity(Gravity.CENTER);
        if (last != null) acts.addView(Ui.pill(m, "Undo the last", Ui.OUTLINE, v -> undo()));
        acts.addView(Ui.pill(m, "Back to the path", Ui.SOLID, v -> m.home()));
        card.addView(acts);
        col.addView(card);
    }
}
