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
 * A round of questions: the check after a batch (its first grade), or a review. Three kinds:
 * choose (the character, and four answers, each a reading and a meaning), pick (the reading
 * and meaning, and four characters) and listen (it's said aloud, and four characters). The
 * check, and an item seen fewer than twice, get choose; after that a review draws one of the
 * three (listen only while Settings' "Say it aloud" is on). The other three answers are close
 * (characters sharing a part with it, else near it on the path, ones you've met first). A
 * tap grades it: right and quick is knew it (3), right after SLOW ms unsure (2), wrong
 * again (1), which puts it at the end of the round (up to three times). Then the answer in
 * full, and Next. Undo takes the last grade back.
 *
 * A word (Words) gets the same three, with the word where the character was: word-choose
 * (the word, four readings and meanings), word-pick (its reading and meaning, four words)
 * and word-listen (said aloud, four words). Its wrong answers are other words, ones sharing
 * a character with it first, ones you've met or could (their characters known) before the rest.
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
    String mode;           // choose | pick | listen, or word-choose | word-pick | word-listen
    String picked;         // the answer tapped; null while it's open
    int grade;
    HanziView hv;
    final Random rnd = new Random();
    boolean fresh;         // just answered: the rings come in (Motion), once
    final List<View> ringed = new ArrayList<>(), faded = new ArrayList<>();

    static class Last {
        Progress.Undo undo;
        List<String> queue;
        int done, misses;
        Map<String, Integer> tries;
        List<String> options;
        String mode;
    }

    Quiz(Main m, List<String> items, String kind) {
        this.m = m;
        this.kind = kind;
        this.queue = new ArrayList<>(items);
        this.total = items.size();
    }

    void show() {
        String h = queue.get(0);
        String[] word = Words.get(h);
        Item it = PathData.get(h);
        if (!h.equals(optionsFor)) {
            mode = kindFor(h);
            options = options(h, mode);
            optionsFor = h;
            shown = System.currentTimeMillis();
            if (mode.endsWith("listen")) m.say(h);
        }
        LinearLayout col = m.page("quiz", Arrays.asList(this, done, h, tries(h)), this::show);
        ringed.clear();
        faded.clear();
        TextView undo = last == null ? null : smallPill("Undo", v -> undo());
        col.addView(Cards.bar(m, (kind.equals("check") ? "check " : "review ") + Math.min(done + 1, total) + " / " + total,
                undo, Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        TextView ask = Ui.label(m, picked == null ? (mode.endsWith("listen") ? "Which one did you hear?" : "Which one is it?")
                : picked.equals(h) ? "Right" : "Not that one");
        ask.setGravity(Gravity.CENTER);
        if (picked != null) ask.setTextColor(picked.equals(h) ? Ink.tone[2] : Ink.tone[4]);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        if (mode.endsWith("choose")) {
            if (word != null) {
                hv = null;
                card.addView(Cards.word(m, h));
            } else {
                hv = Cards.box(m, it);
                card.addView(hv);
                hv.show();
                hv.setOnClickListener(v -> hv.animateStrokes());
            }
            LinearLayout opts = Ui.column(m, 10);
            for (String o : options) opts.addView(option(o, h), new LinearLayout.LayoutParams(-1, -2));
            card.addView(opts);
        } else {
            hv = null;
            card.addView(mode.endsWith("pick") ? said(h) : listen(h));
            card.addView(grid(h));
        }

        if (picked != null) {
            // Next right under the answers: the rest is there to read, not to scroll past
            card.addView(Ui.pill(m, queue.size() > 1 || grade == 1 && tries(h) < 3 ? "Next" : "Finish",
                    Ui.SOLID, v -> next()), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout answer = Ui.column(m, 16);
            answer.setPadding(0, Ink.dp(16), 0, 0);
            answer.setBackground(Home.topRule());
            if (word != null) {
                answer.addView(Cards.wordReading(m, word));
                answer.addView(Cards.chips(m, "Its characters", Words.chars(h)));
            } else {
                answer.addView(Cards.reading(m, it));
                Cards.parts(m, answer, it);
                Cards.add(answer, Cards.why(m, it));
            }
            card.addView(answer);
            if (fresh) reveal(answer);
        }
        col.addView(card);
    }

    /** The answer coming in after a tap (Motion.ANSWER): the right one's ring and a wrong pick's
     *  fade in as they settle from a touch smaller, the other answers fade back, the answer
     *  in full fades in under them. */
    void reveal(View answer) {
        fresh = false;
        m.motion = Motion.run(m, Motion.ANSWER, f -> {
            for (View v : ringed) {
                v.getBackground().setAlpha(Math.round(255 * f));
                v.setScaleX(0.96f + 0.04f * f);
                v.setScaleY(0.96f + 0.04f * f);
            }
            for (View v : faded) v.setAlpha(1 - 0.55f * f);
            answer.setAlpha(f);
        });
    }

    /** The kind of question for h: choose in the check and while it's new (seen fewer than
     *  twice), then any of the three; listen only with a voice to say it. */
    String kindFor(String h) {
        Progress.Card c = m.prog.cards.get(h);
        String w = Words.is(h) ? "word-" : "";
        if (kind.equals("check") || c == null || c.reps < 2) return w + "choose";
        List<String> k = new ArrayList<>(Arrays.asList("choose", "pick"));
        if (m.prog.say && !py(h).isEmpty()) k.add("listen");
        return w + k.get(rnd.nextInt(k.size()));
    }

    /** A card's reading ("" for a part without one) and meaning: a character's, or a word's. */
    static String py(String h) {
        String[] w = Words.get(h);
        if (w != null) return w[1];
        Item it = PathData.get(h);
        return it.py.length > 0 ? it.py[0] : "";
    }

    static String en(String h) {
        String[] w = Words.get(h);
        return w != null ? w[2] : PathData.get(h).en;
    }

    /** Pick's question: the reading in the tones' inks, the meaning under it. */
    View said(String h) {
        LinearLayout c = Ui.column(m, 4);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ink.dp(12), 0, Ink.dp(12));
        if (!py(h).isEmpty()) {
            TextView py = Ui.text(m, Ui.pinyin(py(h)), 30, Ink.monoMedium, Ink.ink);
            py.setGravity(Gravity.CENTER);
            c.addView(py);
        }
        TextView en = Ui.text(m, en(h), 19, Ink.sans, Ink.ink);
        en.setGravity(Gravity.CENTER);
        c.addView(en);
        return c;
    }

    /** Listen's question: the speaker, large, to hear it again. */
    View listen(String h) {
        LinearLayout c = Ui.column(m, 10);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ink.dp(8), 0, Ink.dp(8));
        View sp = Ui.speaker(m, v -> m.say(h));
        c.addView(sp, new LinearLayout.LayoutParams(Ink.dp(88), Ink.dp(88)));
        TextView t = Ui.text(m, "tap to hear it again", 13, Ink.mono, Ink.ink2);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    /** The four characters, two by two in equal squares. */
    View grid(String h) {
        LinearLayout g = Ui.column(m, 10);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(m, 10);
            for (int i = 2 * r; i < 2 * r + 2 && i < options.size(); i++) row.addView(cell(options.get(i), h), Ui.weight(1));
            g.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return g;
    }

    /** A character to pick, drawn from its strokes. After a tap, as option(): the right one
     *  ringed green, a wrong pick pink, the rest fade. */
    View cell(String o, String h) {
        View v;
        if (Words.is(o)) {
            // a word, written: four characters still fit half the card
            TextView t = Ui.text(m, o, Words.chars(o).length > 3 ? 30 : 34, Ink.han, Ink.ink);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setMinHeight(Ink.dp(96));
            v = t;
        } else {
            HanziView c = new HanziView(m);
            c.bare = true;
            c.pad = 0.2f;
            c.setGlyph(PathData.glyph(m, o));
            c.show();
            v = c;
        }
        v.setContentDescription(o);
        if (picked == null) {
            Ui.touch(v, Ink.paper, Ink.ink, 16);
            v.setOnClickListener(x -> pick(o));
        } else mark(v, o, h, 16);
        return v;
    }

    /** After a tap: the right answer ringed green, a wrong pick pink, the rest fade. */
    void mark(View v, String o, String h, float radius) {
        boolean right = o.equals(h), mine = o.equals(picked);
        android.graphics.drawable.GradientDrawable g = Ui.shape(Ink.paper, 0, radius, false);
        if (right || mine) g.setStroke(Ink.dp(2), right ? Ink.tone[2] : Ink.tone[4]);
        else g.setStroke(Math.max(1, Ink.dp(1)), Ink.rule);
        v.setBackground(g);
        if (right || mine) ringed.add(v);
        else { v.setAlpha(0.45f); faded.add(v); }
    }

    /** An answer: its reading in the tones' inks, then its meaning. After a tap, the right one
     *  is ringed green, a wrong pick pink, the rest fade. */
    View option(String o, String h) {
        String py = py(o), en = en(o);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!py.isEmpty()) {
            b.append(Ui.pinyin(py));
            b.setSpan(new TypefaceSpan(Ink.monoMedium), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append("   ");
        }
        b.append(en);
        TextView t = Ui.text(m, b, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setMinHeight(Ink.dp(52));
        t.setPadding(Ink.dp(20), Ink.dp(8), Ink.dp(20), Ink.dp(8));
        if (picked == null) {
            Ui.style(t, Ui.OUTLINE);
            t.setOnClickListener(v -> pick(o));
        } else mark(t, o, h, 999);
        t.setContentDescription((!py.isEmpty() ? py + ", " : "") + en);
        return t;
    }

    /** Three close wrong answers: siblings (sharing a part), then neighbours on the path; ones
     *  met first; each meaning once; to listen, each reading once too. */
    List<String> options(String h, String mode) {
        if (Words.is(h)) return pickFrom(h, mode, wordPool(h));
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
        return pickFrom(h, mode, pool);
    }

    /** A word's wrong answers, in order of closeness: words sharing a character with it, then
     *  its neighbours among the path's words; in each, ones met or ready first. */
    List<String> wordPool(String h) {
        long now = System.currentTimeMillis();
        List<String> sib = new ArrayList<>(), near = new ArrayList<>();
        for (String c : Words.chars(h)) for (String w : Words.with(c)) if (!w.equals(h) && !sib.contains(w)) sib.add(w);
        int at = Words.pos.get(h);
        for (int d = -40; d <= 40; d++) {
            int i = at + d;
            if (d != 0 && i >= 0 && i < Words.all.size()) near.add(Words.all.get(i)[0]);
        }
        Collections.shuffle(sib, rnd);
        Collections.shuffle(near, rnd);
        List<String> pool = new ArrayList<>(), rest = new ArrayList<>();
        for (List<String> l : Arrays.asList(sib, near))
            for (String s : l) (m.prog.cards.containsKey(s) || Words.ready(m.prog, s, now) ? pool : rest).add(s);
        pool.addAll(rest);
        return pool;
    }

    /** The four from a pool in order of preference: each meaning once; to listen, each
     *  reading once too. */
    List<String> pickFrom(String h, String mode, List<String> pool) {
        List<String> out = new ArrayList<>();
        Set<String> meanings = new HashSet<>(), readings = new HashSet<>();
        out.add(h);
        meanings.add(en(h));
        if (!py(h).isEmpty()) readings.add(py(h));
        boolean heard = mode.endsWith("listen");
        for (String s : pool) {
            if (out.size() >= 4) break;
            if (!Words.onPath(s) || out.contains(s) || meanings.contains(en(s))
                    || heard && (py(s).isEmpty() || readings.contains(py(s)))) continue;
            out.add(s);
            meanings.add(en(s));
            if (!py(s).isEmpty()) readings.add(py(s));
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
        l.undo = m.prog.grade(h, grade, mode, ms);
        l.queue = new ArrayList<>(queue);
        l.done = done;
        l.misses = misses;
        l.tries = new HashMap<>(tries);
        l.options = options;
        l.mode = mode;
        last = l;
        picked = o;
        fresh = true;
        Motion.felt(m.root, o.equals(h));
        if (m.prog.say && !py(h).isEmpty()) m.say(h);
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

    /** The last grade taken back: the question open again, as it was (the same kind, the
     *  same four), timed afresh. */
    void undo() {
        if (last == null) return;
        Last l = last;
        m.prog.ungrade(l.undo);
        queue = l.queue;
        done = l.done;
        misses = l.misses;
        tries = l.tries;
        last = null;
        optionsFor = queue.get(0);
        options = l.options;
        mode = l.mode;
        shown = System.currentTimeMillis();
        picked = null;
        if (mode.endsWith("listen")) m.say(optionsFor);
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
