package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Read round: sentences made only of characters you know (Sentences), one at a time. The
 * sentence first, alone; Show gives its pinyin and English (and says it, with "Say it aloud"
 * on); Next the one after. Nothing's graded: it's reading, not a test. Each one read goes in
 * the log (Progress.read, mode "read"), and the next round takes those not read for longest
 * first: never read, the ones that came within reach last first; then the longest ago.
 *
 * What was just learnt comes first: home's round puts the sentences with today's new in them
 * ahead of the rest, and a Learn batch's end offers a round of only those with the batch in
 * them (WITH at least). Either way what's new is on the highlighter, as on a card. Once shown,
 * each sentence says its level at the top right: the highest HSK level among its characters
 * (Levels), nothing when one of them is in no HSK word.
 */
class Read {
    static final int ROUND = 10;   // sentences in a round
    static final int MIN = 5;      // home offers a round once this many are readable
    static final int WITH = 3;     // a batch's end offers its own round once this many have it in them

    final Main m;
    final List<Sentences.S> queue;
    final List<String> marks;       // what's on the highlighter: today's new, or the batch's
    Map<String, Integer> levels;    // each character's HSK level (Levels.levelOf), read once a round
    int i;
    boolean shown;
    long since;                     // when the sentence came up: how long it was read before Show

    Read(Main m) {
        this.m = m;
        long now = System.currentTimeMillis();
        marks = todayNew(m.prog, now);
        queue = round(m, now);
    }

    /** A round of only the sentences with some of these in them (a batch just learnt). */
    Read(Main m, List<String> with) {
        this.m = m;
        marks = new ArrayList<>(with);
        List<Sentences.S> can = with(Sentences.readable(m, reach(m.prog, System.currentTimeMillis(), with)), with);
        queue = take(order(m, can));
    }

    /** The round's sentences: those with today's new in them first, then the rest; each way
     *  never read first (the latest within reach first), then the longest ago read. */
    static List<Sentences.S> round(Main m, long now) {
        List<String> fresh = todayNew(m.prog, now);
        List<Sentences.S> can = order(m, Sentences.readable(m, reach(m.prog, now, fresh)));
        List<Sentences.S> out = with(can, fresh);
        for (Sentences.S s : can) if (!has(s, fresh)) out.add(s);
        return take(out);
    }

    /** Never read first (the latest within reach first), then the longest ago read. */
    static List<Sentences.S> order(Main m, List<Sentences.S> can) {
        Map<String, Long> last = m.prog.lastRead();
        List<Sentences.S> out = new ArrayList<>(can);
        Collections.reverse(out);   // the path's order, the latest first
        Collections.sort(out, (a, b) -> Long.compare(last.containsKey(a.zh) ? last.get(a.zh) : 0, last.containsKey(b.zh) ? last.get(b.zh) : 0));
        return out;
    }

    static List<Sentences.S> take(List<Sentences.S> l) { return new ArrayList<>(l.subList(0, Math.min(ROUND, l.size()))); }

    /** Those with any of hs (characters, or words) in them, in their order. */
    static List<Sentences.S> with(List<Sentences.S> l, Collection<String> hs) {
        List<Sentences.S> out = new ArrayList<>();
        for (Sentences.S s : l) if (has(s, hs)) out.add(s);
        return out;
    }

    static boolean has(Sentences.S s, Collection<String> hs) {
        for (String h : hs) if (s.zh.contains(h)) return true;
        return false;
    }

    /** What you can read with: the known (Sentences.known), and the characters of these just
     *  learnt, which aren't known yet (a week's strength) but are what the round is for. */
    static Set<String> reach(Progress p, long now, Collection<String> plus) {
        Set<String> out = Sentences.known(p, now);
        for (String h : plus) for (String c : Words.chars(h)) if (PathData.get(c) != null) out.add(c);
        return out;
    }

