package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Learning steps around FSRS: the database's step from version 2 (cards gain "step", every row
 * kept, all graduated: 0); a batch learnt and checked right is due again in an hour, then the
 * next day, then graduates to FSRS's interval, with S from FSRS's formulas on every grade; a
 * miss on a step starts it over at 10 minutes; a lapse relearns (10 minutes, a day); home's
 * count, the week strip, the reminder and the widget all see the steps due; undo puts the step
 * back; a copy keeps it (one without it reads as graduated). Time passing is the card's times
 * moved back (later). Renders: ls*.png (light), lsn*.png (night): home with the batch due an
 * hour on, and the week strip after the hour's step.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class LearningStepsTest {
    static final long DAY = Progress.DAY, HOUR = 3_600_000L, MIN = 60_000L;
    final HomeTest shots = new HomeTest();

    /** ms passing for a card: its times moved back, as if it had been graded that long ago. */
    static void later(Progress p, String h, long ms) {
        Progress.Card c = p.cards.get(h);
        c.first -= ms; c.last -= ms; c.due -= ms;
        p.putCard(h, c);
    }

    static void later(Progress p, long ms) { for (String h : p.cards.keySet()) later(p, h, ms); }

    /** Due about `in` from now (a few seconds' slack for the test's own time). */
    static void dueIn(Progress.Card c, long in) {
        long now = System.currentTimeMillis();
        assertTrue("due in " + in / MIN + " min, not " + (c.due - now) / MIN, Math.abs(c.due - now - in) < 30_000);
    }

    // ── the database, from version 2 ──
    @Test
    public void upgrade() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File f = ctx.getDatabasePath("hanzi.db");
        f.getParentFile().mkdirs();
        f.delete();
        long now = System.currentTimeMillis();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null)) {
            // version 2's tables, as Progress made them then
            db.execSQL("CREATE TABLE cards (h TEXT PRIMARY KEY, s REAL, d REAL, reps INTEGER, lapses INTEGER,"
                    + " first INTEGER, last INTEGER, due INTEGER, grade INTEGER)");
            db.execSQL("CREATE TABLE stories (h TEXT PRIMARY KEY, text TEXT, at INTEGER)");
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
            db.execSQL("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, h TEXT,"
                    + " grade INTEGER, mode TEXT, ms INTEGER, r REAL, s REAL, d REAL, picked TEXT)");
            db.execSQL("CREATE INDEX reviews_h ON reviews (h)");
            // one known, one missed ten minutes ago (today's RELEARN), one just checked
            db.execSQL("INSERT INTO cards VALUES ('人', 20, 5, 6, 1, " + (now - 90 * DAY) + ", " + (now - 10 * DAY) + ", " + (now + 10 * DAY) + ", 3)");
            db.execSQL("INSERT INTO cards VALUES ('入', 0.4, 6, 3, 1, " + (now - 9 * DAY) + ", " + (now - 11 * MIN) + ", " + (now - MIN) + ", 1)");
            db.execSQL("INSERT INTO cards VALUES ('口', 3.7, 5, 1, 0, " + (now - HOUR) + ", " + (now - HOUR) + ", " + (now + 3 * DAY) + ", 3)");
            db.execSQL("INSERT INTO meta VALUES ('opts', '{\"batch\":7}')");
            for (int i = 0; i < 4; i++)
                db.execSQL("INSERT INTO reviews (at, h, grade, mode, ms, r, s, d, picked) VALUES (" + (1000 + i) + ", '人', 3, 'choose', 900, 0.9, 3, 5, NULL)");
            db.setVersion(2);
        }
        Progress p = new Progress(ctx);
        assertEquals(Progress.VERSION, p.getReadableDatabase().getVersion());
        assertTrue(Progress.VERSION >= 3);
        boolean has = false;
        try (Cursor c = p.getReadableDatabase().rawQuery("PRAGMA table_info(cards)", null)) {
            while (c.moveToNext()) has |= c.getString(1).equals("step");
        }
        assertTrue("cards gained step", has);
        assertEquals("every card kept", 3, p.cards.size());
        for (Progress.Card c : p.cards.values()) assertEquals("graduated, as they were", 0, c.step);
        try (Cursor c = p.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cards WHERE step = 0", null)) {
            c.moveToNext();
            assertEquals(3, c.getInt(0));
        }
        assertEquals(20, p.cards.get("人").s, 1e-9);
        assertEquals(now + 10 * DAY, p.cards.get("人").due);
        assertEquals("their due times unchanged", now + 3 * DAY, p.cards.get("口").due);
        assertEquals(4, p.reviewTotals()[0]);
        assertEquals(7, p.batch);
        assertTrue("still due", p.cards.get("入").due <= now);
        // the one in its old 10 minutes: right now, it goes on by FSRS's interval as before
        p.grade("入", 3, "choose", 900);
        Progress.Card c = p.cards.get("入");
        assertEquals(0, c.step);
        dueIn(c, (long) (c.s * DAY));
        p.close();
    }

    // ── a new item's steps: an hour, a day, then FSRS ──
    @Test
    public void steps() throws Exception {
        Main m = RoundTest.open();
        Progress p = m.prog;
        String h = PathData.items.get(0).h;
        p.grade(h, 3, "check", 1000);
        Progress.Card c = p.cards.get(h);
        assertEquals("FSRS's first stability, as before", p.w[2], c.s, 1e-9);
        assertEquals(2, c.step);
        dueIn(c, HOUR);
        assertFalse("not due now", p.due(System.currentTimeMillis()).contains(h));

        // an hour on: due, and right again: tomorrow
        later(p, h, HOUR + MIN);
        long now = System.currentTimeMillis();
        assertTrue(p.due(now).contains(h));
        Progress.Card was = c.copy();
        double r = Progress.recall(was, now);
        p.grade(h, 3, "choose", 1000);
        c = p.cards.get(h);
        assertEquals("S by FSRS's formula", Progress.recallS(p.w, was.s, was.d, r, 3), c.s, 1e-3);
        assertTrue("an hour on adds little", c.s - was.s < 0.5);
        assertEquals(Progress.nextD(p.w, was.d, 3), c.d, 1e-9);
        assertEquals(3, c.step);
        dueIn(c, DAY);

        // a day on: right, graduated, FSRS's interval
        later(p, h, DAY + MIN);
        now = System.currentTimeMillis();
        was = c.copy();
        r = Progress.recall(was, now);
        p.grade(h, 2, "choose", 1000);
        c = p.cards.get(h);
        assertEquals(Progress.recallS(p.w, was.s, was.d, r, 2), c.s, 1e-3);
        assertEquals("graduated", 0, c.step);
        assertEquals(3, c.reps);
        dueIn(c, (long) (c.s * DAY));
        assertTrue(c.due > now + DAY);

        // graduated: a right review keeps FSRS's interval, no steps
        later(p, h, (long) (c.s * DAY));
        p.grade(h, 3, "choose", 1000);
        c = p.cards.get(h);
        assertEquals(0, c.step);
        dueIn(c, (long) (c.s * DAY));
    }

    // ── a miss on a step: back to 10 minutes, then the steps again ──
    @Test
    public void miss() throws Exception {
        Main m = RoundTest.open();
        Progress p = m.prog;
        String h = PathData.items.get(1).h;
        p.grade(h, 1, "check", 1000);   // missed at the check: 10 minutes, as before
        Progress.Card c = p.cards.get(h);
        assertEquals(1, c.step);
        dueIn(c, Progress.RELEARN);
        later(p, h, 11 * MIN);
        p.grade(h, 3, "choose", 1000);
        assertEquals(2, p.cards.get(h).step);
        dueIn(p.cards.get(h), HOUR);
        later(p, h, HOUR + MIN);
        p.grade(h, 3, "choose", 1000);
        assertEquals(3, p.cards.get(h).step);
        dueIn(p.cards.get(h), DAY);

        // missed at the day's step: it starts over
        later(p, h, DAY + MIN);
        long lapses = p.cards.get(h).lapses;
        Progress.Card was = p.cards.get(h).copy();
        double r = Progress.recall(was, System.currentTimeMillis());
        p.grade(h, 1, "choose", 1000);
        c = p.cards.get(h);
        assertEquals("restarted", 1, c.step);
        dueIn(c, Progress.RELEARN);
        assertEquals("S by FSRS's lapse formula", Math.max(0.05, Progress.lapseS(p.w, was.s, was.d, r)), c.s, 1e-3);
        assertEquals(lapses + 1, c.lapses);
        // and the steps once more before it graduates
        for (long[] st : new long[][]{{11 * MIN, 2, HOUR}, {HOUR + MIN, 3, DAY}}) {
            later(p, h, st[0]);
            p.grade(h, 3, "choose", 1000);
            assertEquals(st[1], p.cards.get(h).step);
            dueIn(p.cards.get(h), st[2]);
        }
        later(p, h, DAY + MIN);
        p.grade(h, 3, "choose", 1000);
        c = p.cards.get(h);
        assertEquals(0, c.step);
        assertTrue("no sooner than the day's step", c.due >= System.currentTimeMillis() + DAY - 30_000);

        // undo puts the step back
        Progress.Undo u = p.grade(h, 1, "choose", 1000);
        assertEquals(-1, p.cards.get(h).step);
        p.ungrade(u);
        assertEquals(0, p.cards.get(h).step);
        p.load();
        assertEquals("and in the database", 0, p.cards.get(h).step);
    }

    // ── a lapse: relearning, 10 minutes then a day ──
    @Test
    public void lapse() throws Exception {
        Main m = RoundTest.open();
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        String h = PathData.items.get(2).h;
        Progress.Card k = new Progress.Card();
        k.s = 12; k.d = 5; k.reps = 5; k.grade = 3; k.first = now - 60 * DAY; k.last = now - 14 * DAY; k.due = now - 2 * DAY;
        p.cards.put(h, k);
        p.putCard(h, k);
        Progress.Card was = k.copy();
        double r = Progress.recall(was, now);
        p.grade(h, 1, "choose", 1000);
        Progress.Card c = p.cards.get(h);
        assertEquals("relearning", -1, c.step);
        assertEquals(1, c.lapses);
        assertEquals(Math.max(0.05, Progress.lapseS(p.w, was.s, was.d, r)), c.s, 1e-3);
        dueIn(c, Progress.RELEARN);

        later(p, h, 11 * MIN);
        p.grade(h, 3, "choose", 1000);
        assertEquals(-2, p.cards.get(h).step);
        dueIn(p.cards.get(h), DAY);
        // missed again while relearning: back to 10 minutes
        later(p, h, DAY + MIN);
        p.grade(h, 1, "choose", 1000);
        assertEquals(-1, p.cards.get(h).step);
        dueIn(p.cards.get(h), Progress.RELEARN);
        later(p, h, 11 * MIN);
        p.grade(h, 3, "choose", 1000);
        later(p, h, DAY + MIN);
        p.grade(h, 3, "choose", 1000);
        c = p.cards.get(h);
        assertEquals("graduated again", 0, c.step);
        dueIn(c, Math.max((long) (c.s * DAY), DAY));
    }

    // ── a batch through the app: home, the week strip, the reminder and the widget ──
    void batch(boolean night) throws Exception {
        Main m = RoundTest.open();
        String p0 = night ? "lsn" : "ls";
        WalkTest.tap(m, "Learn next 5");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        for (int i = 0; i < 5; i++) {
            String q = m.quiz.queue.get(0);
            WalkTest.options(m).stream().filter(t -> t.getText().toString().endsWith(PathData.get(q).en)).findFirst().get().performClick();
            ShadowLooper.idleMainLooper();
            WalkTest.tap(m, i < 4 ? "Next" : "Finish");
        }
        assertNotNull("the done line says when", WalkTest.find(m.root, "5 new on the path. They come back in an hour, then tomorrow"));
        shots.shot(m, p0 + "1-checked");
        for (Progress.Card c : m.prog.cards.values()) { assertEquals(2, c.step); dueIn(c, HOUR); }
        WalkTest.tap(m, "Back to the path");
        assertNull("nothing due yet", WalkTest.find(m.root, "Review "));
        assertNotNull(WalkTest.find(m.root, "Practice 5"));

        // an hour on: all five due, on home, in the strip, for the reminder and the widget
        later(m.prog, HOUR + MIN);
        long now = System.currentTimeMillis();
        m.home();
        ShadowLooper.idleMainLooper();
        assertEquals(5, m.prog.due(now).size());
        assertNotNull(WalkTest.find(m.root, "Review 5"));
        assertEquals("today's bar", 5, Home.week(m.prog, now)[0]);
        assertEquals(5, Progress.peek(m, now).due.size());
        assertEquals(5, Widget.show(m, now).due);
        shots.shot(m, p0 + "2-hour-due");

        // reviewed: tomorrow's bar
        WalkTest.tap(m, "Review 5");
        RoundTest.answer(m);
        for (Progress.Card c : m.prog.cards.values()) { assertEquals(3, c.step); dueIn(c, DAY); }
        WalkTest.tap(m, "Back to the path");
        int[] wk = first(m);
        assertEquals("none today", 0, wk[0]);
        assertEquals("all tomorrow", 5, wk[1]);
        shots.shot(m, p0 + "3-tomorrow");

        // a day on: due again; right, they graduate past tomorrow
        later(m.prog, DAY + MIN);
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Review 5");
        RoundTest.answer(m);
        now = System.currentTimeMillis();
        for (Progress.Card c : m.prog.cards.values()) {
            assertEquals(0, c.step);
            assertTrue(c.due > now + DAY);
            dueIn(c, (long) (c.s * DAY));
        }
    }

    int[] first(Main m) { return HomeTest.first(m.root, WeekView.class).counts; }

    @Test
    public void batch() throws Exception { batch(false); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception { batch(true); }

    // ── a copy keeps the step ──
    @Test
    public void copy() throws Exception {
        Main m = RoundTest.open();
        Progress p = m.prog;
        String a = PathData.items.get(0).h, b = PathData.items.get(1).h, c = PathData.items.get(2).h;
        p.grade(a, 3, "check", 1000);   // learning, an hour
        p.grade(b, 1, "check", 1000);   // learning, 10 minutes
        Progress.Card k = new Progress.Card();
        long now = System.currentTimeMillis();
        k.s = 9; k.d = 5; k.reps = 4; k.grade = 3; k.first = now - 40 * DAY; k.last = now - 9 * DAY; k.due = now;
        p.cards.put(c, k);
        p.putCard(c, k);
        p.grade(c, 1, "choose", 1000);  // relearning
        String text = p.export();
        assertEquals(2, new JSONObject(text).getJSONObject("cards").getJSONObject(a).getInt("step"));
        p.replace(text);
        assertEquals(2, p.cards.get(a).step);
        assertEquals(1, p.cards.get(b).step);
        assertEquals(-1, p.cards.get(c).step);
        // a copy from before the steps: graduated
        JSONObject o = new JSONObject(text);
        o.getJSONObject("cards").getJSONObject(a).remove("step");
        p.replace(o.toString());
        assertEquals(0, p.cards.get(a).step);
        assertEquals(1, p.cards.get(b).step);
    }
}
