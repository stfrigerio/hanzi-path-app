package dev.stefanofrigerio.hanzipath;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.Calendar;

import static org.junit.Assert.*;

/**
 * The first run's lines on home (shown once, put away by Got it or by the first batch), and
 * the round's size: Settings' "Reviews per round", and the done screen's "N still due" with
 * Another round, or when the next fall due. Renders are r*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class RoundTest {
    static final long DAY = 24 * 3600_000L;
    static final String INTRO = "Parts first";

    void shot(Main m, String name) throws Exception { new HomeTest().shot(m, name); }

    static Main open() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        return m;
    }

    /** n items off the path's start, all overdue by an hour, the most overdue first. */
    static void due(Main m, int n) {
        long now = System.currentTimeMillis();
        m.prog.cards.clear();
        for (int i = 0; i < n; i++) {
            Progress.Card k = new Progress.Card();
            k.s = 3; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 20 * DAY; k.last = now - 4 * DAY - i * 60_000L; k.due = now - 3600_000L;
            String h = PathData.items.get(i).h;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
        }
    }

    /** The round answered, every one right. */
    static void answer(Main m) {
        Quiz q = m.quiz;
        while (m.screen.equals("quiz")) {
            q.pick(q.queue.get(0));
            ShadowLooper.idleMainLooper();
            q.next();
            ShadowLooper.idleMainLooper();
        }
        assertEquals("done", m.screen);
    }

    @Test
    public void firstRun() throws Exception {
        Main m = open();
        assertFalse(m.prog.introDone);
        assertNotNull("a fresh install explains the path", WalkTest.find(m.root, INTRO));
        assertNotNull(WalkTest.find(m.root, "Words follow"));
        shot(m, "r1-first-run");
        WalkTest.tap(m, "Got it");
        assertNull("Got it puts it away", WalkTest.find(m.root, INTRO));
        assertTrue(m.prog.introDone);
        assertTrue("in the copy", new JSONObject(m.prog.export()).getJSONObject("meta").getJSONObject("opts").getBoolean("intro"));
        // the database opened again: still away
        Progress again = new Progress(m);
        assertTrue(again.introDone);
        m.prog = again;
        m.home();
        ShadowLooper.idleMainLooper();
        assertNull(WalkTest.find(m.root, INTRO));
        assertNotNull(WalkTest.find(m.root, "Learn next 5"));
    }

    @Test
    public void firstBatch() throws Exception {
        Main m = open();
        assertNotNull(WalkTest.find(m.root, INTRO));
        WalkTest.tap(m, "Learn next 5");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        answer(m);
        WalkTest.tap(m, "Back to the path");
        assertNull("the first batch learnt puts it away", WalkTest.find(m.root, INTRO));
        assertTrue(new Progress(m).introDone);
    }

    @Test
    public void setting() throws Exception {
        Main m = open();
        assertEquals("40 by default", 40, m.prog.round);
        m.openSettings();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Reviews per round"));
        shot(m, "r2-settings");
        WalkTest.tap(m, "40  ›");
        assertEquals(60, m.prog.round);
        WalkTest.tap(m, "60  ›");
        assertEquals("all", 0, m.prog.round);
        assertEquals(45, m.prog.roundOf(45));
        WalkTest.tap(m, "all  ›");
        assertEquals(20, m.prog.round);
        assertEquals(20, new Progress(m).round);
        m.openSettings();
        ShadowLooper.idleMainLooper();
        assertNotNull("shown as saved", WalkTest.find(m.root, "20  ›"));

        // opts saved before the setting existed load as 40
        m.prog.putMeta("opts", "{\"batch\":5,\"perDay\":15,\"say\":true,\"remind\":-1}");
        Progress old = new Progress(m);
        assertEquals(40, old.round);
        assertFalse(old.introDone);
        // and so does a copy made before it
        JSONObject copy = new JSONObject(m.prog.export());
        copy.getJSONObject("meta").getJSONObject("opts").remove("round");
        m.prog.round = 20;
        m.prog.replace(copy.toString());
        assertEquals(40, m.prog.round);
    }

    @Test
    public void rounds() throws Exception {
        Main m = open();
        due(m, 45);
        m.prog.round = 20;
        m.prog.saveOpts();
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Review 45");
        assertEquals(20, m.quiz.total);
        answer(m);
        assertNotNull(WalkTest.find(m.root, "25 still due"));
        assertNotNull(WalkTest.find(m.root, "Back to the path"));
        shot(m, "r3-done-more");
        Quiz before = m.quiz;
        WalkTest.tap(m, "Another round");
        assertNotSame(before, m.quiz);
        assertEquals("quiz", m.screen);
        assertEquals("the next 20", 20, m.quiz.total);
        assertEquals(25, m.prog.due(System.currentTimeMillis()).size());
        answer(m);
        assertNotNull(WalkTest.find(m.root, "5 still due"));
        WalkTest.tap(m, "Another round");
        assertEquals(5, m.quiz.total);

        // one more, falling due in three hours: the soonest
        long now = System.currentTimeMillis();
        Progress.Card k = new Progress.Card();
        k.s = 1; k.d = 5; k.reps = 2; k.grade = 3; k.first = now - DAY; k.last = now - 3600_000L; k.due = now + 3 * 3600_000L + 30_000;
        m.prog.cards.put(PathData.items.get(50).h, k);
        answer(m);
        assertNull(WalkTest.find(m.root, "Another round"));
        assertNotNull(WalkTest.find(m.root, "Nothing more is due. The next fall due in 3 hours."));
        assertNotNull(WalkTest.find(m.root, "Back to the path"));
        shot(m, "r4-done-later");
        WalkTest.tap(m, "Back to the path");
        assertEquals("home", m.screen);
    }

    @Test
    public void when() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 7, 10, 0, 0);
        long now = c.getTimeInMillis();
        assertEquals("in a minute", Quiz.when(now, now + 20_000));
        assertEquals("in 10 minutes", Quiz.when(now, now + 10 * 60_000));
        assertEquals("in an hour", Quiz.when(now, now + 61 * 60_000));
        assertEquals("in 3 hours", Quiz.when(now, now + 3 * 3600_000L));
        assertEquals("tomorrow", Quiz.when(now, now + 23 * 3600_000L));
        assertEquals("in 3 days", Quiz.when(now, now + 3 * DAY));
        c.set(Calendar.HOUR_OF_DAY, 23);
        long late = c.getTimeInMillis();
        assertEquals("past midnight but soon: hours", "in 2 hours", Quiz.when(late, late + 2 * 3600_000L));
        assertEquals("tomorrow", Quiz.when(late, late + 14 * 3600_000L));
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = open();
        shot(m, "rn1-first-run");
        WalkTest.tap(m, "Got it");
        due(m, 45);
        m.prog.round = 20;
        m.startReview();
        answer(m);
        shot(m, "rn3-done-more");
        WalkTest.tap(m, "Another round");
        answer(m);
        WalkTest.tap(m, "Another round");
        answer(m);
        shot(m, "rn4-done-later");
    }
}
