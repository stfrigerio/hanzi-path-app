package dev.stefanofrigerio.hanzipath;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Calendar;

import static org.junit.Assert.*;

/**
 * The home screen: empty, after a batch learnt and checked, and with reviews falling due
 * across the week; the garden folded and open; light and dark. Renders are h*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class HomeTest {
    static final String OUT = System.getProperty("shots");
    static final long DAY = 24 * 3600_000L;

    /** The window as Android lays it out (no re-measuring), each screenful down the scroll. */
    void shot(Main m, String name) throws Exception {
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS);
        View w = m.getWindow().getDecorView();
        ScrollView sv = (ScrollView) m.root.getChildAt(0);
        int content = sv.getChildAt(0).getHeight(), view = sv.getHeight(), n = 0;
        for (int y = 0; n == 0 || (y < content && n < 6); y += view, n++) {
            sv.scrollTo(0, y);
            ShadowLooper.idleMainLooper();
            Bitmap b = Bitmap.createBitmap(w.getWidth(), w.getHeight(), Bitmap.Config.ARGB_8888);
            w.draw(new Canvas(b));
            try (FileOutputStream o = new FileOutputStream(new File(OUT, name + (n == 0 ? "" : "-" + n) + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        }
        sv.scrollTo(0, 0);
        StringBuilder s = new StringBuilder();
        WalkTest.dump(m.root, "", s);
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".txt"))) { o.write(s.toString().getBytes("UTF-8")); }
    }

    static <T extends View> T first(View v, Class<T> k) {
        if (k.isInstance(v)) return k.cast(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            T t = first(((ViewGroup) v).getChildAt(i), k);
            if (t != null) return t;
        }
        return null;
    }

    static int contentHeight(Main m) {
        return ((ScrollView) m.root.getChildAt(0)).getChildAt(0).getHeight();
    }

    /** Sixty cards on the path's first items: some known, some learning, four wilting, due across the week. */
    static int[] seed(Main m) {
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        int[] spread = {6, 9, 4, 12, 2, 0, 7, 20};   // days 0..6, then beyond the week
        int[] want = new int[7];
        int i = 0;
        p.cards.clear();
        for (int day = 0; day < spread.length; day++)
            for (int j = 0; j < spread[day]; j++, i++) {
                Progress.Card k = new Progress.Card();
                boolean known = i % 3 != 0;
                k.s = known ? 60 : 3;
                k.d = 5;
                k.reps = 3;
                k.first = now - 20 * DAY;
                k.last = known ? now - DAY : now - 3600_000L;
                Calendar d = (Calendar) c.clone();
                d.add(Calendar.DATE, day);
                k.due = d.getTimeInMillis() + 12 * 3600_000L;
                k.grade = 3;
                p.cards.put(PathData.items.get(i).h, k);
                if (day < 7) want[day]++;
            }
        // four wilting: overdue and slipping
        for (int j = 0; j < 4; j++, i++) {
            Progress.Card k = new Progress.Card();
            k.s = 2; k.d = 7; k.reps = 4; k.lapses = 1; k.grade = 2;
            k.first = now - 30 * DAY; k.last = now - 9 * DAY; k.due = now - 2 * DAY;
            p.cards.put(PathData.items.get(i).h, k);
            want[0]++;
        }
        // three days in a row
        try {
            for (int d = 0; d < 3; d++) {
                Calendar x = Calendar.getInstance();
                x.add(Calendar.DATE, -d);
                p.days.put(Progress.date(x), new org.json.JSONObject().put("reviews", 12));
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        m.home();
        ShadowLooper.idleMainLooper();
        return want;
    }

    @Test
    public void home() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();

        // empty: nothing due, the first batch next, the garden folded to three rows
        shot(m, "h1-empty");
        assertNotNull(WalkTest.find(m.root, "Nothing due"));
        assertNotNull(WalkTest.find(m.root, "Learn next 5"));
        assertNotNull(WalkTest.find(m.root, "none yet"));
        GardenView g = first(m.root, GardenView.class);
        assertEquals(3 * g.cols, g.count());
        assertTrue("cells stay tappable", g.cell >= 32 * m.getResources().getDisplayMetrics().density - 0.5f);
        assertNotNull(WalkTest.find(m.root, "Show the whole path (" + (PathData.items.size() - g.count()) + " more)"));
        assertNull("no empty bar strip", first(m.root, WeekView.class));
        assertNotNull(WalkTest.find(m.root, "Nothing to review yet"));
        int folded = contentHeight(m);
        assertTrue("home is about a screen and a bit, not five: " + folded, folded < 3 * ((ScrollView) m.root.getChildAt(0)).getHeight() / 2);

        // a batch learnt and checked: five met, their reviews in the week ahead
        WalkTest.tap(m, "Learn next 5");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        for (int i = 0; i < 5; i++) {
            String q = m.quiz.queue.get(0);
            WalkTest.options(m).stream().filter(t -> t.getText().toString().endsWith(PathData.get(q).en)).findFirst().get().performClick();
            ShadowLooper.idleMainLooper();
            WalkTest.tap(m, i < 4 ? "Next" : "Finish");
        }
        WalkTest.tap(m, "Back to the path");
        shot(m, "h2-after-batch");
        assertNotNull(WalkTest.find(m.root, "5 of " + PathData.items.size() + " met"));
        assertNotNull(WalkTest.find(m.root, "0 · 5 done today"));
        int[] wk = first(m.root, WeekView.class).counts;
        int sum = 0;
        for (int n : wk) sum += n;
        assertEquals("the five come back within the week", 5, sum);
        // a perfect check: all five again in an hour (their first learning step), today unless that's past midnight
        Calendar mid = Calendar.getInstance();
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 0); mid.set(Calendar.MILLISECOND, 0);
        mid.add(Calendar.DATE, 1);
        boolean today = System.currentTimeMillis() + 3_600_000L < mid.getTimeInMillis();
        assertEquals("back in an hour after a perfect check", 5, wk[today ? 0 : 1]);

        // reviews spread over the week
        int[] want = seed(m);
        shot(m, "h3-week");
        assertArrayEquals(want, first(m.root, WeekView.class).counts);
        assertArrayEquals(want, Home.week(m.prog, System.currentTimeMillis()));
        assertNotNull(WalkTest.find(m.root, "Review " + m.prog.due(System.currentTimeMillis()).size()));
        assertNotNull(WalkTest.find(m.root, "wilting"));
        g = first(m.root, GardenView.class);
        int shown = g.count();
        assertTrue("every card met is shown", shown >= m.prog.cards.size());
        assertTrue(shown < PathData.items.size());

        // open the garden in place, then fold it again
        int before = contentHeight(m);
        WalkTest.tap(m, "Show the whole path");
        ShadowLooper.idleMainLooper();
        assertSame("expanded in place, not rebuilt", g, first(m.root, GardenView.class));
        assertEquals(PathData.items.size(), g.count());
        assertTrue(contentHeight(m) > before);
        shot(m, "h4-open");
        WalkTest.tap(m, "Fold the path");
        assertEquals(shown, g.count());
        assertEquals(before, contentHeight(m));
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        shot(m, "hn1-empty");
        seed(m);
        shot(m, "hn3-week");
        WalkTest.tap(m, "Show the whole path");
        shot(m, "hn4-open");
    }
}
