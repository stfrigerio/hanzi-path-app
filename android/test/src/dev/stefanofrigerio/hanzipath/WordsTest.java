package dev.stefanofrigerio.hanzipath;

import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Words as cards: ready once their characters are known, learnt in batches of their own,
 *  asked three ways, kept in the copy. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WordsTest {
    static final long DAY = Progress.DAY;
    final PickTest pt = new PickTest();

    void shot(Main m, String name) throws Exception { pt.shot(m, name); }

    /** The first n characters and parts of the path, known (long stability, seen yesterday). */
    static Set<String> know(Main m, int n) {
        long now = System.currentTimeMillis();
        Set<String> out = new HashSet<>();
        m.prog.cards.clear();
        for (int i = 0; i < n; i++) {
            Progress.Card k = new Progress.Card();
            k.s = 60; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 30 * DAY; k.last = now - DAY; k.due = now + 30 * DAY;
            String h = PathData.items.get(i).h;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
            out.add(h);
        }
        return out;
    }

    /** A word card seen twice (so a review may ask it any way), due now. */
    static void seen(Main m, String w) {
        long now = System.currentTimeMillis();
        Progress.Card k = new Progress.Card();
        k.s = 4; k.d = 5; k.reps = 2; k.grade = 3;
        k.first = now - 10 * DAY; k.last = now - 5 * DAY; k.due = now - 60_000;
        m.prog.cards.put(w, k);
        m.prog.putCard(w, k);
    }

    /** What can be tapped as an answer, by name: the option rows (word-choose) or the cells. */
    static View answer(Main m, String o) {
        View v = byDesc(m.root, o);
        assertNotNull("no answer " + o, v);
        return v;
    }

    static View byDesc(View v, String d) {
        if (v.hasOnClickListeners() && d.equals(v.getTag())) return v;   // an answer: tagged with what it is
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View x = byDesc(((ViewGroup) v).getChildAt(i), d);
            if (x != null) return x;
        }
        return null;
    }

    /** The name an answer goes by: its tag, the word (its spoken label says its place or its reading). */
    static String name(Quiz q, String o) {
        return o;
    }

    static void tapAnswer(Main m, Quiz q, String o) {
        answer(m, name(q, o)).performClick();
        ShadowLooper.idleMainLooper();
    }

    /** A word's big text on one line, all of it inside the view. */
    static void fits(TextView t) {
        Layout l = t.getLayout();
        assertNotNull(l);
        assertEquals("one line: " + t.getText(), 1, l.getLineCount());
        assertTrue("inside its view: " + t.getText() + " " + l.getLineWidth(0) + " > " + t.getWidth(),
                l.getLineWidth(0) <= t.getWidth() - t.getPaddingLeft() - t.getPaddingRight());
    }

    static TextView text(View v, String s) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(s)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            TextView x = text(((ViewGroup) v).getChildAt(i), s);
            if (x != null) return x;
        }
        return null;
    }

    /** The four-character word with the longest reading: the widest a word gets. */
    static String widest() {
        Words.build();
        String best = null;
        for (String[] w : Words.all)
            if (Words.chars(w[0]).length >= 4 && (best == null || w[1].length() > Words.get(best)[1].length())) best = w[0];
        return best;
    }

    /** Availability: ready only when every character is known; Learn brings a word batch on its turn. */
    @Test
    public void learn() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        long now = System.currentTimeMillis();
        Set<String> known = know(m, 60);
        List<String> fresh = Words.fresh(m.prog, -1, now);
        assertTrue("words ready: " + fresh.size(), fresh.size() >= 5);
        for (String w : fresh) for (String c : Words.chars(w)) assertTrue(w + ": " + c + " is known", known.contains(c));
        // a word with a character not known yet isn't ready; once it is, it is
        String later = null;
        for (String[] w : Words.all) {
            int unknown = 0;
            for (String c : Words.chars(w[0])) if (!known.contains(c)) unknown++;
            if (unknown == 1) { later = w[0]; break; }
        }
        assertNotNull(later);
        assertFalse(Words.ready(m.prog, later, now));
        for (String c : Words.chars(later)) if (!known.contains(c)) {
            Progress.Card k = m.prog.cards.get(PathData.items.get(0).h).copy();
            m.prog.cards.put(c, k);
            assertTrue("ready once its last character is known", Words.ready(m.prog, later, now));
            // met but not known (seen once, minutes ago): not yet
            Progress.Card y = new Progress.Card();
            y.s = 3.7; y.d = 5; y.reps = 1; y.grade = 3; y.first = now; y.last = now; y.due = now + 3 * DAY;
            m.prog.cards.put(c, y);
            assertFalse("a character only met keeps it waiting", Words.ready(m.prog, later, now));
            m.prog.cards.remove(c);
        }

        // turns: characters while it isn't the words' turn, words when it is
        m.prog.sinceWords = 0;
        assertFalse(Words.is(Words.next(m.prog, 5, now).get(0)));
        m.prog.sinceWords = Words.EVERY;
        assertTrue(Words.is(Words.next(m.prog, 5, now).get(0)));

        m.home();
        ShadowLooper.idleMainLooper();
        shot(m, "w1-home");
        assertNotNull("the learn pill says words", WalkTest.find(m.root, "Learn 5 words"));
        assertNotNull(WalkTest.find(m.root, "Next, words"));
        assertNotNull("words counted apart", WalkTest.find(m.root, "Words known"));
        assertNotNull("the path's count is characters only", WalkTest.find(m.root, "60 of " + PathData.items.size() + " met"));

        WalkTest.tap(m, "Learn 5 words");
        List<String> batch = new ArrayList<>(m.learn.items);
        assertEquals(fresh.subList(0, 5), batch);
        String w0 = batch.get(0);
        String[] w = Words.get(w0);
        assertNotNull("the word, large", text(m.root, w0));
        assertTrue("its reading", PickTest.shows(m.root, w[1].split(" ")[0]));
        assertTrue("its meaning", PickTest.shows(m.root, w[2]));
        for (String c : Words.chars(w0)) assertTrue("its character " + c + "'s meaning", PickTest.shows(m.root, PathData.get(c).en));
        assertTrue("said aloud", w0.equals(Speech.waiting) || Speech.ready);
        shot(m, "w2-learn");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        assertEquals("a word batch resets the turn", 0, m.prog.sinceWords);
        assertEquals("words count as new today", 5, m.prog.todayNew);
        Quiz q = m.quiz;
        assertEquals("word-choose", q.mode);
        shot(m, "w3-check");
        // a wrong answer, undo, then all right
        String h = q.queue.get(0);
        List<String> before = new ArrayList<>(q.options);
        assertEquals(4, new HashSet<>(before).size());
        String bad = null;
        for (String o : before) if (!o.equals(h)) bad = o;
        assertTrue("wrong answers are words", Words.is(bad));
        tapAnswer(m, q, bad);
        assertEquals(1, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{"word-choose", "1"}, PickTest.lastLog(m));
        shot(m, "w4-check-wrong");
        WalkTest.tap(m, "Undo");
        assertNull(m.prog.cards.get(h));
        assertEquals(before, q.options);
        for (int i = 0; i < 5; i++) {
            String x = q.queue.get(0);
            tapAnswer(m, q, x);
            assertEquals(3, m.prog.cards.get(x).grade);
            if (i == 0) shot(m, "w5-check-right");
            WalkTest.tap(m, i < 4 ? "Next" : "Finish");
        }
        for (String x : batch) assertNotNull(m.prog.cards.get(x));
        // after the word batch, characters again
        m.home();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Learn next 5"));
        assertNotNull(WalkTest.find(m.root, "Next on the path"));
        assertFalse(Words.is(Words.next(m.prog, 5, now).get(0)));
        shot(m, "w6-home-after");
    }

    /** One forced question of a kind on a word: wrong (again), undo (the same four), right. */
    void round(Main m, String kind, String h, String prefix) throws Exception {
        Quiz q = PickTest.forced(m, Arrays.asList(h), kind);
        assertEquals(kind, q.mode);
        assertNull("no stroke square for a word", q.hv);
        List<String> before = new ArrayList<>(q.options);
        assertEquals(4, new HashSet<>(before).size());
        assertTrue(before.contains(h));
        for (String o : before) assertTrue(o + " is a word", Words.is(o));
        if (kind.equals("word-listen")) {
            assertFalse("no reading before the tap", PickTest.shows(m.root, Words.get(h)[1]));
            Set<String> heard = new HashSet<>();
            for (String o : before) heard.add(Quiz.py(o));
            assertEquals("four different sounds", 4, heard.size());
        } else if (kind.equals("word-pick")) {
            assertTrue(PickTest.shows(m.root, Words.get(h)[2]));
        }
        // the cells and the big word: four characters fit
        if (!kind.equals("word-choose")) for (String o : before) fits((TextView) answer(m, o));
        else fits(text(m.root, h));
        shot(m, prefix);
        String bad = null;
        for (String o : before) if (!o.equals(h)) bad = o;
        tapAnswer(m, q, bad);
        assertEquals(1, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{kind, "1"}, PickTest.lastLog(m));
        assertTrue(PickTest.shows(m.root, "NOT THAT ONE"));
        assertTrue("the answer in full", PickTest.shows(m.root, Words.get(h)[2]));
        shot(m, prefix + "-wrong");
        WalkTest.tap(m, "Undo");
        assertEquals("undo restores the card", 2, m.prog.cards.get(h).reps);
        assertEquals(kind, q.mode);
        assertEquals(before, q.options);
        q.shown -= Quiz.SLOW + 1000;
        tapAnswer(m, q, h);
        assertEquals("right but slow: unsure", 2, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{kind, "2"}, PickTest.lastLog(m));
        shot(m, prefix + "-right");
        WalkTest.tap(m, "Undo");
        seen(m, h);
    }

    /** Reviews: words due come in, asked three ways, wrong answers close; copy and load keep them. */
    @Test
    public void review() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        long now = System.currentTimeMillis();
        know(m, 120);
        List<String> fresh = Words.fresh(m.prog, -1, now);
        // a word with others sharing a character, among the ready ones
        String h = null;
        for (String w : fresh) {
            int sib = 0;
            for (String c : Words.chars(w)) for (String x : Words.with(c)) if (!x.equals(w) && fresh.contains(x)) sib++;
            if (sib >= 3) { h = w; break; }
        }
        assertNotNull(h);
        seen(m, h);
        assertTrue("due includes words", m.prog.due(now).contains(h));
        assertTrue("the reminder sees them too", Progress.peek(m, now).due.contains(h));
        // wrong answers share a character with it when they can
        Quiz probe = new Quiz(m, Arrays.asList(h), "review");
        for (int i = 0; i < 20; i++) {
            List<String> o = probe.options(h, "word-choose");
            int share = 0;
            for (String x : o) if (!x.equals(h)) for (String c : Words.chars(h)) if (x.contains(c)) { share++; break; }
            assertTrue("close wrong answers: " + o, share >= 2);
        }
        // which kind: choose while new, then any of the three (listen only with the voice)
        Set<String> got = new HashSet<>();
        for (int i = 0; i < 300; i++) got.add(probe.kindFor(h));
        assertEquals(new HashSet<>(Arrays.asList("word-choose", "word-pick", "word-listen")), got);
        assertEquals("word-choose", new Quiz(m, Arrays.asList(h), "check").kindFor(h));
        m.prog.say = false;
        for (int i = 0; i < 100; i++) assertNotEquals("word-listen", probe.kindFor(h));
        m.prog.say = true;

        round(m, "word-choose", h, "w7-choose");
        round(m, "word-pick", h, "w8-pick");
        round(m, "word-listen", h, "w9-listen");
        String wide = widest();
        seen(m, wide);
        round(m, "word-choose", wide, "w10-choose-4");
        round(m, "word-pick", wide, "w11-pick-4");
        m.learn = new Learn(m, Arrays.asList(wide), false, null);
        m.learn.show();
        ShadowLooper.idleMainLooper();
        fits(text(m.root, wide));
        shot(m, "w12-learn-4");

        // a real review round takes the word with the characters, logged by its kind
        m.startReview();
        ShadowLooper.idleMainLooper();
        assertTrue(m.quiz.queue.contains(h));

        // the garden stays characters: its count doesn't take the words
        m.home();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "120 of " + PathData.items.size() + " met"));
        assertNotNull(WalkTest.find(m.root, "Due now"));

        // copy and load: the word cards and the turn come back
        m.prog.sinceWords = 1;
        String copy = m.prog.export();
        assertEquals(m.prog.cards.size(), Progress.cardsIn(copy));
        Progress.Card was = m.prog.cards.get(h).copy();
        m.prog.cards.clear();
        m.prog.sinceWords = 2;
        m.prog.replace(copy);
        assertNotNull(m.prog.cards.get(h));
        assertEquals(was.s, m.prog.cards.get(h).s, 1e-9);
        assertEquals(was.reps, m.prog.cards.get(h).reps);
        assertNotNull(m.prog.cards.get(wide));
        assertEquals(1, m.prog.sinceWords);
        Progress again = new Progress(m);
        assertNotNull("in SQLite", again.cards.get(h));
        assertEquals(1, again.sinceWords);
        assertTrue(again.due(now).contains(h));
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        long now = System.currentTimeMillis();
        know(m, 60);
        m.prog.sinceWords = Words.EVERY;
        m.home();
        ShadowLooper.idleMainLooper();
        shot(m, "wn1-home");
        WalkTest.tap(m, "Learn 5 words");
        shot(m, "wn2-learn");
        String h = Words.fresh(m.prog, 1, now).get(0);
        seen(m, h);
        round(m, "word-choose", h, "wn3-choose");
        round(m, "word-pick", h, "wn4-pick");
        round(m, "word-listen", h, "wn5-listen");
        String wide = widest();
        seen(m, wide);
        round(m, "word-choose", wide, "wn6-choose-4");
        round(m, "word-pick", wide, "wn7-pick-4");
        m.learn = new Learn(m, Arrays.asList(wide), false, null);
        m.learn.show();
        ShadowLooper.idleMainLooper();
        fits(text(m.root, wide));
        shot(m, "wn8-learn-4");
    }
}
