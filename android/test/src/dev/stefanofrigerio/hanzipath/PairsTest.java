package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Confusable pairs: the database's step from version 1 (reviews gain "picked", every row
 * kept, old ones null); a wrong answer, graded or practised, logs what was tapped; two
 * confusions of a pair within 60 days (either way round) make it confusable, one doesn't, nor
 * one older than that; its wrong answers then draw on the other; a review round starts with
 * the two side by side ("Tell them apart") once a week a pair, not graded, and Next goes on
 * to the questions; Stats' "Mixed up" lists the pair and opens the same card; a copy keeps
 * "picked" both ways. Light and dark; renders are x*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class PairsTest {
    static final long DAY = Progress.DAY;
    static final String A = "活", B = "话";
    final HomeTest shots = new HomeTest();

    static int count(Progress p, String where) {
        try (Cursor c = p.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews WHERE " + where, null)) {
            c.moveToNext();
            return c.getInt(0);
        }
    }

    /** These met, every one due an hour ago. */
    static void due(Main m, String... hs) {
        long now = System.currentTimeMillis();
        for (String h : hs) {
            Progress.Card k = new Progress.Card();
            k.s = 3; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 20 * DAY; k.last = now - 4 * DAY; k.due = now - 3600_000L;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
        }
    }

    // ── the database, from version 1 ──
    @Test
    public void upgrade() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File f = ctx.getDatabasePath("hanzi.db");
        f.getParentFile().mkdirs();
        f.delete();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null)) {
            // version 1's tables, as Progress made them then
            db.execSQL("CREATE TABLE cards (h TEXT PRIMARY KEY, s REAL, d REAL, reps INTEGER, lapses INTEGER,"
                    + " first INTEGER, last INTEGER, due INTEGER, grade INTEGER)");
            db.execSQL("CREATE TABLE stories (h TEXT PRIMARY KEY, text TEXT, at INTEGER)");
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
            db.execSQL("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, h TEXT,"
                    + " grade INTEGER, mode TEXT, ms INTEGER, r REAL, s REAL, d REAL)");
            db.execSQL("CREATE INDEX reviews_h ON reviews (h)");
            db.execSQL("INSERT INTO cards VALUES ('人', 3.5, 5, 2, 0, 1000, 2000, 3000, 3)");
            db.execSQL("INSERT INTO meta VALUES ('opts', '{\"batch\":7}')");
            for (int i = 0; i < 5; i++)
                db.execSQL("INSERT INTO reviews (at, h, grade, mode, ms, r, s, d) VALUES (" + (1000 + i) + ", '人', " + (1 + i % 3) + ", 'choose', 900, 0.9, 3, 5)");
            db.setVersion(1);
        }
        Progress p = new Progress(ctx);
        assertEquals(Progress.VERSION, p.getReadableDatabase().getVersion());
        boolean has = false;
        try (Cursor c = p.getReadableDatabase().rawQuery("PRAGMA table_info(reviews)", null)) {
            while (c.moveToNext()) has |= c.getString(1).equals("picked");
        }
        assertTrue("reviews gained picked", has);
        assertEquals("every row kept", 5, count(p, "1"));
        assertEquals("old rows null", 5, count(p, "picked IS NULL"));
        assertEquals(5, count(p, "h = '人' AND mode = 'choose' AND ms = 900"));
        assertEquals(1, p.cards.size());
        assertEquals(3.5, p.cards.get("人").s, 1e-9);
        assertEquals(7, p.batch);
        assertEquals(5, p.reviewTotals()[0]);
        // and an answer now keeps what was tapped
        p.grade("人", 1, "choose", 1200, "入");
        assertEquals(1, count(p, "picked = '入'"));
        p.close();
    }

    // ── detecting ──
    @Test
    public void detect() throws Exception {
        Main m = RoundTest.open();
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        due(m, A, B, "人", "入", "氵", "讠", "舌");

        // a wrong answer in a review: the grade and what was tapped
        Quiz q = new Quiz(m, Arrays.asList(A), "review");
        m.quiz = q;
        q.show();
        String wrong = PracticeTest.wrong(q);
        q.pick(wrong);
        ShadowLooper.idleMainLooper();
        assertEquals(1, count(p, "h = '" + A + "' AND grade = 1 AND picked = '" + wrong + "'"));
        // Undo takes the row, and what was tapped, back
        q.undo();
        assertEquals(0, count(p, "picked IS NOT NULL"));
        // a right one keeps its own
        q.pick(A);
        assertEquals(1, count(p, "h = '" + A + "' AND grade >= 2 AND picked = '" + A + "'"));

        // practice too
        Quiz pr = new Quiz(m, Arrays.asList(B), "practice");
        m.quiz = pr;
        pr.show();
        String w2 = PracticeTest.wrong(pr);
        pr.pick(w2);
        assertEquals(1, count(p, "h = '" + B + "' AND mode LIKE 'practice-%' AND grade = 1 AND picked = '" + w2 + "'"));
        m.home();
        p.getWritableDatabase().delete("reviews", null, null);

        // one confusion: no pair
        p.grade(A, 1, "choose", 3000, B);
        assertTrue(p.pairs(now + 1000).isEmpty());
        assertTrue(p.confusables(A, now + 1000).isEmpty());
        // a tone missed, a pick that isn't a card, a right answer: not confusions
        p.grade(A, 1, "tone", 3000, "2");
        p.grade(B, 1, "pick", 3000, "不是一个字");
        p.grade(B, 3, "pick", 3000, B);
        assertTrue(p.pairs(System.currentTimeMillis()).isEmpty());
        // one more, older than 60 days: still none
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("at", now - 61 * DAY); v.put("h", B); v.put("grade", 1); v.put("mode", "choose"); v.put("ms", 2000); v.put("picked", A);
        p.getWritableDatabase().insert("reviews", null, v);
        assertTrue("older than 60 days doesn't count", p.pairs(System.currentTimeMillis()).isEmpty());
        // the other way round, within the 60 days: a pair
        p.practised(B, 1, "listen", 3000, A);
        List<Progress.Pair> pairs = p.pairs(System.currentTimeMillis());
        assertEquals(1, pairs.size());
        assertEquals("in the path's order: 话 comes first", B, pairs.get(0).a);
        assertEquals(2, pairs.get(0).n);
        assertEquals(Arrays.asList(B), p.confusables(A, System.currentTimeMillis()));
        assertEquals(Arrays.asList(A), p.confusables(B, System.currentTimeMillis()));

        // its wrong answers draw on the other first, whatever the kind
        Quiz q2 = new Quiz(m, Arrays.asList(A), "practice");
        for (String kind : new String[]{"choose", "pick", "listen"})
            for (int i = 0; i < 20; i++) {
                assertTrue(kind + ": " + B + " among " + A + "'s answers", q2.options(A, kind).contains(B));
                assertTrue(kind + ": " + A + " among " + B + "'s answers", q2.options(B, kind).contains(A));
            }
        // one not confused draws as before: no 话 for 人
        int with = 0;
        for (int i = 0; i < 20; i++) if (q2.options("人", "choose").contains(B)) with++;
        assertEquals(0, with);
    }

    // ── using: the card, Stats, the copy ──
    @Test
    public void apart() throws Exception { apart(RoundTest.open(), "x"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void apartNight() throws Exception { apart(RoundTest.open(), "xn"); }

    void apart(Main m, String prefix) throws Exception {
        Progress p = m.prog;
        p.introDone = true;
        p.grade(A, 1, "choose", 3000, B);
        p.grade(B, 1, "pick", 3000, A);
        p.grade("人", 1, "listen", 3000, "入");
        p.grade("人", 1, "choose", 3000, "入");
        p.grade("入", 1, "choose", 3000, "人");
        due(m, A, B, "人", "入", "氵", "讠", "舌");
        assertEquals(2, p.pairs(System.currentTimeMillis()).size());
        assertEquals("the most confused first", 3, p.pairs(System.currentTimeMillis()).get(0).n);
        int logged = count(p, "1");

        // a review round starts with the pair, side by side
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Review 7");
        assertEquals("apart", m.screen);
        assertNotNull(WalkTest.find(m.root, "TELL THEM APART"));
        assertNotNull(WalkTest.find(m.root, "review · mixed up 3 times"));
        assertNotNull(WalkTest.find(m.root, "人\u00a0person, 入\u00a0to enter: no part in common."));
        assertNotNull(WalkTest.find(m.root, "no smaller parts"));
        assertEquals(2, count(m.root, HanziView.class));
        shots.shot(m, prefix + "1-apart");
        // Next: the round, as it was, nothing graded
        WalkTest.tap(m, "Next");
        assertEquals("quiz", m.screen);
        assertNotNull(WalkTest.find(m.root, "review 1 / 7"));
        assertEquals(7, m.quiz.queue.size());
        assertEquals(logged, count(p, "1"));
        RoundTest.answer(m);
        assertEquals(logged + 7, count(p, "1"));

        // the next round starts with the other pair; then neither, within the week
        due(m, A, B, "人", "入");
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Review 4");
        assertEquals("apart", m.screen);
        TextView line = WalkTest.find(m.root, "Both have 舌\u00a0tongue");
        assertNotNull(line);
        assertTrue(line.getText().toString(), line.getText().toString().startsWith("Both have 舌\u00a0tongue: 话 has 讠\u00a0speech, 活 has 氵\u00a0water. Said huà and huó."));
        shots.shot(m, prefix + "2-apart-shared");
        WalkTest.tap(m, "Next");
        RoundTest.answer(m);
        due(m, A, B);
        m.home();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Review 2");
        assertEquals("once a week a pair", "quiz", m.screen);
        m.home();
        // a week on, it comes again (and the week is kept with the rest)
        Progress again = new Progress(m);
        assertTrue(again.apartShown.has(p.pairs(System.currentTimeMillis()).get(0).key()));
        for (java.util.Iterator<String> i = p.apartShown.keys(); i.hasNext(); ) {
            String k = i.next();
            p.apartShown.put(k, p.apartShown.getLong(k) - 8 * DAY);
        }
        WalkTest.tap(m, "Review 2");
        assertEquals("apart", m.screen);
        m.home();

        // Stats: the pairs, the most confused first; a tap opens them side by side
        m.openStats();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "MIXED UP"));
        TextView first = WalkTest.find(m.root, "人 入");
        assertNotNull(first);
        assertNotNull(WalkTest.find(m.root, B + " " + A));
        assertNotNull(WalkTest.find(m.root, "person / to enter"));
        shots.shot(m, prefix + "3-stats");
        ((View) WalkTest.find(m.root, B + " " + A).getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("apart", m.screen);
        assertNotNull(WalkTest.find(m.root, "mixed up twice"));
        assertNull("not a review's", WalkTest.find(m.root, "review ·"));
        shots.shot(m, prefix + "4-apart-stats");
        WalkTest.tap(m, "Back to Stats");
        assertEquals("stats", m.screen);
        ((View) WalkTest.find(m.root, "人 入").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(m.back());
        assertEquals("back goes to Stats", "stats", m.screen);
        m.home();

        // the copy carries what was tapped, both ways
        int picked = count(p, "picked IS NOT NULL");
        JSONObject copy = new JSONObject(p.export());
        JSONArray rv = copy.getJSONArray("reviews");
        int inCopy = 0;
        for (int i = 0; i < rv.length(); i++) if (rv.getJSONObject(i).has("picked")) inCopy++;
        assertEquals(picked, inCopy);
        p.getWritableDatabase().delete("reviews", null, null);
        assertTrue(p.pairs(System.currentTimeMillis()).isEmpty());
        p.replace(copy.toString());
        assertEquals(picked, count(p, "picked IS NOT NULL"));
        assertEquals(1, count(p, "h = '" + B + "' AND picked = '" + A + "' AND mode = 'pick'"));
        assertEquals(2, p.pairs(System.currentTimeMillis()).size());
        // a copy from before picked: its rows come in null
        for (int i = 0; i < rv.length(); i++) rv.getJSONObject(i).remove("picked");
        p.replace(copy.toString());
        assertEquals(rv.length(), count(p, "picked IS NULL"));
        assertTrue(p.pairs(System.currentTimeMillis()).isEmpty());
        m.home();
        ShadowLooper.idleMainLooper();
        m.openStats();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Nothing mixed up."));
    }

    static int count(View v, Class<?> k) {
        int n = k.isInstance(v) ? 1 : 0;
        if (v instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) n += count(((android.view.ViewGroup) v).getChildAt(i), k);
        return n;
    }
}