    /** What was met for the first time today: characters and words, in the order met. */
    static List<String> todayNew(Progress p, long now) {
        long from = Stats.dayStart(now);
        List<Map.Entry<String, Progress.Card>> es = new ArrayList<>();
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet()) if (e.getValue().first >= from) es.add(e);
        Collections.sort(es, (a, b) -> Long.compare(a.getValue().first, b.getValue().first));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Progress.Card> e : es) out.add(e.getKey());
        return out;
    }

    /** The sentence's level: the highest HSK level among its characters, 0 when one is in no HSK word. */
    static int level(Sentences.S s, Map<String, Integer> of) {
        int top = 0;
        for (String c : s.han) {
            Integer l = of.get(c);
            if (l == null) return 0;
            top = Math.max(top, l);
        }
        return top;
    }

    /** A Learn batch's end: "Read sentences with these" (small) when WITH or more readable have
     *  some of the batch in them; null otherwise. */
    static TextView offer(Main m, List<String> batch) {
        if (batch == null || batch.isEmpty()) return null;
        int n = with(Sentences.readable(m, reach(m.prog, System.currentTimeMillis(), batch)), batch).size();
        if (n < WITH) return null;
        TextView t = Ui.small(m, "Read sentences with these", v -> start(m, batch));
        t.setContentDescription("Read " + Math.min(ROUND, n) + " sentences with these");
        return t;
    }

    /** A round of the sentences with the batch in them. */
    static void start(Main m, List<String> batch) {
        Read r = new Read(m, batch);
        if (r.queue.isEmpty()) return;
        m.read = r;
        r.show();
    }

    /** Home's line in the Today card: "Read 10 sentences", how many you can read, once there are MIN. */
    static View line(Main m, long now) {
        Progress p = m.prog;
        int known = 0;
        for (String h : p.cards.keySet()) if (PathData.get(h) != null) known++;
        if (known < 4) return null;   // no sentence yet: not worth reading the file for
        List<String> today = todayNew(p, now);
        List<Sentences.S> can = Sentences.readable(m, reach(p, now, today));
        int n = can.size();
        if (n < MIN) return null;
        // with today's new in some: how many the round takes (first), in place of how many you can read
        int fresh = Math.min(ROUND, with(can, today).size());
        LinearLayout r = Ui.row(m, 12);
        r.setMinimumHeight(Ink.dp(38));
        r.addView(Ui.text(m, "Read " + Math.min(ROUND, n) + " sentences", 15, Ink.sans, Ink.ink), Ui.weight(1));
        r.addView(Home.stat(m, fresh > 0 ? fresh + " with today's new" : n + " you can read"));
        r.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));   // the › as on every way in
        Ui.press(r, Home.topRule(Ink.grid));
        r.setOnClickListener(v -> m.startRead());
        r.setContentDescription("Read " + Math.min(ROUND, n) + " sentences, of " + n + " you can read"
                + (fresh > 0 ? ", " + fresh + " with today's new" : ""));
        return r;
    }

    void show() {
        Sentences.S s = queue.get(i);
        LinearLayout col = m.page("read", java.util.Arrays.asList(this, i), this::show);
        if (!shown) since = System.currentTimeMillis();
        col.addView(Cards.bar(m, "read " + (i + 1) + " / " + queue.size(), Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        // the label across the top; once shown, the sentence's level at its right
        FrameLayout top = new FrameLayout(m);
        TextView ask = Ui.label(m, "Read it");
        ask.setGravity(Gravity.CENTER);
        top.addView(ask, new FrameLayout.LayoutParams(-1, -2));
        if (shown) {
            if (levels == null) levels = Levels.levelOf(m);
            int l = level(s, levels);
            if (l > 0) {
                TextView lv = Ui.label(m, "HSK " + l);
                lv.setContentDescription("HSK level " + l);
                top.addView(lv, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
            }
        }
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView zh = Ui.text(m, Sentences.marked(s, marks), 30, Ink.han, Ink.ink);
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
        // as the quiz's end: the two side by side (one under the other once the text is large),
        // or the way back alone across the card
        card.addView(can > n ? Ui.even(m, 10, Ui.pill(m, "Back to the path", Ui.OUTLINE, v -> m.home()),
                        Ui.pill(m, "Another round", Ui.SOLID, v -> m.startRead()))
                : Ui.pill(m, "Back to the path", Ui.SOLID, v -> m.home()), new LinearLayout.LayoutParams(-1, -2));
        col.addView(card);
    }
}
