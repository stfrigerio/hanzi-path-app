package dev.stefanofrigerio.hanzipath;

import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Moving forward: every round comes in a random order (the set the round's, only the order
 * shuffled), a miss never straight after itself; practice draws twenty from the weakest
 * forty and grades them through FSRS (practice-*, undone like a review); a round with no
 * misses ends on "Learn next N", which starts a batch; and New per day is soft on a good
 * day (ten answers today, nine in ten right): home's Learn reads "Learn 5 more". Renders
 * are f*.png, light and dark.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class ForwardTest {
    final HomeTest shots = new HomeTest();

    /** The rounds: the check, reviews and practice each shuffled, over several seeds. */
    @Test
    public void shuffled() throws Exception {
        Main m = RoundTest.open();
        RoundTest.due(m, 50);
        long now = System.currentTimeMillis();
        List<String> due = m.prog.due(now);
        assertEquals(50, due.size());
        for (String kind : Arrays.asList("review", "check", "practice")) {
            List<String> items = due.subList(0, 10);
            Set<String> firsts = new HashSet<>();
            for (int seed = 0; seed < 8; seed++) {
                Quiz q = new Quiz(m, items, kind, new Random(seed));
                assertEquals(kind + ": the same set", new HashSet<>(items), new HashSet<>(q.queue));
                assertEquals(10, q.queue.size());
                firsts.add(q.queue.get(0));
            }
            assertTrue(kind + ": the first item differs over the seeds " + firsts, firsts.size() > 1);
        }
        // a review round from home: still the 40 most due, in another order
        m.startReview();
        ShadowLooper.idleMainLooper();
        assertEquals("review", m.quiz.kind);
        assertEquals("the round's set is the most due", new HashSet<>(due.subList(0, 40)), new HashSet<>(m.quiz.queue));
        assertNotEquals("but not in due order", due.subList(0, 40), m.quiz.queue);
    }

    /** Every answer a miss: a missed item comes back, never straight after itself while
     *  anything else is left. */
    @Test
    public void missNotRepeated() throws Exception {
        Main m = RoundTest.open();
        RoundTest.due(m, 6);
        for (int seed = 0; seed < 4; seed++) {
            Quiz q = new Quiz(m, m.prog.due(System.currentTimeMillis()), "review", new Random(seed));
            m.quiz = q;
            q.show();
            ShadowLooper.idleMainLooper();
            String prev = null;
            int asked = 0;
            while (m.screen.equals("quiz")) {
                String h = q.queue.get(0);
                if (prev != null && q.queue.size() > 1) assertNotEquals("not straight after itself", prev, h);
                // with others left behind it, the one just missed isn't next
                q.pick(miss(q));
                ShadowLooper.idleMainLooper();
                q.next();
                ShadowLooper.idleMainLooper();
                if (m.screen.equals("quiz") && q.queue.size() > 1) assertNotEquals(h, q.queue.get(0));
                prev = h;
                asked++;
            }
            assertEquals("each three times", 18, asked);
            assertEquals("done", m.screen);
            RoundTest.due(m, 6);   // due again for the next seed, its misses forgotten (no "Tell them apart")
            m.prog.getWritableDatabase().delete("reviews", null, null);
        }
    }

    /** A wrong answer to the open question, whatever its kind (a tone too). */
    static String miss(Quiz q) {
        for (String o : q.options) if (!q.right(o, q.queue.get(0))) return o;
        throw new AssertionError("no wrong answer");
    }

    /** Practice's set: twenty of the weakest forty, a different twenty from round to round. */
    @Test
    public void practiceVaries() throws Exception {
        Main m = RoundTest.open();
        seedMet(m, 60);
        long now = System.currentTimeMillis();
        Set<String> pool = new HashSet<>(m.prog.weakest(Progress.PRACTICE_POOL, now));
        assertEquals(40, pool.size());
        Set<Set<String>> sets = new HashSet<>();
        for (int seed = 0; seed < 6; seed++) {
            List<String> s = m.prog.practiceSet(now, new Random(seed));
            assertEquals(20, s.size());
            assertEquals("no item twice", 20, new HashSet<>(s).size());
            assertTrue("drawn from the weakest 40", pool.containsAll(s));
            sets.add(new HashSet<>(s));
        }
        assertTrue("the sets vary", sets.size() > 1);
        // home's Practice starts one
        m.prog.introDone = true;
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Practice 20");
        assertEquals("practice", m.quiz.kind);
        assertEquals(20, m.quiz.queue.size());
        assertTrue(pool.containsAll(m.quiz.queue));
    }

    /** Practice grades: right raises stability and moves due, wrong lapses; both logged as
     *  practice-*, counted as graded (Stats), and Undo takes the grade back. */
    @Test
    public void practiceGrades() throws Exception {
        Main m = RoundTest.open();
        seedMet(m, 30);
        String h = PathData.items.get(3).h;
        Progress.Card before = m.prog.cards.get(h).copy();
        long[] totals = m.prog.reviewTotals();

        Quiz q = new Quiz(m, Arrays.asList(h), "practice", new Random(1));
        m.quiz = q;
        q.show();
        ShadowLooper.idleMainLooper();
        q.pick(h);   // right and quick: knew it
        ShadowLooper.idleMainLooper();
        Progress.Card c = m.prog.cards.get(h);
        assertEquals(3, c.grade);
        assertTrue("stability up: " + before.s + " → " + c.s, c.s > before.s);
        assertEquals(before.reps + 1, c.reps);
        assertTrue("due moved on", c.due > System.currentTimeMillis() + Progress.DAY);
        assertEquals(1, PracticeTest.logged(m, "mode = 'practice-" + q.mode + "' AND grade = 3 AND s > 0 AND h = '" + h + "'"));
        assertEquals("counted in Stats", totals[0] + 1, m.prog.reviewTotals()[0]);
        assertEquals(1, m.prog.reviewsSince(0).size());
        assertEquals(1, m.prog.todayCount("practice"));

        // Undo: the card as it was, the row gone
        q.undo();
        ShadowLooper.idleMainLooper();
        assertEquals(PracticeTest.snapshot(m.prog).get(h), card(before));
        assertEquals(0, PracticeTest.logged(m, "mode LIKE 'practice-%'"));
        assertEquals(0, m.prog.todayCount("practice"));

        // a miss: a lapse, back in minutes
        q.pick(miss(q));
        ShadowLooper.idleMainLooper();
        c = m.prog.cards.get(h);
        assertEquals(before.lapses + 1, c.lapses);
        assertTrue("stability down", c.s < before.s);
        assertTrue("due in minutes", c.due <= System.currentTimeMillis() + Progress.RELEARN);
        assertEquals(1, PracticeTest.logged(m, "mode LIKE 'practice-%' AND grade = 1"));
        // in the database too
        assertEquals(card(c), PracticeTest.snapshot(new Progress(m)).get(h));
        // and a sentence read is still not a grade
        m.prog.read("我是人", 1000);
        assertEquals(1, m.prog.reviewsSince(0).size());
    }

    static String card(Progress.Card c) {
        return c.s + " " + c.d + " " + c.reps + " " + c.lapses + " " + c.last + " " + c.due + " " + c.grade;
    }

    /** n items met, none due, weaker towards the start. */
    static void seedMet(Main m, int n) {
        long now = System.currentTimeMillis();
        m.prog.cards.clear();
        for (int i = 0; i < n; i++) {
            Progress.Card k = new Progress.Card();
            k.s = 2 + i; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 30 * Progress.DAY; k.last = now - Progress.DAY; k.due = now + 2 * Progress.DAY;
            String h = PathData.items.get(i).h;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
        }
    }

    @Test
    public void perfect() throws Exception { perfect("f"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void perfectNight() throws Exception { perfect("fn"); }

    /** The first batch, its check all right: the done screen's main way on is Learn next 5,
     *  and it starts the next batch; a round with a miss doesn't offer it. Then the soft cap. */
    void perfect(String prefix) throws Exception {
        Main m = RoundTest.open();
        m.prog.introDone = true;
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Learn next 5");
        List<String> first = new ArrayList<>(m.learn.items);
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        RoundTest.answer(m);
        assertNotNull(WalkTest.find(m.root, "好"));
        TextView on = WalkTest.find(m.root, "Learn next 5");
        assertNotNull("a perfect round offers the next batch", on);
        assertEquals("solid: the main way on", Ink.paper, on.getCurrentTextColor());
        assertNotNull(WalkTest.find(m.root, "Back to the path"));
        assertNotNull(WalkTest.find(m.root, "Undo the last"));
        shots.shot(m, prefix + "1-done-perfect");
        WalkTest.tap(m, "Learn next 5");
        assertEquals("learn", m.screen);
        assertEquals(5, m.learn.items.size());
        for (String h : m.learn.items) assertFalse("the next batch, not the last: " + h, first.contains(h));

        // its check with a miss: no Learn next
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        Quiz q = m.quiz;
        q.pick(miss(q));
        ShadowLooper.idleMainLooper();
        q.next();
        ShadowLooper.idleMainLooper();
        RoundTest.answer(m);
        assertNull("a miss: no Learn next", WalkTest.find(m.root, "Learn next"));
        assertNotNull(WalkTest.find(m.root, "Back to the path"));
        WalkTest.tap(m, "Back to the path");

        // the soft cap: today at New per day
        Progress p = m.prog;
        p.learnt(p.perDay - p.newToday());
        assertEquals(0, p.newLeftToday());
        p.getWritableDatabase().delete("reviews", null, null);
        m.home();
        ShadowLooper.idleMainLooper();
        TextView lb = WalkTest.find(m.root, "Enough new for today");
        assertNotNull("no answers today: the cap holds", lb);
        assertFalse(lb.isEnabled());

        // ten answers, nine right: a good day
        answers(p, 9, 1);
        m.home();
        ShadowLooper.idleMainLooper();
        lb = WalkTest.find(m.root, "Learn 5 more");
        assertNotNull("90% right over 10: one batch more", lb);
        assertTrue(lb.isEnabled());
        assertEquals("outlined", Ink.ink, lb.getCurrentTextColor());
        assertNull(WalkTest.find(m.root, "Enough new for today"));
        shots.shot(m, prefix + "2-home-more");

        // it learns past New per day, counted toward today's
        WalkTest.tap(m, "Learn 5 more");
        assertEquals("learn", m.screen);
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        assertEquals(p.perDay + 5, p.newToday());
        m.home();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, (p.perDay + 5) + " learnt, " + p.perDay + " a day"));
        shots.shot(m, prefix + "3-home-past");

        // below 90%: the cap holds again (8 of 10)
        p.getWritableDatabase().delete("reviews", null, null);
        answers(p, 8, 2);
        assertFalse(p.goodDay());
        m.home();
        ShadowLooper.idleMainLooper();
        lb = WalkTest.find(m.root, "Enough new for today");
        assertNotNull("80% right: the cap holds", lb);
        assertFalse(lb.isEnabled());
        assertNull(WalkTest.find(m.root, "Learn 5 more"));
        m.startLearn();
        assertEquals("closed: Learn doesn't start", "home", m.screen);

        // all right but fewer than ten: not enough to tell
        p.getWritableDatabase().delete("reviews", null, null);
        answers(p, 9, 0);
        assertFalse(p.goodDay());
        answers(p, 1, 0);
        assertTrue(p.goodDay());
    }

    /** right answers and wrong ones today, on items far along the path (graded: cards made). */
    static void answers(Progress p, int right, int wrong) {
        for (int i = 0; i < right + wrong; i++) p.grade(PathData.items.get(1000 + i).h, i < right ? 3 : 1, "choose", 1000);
    }
}
