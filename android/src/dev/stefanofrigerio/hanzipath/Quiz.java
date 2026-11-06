package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A round of questions: the check after a batch (its first grade), or a review. Read: the
 * character, then Show for its reading and meaning. Write: the reading and meaning, then
 * trace the character from nothing; the grade is suggested from the mistakes. Again puts it
 * at the end of the round (up to three times). Undo takes the last grade back.
 */
class Quiz {
    final Main m;
    final String kind;     // check | review
    List<String> queue;
    int done, total, misses;
    Map<String, Integer> tries = new HashMap<>();
    String mode;
    int suggest;
    long shown, ms;
    boolean revealed;
    Last last;
    HanziView hv;
    LinearLayout answer;
    View showRow, grades;
    TextView[] gradeBtn = new TextView[4];
    static final Random rnd = new Random();

    static class Last {
        Progress.Undo undo;
        String mode;
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

    String modeFor(String h) {
        Item it = PathData.get(h);
        Progress.Card c = m.prog.cards.get(h);
        if (kind.equals("check") || it.isPart() || c == null) return "read";
        if (m.prog.mode.equals("mix")) return c.reps >= 2 && rnd.nextFloat() < 0.4f ? "write" : "read";
        return m.prog.mode;
    }

    void show(String forceMode) {
        String h = queue.get(0);
        Item it = PathData.get(h);
        mode = forceMode != null ? forceMode : modeFor(h);
        suggest = 0;
        revealed = false;
        shown = System.currentTimeMillis();
        final String keep = mode;
        LinearLayout col = m.page("quiz", () -> show(keep));
        TextView undo = last == null ? null : smallPill("Undo", v -> undo());
        col.addView(Cards.bar(m, (kind.equals("check") ? "check " : "review ") + Math.min(done + 1, total) + " / " + total,
                undo, Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        boolean write = mode.equals("write");
        TextView ask = Ui.label(m, write ? "Write the character" : "What is it, how is it read?");
        ask.setGravity(Gravity.CENTER);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        if (write) card.addView(Cards.reading(m, it));
        hv = Cards.box(m, it);
        card.addView(hv);
        if (write) {
            hv.quiz(3, mistakes -> {
                suggest = mistakes == 0 ? 3 : mistakes <= 3 ? 2 : 1;
                reveal();
            });
        } else {
            hv.show();
            hv.setOnClickListener(v -> hv.animateStrokes());
        }
        answer = Ui.column(m, 16);
        answer.setVisibility(View.GONE);
        card.addView(answer);
        col.addView(card);

        TextView sh = Ui.pill(m, "Show", Ui.SOLID, v -> reveal());
        showRow = sh;
        col.addView(sh, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout g = Ui.row(m, 8);
        String[] names = {"", "Again", "Unsure", "Knew it"};
        int[] edge = {0, Ink.tone[4], Ink.ink, Ink.tone[2]};
        for (int k = 1; k <= 3; k++) {
            final int grade = k;
            TextView b = Ui.pill(m, names[k], Ui.OUTLINE, v -> answer(grade));
            b.setPadding(Ink.dp(8), 0, Ink.dp(8), 0);
            b.setBackground(Ui.shape(Ink.paper, edge[k], 999, false));
            gradeBtn[k] = b;
            g.addView(b, Ui.weight(1));
        }
        grades = g;
        g.setVisibility(View.GONE);
        col.addView(g);
    }

    TextView smallPill(String s, View.OnClickListener on) {
        TextView t = Ui.pill(m, s, Ui.OUTLINE, on);
        t.setMinHeight(Ink.dp(36));
        t.setTextSize(14);
        t.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        return t;
    }

    void reveal() {
        if (revealed) return;
        revealed = true;
        String h = queue.get(0);
        Item it = PathData.get(h);
        ms = System.currentTimeMillis() - shown;
        if (mode.equals("write")) {
            if (suggest == 0) { suggest = 1; hv.show(); }
        } else if (m.prog.say && it.py.length > 0) m.say(h);
        if (mode.equals("read")) answer.addView(Cards.reading(m, it));
        Cards.parts(m, answer, it);
        Cards.add(answer, Cards.storyRead(m, it));
        answer.setVisibility(View.VISIBLE);
        showRow.setVisibility(View.GONE);
        grades.setVisibility(View.VISIBLE);
        if (suggest > 0) Ui.style(gradeBtn[suggest], Ui.HIGHLIGHT);
    }

    void answer(int g) {
        if (!revealed) return;
        String h = queue.remove(0);
        Last l = new Last();
        l.undo = m.prog.grade(h, g, mode, ms);
        l.mode = mode;
        l.queue = new ArrayList<>(queue);
        l.queue.add(0, h);
        l.done = done;
        l.misses = misses;
        l.tries = new HashMap<>(tries);
        last = l;
        int t = (tries.containsKey(h) ? tries.get(h) : 0) + 1;
        tries.put(h, t);
        if (g == 1) misses++;
        if (g == 1 && t < 3) queue.add(h);   // once more at the end of the round
        else done++;
        if (queue.isEmpty()) finish();
        else show(null);
    }

    void undo() {
        if (last == null) return;
        Last l = last;
        m.prog.ungrade(l.undo);
        queue = l.queue;
        done = l.done;
        misses = l.misses;
        tries = l.tries;
        last = null;
        m.quiz = this;
        show(l.mode);
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
