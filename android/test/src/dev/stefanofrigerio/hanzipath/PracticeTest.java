package dev.stefanofrigerio.hanzipath;

import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Practice anytime: with cards met and none due, home's left button is "Practice N"
 * (outlined); the round draws from the weakest, in a random order; its answers are graded
 * as reviews are (FSRS: a miss lapses the card), logged as practice-*, and counted in
 * Stats and the day's reviews; Undo takes a practice grade back, the card as it was; with
 * something due the button is Review again. Light and dark; renders are p*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class PracticeTest {
    static final long DAY = Progress.DAY;
    final HomeTest shots = new HomeTest();

    /** 30 items met, none due: the stronger the further along, and two weak ones to come first. */
    static void seed(Main m) {
        long now = System.currentTimeMillis();
        m.prog.cards.clear();
        for (int i = 0; i < 30; i++) {
            Progress.Card k = new Progress.Card();
            k.s = 5 + 3 * i; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 30 * DAY; k.last = now - DAY; k.due = now + 4 * DAY;
            if (i == 25) { k.s = 0.6; k.reps = 1; k.due = now + 3600_000L; }            // the weakest, still new: choose
            if (i == 12 || i == 13) { k.s = 1.5; k.due = now + 2 * DAY; k.lapses = i == 13 ? 3 : 1; }  // a tie: more lapses first
            String h = PathData.items.get(i).h;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
        }
    }

    static Map<String, String> snapshot(Progress p) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet()) {
            Progress.Card c = e.getValue();
            out.put(e.getKey(), c.s + " " + c.d + " " + c.reps + " " + c.lapses + " " + c.last + " " + c.due + " " + c.grade);
        }
        return out;
    }

    static int logged(Main m, String where) {
        try (android.database.Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews WHERE " + where, null)) {
            c.moveToNext();
            return c.getInt(0);
        }
    }

    static String wrong(Quiz q) {
        String h = q.queue.get(0);
        for (String o : q.options) if (!o.equals(h)) return o;
        throw new AssertionError("no wrong answer");
    }

    @Test
    public void practice() throws Exception { practice(RoundTest.open(), "p"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void practiceNight() throws Exception { practice(RoundTest.open(), "pn"); }

    void practice(Main m, String prefix) throws Exception {
        seed(m);
        m.prog.introDone = true;
        m.home();
        ShadowLooper.idleMainLooper();
        long now = System.currentTimeMillis();
        assertTrue("nothing due", m.prog.due(now).isEmpty());
        assertNull("not Nothing due", WalkTest.find(m.root, "Nothing due"));
        TextView pb = WalkTest.find(m.root, "Practice ");
        assertNotNull("home offers practice", pb);
        assertEquals("up to 20 of the 30 met", "Practice 20", pb.getText().toString());
        assertTrue(pb.isEnabled());
        assertEquals("outlined, not solid: nothing is due", Ink.ink, pb.getCurrentTextColor());
        shots.shot(m, prefix + "1-home");

        // the weakest first: the new one, then the tie by lapses
        List<String> weak = m.prog.weakest(Progress.PRACTICE, now);
        assertEquals(20, weak.size());
        assertEquals(PathData.items.get(25).h, weak.get(0));
        assertEquals(PathData.items.get(13).h, weak.get(1));
        assertEquals(PathData.items.get(12).h, weak.get(2));
        for (int i = 3; i < 20; i++) assertEquals("then by strength", PathData.items.get(i - 3 + (i - 3 >= 12 ? 2 : 0)).h, weak.get(i));

        Map<String, String> before = snapshot(m.prog);
        long[] totals = m.prog.reviewTotals();
        int reviewed = m.prog.todayCount("reviews");
        String days = m.prog.days.toString();

        WalkTest.tap(m, "Practice 20");
        assertEquals("quiz", m.screen);
        Quiz q = m.quiz;
        assertEquals("practice", q.kind);
        assertEquals(20, q.queue.size());
        assertTrue("drawn from the weakest", m.prog.weakest(Progress.PRACTICE_POOL, now).containsAll(q.queue));
        assertNotNull(WalkTest.find(m.root, "practice 1 / 20"));
        String first = q.queue.get(0);
        shots.shot(m, prefix + "2-question");

        // a miss: logged as practice-<kind>, grade 1, the card lapsed
        long lapses = m.prog.cards.get(first).lapses;
        q.pick(wrong(q));
        ShadowLooper.idleMainLooper();
        assertEquals(1, logged(m, "mode = 'practice-" + q.mode + "' AND grade = 1 AND h = '" + first + "'"));
        assertEquals(lapses + 1, m.prog.cards.get(first).lapses);
        assertNotNull(WalkTest.find(m.root, "NOT THAT ONE"));
        shots.shot(m, prefix + "3-answered");

        // Undo: the row gone, the card as it was, the question open again
        WalkTest.tap(m, "Undo");
        assertEquals(0, logged(m, "mode LIKE 'practice-%'"));
        assertEquals(before, snapshot(m.prog));
        assertNull(q.picked);
        assertEquals(first, q.queue.get(0));

        // missed again: it comes back at the end of the round
        q.pick(wrong(q));
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Next");
        assertEquals(first, q.queue.get(q.queue.size() - 1));
        assertEquals(20, q.queue.size());
        assertNotNull(WalkTest.find(m.root, "practice 1 / 20"));
        RoundTest.answer(m);
        assertEquals("21 answers, each in the log", 21, logged(m, "mode LIKE 'practice-%'"));
        assertEquals(1, logged(m, "mode LIKE 'practice-%' AND grade = 1"));

        // graded: the cards moved (in memory and in the database), Stats and the day count them
        assertNotEquals(before, snapshot(m.prog));
        assertEquals(snapshot(m.prog), snapshot(new Progress(m)));
        for (String h : q.tries.keySet()) assertEquals("each answered once more", Long.parseLong(before.get(h).split(" ")[2]) + q.tries.get(h), m.prog.cards.get(h).reps);
        assertEquals("in Stats' counts", totals[0] + 21, m.prog.reviewTotals()[0]);
        assertEquals("in Stats' days", 21, m.prog.reviewsSince(0).size());
        assertEquals(reviewed + 21, m.prog.todayCount("reviews"));
        assertEquals(21, m.prog.todayCount("practice"));
        assertNotEquals(days, m.prog.days.toString());

        // the end: N practised, the seal, the way back and another round
        assertEquals("done", m.screen);
        assertNotNull(WalkTest.find(m.root, "20 practised, 1 missed."));
        assertNotNull(WalkTest.find(m.root, "Each answer counts toward when they come back."));
        assertNull("a miss: no Learn next", WalkTest.find(m.root, "Learn next"));
        assertNotNull(WalkTest.find(m.root, "Back to the path"));
        assertNotNull(WalkTest.find(m.root, "行"));
        shots.shot(m, prefix + "4-done");
        WalkTest.tap(m, "Another round");
        assertEquals("quiz", m.screen);
        assertEquals("practice", m.quiz.kind);
        assertNotNull(WalkTest.find(m.root, "practice 1 / 20"));
        assertTrue(m.back());

        // something due: Review, as before
        Progress.Card c = m.prog.cards.get(PathData.items.get(5).h);
        c.due = System.currentTimeMillis() - 60_000;
        m.prog.putCard(PathData.items.get(5).h, c);
        m.home();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Review 1"));
        assertNull(WalkTest.find(m.root, "Practice "));
        shots.shot(m, prefix + "5-home-due");
    }

    /** Nothing met: no practice, the button says Nothing due and can't be tapped. */
    @Test
    public void nothingMet() throws Exception {
        Main m = RoundTest.open();
        assertNotNull(WalkTest.find(m.root, "Nothing due"));
        assertFalse(WalkTest.find(m.root, "Nothing due").isEnabled());
        assertNull(WalkTest.find(m.root, "Practice "));
        assertTrue(m.prog.weakest(Progress.PRACTICE, System.currentTimeMillis()).isEmpty());
    }
}
