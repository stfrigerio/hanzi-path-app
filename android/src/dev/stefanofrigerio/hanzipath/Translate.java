package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A Translate round: ROUND sentences you can read (Read's: what you know, and today's new),
 * the two ways in turn. Into English: the sentence, and four English ones to pick from (the
 * others of about its length). Into Chinese: the English, and the sentence's pieces shuffled
 * on tiles (its words as the path knows them, else its characters, and two that aren't in it)
 * to tap in order; Check, then the right one under yours. Each answer goes in the log (mode
 * "translate", 3 right, 1 wrong; not a card's grade: GRADED leaves it out), and the next round
 * takes the never translated first, then the longest ago.
 */
class Translate {
    static final int ROUND = 10;
    static final int TILES_MAX = 8;   // into Chinese only for sentences of at most this many pieces
    static final int DECOYS = 2;

    final Main m;
    final List<Sentences.S> queue;
    final Random rnd = new Random();
    final List<String> marks;
    int i, right;
    long since;
    // the question in hand
    List<String> options;            // into English: the four
    List<String> tiles;              // into Chinese: shuffled, decoys in
    final List<Integer> placed = new ArrayList<>();   // the tiles tapped, in order (their places in tiles)
    String picked;                   // into English: the one tapped
    boolean checked;

    Translate(Main m) {
        this.m = m;
        long now = System.currentTimeMillis();
        marks = Read.todayNew(m.prog, now);
        queue = round(m, now);
    }

    /** Never translated first (the latest within reach first), then the longest ago. */
    static List<Sentences.S> round(Main m, long now) {
        List<Sentences.S> can = new ArrayList<>(Sentences.readable(m, Read.reach(m.prog, now, Read.todayNew(m.prog, now))));
        Map<String, Long> last = m.prog.lastTranslated();
        Collections.reverse(can);
        Collections.sort(can, (a, b) -> Long.compare(last.containsKey(a.zh) ? last.get(a.zh) : 0, last.containsKey(b.zh) ? last.get(b.zh) : 0));
        return new ArrayList<>(can.subList(0, Math.min(ROUND, can.size())));
    }

    /** Into Chinese for every other one, when its pieces fit on the tiles. */
    boolean intoChinese(int k) { return k % 2 == 1 && pieces(queue.get(k)).size() <= TILES_MAX; }

    /** A sentence's pieces: the longest words the path knows (2 to 4 characters), else single
     *  characters; punctuation left out. */
    static List<String> pieces(Sentences.S s) {
        List<String> out = new ArrayList<>();
        String[] h = s.han;
        for (int k = 0; k < h.length; ) {
            int took = 1;
            for (int n = Math.min(4, h.length - k); n >= 2; n--) {
                StringBuilder w = new StringBuilder();
                for (int j = k; j < k + n; j++) w.append(h[j]);
                if (Words.get(w.toString()) != null) { took = n; break; }
            }
            StringBuilder w = new StringBuilder();
            for (int j = k; j < k + took; j++) w.append(h[j]);
            out.add(w.toString());
            k += took;
        }
        return out;
    }

    static String joined(Sentences.S s) {
        StringBuilder b = new StringBuilder();
        for (String c : s.han) b.append(c);
        return b.toString();
    }

    void ask() {
        Sentences.S s = queue.get(i);
        picked = null;
        checked = false;
        placed.clear();
        since = System.currentTimeMillis();
        if (intoChinese(i)) {
            tiles = pieces(s);
            Set<String> in = new HashSet<>(java.util.Arrays.asList(s.han));
            List<String> known = new ArrayList<>(Read.reach(m.prog, since, marks));
            Collections.shuffle(known, rnd);
            int d = 0;
            for (String c : known) {
                if (d >= DECOYS) break;
                if (in.contains(c) || PathData.get(c) == null || PathData.get(c).isPart()) continue;
                tiles.add(c);
                d++;
            }
            Collections.shuffle(tiles, rnd);
            options = null;
        } else {
            options = englishOptions(s);
            tiles = null;
        }
        show();
    }

    /** The English and three others, each different, of the readable ones closest in length. */
    List<String> englishOptions(Sentences.S s) {
        List<Sentences.S> pool = new ArrayList<>(Sentences.readable(m, Read.reach(m.prog, System.currentTimeMillis(), marks)));
        Collections.shuffle(pool, rnd);
        Collections.sort(pool, (a, b) -> Integer.compare(Math.abs(a.en.length() - s.en.length()), Math.abs(b.en.length() - s.en.length())));
        List<String> out = new ArrayList<>();
        out.add(s.en);
        Set<String> seen = new HashSet<>();
        seen.add(s.en.toLowerCase());
        for (Sentences.S o : pool) {
            if (out.size() >= 4) break;
            if (seen.add(o.en.toLowerCase()) && !o.zh.equals(s.zh)) out.add(o.en);
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    void show() {
        Sentences.S s = queue.get(i);
        boolean zhWay = tiles != null;
        LinearLayout col = m.page("translate", java.util.Arrays.asList(this, i), this::show);
        col.addView(Cards.bar(m, "translate " + (i + 1) + " / " + queue.size(), Ui.close(m, v -> m.home())));
        LinearLayout card = Ui.card(m);
        TextView ask = Ui.label(m, zhWay ? "Into Chinese" : "Into English");
        ask.setGravity(Gravity.CENTER);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        if (zhWay) {
            TextView en = Ui.text(m, s.en, 20, Ink.sans, Ink.ink);
            en.setGravity(Gravity.CENTER);
            en.setPadding(0, Ink.dp(8), 0, Ink.dp(8));
            card.addView(en, new LinearLayout.LayoutParams(-1, -2));
            card.addView(built(s), new LinearLayout.LayoutParams(-1, -2));
            if (!checked) {
                Flow tray = new Flow(m, 8);
                tray.center = true;
                for (int k = 0; k < tiles.size(); k++) {
                    final int at = k;
                    TextView t = tile(tiles.get(k));
                    if (placed.contains(k)) { t.setAlpha(0.25f); t.setEnabled(false); }
                    else t.setOnClickListener(v -> { placed.add(at); show(); });
                    t.setContentDescription("Tile " + tiles.get(k));
                    tray.addView(t);
                }
                card.addView(tray, new LinearLayout.LayoutParams(-1, -2));
                TextView check = Ui.pill(m, "Check", Ui.SOLID, v -> check());
                check.setEnabled(!placed.isEmpty());
                card.addView(check, new LinearLayout.LayoutParams(-1, -2));
            } else answer(card, s, yours(s).equals(joined(s)));
        } else {
            TextView zh = Ui.text(m, Sentences.marked(s, marks), 30, Ink.han, Ink.ink);
            zh.setGravity(Gravity.CENTER);
            zh.setLineSpacing(0, 1.15f);
            zh.setPadding(0, Ink.dp(8), 0, Ink.dp(8));
            if (picked != null) Taps.link(m, zh);
            card.addView(zh, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout opts = Ui.column(m, 10);
            for (String o : options) opts.addView(option(o, s.en), new LinearLayout.LayoutParams(-1, -2));
            card.addView(opts, new LinearLayout.LayoutParams(-1, -2));
            if (picked != null) answer(card, s, picked.equals(s.en));
        }
        col.addView(card);
    }

    /** What's been tapped so far, as a line of tiles (tap one to take it back); once checked,
     *  ringed green when right, pink when not. */
    View built(Sentences.S s) {
        // the box round it (Flow lays out without padding: the box is a frame round the flow)
        android.widget.FrameLayout box = new android.widget.FrameLayout(m);
        box.setMinimumHeight(Ink.dp(60));
        box.setPadding(Ink.dp(8), Ink.dp(8), Ink.dp(8), Ink.dp(8));
        android.graphics.drawable.GradientDrawable g = Ui.shape(0, 0, 16, false);
        if (checked) g.setStroke(Ink.dp(2), yours(s).equals(joined(s)) ? Ink.tone[2] : Ink.tone[4]);
        else g.setStroke(Math.max(1, Ink.dp(1)), Ink.rule, Ink.dp(4), Ink.dp(4));
        box.setBackground(g);
        Flow line = new Flow(m, 6);
        line.center = true;
        box.addView(line, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        for (int k = 0; k < placed.size(); k++) {
            final int at = k;
            TextView t = tile(tiles.get(placed.get(k)));
            if (!checked) t.setOnClickListener(v -> { placed.remove(at); show(); });
            t.setContentDescription("Placed " + tiles.get(placed.get(k)) + (checked ? "" : ", tap to take back"));
            line.addView(t);
        }
        box.setContentDescription(placed.isEmpty() ? "Your sentence: empty" : "Your sentence: " + yours(s));
        return box;
    }

    String yours(Sentences.S s) {
        StringBuilder b = new StringBuilder();
        for (int k : placed) b.append(tiles.get(k));
        return b.toString();
    }

    TextView tile(String s) {
        TextView t = Ui.text(m, s, 22, Ink.han, Ink.ink);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ink.dp(44));
        t.setMinWidth(Ink.dp(44));
        t.setPadding(Ink.dp(10), 0, Ink.dp(10), 0);
        Ui.touch(t, Ink.paper, Ink.ink, 12);
        return t;
    }

    View option(String o, String right) {
        TextView t = Ui.text(m, o, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setMinHeight(Ink.dp(52));
        t.setPadding(Ink.dp(20), Ink.dp(8), Ink.dp(20), Ink.dp(8));
        if (picked == null) {
            Ui.style(t, Ui.OUTLINE);
            t.setOnClickListener(v -> pick(o));
        } else {
            boolean r = o.equals(right), mine = o.equals(picked);
            android.graphics.drawable.GradientDrawable g = Ui.shape(Ink.paper, 0, 999, false);
            if (r || mine) g.setStroke(Ink.dp(2), r ? Ink.tone[2] : Ink.tone[4]);
            else { g.setStroke(Math.max(1, Ink.dp(1)), Ink.rule); t.setAlpha(0.45f); }
            t.setBackground(g);
        }
        t.setTag(o);
        t.setContentDescription("Answer: " + o + (picked == null ? "" : o.equals(right) ? ", the right one" : o.equals(picked) ? ", your pick" : ""));
        return t;
    }

    void pick(String o) {
        if (picked != null) return;
        picked = o;
        log(o.equals(queue.get(i).en));
        show();
    }

    void check() {
        if (checked || placed.isEmpty()) return;
        checked = true;
        Sentences.S s = queue.get(i);
        log(yours(s).equals(joined(s)));
        show();
    }

    void log(boolean ok) {
        if (ok) right++;
        m.prog.translated(queue.get(i).zh, ok, System.currentTimeMillis() - since);
        if (m.prog.say) m.say(queue.get(i).zh);
    }

    /** Under an answer: how it went, the sentence (written, its pinyin, the English, said),
     *  and Next. */
    void answer(LinearLayout card, Sentences.S s, boolean ok) {
        TextView how = Ui.label(m, ok ? "Right" : "Not quite");
        how.setTextColor(ok ? Ink.tone[2] : Ink.tone[4]);
        how.setGravity(Gravity.CENTER);
        card.addView(how, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout a = Ui.column(m, 8);
        a.setGravity(Gravity.CENTER_HORIZONTAL);
        a.setPadding(0, Ink.dp(16), 0, 0);
        a.setBackground(Home.topRule());
        if (tiles != null) {
            TextView zh = Ui.text(m, Sentences.marked(s, marks), 26, Ink.han, Ink.ink);
            zh.setGravity(Gravity.CENTER);
            Taps.link(m, zh);
            a.addView(zh, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout pr = Ui.row(m, 10);
        pr.setGravity(Gravity.CENTER);
        TextView py = Ui.text(m, Sentences.pinyin(s, null), 17, Ink.mono, Ink.ink);
        py.setGravity(Gravity.CENTER);
        pr.addView(py, new LinearLayout.LayoutParams(0, -2, 1));
        View say = Ui.speaker(m, v -> m.say(s.zh));
        say.setContentDescription("Say the sentence");
        pr.addView(say);
        a.addView(pr, new LinearLayout.LayoutParams(-1, -2));
        if (tiles != null) {
            TextView en = Ui.text(m, s.en, 17, Ink.sans, Ink.ink2);
            en.setGravity(Gravity.CENTER);
            a.addView(en, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(a, new LinearLayout.LayoutParams(-1, -2));
        card.addView(Ui.pill(m, i + 1 < queue.size() ? "Next" : "Finish", Ui.SOLID, v -> next()), new LinearLayout.LayoutParams(-1, -2));
    }

    void next() {
        if (++i < queue.size()) ask();
        else finish();
    }

    /** The round's end, as the quiz's: the seal (好 all right, 行 most, 练 practise), how it
     *  went, the way back, another round. */
    void finish() {
        LinearLayout col = m.page("translate-done", this::finish);
        LinearLayout card = Ui.card(m);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ink.dp(8);
        int n = queue.size();
        card.addView(Ui.seal(m, right == n ? "好" : right * 2 >= n ? "行" : "练"), sp);
        TextView t = Ui.text(m, right + " of " + n + " translated right.", 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView note = Ui.note(m, "The next round starts with the ones not translated yet.");
        note.setGravity(Gravity.CENTER);
        card.addView(note, new LinearLayout.LayoutParams(-1, -2));
        card.addView(Ui.even(m, 10, Ui.pill(m, "Back to the path", Ui.OUTLINE, v -> m.home()),
                Ui.pill(m, "Another round", Ui.SOLID, v -> m.startTranslate())), new LinearLayout.LayoutParams(-1, -2));
        col.addView(card);
    }

    /** Home's line under Read's: "Translate 10 sentences", once Read has enough. */
    static View line(Main m, long now) {
        Progress p = m.prog;
        int seen = 0;
        for (String h : p.cards.keySet()) if (PathData.get(h) != null) seen++;
        if (seen < 4) return null;
        int n = Sentences.readable(m, Read.reach(p, now, Read.todayNew(p, now))).size();
        if (n < Read.MIN) return null;
        LinearLayout r = Ui.row(m, 12);
        r.setMinimumHeight(Ink.dp(38));
        r.addView(Ui.text(m, "Translate " + Math.min(ROUND, n) + " sentences", 15, Ink.sans, Ink.ink), Ui.weight(1));
        r.addView(Home.stat(m, "both ways"));
        r.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));
        Ui.press(r, Home.topRule(Ink.grid));
        r.setOnClickListener(v -> m.startTranslate());
        r.setContentDescription("Translate " + Math.min(ROUND, n) + " sentences, into English and into Chinese");
        return r;
    }
}
