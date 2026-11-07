package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HSK levels, from Stats: for each level 1 to 6, how many of its characters and words are
 * known, a bar (known blue, learning pink, the rest paper, as Stats' path bar), and its
 * characters as a small garden in the home garden's states, folded to a few rows (Show all);
 * a tap opens the character. Then the path's characters in no HSK word, Beyond HSK.
 *
 * From the HSK 2.0 lists as they are (assets/hsk.json, sources/hsk.json): the path's own
 * words leave out one-character words (你, 很) and keep a few words a character, so 你 would
 * be in no HSK word at all. A character's level is the lowest of the HSK words it's in, one
 * character words included; a part counts only when it's in an HSK word (勺 in 勺子). A
 * level's words are the list's, each at its lowest level; one known is its card known (a
 * one-character word: the character's card), as the garden's blue (Progress.KNOWN): the words
 * beyond the path count, never known yet. Read only; back (and ×) return to Stats.
 */
final class Levels {
    static final int LEVELS = 6, FOLD = 3;    // HSK 1 … 6; rows a level's garden shows folded
    static Map<String, Integer> hsk;          // every HSK word, its lowest level, list order
    final Main m;
    final boolean[] open = new boolean[LEVELS + 1];   // a level's garden unfolded (0: Beyond HSK)

    Levels(Main m) { this.m = m; }

    /** What a level holds and how much of it is known: from the progress as it is now. */
    static final class Level {
        final int n;                                  // 1 … 6, 0 Beyond HSK
        final List<Integer> chars = new ArrayList<>();   // places on the path, in path order
        final List<String> words = new ArrayList<>();
        int known, learning, wordsKnown;

        Level(int n) { this.n = n; }

        String name() { return n == 0 ? "Beyond HSK" : "HSK " + n; }

        /** "182 of 270 characters known · 410 of 600 words" */
        String line() {
            String s = known + " of " + chars.size() + " characters known";
            return n == 0 ? s : s + " · " + wordsKnown + " of " + words.size() + " words";
        }
    }

    /** The HSK lists, read the first time: each word at the lowest level it's listed at. */
    static synchronized Map<String, Integer> hsk(Context ctx) {
        if (hsk != null) return hsk;
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            JSONObject ls = new JSONObject(PathData.read(ctx, "hsk.json")).getJSONObject("levels");
            for (int l = 1; l <= LEVELS; l++) {
                JSONArray a = ls.optJSONArray(String.valueOf(l));
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    String w = a.getJSONArray(i).getString(0);
                    if (!out.containsKey(w)) out.put(w, l);
                }
            }
        } catch (Exception e) { return out; /* no lists: every character beyond HSK (read again when next wanted) */ }
        return hsk = out;
    }

    /** Each character on the path: the lowest level among the HSK words it's in; absent, none. */
    static Map<String, Integer> levelOf(Context ctx) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : hsk(ctx).entrySet())
            for (String c : Words.chars(e.getKey())) {
                if (PathData.get(c) == null) continue;
                Integer was = out.get(c);
                if (was == null || e.getValue() < was) out.put(c, e.getValue());
            }
        return out;
    }

    /** HSK 1 … 6 at [1 … 6], Beyond HSK (the path's characters in no HSK word) at [0]. */
    static Level[] levels(Context ctx, Progress p, long now) {
        Level[] out = new Level[LEVELS + 1];
        for (int i = 0; i <= LEVELS; i++) out[i] = new Level(i);
        Map<String, Integer> of = levelOf(ctx);
        for (Item it : PathData.items) {
            Integer l = of.get(it.h);
            if (l == null && it.isPart()) continue;    // 氵, 亻: parts, not characters of their own
            Level v = out[l == null ? 0 : l];
            v.chars.add(it.at);
            if (p.strength(it.h, now) >= Progress.KNOWN) v.known++;
            else if (p.cards.containsKey(it.h)) v.learning++;
        }
        for (Map.Entry<String, Integer> e : hsk(ctx).entrySet()) {
            Level v = out[e.getValue()];
            v.words.add(e.getKey());
            if (p.strength(e.getKey(), now) >= Progress.KNOWN) v.wordsKnown++;
        }
        return out;
    }

    /** The garden's state of every place on the path, as home draws it: known, learning,
     *  wilting; the next batch on the highlighter, the forty after it outlined, the rest faint. */
    static int[] states(Progress p, long now) {
        int[] st = new int[PathData.items.size()];
        int firstNew = -1, b = Math.min(p.batch, p.newLeftToday()), k = b > 0 ? b : p.batch;
        for (int i = 0; i < st.length; i++) {
            Item it = PathData.items.get(i);
            Progress.Card c = p.cards.get(it.h);
            if (c != null) {
                st[i] = p.strength(it.h, now) >= Progress.KNOWN ? GardenView.KNOWN
                        : c.due <= now && c.reps > 1 ? GardenView.WILT : GardenView.YOUNG;
            } else {
                if (firstNew < 0) firstNew = i;
                st[i] = i < firstNew + k ? GardenView.NEXT : i < firstNew + 40 ? GardenView.NEAR : GardenView.FAR;
            }
        }
        return st;
    }

    void show() {
        LinearLayout col = m.page("levels", this::show);
        col.addView(Cards.bar(m, "HSK LEVELS", Ui.close(m, v -> m.back())));
        long now = System.currentTimeMillis();
        Level[] ls = levels(m, m.prog, now);
        int[] st = states(m.prog, now);

        LinearLayout head = Ui.column(m, 10);
        head.addView(Ui.text(m, "A character's level is the lowest of the HSK words it's in. Tap one to open it.",
                14, Ink.sans, Ink.ink2));
        LinearLayout lg = Ui.row(m, 12);
        lg.addView(Home.legend(m, Ink.hl, Ink.hl, "next"));
        lg.addView(Home.legend(m, Ink.young, Ink.young, "learning"));
        lg.addView(Home.legend(m, Ink.known, Ink.known, "known"));
        lg.addView(Home.legend(m, 0, Ink.rule, "ahead"));
        head.addView(lg);
        col.addView(head);

        for (int i = 1; i <= LEVELS; i++) col.addView(section(ls[i], st));
        if (!ls[0].chars.isEmpty()) col.addView(section(ls[0], st));
    }

    /** A level: its name, the counts, the bar, its characters folded to a few rows. */
    View section(Level l, int[] st) {
        LinearLayout s = Ui.column(m, 10);
        s.setPadding(0, Ink.dp(14), 0, 0);
        s.setBackground(Home.topRule());
        s.addView(Ui.label(m, l.name()));
        TextView line = Home.stat(m, l.line());
        line.setTextSize(13);
        s.addView(line);
        s.setContentDescription(l.name() + ": " + l.line());
        if (l.chars.isEmpty()) return s;

        Stats.PathBar bar = new Stats.PathBar(m);
        bar.set(l.known, l.learning, l.chars.size());
        s.addView(bar, new LinearLayout.LayoutParams(-1, Ink.dp(8)));

        GardenView g = new GardenView(m);
        g.pick = new int[l.chars.size()];
        for (int j = 0; j < g.pick.length; j++) g.pick[j] = l.chars.get(j);
        g.set(st);
        g.tap = i -> m.lookup(PathData.items.get(i).h);
        g.setContentDescription(l.name() + "'s characters: " + l.known + " known, " + l.learning + " learning, of " + l.chars.size());
        int fold = FOLD * GardenView.columns(m.contentWidth());
        g.show(open[l.n] ? Integer.MAX_VALUE : fold);
        s.addView(g, new LinearLayout.LayoutParams(-1, -2));

        if (l.chars.size() > fold) {
            int more = l.chars.size() - fold;
            TextView t = Ui.pill(m, "", Ui.OUTLINE, null);
            t.setMinHeight(Ink.dp(36));
            t.setTextSize(14);
            t.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
            Runnable label = () -> t.setText(open[l.n] ? "Fold " + l.name() : "Show all (" + more + " more)");
            label.run();
            t.setOnClickListener(v -> {
                open[l.n] = !open[l.n];
                g.show(open[l.n] ? Integer.MAX_VALUE : fold);
                label.run();
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.gravity = Gravity.CENTER_HORIZONTAL;
            s.addView(t, tp);
        }
        return s;
    }

    /** Stats' way in, at its top: a row like home's Stats and Settings rows. */
    static View entry(Main m) {
        LinearLayout s = Ui.row(m, 8);
        s.setPadding(0, Ink.dp(12), 0, Ink.dp(12));
        s.addView(Ui.label(m, "HSK levels"), Ui.weight(1));
        s.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));
        s.setMinimumHeight(Ink.dp(48));
        Ui.press(s, Home.topRule(Ink.rule));
        s.setContentDescription("HSK levels: each level's characters and words, how many known");
        s.setOnClickListener(v -> m.openLevels());
        return s;
    }
}
