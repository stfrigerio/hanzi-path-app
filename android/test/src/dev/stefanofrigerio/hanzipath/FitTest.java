package dev.stefanofrigerio.hanzipath;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * FSRS fitted to the learner (Fit): a simulated learner whose memory follows other weights than the
 * defaults, scheduled by the app and reviewing late or early, gives a log the fit must predict
 * better than the defaults on cards it didn't see, in a time a phone can afford; a log whose
 * outcomes are only noise around the defaults' own predictions must leave the defaults in
 * place; below the threshold nothing is fitted; only grades count; new grades take the fitted
 * weights; reset puts the defaults back; a copy carries them. Renders are f*.png: Settings'
 * line with the defaults and fitted, light and dark.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class FitTest {
    static final long DAY = Progress.DAY;
    /** The simulated learner: forgets faster than the defaults, lapses harder, "unsure" counts for less. */
    static final double[] TRUTH = Progress.W.clone();
    static {
        TRUTH[0] = 0.15; TRUTH[1] = 0.5; TRUTH[2] = 1.2; TRUTH[6] = 1.3; TRUTH[8] = 0.9; TRUTH[9] = 0.25;
        TRUTH[10] = 1.4; TRUTH[11] = 1.0; TRUTH[13] = 0.45; TRUTH[15] = 0.45;
    }

    void shot(Main m, String name) throws Exception { new HomeTest().shot(m, name); }

    static Main open() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        return m;
    }

    /**
     * A log of `cards` items (the path's first), `per` grades each, oldest first. The app
     * schedules with the defaults; truth decides what's remembered (null: the defaults' own
     * prediction, so the outcomes are only noise around it, and the reviews fall on time). The
     * last review falls an hour before end.
     * Remembered is knew more often the surer the memory; the check after learning is right
     * four times in five.
     */
    static List<Progress.Logged> simulate(double[] truth, int cards, int per, long seed, long end) {
        Random rnd = new Random(seed);
        double[] W = Progress.W;
        long t0 = 0;
        List<Progress.Logged> out = new ArrayList<>();
        for (int c = 0; c < cards; c++) {
            String h = PathData.items.get(c).h;
            long at = t0 + (c / 5) * DAY + rnd.nextInt(3600_000), last = 0;
            double s = 0, d = 0, ts = 0, td = 0;
            for (int j = 0; j < per; j++) {
                double days = (at - last) / (double) DAY;
                int g;
                if (j == 0) g = rnd.nextDouble() < 0.8 ? (rnd.nextDouble() < 0.7 ? 3 : 2) : 1;
                else {
                    double rt = Progress.recall(truth == null ? s : ts, days);
                    g = rnd.nextDouble() < rt ? (rnd.nextDouble() < 0.25 + 0.6 * rt ? 3 : 2) : 1;
                }
                Progress.Logged l = new Progress.Logged();
                l.at = at; l.h = h; l.grade = g; l.mode = j % 3 == 0 ? "choose" : j % 3 == 1 ? "pick" : "listen"; l.ms = 3000;
                out.add(l);
                if (j == 0) {
                    s = W[g - 1]; d = Progress.d0(W, g);
                    if (truth != null) { ts = truth[g - 1]; td = Progress.d0(truth, g); }
                } else {
                    double r = Progress.recall(s, days);
                    s = g == 1 ? Math.max(0.05, Progress.lapseS(W, s, d, r)) : Progress.recallS(W, s, d, r, g);
                    d = Progress.nextD(W, d, g);
                    if (truth != null) {
                        double rt = Progress.recall(ts, days);
                        ts = Math.min(Progress.MAX_S, g == 1 ? Math.max(0.05, Progress.lapseS(truth, ts, td, rt)) : Progress.recallS(truth, ts, td, rt, g));
                        td = Progress.nextD(truth, td, g);
                    }
                }
                s = Math.min(s, Progress.MAX_S);
                last = at;
                long ivl = g == 1 ? Progress.RELEARN : (long) (s * DAY);
                at += (long) (ivl * (truth == null ? 1 : 0.7 + rnd.nextDouble() * 1.8));   // late, or early
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        long shift = end - 3600_000L - out.get(out.size() - 1).at;   // the last an hour before end
        for (Progress.Logged l : out) l.at += shift;
        return out;
    }

    /** The log written into the database, as Progress.grade writes it, in one go. */
    static void insert(Progress p, List<Progress.Logged> log) {
        SQLiteDatabase db = p.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Progress.Logged l : log) {
                ContentValues v = new ContentValues();
                v.put("at", l.at); v.put("h", l.h); v.put("grade", l.grade); v.put("mode", l.mode); v.put("ms", l.ms);
                v.put("r", 0); v.put("s", 0); v.put("d", 0);
                db.insert("reviews", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A background fit waited for, and its result kept (posted to the main thread). */
    static void settle(Progress p) throws Exception {
        Thread t = p.fitting;
        if (t != null) t.join(20_000);
        ShadowLooper.idleMainLooper();
        assertNull("the fit has finished", p.fitting);
    }

    /** The log loss as Progress.grade itself would have replayed the log, using its formulas:
     *  what Fit.loss must agree with. */
    static double reference(double[] w, List<Progress.Logged> log) {
        java.util.Map<String, double[]> st = new java.util.HashMap<>();   // h → {s, d, last}
        double sum = 0;
        int n = 0;
        for (Progress.Logged l : log) {
            double[] c = st.get(l.h);
            int g = l.grade;
            if (c == null) {
                st.put(l.h, new double[]{Math.min(w[g - 1], Progress.MAX_S), Progress.d0(w, g), l.at});
                continue;
            }
            double r = Progress.recall(c[0], (l.at - c[2]) / (double) DAY);
            double rc = Math.min(1 - 1e-6, Math.max(1e-6, r));
            sum -= g >= 2 ? Math.log(rc) : Math.log(1 - rc);
            n++;
            c[0] = g == 1 ? Math.max(0.05, Progress.lapseS(w, c[0], c[1], r)) : Progress.recallS(w, c[0], c[1], r, g);
            c[0] = Math.min(c[0], Progress.MAX_S);
            c[1] = Progress.nextD(w, c[1], g);
            c[2] = l.at;
        }
        return sum / n;
    }

    static double[] arr(JSONArray a) {
        double[] out = new double[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optDouble(i);
        return out;
    }

    @Test
    public void recovers() throws Exception {
        open();   // the path loaded
        long now = System.currentTimeMillis();
        List<Progress.Logged> log = simulate(TRUTH, 200, 15, 7, now);
        assertTrue("about 3000 reviews: " + log.size(), log.size() >= 2800 && log.size() <= 3000);
        Fit.Data[] d = Fit.split(log);
        assertEquals(200, d[0].cards);
        assertTrue("a fifth held out: " + d[2].cards, d[2].cards >= 25 && d[2].cards <= 55);
        assertEquals(d[0].reviews, d[1].reviews + d[2].reviews);

        // the fit's replay is Progress's: the same loss as its own formulas give, at the
        // defaults and elsewhere; and the gradient is the loss's
        assertEquals(reference(Progress.W, log), Fit.loss(Progress.W, d[0], null), 1e-9);
        assertEquals(reference(TRUTH, log), Fit.loss(TRUTH, d[0], null), 1e-9);
        double[] g = new double[17];
        Fit.loss(TRUTH, d[1], g);
        for (int k : Fit.FREE) {
            double h = 1e-6 * Math.max(1, TRUTH[k]);
            double[] a = TRUTH.clone(), b = TRUTH.clone();
            a[k] += h; b[k] -= h;
            double fd = (Fit.loss(a, d[1], null) - Fit.loss(b, d[1], null)) / (2 * h);
            assertEquals("d/dw" + k, fd, g[k], 1e-5 + 1e-4 * Math.abs(fd));
        }

        long t = System.currentTimeMillis();
        JSONObject r = Fit.run(log, now);
        long ms = System.currentTimeMillis() - t;
        assertNotNull(r);
        assertEquals(log.size(), r.getInt("n"));
        assertTrue("fitted, kept: " + r, r.has("w"));
        double before = r.getDouble("before"), after = r.getDouble("after");
        assertTrue("predicts the held-out cards better by the margin: " + before + " → " + after, after < before * (1 - Fit.MARGIN));
        assertTrue("in a time a phone can afford: " + ms + " ms", ms < 3000);
        double[] w = arr(r.getJSONArray("w"));
        for (int k = 0; k < 17; k++) assertTrue("w" + k + " in its bounds", w[k] >= Fit.LO[k] - 1e-9 && w[k] <= Fit.HI[k] + 1e-9);
        assertEquals("easy's first stability isn't fitted", Progress.W[3], w[3], 1e-9);
        assertTrue("it found the learner forgets fresh items faster: " + w[2], w[2] < Progress.W[2]);
        double truthHeld = Fit.loss(TRUTH, d[2], null);
        System.out.println("fit: " + log.size() + " reviews, held-out loss defaults " + before + ", fitted " + after
                + ", the learner's own weights " + Fit.round(truthHeld) + "; " + ms + " ms");

        // and on a bigger log, a slow phone's worth: 5000 reviews within the budget
        List<Progress.Logged> big = simulate(TRUTH, 340, 15, 11, now);
        t = System.currentTimeMillis();
        Fit.run(big, now);
        System.out.println("fit: " + big.size() + " reviews in " + (System.currentTimeMillis() - t) + " ms");
        assertTrue(System.currentTimeMillis() - t < 5000);
    }

    @Test
    public void noise() throws Exception {
        // outcomes drawn from the defaults' own predictions: nothing to learn but noise
        open();
        long now = System.currentTimeMillis();
        for (long seed = 1; seed <= 3; seed++) {
            JSONObject r = Fit.run(simulate(null, 200, 15, seed, now), now);
            assertNotNull(r);
            assertFalse("the guard keeps the defaults: " + r, r.has("w"));
            assertEquals("no better", r.getString("why"));
            Main m = open();
            m.prog.putFit(r);
            assertSame(Progress.W, m.prog.w);
            assertSame("and after a reload", Progress.W, new Progress(m).w);
        }
    }

    @Test
    public void threshold() throws Exception {
        Main m = open();
        long now = System.currentTimeMillis();
        assertNull("too few reviews: nothing fitted", Fit.run(simulate(TRUTH, 30, 13, 3, now), now));
        // enough reviews, too few second looks
        List<Progress.Logged> firsts = simulate(TRUTH, 420, 1, 3, now);
        assertEquals(420, firsts.size());
        assertNull(Fit.run(firsts, now));

        insert(m.prog, simulate(TRUTH, 30, 4, 3, now));
        assertFalse(Fit.due(m.prog, now));
        assertFalse(Fit.start(m.prog, now, false, null));
        assertEquals("Review timing: the defaults (fitted once you have 400 reviews; you have 120)",
                Settings.timingLine(m.prog, m.prog.logCounts()));
        m.openSettings();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Review timing: the defaults (fitted once you have 400 reviews; you have 120)"));
        assertNull("nothing to reset", WalkTest.find(m.root, "Reset to defaults"));
        shot(m, "f1-settings-defaults");
        // a round's end below the threshold: nothing runs
        RoundTest.due(m, 3);
        m.startReview();
        RoundTest.answer(m);
        assertNull(m.prog.fitting);
        assertNull(m.prog.fsrs);
    }

    @Test
    public void onlyGrades() throws Exception {
        long now = System.currentTimeMillis();
        Main m = open();
        List<Progress.Logged> log = simulate(TRUTH, 200, 15, 7, now);
        insert(m.prog, log);
        // practice answers and sentences read, all of them "wrong" and at odd times: ignored
        List<Progress.Logged> noise = new ArrayList<>();
        Random rnd = new Random(5);
        for (int i = 0; i < 2000; i++) {
            Progress.Logged l = new Progress.Logged();
            l.at = now - rnd.nextInt(300) * DAY; l.h = PathData.items.get(rnd.nextInt(200)).h; l.grade = 1;
            l.mode = i % 4 == 0 ? "read" : i % 4 == 1 ? "practice-choose" : i % 4 == 2 ? "practice-pick" : "practice-tone";
            noise.add(l);
        }
        insert(m.prog, noise);
        assertEquals(log.size(), m.prog.logCounts()[0]);
        assertTrue(Fit.due(m.prog, now));
        assertTrue(Fit.start(m.prog, now, false, null));
        settle(m.prog);
        assertNotNull(m.prog.fsrs);
        assertEquals("fitted to the grades alone", log.size(), m.prog.fsrs.getInt("n"));
        assertEquals("the same fit as the grades alone give", Fit.run(log, now).getJSONArray("w").toString(),
                m.prog.fsrs.getJSONArray("w").toString());
    }

    @Test
    public void use() throws Exception {
        long now = System.currentTimeMillis();
        Main m = open();
        List<Progress.Logged> log = simulate(TRUTH, 200, 15, 7, now);
        insert(m.prog, log);
        // an item met before the fit keeps its s and d until its next grade
        String old = PathData.items.get(300).h;
        Progress.Card k = new Progress.Card();
        k.s = 4; k.d = 5; k.reps = 3; k.grade = 3; k.first = now - 30 * DAY; k.last = now - 4 * DAY; k.due = now;
        m.prog.cards.put(old, k);
        m.prog.putCard(old, k);

        // a round's end runs it in the background, and keeps it
        RoundTest.due(m, 0);
        m.prog.cards.put(old, k);
        m.startReview();
        RoundTest.answer(m);
        settle(m.prog);
        assertNotNull("a graded round's end ran a fit", m.prog.fsrs);
        assertNotSame("fitted", Progress.W, m.prog.w);
        double[] w = m.prog.w;
        assertArrayEquals("as kept", w, Progress.weights(new Progress(m).fsrs), 0);
        assertArrayEquals("and in use after a reload", w, new Progress(m).w, 0);
        assertFalse("not again within the week", Fit.due(m.prog, now + 6 * DAY));
        assertTrue("a week on", Fit.due(m.prog, now + Fit.EVERY + 60_000));

        // a new item starts on the fitted weights
        String fresh = PathData.items.get(400).h;
        m.prog.grade(fresh, 3, "check", 2000);
        assertEquals(w[2], m.prog.cards.get(fresh).s, 1e-9);
        assertEquals(Progress.d0(w, 3), m.prog.cards.get(fresh).d, 1e-9);
        // the one met before: its s and d as they were, the fitted formulas from them
        Progress.Card c = m.prog.cards.get(old);
        c.last -= 5 * DAY;   // five days since its review in the round
        double s0 = c.s, d0 = c.d, r = Progress.recall(c, System.currentTimeMillis());
        m.prog.grade(old, 2, "choose", 5000);
        assertEquals(Progress.recallS(w, s0, d0, r, 2), m.prog.cards.get(old).s, 1e-3 * s0);
        assertEquals(Progress.nextD(w, d0, 2), m.prog.cards.get(old).d, 1e-9);
        assertNotEquals("not what the defaults would give", Progress.recallS(Progress.W, s0, d0, r, 2), m.prog.cards.get(old).s, 1e-3);

        // Settings says so
        String day = new java.text.SimpleDateFormat("MMM d", java.util.Locale.ENGLISH).format(new java.util.Date(now));
        m.openSettings();
        ShadowLooper.idleMainLooper();
        assertEquals("the log and the round's one grade", log.size() + 1, m.prog.fsrs.getInt("n"));
        String line = "Review timing: fitted to your " + (log.size() + 1) + " reviews (" + day + ")";
        assertNotNull(line, WalkTest.find(m.root, line));
        shot(m, "f2-settings-fitted");

        // a copy carries them
        JSONObject copy = new JSONObject(m.prog.export());
        assertTrue(copy.getJSONObject("meta").getJSONObject("fsrs").has("w"));
        String text = copy.toString();
        m.prog.clearFit();
        assertSame(Progress.W, m.prog.w);
        m.prog.replace(text);
        assertArrayEquals("loaded back from the copy", w, m.prog.w, 0);
        assertArrayEquals(w, new Progress(m).w, 0);
        // a copy from before the fit: the defaults
        copy.getJSONObject("meta").remove("fsrs");
        m.prog.replace(copy.toString());
        assertSame(Progress.W, m.prog.w);
        assertNull(m.prog.fsrs);
        m.prog.replace(text);

        // reset to defaults, by hand: kept so, not refitted on its own
        m.openSettings();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Reset to defaults");
        assertSame(Progress.W, m.prog.w);
        assertSame(Progress.W, new Progress(m).w);
        assertTrue(m.prog.fsrs.getBoolean("reset"));
        assertNotNull(WalkTest.find(m.root, "Review timing: the defaults (put back on " + day + ")"));
        assertFalse("not refitted on its own", Fit.due(m.prog, now + 30 * DAY));
        shot(m, "f3-settings-reset");
        m.prog.grade(PathData.items.get(401).h, 3, "check", 2000);
        assertEquals("new items on the defaults again", Progress.W[2], m.prog.cards.get(PathData.items.get(401).h).s, 1e-9);
        // and Fit to me fits again
        WalkTest.tap(m, "Fit to me");
        settle(m.prog);
        assertNotSame(Progress.W, m.prog.w);
        assertFalse(m.prog.fsrs.optBoolean("reset"));
        assertNotNull(WalkTest.find(m.root, "Review timing: fitted to your"));
        assertNotNull(WalkTest.find(m.root, "Reset to defaults"));
    }

    @Test
    public void lines() throws Exception {
        Main m = open();
        Progress p = m.prog;
        assertEquals("Review timing: the defaults (fitted once 100 reviews are of things seen before; you have 60)",
                Settings.timingLine(p, new long[]{450, 390}));
        assertEquals("Review timing: the defaults (fitted to your 900 reviews after your next round)",
                Settings.timingLine(p, new long[]{900, 300}));
        p.putFit(new JSONObject().put("n", 900).put("at", System.currentTimeMillis()).put("why", "no better"));
        assertTrue(Settings.timingLine(p, new long[]{900, 300}).startsWith("Review timing: the defaults (a fit to your 900 reviews predicted no better, "));
        // a kept value that isn't 17 numbers: the defaults
        p.putFit(new JSONObject().put("n", 900).put("at", 1).put("w", new JSONArray().put(1).put(2)));
        assertSame(Progress.W, p.w);
        p.putMeta("fsrs", "{\"w\": [1, \"x\"]}");
        assertSame(Progress.W, new Progress(m).w);
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        long now = System.currentTimeMillis();
        Main m = open();
        insert(m.prog, simulate(TRUTH, 30, 4, 3, now));
        m.openSettings();
        ShadowLooper.idleMainLooper();
        shot(m, "fn1-settings-defaults");
        insert(m.prog, simulate(TRUTH, 200, 15, 7, now));
        m.prog.putFit(Fit.run(m.prog.reviewsSince(0), now));
        assertNotSame(Progress.W, m.prog.w);
        m.openSettings();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "Review timing: fitted to your"));
        shot(m, "fn2-settings-fitted");
    }
}
