package dev.stefanofrigerio.hanzipath;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Writing, back: the judge (a stroke along its median, its way, passes; reversed, or another
 * stroke, or off in a corner, fails), Learn's Write it (traced over the outline, a miss, the
 * hint after two, Again, Stop; stepping left when writing starts, the Step pill out of sight
 * while it lasts), the review's write question (graded from the misses: 0 knew, up to 3
 * unsure, more again; Show it gives up; Undo), Settings' "Writing in reviews" (steps, kept,
 * older settings load as sometimes) and when it's drawn (off never, often about half,
 * sometimes about a quarter; never in the check, for a part, a word or a card seen once;
 * in practice too). Renders w*.png (light), wn*.png (night).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WriteTest {
    static final String OUT = System.getProperty("shots");

    static void settle() { ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS); }

    /** The window as it is now (nothing settled first: a hint mid-flash, a finger mid-stroke). */
    static void still(Main m, String name) throws Exception {
        View w = m.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(w.getWidth(), w.getHeight(), Bitmap.Config.ARGB_8888);
        w.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** The window scrolled so the view v (the pills under a card) is in it, as it is now. */
    static void stillAt(Main m, View v, String name) throws Exception {
        ScrollView sv = (ScrollView) m.root.getChildAt(0);
        int[] a = new int[2], s = new int[2];
        v.getLocationInWindow(a);
        sv.getLocationInWindow(s);
        int y = sv.getScrollY() + a[1] - s[1] - sv.getHeight() + v.getHeight() + Ink.dp(24);
        sv.scrollTo(0, Math.max(0, y));
        ShadowLooper.idleMainLooper();
        still(m, name);
        sv.scrollTo(0, 0);
        ShadowLooper.idleMainLooper();
    }

    // ── a finger on the square ──

    /** Stroke i's median, in the glyph's units, a point every few units (reversed: end first). */
    static List<float[]> along(HanziView hv, int i, boolean reversed) {
        PointF[] md = hv.medians[i];
        List<float[]> out = new ArrayList<>();
        for (int j = 0; j < md.length; j++) {
            if (j == 0) { out.add(new float[]{md[0].x, md[0].y}); continue; }
            for (int k = 1; k <= 8; k++) {
                float t = k / 8f;
                out.add(new float[]{md[j - 1].x + (md[j].x - md[j - 1].x) * t, md[j - 1].y + (md[j].y - md[j - 1].y) * t});
            }
        }
        if (reversed) java.util.Collections.reverse(out);
        return out;
    }

    /** A long line across the very top of the box, over no stroke's path. */
    static List<float[]> offTop() {
        List<float[]> out = new ArrayList<>();
        for (int k = 0; k <= 16; k++) out.add(new float[]{40 + k * 59, 885});
        return out;
    }

    /** The finger down at the first point, through the rest, and up (or held, mid-stroke). */
    static void draw(HanziView hv, List<float[]> glyphPts, boolean up) {
        long t0 = SystemClock.uptimeMillis();
        for (int k = 0; k < glyphPts.size(); k++) {
            float[] p = glyphPts.get(k).clone();
            hv.toScreen.mapPoints(p);
            int action = k == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE;
            MotionEvent e = MotionEvent.obtain(t0, t0 + 16L * k, action, p[0], p[1], 0);
            assertTrue("the square takes the finger", hv.onTouchEvent(e));
            e.recycle();
        }
        if (!up) return;
        float[] p = glyphPts.get(glyphPts.size() - 1).clone();
        hv.toScreen.mapPoints(p);
        MotionEvent e = MotionEvent.obtain(t0, t0 + 16L * glyphPts.size(), MotionEvent.ACTION_UP, p[0], p[1], 0);
        hv.onTouchEvent(e);
        e.recycle();
    }

    static void stroke(HanziView hv, int i) { draw(hv, along(hv, i, false), true); }

    /** Every stroke from the next one on, each along its median. */
    static void rest(HanziView hv) {
        int n = hv.fill.length;
        for (int i = hv.next; i < n; i++) stroke(hv, i);
    }

    static float[][] arr(List<float[]> l) { return l.toArray(new float[0][]); }

    static float length(List<float[]> l) {
        float d = 0;
        for (int i = 1; i < l.size(); i++) d += HanziView.dist(l.get(i - 1)[0], l.get(i - 1)[1], l.get(i)[0], l.get(i)[1]);
        return d;
    }

    static boolean fits(HanziView hv, List<float[]> pts, int stroke) {
        return HanziView.fits(arr(pts), length(pts), hv.medians[stroke]);
    }

    // ── the judge ──

    @Test
    public void judge() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        m.lookup("我");
        settle();
        HanziView hv = m.learn.hv;
        int n = hv.fill.length, reversedFailed = 0, longOnes = 0;
        for (int i = 0; i < n; i++) {
            assertTrue("stroke " + (i + 1) + " along its median, its way", fits(hv, along(hv, i, false), i));
            float len = length(along(hv, i, false));
            if (len > 300) {   // long enough that its ends are apart: reversed must fail
                longOnes++;
                assertFalse("stroke " + (i + 1) + " drawn backwards", fits(hv, along(hv, i, true), i));
                reversedFailed++;
            }
            assertFalse("a line off in the top of the box for stroke " + (i + 1), fits(hv, offTop(), i));
        }
        assertTrue("我 has long strokes to reverse", longOnes >= 3);
        assertEquals(longOnes, reversedFailed);
        // another stroke's path for this one: 我's 1st (the short falling one) and 2nd (the long
        // rising horizontal) and 3rd (the hook down) aren't each other
        assertFalse("the 2nd for the 1st", fits(hv, along(hv, 1, false), 0));
        assertFalse("the 3rd for the 2nd", fits(hv, along(hv, 2, false), 1));
        assertFalse("the 2nd for the 3rd", fits(hv, along(hv, 1, false), 2));
        // a tap isn't a stroke, and isn't a miss
        hv.quiz(3, true, k -> {});
        List<float[]> tap = new ArrayList<>(Arrays.asList(new float[]{500, 400}, new float[]{505, 402}));
        draw(hv, tap, true);
        assertEquals(0, hv.mistakes);
        assertEquals(0, hv.next);
    }

    // ── Learn: Write it ──

    @Test
    public void learn() throws Exception { learn("w"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void learnNight() throws Exception { learn("wn"); }

    void learn(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        m.lookup("我");
        settle();
        HanziView hv = m.learn.hv;
        int n = hv.fill.length;
        TextView write = WalkTest.find(m.root, "Write it");
        assertNotNull("Write it", write);
        TextView done = WalkTest.find(m.root, "Done");
        assertNotNull(done);
        // beside the way on, the same row, the same height
        int[] a = new int[2], b = new int[2];
        write.getLocationInWindow(a);
        done.getLocationInWindow(b);
        assertEquals("beside it", a[1], b[1]);
        assertTrue("left of it", a[0] < b[0]);
        assertEquals(write.getHeight(), done.getHeight());

        // stepping first: writing leaves it, the Step pill out of sight (keeping its place)
        TextView step = StepTest.pill(m, hv);
        step.performClick();
        settle();
        assertEquals(1, hv.step);
        int stepTop = step.getTop();
        write.performClick();
        settle();
        assertEquals("out of stepping", -1, hv.step);
        assertTrue(hv.quizzing);
        assertTrue("over the outline", hv.outline);
        assertEquals("Step out of sight while writing", View.INVISIBLE, step.getVisibility());
        assertEquals("and nothing moved", stepTop, step.getTop());
        assertEquals("Stop", write.getText().toString());
        for (float f : hv.fill) assertEquals("from nothing", 0f, f, 0f);
        still(m, prefix + "1-learn-write");

        // the first stroke, along its median: it fills, no miss
        stroke(hv, 0);
        settle();
        assertEquals(1f, hv.fill[0], 0f);
        assertEquals(0f, hv.fill[1], 0f);
        assertEquals(0, hv.mistakes);
        assertEquals(1, hv.next);
        // the second, backwards: a miss, nothing fills; a second miss and its place flashes
        draw(hv, along(hv, 1, true), true);
        assertEquals(1, hv.mistakes);
        assertEquals(0f, hv.fill[1], 0f);
        assertEquals("no hint after one", -1, hv.hintStroke);
        draw(hv, offTop(), true);
        assertEquals(2, hv.mistakes);
        assertEquals("the hint after two misses", 1, hv.hintStroke);
        assertNotNull(hv.hintAnim);
        hv.hintAnim.setCurrentPlayTime(500);
        assertTrue("showing", hv.hintAlpha > 0.9f);
        // a finger part way along it, held
        List<float[]> half = along(hv, 1, false);
        draw(hv, half.subList(0, half.size() / 2), false);
        assertFalse("the finger's trail", hv.trail.isEmpty());
        still(m, prefix + "2-learn-hint");
        draw(hv, half, true);
        settle();
        assertEquals("the hint goes once it's right", -1, hv.hintStroke);
        assertEquals(1f, hv.fill[1], 0f);
        assertEquals(2, hv.mistakes);
        assertTrue(hv.trail.isEmpty());
        // the rest: done, not graded
        int logged = reviews(m);
        rest(hv);
        settle();
        assertFalse(hv.quizzing);
        assertFalse("the outline goes", hv.outline);
        for (float f : hv.fill) assertEquals("written whole", 1f, f, 0f);
        assertEquals("2 misses. Again", write.getText().toString());
        assertEquals("Step back", View.VISIBLE, step.getVisibility());
        assertEquals("Step", step.getText().toString());
        assertEquals("not graded", logged, reviews(m));
        stillAt(m, write, prefix + "3-learn-written");

        // Again: from nothing, clean this time
        write.performClick();
        settle();
        assertTrue(hv.quizzing);
        assertEquals(0, hv.next);
        rest(hv);
        settle();
        assertEquals("Clean. Again?", write.getText().toString());

        // Stop part way: the character whole again
        write.performClick();
        stroke(hv, 0);
        settle();
        write.performClick();
        settle();
        assertFalse(hv.quizzing);
        assertEquals("Write it", write.getText().toString());
        for (float f : hv.fill) assertEquals("whole", 1f, f, 0f);
        // and stepping while it's being written ends the writing too
        write.performClick();
        settle();
        hv.step();
        settle();
        assertFalse(hv.quizzing);
        assertFalse(hv.outline);
        assertEquals(1, hv.step);
        assertEquals("Write it", write.getText().toString());
        assertEquals(View.VISIBLE, step.getVisibility());
        assertEquals(n, hv.fill.length);
    }

    @Test
    public void onALearnCard() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        WalkTest.tap(m, "Learn next 5");
        settle();
        TextView write = WalkTest.find(m.root, "Write it");
        assertNotNull("Write it on a learn card", write);
        assertNotNull(WalkTest.find(m.root, "Next"));
        write.performClick();
        settle();
        HanziView hv = m.learn.hv;
        rest(hv);
        settle();
        assertEquals("Clean. Again?", write.getText().toString());
        WalkTest.tap(m, "Next");
        settle();
        assertEquals("the next card starts afresh", "Write it", WalkTest.find(m.root, "Write it").getText().toString());
    }

    static int reviews(Main m) {
        try (Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews", null)) {
            c.moveToNext();
            return c.getInt(0);
        }
    }

    // ── a review's write question ──

    @Test
    public void review() throws Exception { review("w"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void reviewNight() throws Exception { review("wn"); }

    void review(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        List<String> items = several(m, 120, 4);
        for (String h : items) TonesTest.card(m, h, 0, 3);
        Quiz q = PickTest.forced(m, items, "write");
        String h = q.queue.get(0);
        Item it = PathData.get(h);
        assertEquals("write", q.mode);
        assertNotNull(WalkTest.find(m.root, "WRITE IT"));
        assertTrue("the reading asked", PickTest.shows(m.root, Ui.pinyin(it.py[0]).toString()));
        assertTrue("the meaning asked", PickTest.shows(m.root, it.en));
        HanziView hv = q.hv;
        assertTrue("written from nothing", hv.quizzing);
        assertFalse("no outline to trace over", hv.outline);
        for (float f : hv.fill) assertEquals(0f, f, 0f);
        assertNotNull("Show it, to give up", WalkTest.find(m.root, "Show it"));
        assertNull("no Next before it's written", WalkTest.find(m.root, "Next"));
        // mid-way: some strokes in, a finger on the next
        int n = hv.fill.length;
        for (int i = 0; i < Math.min(2, n - 1); i++) stroke(hv, i);
        settle();
        List<float[]> next = along(hv, hv.next, false);
        draw(hv, next.subList(0, Math.max(2, next.size() * 2 / 3)), false);
        still(m, prefix + "4-review-write");
        draw(hv, next, true);
        settle();

        // the rest, clean: knew it (3), logged as write, the answer in full, Next
        rest(hv);
        settle();
        assertNotNull("graded", q.picked);
        assertEquals(3, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{"write", "3"}, PickTest.lastLog(m));
        TextView right = WalkTest.find(m.root, "RIGHT");
        assertNotNull(right);
        assertEquals(Ink.tone[2], right.getCurrentTextColor());
        assertNotNull(WalkTest.find(m.root, "Next"));
        assertNull("no Show it once written", WalkTest.find(m.root, "Show it"));
        assertFalse("the character whole", q.hv.quizzing);
        for (float f : q.hv.fill) assertEquals(1f, f, 0f);
        PickTest pt = new PickTest();
        pt.shot(m, prefix + "5-review-written");

        // Undo: open again, written afresh; the grade and its log row gone
        WalkTest.tap(m, "Undo");
        settle();
        assertEquals("write", q.mode);
        assertEquals(h, q.queue.get(0));
        assertNull(q.picked);
        assertTrue(q.hv.quizzing);
        assertEquals("the grade taken back", 3, m.prog.cards.get(h).reps);
        assertEquals(0, reviews(m));
        // two misses this time: unsure (2)
        hv = q.hv;
        draw(hv, offTop(), true);
        draw(hv, offTop(), true);
        rest(hv);
        settle();
        assertEquals(2, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{"write", "2"}, PickTest.lastLog(m));
        TextView nearly = WalkTest.find(m.root, "NEARLY: 2 MISSES");
        assertNotNull(nearly);
        assertEquals(Ink.tone[3], nearly.getCurrentTextColor());

        // the next: many misses (the hint on the way), again (1), and it comes back at the end
        WalkTest.tap(m, "Next");
        settle();
        String h2 = q.queue.get(0);
        assertNotEquals(h, h2);
        assertEquals("write", q.mode);
        hv = q.hv;
        for (int k = 0; k < 4; k++) draw(hv, offTop(), true);
        assertEquals("the hint after three", 0, hv.hintStroke);
        rest(hv);
        settle();
        assertEquals(1, m.prog.cards.get(h2).grade);
        assertArrayEquals(new String[]{"write", "1"}, PickTest.lastLog(m));
        TextView many = WalkTest.find(m.root, "4 MISSES");
        assertNotNull(many);
        assertEquals(Ink.tone[4], many.getCurrentTextColor());
        pt.shot(m, prefix + "6-review-misses");
        WalkTest.tap(m, "Next");
        settle();
        assertEquals("a miss comes back at the end", h2, q.queue.get(q.queue.size() - 1));

        // Show it: given up, again (1), the character shown
        String h3 = q.queue.get(0);
        WalkTest.tap(m, "Show it");
        settle();
        assertEquals(1, m.prog.cards.get(h3).grade);
        assertArrayEquals(new String[]{"write", "1"}, PickTest.lastLog(m));
        assertNotNull(WalkTest.find(m.root, "SHOWN"));
        for (float f : q.hv.fill) assertEquals(1f, f, 0f);
    }

    /** n characters with readings and strokes, from the path's place `from` on. */
    static List<String> several(Main m, int from, int n) {
        List<String> out = new ArrayList<>();
        for (String h : PickTest.some(from, 3 * n)) if (out.size() < n && PathData.glyph(m, h) != null && PathData.glyph(m, h).count() >= 2) out.add(h);
        assertEquals(n, out.size());
        return out;
    }

    // ── practice: written too, logged as practice ──

    @Test
    public void practice() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        List<String> items = several(m, 200, 2);
        for (String h : items) TonesTest.card(m, h, 0, 3);
        Quiz q = new Quiz(m, items, "practice") {
            @Override String kindFor(String h) { return "write"; }
        };
        m.quiz = q;
        q.show();
        settle();
        rest(q.hv);
        settle();
        assertArrayEquals(new String[]{"practice-write", "3"}, PickTest.lastLog(m));
    }

    // ── Settings: Writing in reviews ──

    @Test
    public void settings() throws Exception { settings("w"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void settingsNight() throws Exception { settings("wn"); }

    void settings(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertEquals("sometimes by default", "sometimes", m.prog.write);
        m.openSettings();
        ShadowLooper.idleMainLooper();
        TextView name = WalkTest.find(m.root, "Writing in reviews");
        assertNotNull("in Settings", name);
        TextView row = WalkTest.find(m.root, "sometimes");
        assertNotNull("its choice", row);
        // in Learning: under Reviews per round, above Voice
        int[] a = new int[2], r = new int[2], v = new int[2];
        name.getLocationInWindow(a);
        WalkTest.find(m.root, "Reviews per round").getLocationInWindow(r);
        WalkTest.find(m.root, "VOICE").getLocationInWindow(v);
        assertTrue("under Reviews per round", a[1] > r[1]);
        assertTrue("above Voice", a[1] < v[1]);
        new PickTest().shot(m, prefix + "7-settings");
        row.performClick();
        assertEquals("often", m.prog.write);
        assertTrue(row.getText().toString().startsWith("often"));
        m.prog.load();
        assertEquals("kept", "often", m.prog.write);
        row.performClick();
        assertEquals("off", m.prog.write);
        m.prog.load();
        assertEquals("off", m.prog.write);
        row.performClick();
        assertEquals("sometimes", m.prog.write);
        // in the copy, and back
        org.json.JSONObject opts = m.prog.opts();
        assertEquals("sometimes", opts.getString("write"));
        // settings from before it (and the old "Reviews ask you to" mode): sometimes
        m.prog.putMeta("opts", "{\"batch\":5,\"perDay\":15,\"say\":true,\"mode\":\"read\"}");
        m.prog.write = "off";
        m.prog.load();
        assertEquals("older settings: sometimes", "sometimes", m.prog.write);
        m.prog.putMeta("opts", "{\"batch\":5,\"write\":\"always\"}");
        m.prog.load();
        assertEquals("an unknown value: sometimes", "sometimes", m.prog.write);
    }

    // ── when it's drawn ──

    static int writes(Quiz q, String h, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) if (q.kindFor(h).equals("write")) k++;
        return k;
    }

    @Test
    public void when() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Words.build();
        List<String> items = several(m, 150, 2);
        String h = items.get(0), once = items.get(1);
        TonesTest.card(m, h, 0, 3);
        TonesTest.card(m, once, 0, 3);
        m.prog.cards.get(once).reps = 1;
        Quiz review = new Quiz(m, items, "review", new java.util.Random(7)),
                check = new Quiz(m, items, "check", new java.util.Random(7)),
                practice = new Quiz(m, items, "practice", new java.util.Random(7));
        int n = 2000;

        m.prog.write = "off";
        assertEquals("off: never", 0, writes(review, h, n));
        assertEquals("off: never in practice", 0, writes(practice, h, n));

        m.prog.write = "often";
        int often = writes(review, h, n);
        assertTrue("often: about half, got " + often + " of " + n, often > n * 0.42 && often < n * 0.58);
        assertTrue("practice too", writes(practice, h, n) > n * 0.35);
        assertEquals("never in the check", 0, writes(check, h, n));
        assertEquals("seen once: never", 0, writes(review, once, n));

        m.prog.write = "sometimes";
        int some = writes(review, h, n);
        assertTrue("sometimes: about a quarter, got " + some + " of " + n, some > n * 0.19 && some < n * 0.31);

        // a part, a word: never
        m.prog.write = "often";
        String part = null;
        for (Item it : PathData.items) if (it.isPart() && PathData.glyph(m, it.h) != null) { part = it.h; break; }
        assertNotNull(part);
        TonesTest.card(m, part, 0, 3);
        assertEquals("a part: never", 0, writes(review, part, n));
        String w = Words.all.get(0)[0];
        TonesTest.card(m, w, 0, 3);
        assertEquals("a word: never", 0, writes(review, w, n));
        for (int i = 0; i < 200; i++) assertTrue(review.kindFor(w).startsWith("word-"));

        // a real round draws it, and asks it
        Quiz q = null;
        for (int i = 0; i < 40 && (q == null || !"write".equals(q.mode)); i++) {
            q = new Quiz(m, Arrays.asList(h), "review");
            m.quiz = q;
            q.show();
            ShadowLooper.idleMainLooper();
        }
        assertEquals("drawn in a real round", "write", q.mode);
        assertTrue(q.hv.quizzing);
        assertTrue(q.options.isEmpty());
    }

    // ── the learner's hand ──

    /** Stroke i as the learner might write it from memory: seven tenths the size, off to the right
     *  and down, with a slow wobble. */
    static List<float[]> small(HanziView hv, int i) {
        List<float[]> out = new ArrayList<>();
        List<float[]> a = along(hv, i, false);
        for (int k = 0; k < a.size(); k++) {
            float[] p = a.get(k);
            float w = (float) (25 * Math.sin(k * 0.4 + i));
            out.add(new float[]{(p[0] - 512) * 0.7f + 512 + 120 + w, (p[1] - 388) * 0.7f + 388 - 120 + w});
        }
        return out;
    }

    /** 我 written small and off centre: every stroke taken, none a miss (it learns the hand);
     *  and the wrong stroke written the same way still isn't. */
    @Test
    public void hand() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        m.lookup("我");
        settle();
        HanziView hv = m.learn.hv;
        int n = hv.fill.length;
        int off = 0;
        for (int i = 0; i < n; i++) if (!fits(hv, small(hv, i), i)) off++;
        assertTrue("the old rule alone turns some of these down: " + off, off >= 2);
        int[] got = {-1};
        hv.quiz(3, false, k -> got[0] = k);
        for (int i = 0; i < n; i++) {
            if (i + 1 < n && i > 0) {
                draw(hv, small(hv, i + 1), true);
                assertEquals("the next stroke for stroke " + (i + 1) + " isn't taken", i, hv.next);
            }
            int before = hv.mistakes;
            draw(hv, small(hv, i), true);
            assertEquals("stroke " + (i + 1) + " taken", i + 1, hv.next);
            assertEquals("stroke " + (i + 1) + " not a miss", before, hv.mistakes);
            settle();
        }
        assertEquals("written, with only the wrong strokes as misses", n - 2, got[0]);
    }

    /** Never stuck: after the hint and two more misses, the stroke fills itself, each a miss. */
    @Test
    public void neverStuck() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        m.lookup("我");
        settle();
        HanziView hv = m.learn.hv;
        hv.quiz(3, false, k -> {});
        for (int k = 0; k < 4; k++) { draw(hv, offTop(), true); assertEquals("still the first after " + (k + 1), 0, hv.next); }
        draw(hv, offTop(), true);
        assertEquals("the fifth miss fills it", 1, hv.next);
        assertEquals(5, hv.mistakes);
        assertEquals(0, hv.strokeMisses);
        settle();
        stroke(hv, 1);
        assertEquals("on from there", 2, hv.next);
    }
}
