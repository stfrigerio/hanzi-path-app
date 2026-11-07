package dev.stefanofrigerio.hanzipath;

import java.text.Normalizer;
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
 * A word question's wrong answers, in order of closeness (Quiz.wordPool):
 *
 *   1. words sharing a character with it that the learner can tell apart (met, or ready: every
 *      character known),
 *   2. words of the same field, met or ready: the same last character (-子, -人, -机) or a
 *      content word shared by the meanings ("to lose" and "lose due to carelessness" share
 *      lose; the stop-words, "to", "a", "of", and the words in too many meanings, "person",
 *      left out),
 *   3. the same two not met yet,
 *   4. its neighbours among the path's words, met or ready first;
 *   5. for pick, any word as long as it, met or ready first.
 *
 * For pick (the reading and meaning asked, four words to tell apart), the ones as long as
 * it first, and among them the ones that sound like it (the same first syllable, or the
 * same tones), so the reading alone doesn't give it away.
 *
 * The meanings' content words (field) also say when two meanings are the same: one's words
 * all in the other's (Quiz.pickFrom keeps such a pair apart, as it does equal meanings).
 */
final class WordChoices {
    static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "to a an the of and or in on at for with by from as be is are was it its into onto out up off "
            + "over under about one ones one's oneself sb sth sb's sth's someone something etc also used "
            + "not no very much more most make do have get give take put go come let can may this that these "
            + "those which who what all any some each other such than then so just only very").split(" ")));
    static final int COMMON = 40;   // a content word in more meanings than this says nothing about the field

    static final Map<String, List<String>> field = new HashMap<>();   // lose → 失去, 遗失, …
    static final Map<String, Set<String>> keys = new HashMap<>();      // 遗失 → {lose, carelessness}
    static int built = -1;

    private WordChoices() {}

    static synchronized void build() {
        Words.build();
        if (built == Words.built) return;
        field.clear(); keys.clear();
        for (String[] w : Words.all) {
            Set<String> k = words(w[2]);
            keys.put(w[0], k);
            for (String s : k) {
                List<String> l = field.get(s);
                if (l == null) field.put(s, l = new ArrayList<>());
                l.add(w[0]);
            }
        }
        for (Set<String> k : keys.values()) k.removeIf(s -> field.get(s).size() > COMMON);
        built = Words.built;
    }

    /** The content words of a meaning: lower case, a plural's s dropped, stop-words out. */
    static Set<String> words(String en) {
        Set<String> out = new HashSet<>();
        for (String s : en.toLowerCase().split("[^a-z']+")) {
            if (s.startsWith("'")) s = s.substring(1);
            if (s.endsWith("'s")) s = s.substring(0, s.length() - 2);
            if (s.length() < 3 || STOP.contains(s)) continue;
            if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.substring(0, s.length() - 1);
            out.add(s);
        }
        return out;
    }

    static Set<String> keysOf(String w) {
        build();
        Set<String> k = keys.get(w);
        return k == null ? Collections.<String>emptySet() : k;
    }

    /** The same meaning, or one inside the other (to lose; lose, lose due to carelessness). */
    static boolean sameMeaning(String a, String b) {
        if (Quiz.en(a).equals(Quiz.en(b))) return true;
        Set<String> x = keysOf(a), y = keysOf(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.containsAll(y) || y.containsAll(x);
    }

    /** Sharing a character with it. */
    static boolean shares(String h, String w) {
        for (String c : Words.chars(h)) if (w.contains(c)) return true;
        return false;
    }

    /** Of the same field: the same last character, or a content word of the meaning. */
    static boolean sameField(String h, String w) {
        String[] a = Words.chars(h), b = Words.chars(w);
        if (a[a.length - 1].equals(b[b.length - 1])) return true;
        for (String s : keysOf(h)) if (keysOf(w).contains(s)) return true;
        return false;
    }

    /** Close to it: sharing a character or a field. */
    static boolean related(String h, String w) { return shares(h, w) || sameField(h, w); }

    /** A reading without its tones: shǒu dū → shou du. */
    static String[] bare(String py) {
        String s = Normalizer.normalize(py.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.trim().split("\\s+");
    }

    static String tones(String py) {
        StringBuilder b = new StringBuilder();
        for (String s : py.trim().split("\\s+")) b.append(Ink.toneOf(s));
        return b.toString();
    }

    /** Sounds like it: the same first syllable, or the same tones. */
    static boolean soundsLike(String h, String w) {
        String a = Quiz.py(h), b = Quiz.py(w);
        if (a.isEmpty() || b.isEmpty()) return false;
        return bare(a)[0].equals(bare(b)[0]) || tones(a).equals(tones(b));
    }

    /** The wrong answers for word h, the closest first. */
    static List<String> pool(Progress p, String h, String mode, Random rnd, long now) {
        build();
        Set<String> seen = new HashSet<>();
        seen.add(h);
        List<String> sib = new ArrayList<>(), fld = new ArrayList<>(), near = new ArrayList<>();
        for (String c : Words.chars(h)) for (String w : Words.with(c)) if (seen.add(w)) sib.add(w);
        String[] hc = Words.chars(h);   // (the same last character: already among sib, sharing it)
        for (String s : keysOf(h)) for (String w : field.get(s)) if (seen.add(w)) fld.add(w);
        int at = Words.pos.get(h);
        for (int d = -40; d <= 40; d++) {
            int i = at + d;
            if (d != 0 && i >= 0 && i < Words.all.size() && seen.add(Words.all.get(i)[0])) near.add(Words.all.get(i)[0]);
        }
        Collections.shuffle(sib, rnd);
        Collections.shuffle(fld, rnd);
        Collections.shuffle(near, rnd);
        List<String> far = new ArrayList<>();   // pick: any word as long as it, the last resort
        if (mode.endsWith("pick")) for (String[] w : Words.all)
            if (Words.chars(w[0]).length == hc.length && seen.add(w[0])) far.add(w[0]);
        Collections.shuffle(far, rnd);
        List<List<String>> tier = new ArrayList<>();
        for (int i = 0; i < 8; i++) tier.add(new ArrayList<String>());
        for (String s : sib) tier.get(known(p, s, now) ? 0 : 2).add(s);
        for (String s : fld) tier.get(known(p, s, now) ? 1 : 3).add(s);
        for (String s : near) tier.get(known(p, s, now) ? 4 : 5).add(s);
        for (String s : far) tier.get(known(p, s, now) ? 6 : 7).add(s);
        List<String> out = new ArrayList<>();
        for (List<String> l : tier) out.addAll(l);
        if (mode.endsWith("pick")) {
            // as long as it first, then sounding like it; the closeness kept within each (a stable sort)
            final int n = hc.length;
            final Map<String, Integer> rank = new HashMap<>();
            for (int i = 0; i < out.size(); i++) rank.put(out.get(i), i);
            final Map<String, Integer> key = new HashMap<>();
            for (String s : out) key.put(s, (Words.chars(s).length == n ? 0 : 2) + (soundsLike(h, s) ? 0 : 1));
            // within the same length, the tier before the sound (a close sibling beats a stranger that rhymes)
            final Map<String, Integer> tierOf = new HashMap<>();
            for (int t = 0; t < tier.size(); t++) for (String s : tier.get(t)) tierOf.put(s, t);
            Collections.sort(out, (a, b) -> {
                int c = Integer.compare(key.get(a) / 2, key.get(b) / 2);
                if (c == 0) c = Integer.compare(tierOf.get(a), tierOf.get(b));
                if (c == 0) c = Integer.compare(key.get(a) % 2, key.get(b) % 2);
                return c != 0 ? c : Integer.compare(rank.get(a), rank.get(b));
            });
        }
        return out;
    }

    /** One the learner can tell apart: met, or ready. */
    static boolean known(Progress p, String w, long now) {
        return p.cards.containsKey(w) || Words.ready(p, w, now);
    }
}
