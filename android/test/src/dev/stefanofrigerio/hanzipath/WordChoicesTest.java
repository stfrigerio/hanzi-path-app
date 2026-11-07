package dev.stefanofrigerio.hanzipath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** A word question's wrong answers: close calls (a shared character or meaning field), the
 *  same length and a like sound for pick, never two of one meaning, the same four on Undo. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WordChoicesTest {
    static final String OUT = System.getProperty("shots");
    static final int KNOWN = 800;   // characters known: plenty of words ready
    static final int[] LEVELS = {150, 300, 800};   // a beginner's few ready words, then more, then plenty

    /** The pool as it was before (sharing a character, then neighbours; met or ready first). */
    static List<String> oldPool(Progress p, String h, Random rnd) {
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
            for (String s : l) (p.cards.containsKey(s) || Words.ready(p, s, now) ? pool : rest).add(s);
        pool.addAll(rest);
        return pool;
    }

    /** Ready words across the path: a few hundred at most. */
    static List<String> sample(Main m) {
        List<String> fresh = Words.fresh(m.prog, -1, System.currentTimeMillis()), out = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i += Math.max(1, fresh.size() / 300)) out.add(fresh.get(i));
        return out;
    }

    static int lenOf(String w) { return Words.chars(w).length; }

    static String show(String h, List<String> o) {
        StringBuilder b = new StringBuilder();
        for (String x : o) b.append(b.length() == 0 ? "" : " | ").append(x).append(x.equals(h) ? "*" : "")
                .append(" ").append(Quiz.py(x)).append(" (").append(Quiz.en(x)).append(")");
        return b.toString();
    }

    /** Before and after, with few words ready and with plenty: the rates, and examples, in
     *  shots/word-choices.txt. */
    @Test
    public void closer() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        StringBuilder out = new StringBuilder();
        for (int k : LEVELS) out.append(measure(m, k, k == KNOWN)).append("\n");
        if (OUT != null) try (FileOutputStream o = new FileOutputStream(new File(OUT, "word-choices.txt"))) {
            o.write(out.toString().getBytes("UTF-8"));
        }
        System.out.println(out);
    }

    String measure(Main m, int known, boolean examples) throws Exception {
        WordsTest.know(m, known);
        long now = System.currentTimeMillis();
        List<String> ws = sample(m);
        assertTrue("words ready: " + ws.size(), ws.size() >= 30);
        int oldRel = 0, newRel = 0, oldKnownRel = 0, newKnownRel = 0, all = 0, newTwo = 0;
        int pickAll = 0, pickLen = 0, pickSound = 0, oldPickLen = 0, oldPickSound = 0;
        StringBuilder ex = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) {
            String h = ws.get(i);
            Quiz a = new Quiz(m, Arrays.asList(h), "review", new Random(1000 + i));
            Quiz b = new Quiz(m, Arrays.asList(h), "review", new Random(1000 + i));
            List<String> before = a.pickFrom(h, "word-choose", oldPool(m.prog, h, a.rnd));
            List<String> after = b.options(h, "word-choose");
            assertEquals(4, after.size());
            assertTrue(after.contains(h));
            int rel = 0;
            for (String x : before) if (!x.equals(h)) {
                all++;
                if (WordChoices.related(h, x)) { oldRel++; if (WordChoices.known(m.prog, x, now)) oldKnownRel++; }
            }
            for (String x : after) if (!x.equals(h) && WordChoices.related(h, x)) {
                newRel++; rel++;
                if (WordChoices.known(m.prog, x, now)) newKnownRel++;
            }
            if (rel >= 2) newTwo++;
            noTwins(h, after, false);
            // pick: as long as it, sounding like it
            Quiz c = new Quiz(m, Arrays.asList(h), "review", new Random(2000 + i));
            Quiz d = new Quiz(m, Arrays.asList(h), "review", new Random(2000 + i));
            List<String> pick = c.options(h, "word-pick"), oldPick = d.pickFrom(h, "word-pick", oldPool(m.prog, h, d.rnd));
            noTwins(h, pick, false);
            for (String x : pick) if (!x.equals(h)) {
                pickAll++;
                if (lenOf(x) == lenOf(h)) pickLen++;
                if (WordChoices.soundsLike(h, x)) pickSound++;
            }
            for (String x : oldPick) if (!x.equals(h)) {
                if (lenOf(x) == lenOf(h)) oldPickLen++;
                if (WordChoices.soundsLike(h, x)) oldPickSound++;
            }
            for (String x : pick) assertEquals("pick: as long as " + h + ": " + pick, lenOf(h), lenOf(x));
            // listen: four sounds
            Quiz e = new Quiz(m, Arrays.asList(h), "review", new Random(3000 + i));
            noTwins(h, e.options(h, "word-listen"), true);
            // the same draw for the same seed (what Undo relies on, beside keeping the list)
            assertEquals(after, new Quiz(m, Arrays.asList(h), "review", new Random(1000 + i)).options(h, "word-choose"));
            if (examples && i % (ws.size() / 10) == 0)
                ex.append(h).append("\n  before: ").append(show(h, before)).append("\n  choose: ").append(show(h, after))
                        .append("\n  pick:   ").append(show(h, pick)).append("\n");
        }
        double was = oldRel / (double) all, is = newRel / (double) all;
        String rates = String.format("%d words, %d known characters%n"
                        + "related wrong answers (a shared character or field): before %.3f, after %.3f%n"
                        + "related and met or ready: before %.3f, after %.3f%n"
                        + "questions with at least 2 of 3 related: after %.3f%n"
                        + "pick, as long as it: before %.3f, after %.3f; sounding like it: before %.3f, after %.3f%n",
                ws.size(), known, was, is, oldKnownRel / (double) all, newKnownRel / (double) all,
                newTwo / (double) ws.size(), oldPick(oldPickLen, pickAll), pickLen / (double) pickAll,
                oldPick(oldPickSound, pickAll), pickSound / (double) pickAll);
        assertTrue("2 of 3 wrong answers related: " + rates, is >= 2 / 3.0);
        assertTrue("closer than before: " + rates, is > was);
        assertTrue("most questions have two close ones: " + rates, newTwo >= 0.8 * ws.size());
        assertTrue("met or ready, and close: " + rates, newKnownRel / (double) all >= 2 / 3.0);
        assertTrue("no further from what the learner knows than before: " + rates, newKnownRel >= oldKnownRel);
        assertTrue("pick sounds more alike than before: " + rates, pickSound > oldPickSound);
        return rates + ex;
    }

    static double oldPick(int n, int all) { return n / (double) all; }

    /** Four different words, no two meaning the same (nor one inside the other); to listen,
     *  four different readings. */
    static void noTwins(String h, List<String> o, boolean heard) {
        assertEquals("four: " + o, 4, new HashSet<>(o).size());
        assertTrue(o.contains(h));
        Set<String> en = new HashSet<>(), py = new HashSet<>();
        for (String x : o) {
            assertTrue(x + " is a word", Words.is(x));
            assertTrue("one meaning once: " + o, en.add(Quiz.en(x)));
            if (heard) assertTrue("one reading once: " + o, py.add(Quiz.py(x)));
        }
        for (String x : o) for (String y : o)
            if (!x.equals(y)) assertFalse("the same meaning: " + x + ", " + y, WordChoices.sameMeaning(x, y));
    }

    /** A wrong tap, then Undo: the same question, the same four, in the same places. */
    @Test
    public void undo() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        WordsTest.know(m, KNOWN);
        List<String> ws = sample(m);
        for (String kind : Arrays.asList("word-pick", "word-choose")) {
            String h = ws.get(kind.equals("word-pick") ? 7 : 11);
            WordsTest.seen(m, h);
            Quiz q = PickTest.forced(m, Arrays.asList(h), kind);
            List<String> before = new ArrayList<>(q.options);
            String bad = null;
            for (String o : before) if (!o.equals(h)) bad = o;
            WordsTest.tapAnswer(m, q, bad);
            assertEquals(1, m.prog.cards.get(h).grade);
            WalkTest.tap(m, "Undo");
            assertEquals(kind, q.mode);
            assertEquals("undo keeps the four", before, q.options);
            for (String o : before) assertNotNull(o + " still there", WordsTest.byDesc(m.root, o));
            if (kind.equals("word-pick")) new PickTest().shot(m, "wc1-pick-close");
        }
    }

    /** The fields: stop-words out, a plural one with its singular, one meaning inside another the same. */
    @Test
    public void fields() {
        assertEquals(new HashSet<>(Arrays.asList("lose", "due", "carelessness")), WordChoices.words("lose, lose due to carelessness"));
        assertEquals(new HashSet<>(Arrays.asList("asset", "property")), WordChoices.words("property, assets"));
        assertTrue(WordChoices.words("to kill a person").contains("kill"));
        assertFalse(WordChoices.words("to kill a person").contains("to"));
        assertEquals("shou", WordChoices.bare("shǒu dū")[0]);
        assertEquals("31", WordChoices.tones("shǒu dū"));
    }
}
