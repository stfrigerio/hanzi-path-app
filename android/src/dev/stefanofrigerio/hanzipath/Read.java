package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Read round: sentences made only of characters you know (Sentences), one at a time. The
 * sentence first, alone; Show gives its pinyin and English (and says it, with "Say it aloud"
 * on); Next the one after. Nothing's graded: it's reading, not a test. Each one read goes in
 * the log (Progress.read, mode "read"), and the next round takes those not read for longest
 * first: never read, the ones that came within reach last first; then the longest ago.
 */
class Read {
    static final int ROUND = 10;   // sentences in a round
    static final int MIN = 5;      // home offers a round once this many are readable

    final Main m;
    final List<Sentences.S> queue;
    int i;
    boolean shown;
    long since;                     // when the sentence came up: how long it was read before Show

    Read(Main m) {
        this.m = m;
        queue = round(m, System.currentTimeMillis());
    }

    /** The round's sentences: never read first (the latest within reach first), then the longest ago read. */
    static List<Sentences.S> round(Main m, long now) {
        List<Sentences.S> can = Sentences.readable(m, Sentences.known(m.prog, now));
        Map<String, Long> last = m.prog.lastRead();
        List<Sentences.S> out = new ArrayList<>(can);
        Collections.reverse(out);   // the path's order, the latest first
        Collections.sort(out, (a, b) -> Long.compare(last.containsKey(a.zh) ? last.get(a.zh) : 0, last.containsKey(b.zh) ? last.get(b.zh) : 0));
        return new ArrayList<>(out.subList(0, Math.min(ROUND, out.size())));
    }

    /** Home's line in the Today card: "Read 10 sentences", how many you can read, once there are MIN. */
    static View line(Main m, long now) {
        Progress p = m.prog;
        int known = 0;
        for (String h : p.cards.keySet()) if (PathData.get(h) != null) known++;
        if (known < 4) return null;   // no sentence yet: not worth reading the file for
        int n = Sentences.readable(m, Sentences.known(p, now)).size();
        if (n < MIN) return null;
        LinearLayout r = Ui.row(m, 12);
        r.setMinimumHeight(Ink.dp(38));
        r.addView(Ui.text(m, "Read " + Math.min(ROUND, n) + " sentences", 15, Ink.sans, Ink.ink), Ui.weight(1));
        r.addView(Home.stat(m, n + " you can read"));
        r.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));   // the › as on every way in
        Ui.press(r, Home.topRule(Ink.grid));
        r.setOnClickListener(v -> m.startRead());
        r.setContentDescription("Read " + Math.min(ROUND, n) + " sentences, of " + n + " you can read");
        return r;
    }

    void show() {
        Sentences.S s = queue.get(i);
        LinearLayout col = m.page("read", java.util.Arrays.asList(this, i), this::show);
        if (!shown) since = System.currentTimeMillis();
        col.addView(Cards.bar(m, "read " + (i + 1) + " / " + queue.size(), Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        TextView ask = Ui.label(m, "Read it");
        ask.setGravity(Gravity.CENTER);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        TextView zh = Ui.text(m, s.zh, 30, Ink.han, Ink.ink);
        zh.setGravity(Gravity.CENTER);
        zh.setLineSpacing(0, 1.15f);
        zh.setPadding(0, Ink.dp(12), 0, Ink.dp(12));
        if (shown) Taps.link(m, zh);   // once shown, each character opens its card: before, it's reading
        card.addView(zh, new LinearLayout.LayoutParams(-1, -2));
        if (shown) {
            LinearLayout answer = Ui.column(m, 8);
            answer.setGravity(Gravity.CENTER_HORIZONTAL);
            answer.setPadding(0, Ink.dp(16), 0, 0);
            answer.setBackground(Home.topRule());
            LinearLayout pr = Ui.row(m, 10);
            pr.setGravity(Gravity.CENTER);
            TextView py = Ui.text(m, Sentences.pinyin(s, null), 17, Ink.mono, Ink.ink);
            py.setGravity(Gravity.CENTER);
            pr.addView(py, new LinearLayout.LayoutParams(0, -2, 1));
            View say = Ui.speaker(m, v -> m.say(s.zh));
            say.setContentDescription("Say the sentence");
            pr.addView(say);
            answer.addView(pr, new LinearLayout.LayoutParams(-1, -2));
            TextView en = Ui.text(m, s.en, 17, Ink.sans, Ink.ink2);
            en.setGravity(Gravity.CENTER);
            answer.addView(en, new LinearLayout.LayoutParams(-1, -2));
            card.addView(answer, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(Ui.pill(m, !shown ? "Show" : i + 1 < queue.size() ? "Next" : "Finish", Ui.SOLID,
                v -> { if (shown) next(); else reveal(); }), new LinearLayout.LayoutParams(-1, -2));
        col.addView(card);
    }

    void reveal() {
        shown = true;
        m.prog.read(queue.get(i).zh, System.currentTimeMillis() - since);
        show();
        if (m.prog.say) m.say(queue.get(i).zh);
    }

    void next() {
        shown = false;
        if (++i < queue.size()) show();
        else finish();
    }

    /** The round's end, laid out as the quiz's: the seal (好: nothing's graded, a round read is a
     *  round done), how many were read, the way back, and another round if there's more. */
    void finish() {
        LinearLayout col = m.page("read-done", this::finish);
        LinearLayout card = Ui.card(m);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ink.dp(8);
        card.addView(Ui.seal(m, "好"), sp);
        int n = queue.size();
        TextView t = Ui.text(m, n + (n == 1 ? " sentence" : " sentences") + " read, made of what you know.", 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        int can = Sentences.readable(m, Sentences.known(m.prog, System.currentTimeMillis())).size();
        TextView more = Ui.text(m, can > n ? can + " you can read: the next round brings the ones read longest ago."
                : "That's all of them for now: more come within reach as you learn.", 14, Ink.sans, Ink.ink2);
        more.setGravity(Gravity.CENTER);
        card.addView(more, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout acts = Ui.row(m, 10);
        acts.setGravity(Gravity.CENTER);
        if (can > n) {
            acts.addView(Ui.pill(m, "Back to the path", Ui.OUTLINE, v -> m.home()), Ui.weight(1));
            acts.addView(Ui.pill(m, "Another round", Ui.SOLID, v -> m.startRead()), Ui.weight(1));
        } else {
            acts.addView(Ui.pill(m, "Back to the path", Ui.SOLID, v -> m.home()));
        }
        card.addView(acts, new LinearLayout.LayoutParams(-1, -2));
        col.addView(card);
    }
}
