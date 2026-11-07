package dev.stefanofrigerio.hanzipath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Words as cards of their own. The path's words (each item's, Item.words: HSK's, or
 * CC-CEDICT's where HSK has none) in path order, each a card keyed by the word itself in
 * Progress.cards, beside the characters'.
 *
 * A word is ready once every character in it is known (Progress.KNOWN, as the garden's
 * blue): then it reinforces what you know instead of adding a second new thing on top of
 * a character met minutes ago. Learn brings ready words as a batch of their own, one for
 * every EVERY character batches (and only words once the path is walked).
 *
 * Which ready words first (use): the most useful, not the path's order, where the first
 * ready are counting compounds (一二, 十二, 二十). HSK's words before CC-CEDICT's, the lower
 * level first; within a level the commoner first (by the path's order, which is Jun Da's
 * rank of the word's rarest character); words made only of numbers (NUMBERS) last. The
 * order is fixed, so a word keeps its place from one session to the next: only newly ready
 * words come in among the others.
 */
final class Words {
    static final int EVERY = 2;   // character batches between two word batches
    static final int MIN = 3;     // a word batch waits for this many ready words (or a batch, if smaller)

    static final String NUMBERS = "一二三四五六七八九十百千万零两";

    static final List<String[]> all = new ArrayList<>();                 // [word, pinyin, meaning, level], path order
    static final List<String[]> use = new ArrayList<>();                 // the same, most useful first
    static final Map<String, String[]> by = new HashMap<>();
    static final Map<String, Integer> pos = new HashMap<>();
    static final Map<String, List<String>> withChar = new HashMap<>();   // 人 → 大人, 人口, …
    static int built = -1;

    private Words() {}

    static synchronized void build() {
        if (built == PathData.items.size()) return;
        all.clear(); use.clear(); by.clear(); pos.clear(); withChar.clear();
        for (Item it : PathData.items)
            for (String[] w : it.words) {
                String h = w[0];
                // a one-character word is the character's own card
                if (h.codePointCount(0, h.length()) < 2 || PathData.get(h) != null || by.containsKey(h)) continue;
                pos.put(h, all.size());
                all.add(w);
                by.put(h, w);
                for (String c : chars(h)) {
                    List<String> l = withChar.get(c);
                    if (l == null) withChar.put(c, l = new ArrayList<>());
                    l.add(h);
                }
            }
        use.addAll(all);
        Collections.sort(use, (a, b) -> {
            int c = Boolean.compare(number(a[0]), number(b[0]));
            if (c == 0) c = Integer.compare(rank(a), rank(b));
            return c != 0 ? c : Integer.compare(pos.get(a[0]), pos.get(b[0]));
        });
        built = PathData.items.size();
    }

    /** Made only of numbers (十二, 一百, 两万): last, there's little in them to learn. */
    static boolean number(String w) {
        for (String c : chars(w)) if (NUMBERS.indexOf(c) < 0) return false;
        return true;
    }

    /** HSK 1 … 6 first, CC-CEDICT's (level 0) after them. */
    static int rank(String[] w) {
        int l = Integer.parseInt(w[3]);
        return l == 0 ? Integer.MAX_VALUE : l;
    }

    static String[] get(String h) { build(); return by.get(h); }

    static boolean is(String h) { return get(h) != null; }

    /** A key that's a card of the path: a character or part, or one of its words. */
    static boolean onPath(String h) { return PathData.get(h) != null || is(h); }

    static String[] chars(String w) {
        String[] out = new String[w.codePointCount(0, w.length())];
        for (int i = 0, at = 0; i < out.length; i++) {
            int n = Character.charCount(w.codePointAt(at));
            out[i] = w.substring(at, at + n);
            at += n;
        }
        return out;
    }

    static List<String> with(String c) {
        build();
        List<String> l = withChar.get(c);
        return l == null ? new ArrayList<String>() : l;
    }

    /** "HSK 3", or "" for a word from CC-CEDICT. */
    static String level(String[] w) { return "0".equals(w[3]) ? "" : "HSK " + w[3]; }

    /** Every character in it known. */
    static boolean ready(Progress p, String w, long now) {
        for (String c : chars(w)) if (p.strength(c, now) < Progress.KNOWN) return false;
        return true;
    }

    /** Ready words not met yet, the most useful first (use): n at most (n < 0: all). */
    static List<String> fresh(Progress p, int n, long now) {
        build();
        List<String> out = new ArrayList<>();
        for (String[] w : use) {
            if (n >= 0 && out.size() >= n) break;
            if (!p.cards.containsKey(w[0]) && ready(p, w[0], now)) out.add(w[0]);
        }
        return out;
    }

    /** What Learn brings next, n of them: characters, or words when it's their turn (and
     *  enough are ready), or words only once the path is walked. */
    static List<String> next(Progress p, int n, long now) {
        List<String> chars = p.nextNew(n);
        if (n <= 0) return chars;
        List<String> w = fresh(p, n, now);
        if (!w.isEmpty() && (chars.isEmpty() || p.sinceWords >= EVERY && w.size() >= Math.min(MIN, n))) return w;
        return chars;
    }

    /** A batch learnt: the count towards the next word batch, kept. */
    static void learnt(Progress p, boolean words) {
        p.sinceWords = words ? 0 : p.sinceWords + 1;
        p.putMeta("words", Progress.obj("since", p.sinceWords).toString());
    }
}
