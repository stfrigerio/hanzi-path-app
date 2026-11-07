package dev.stefanofrigerio.hanzipath;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Stats: empty, then over sixty days of a seeded review log and cards with lapses; the figures
 * it shows checked against the seed, a hard one opened and back; light and dark. Renders s*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class StatsTest {
    static final long DAY = 24 * 3600_000L;

    /** Reviews a day, d days ago: uneven, a few days off, more lately. */
    static int count(int d) { return d % 9 == 4 || d == 1 ? 0 : 4 + (d * 7) % 11 + (d < 14 ? 6 : 0); }

    /** Of those, how many were wrong (grade 1): fewer as the weeks go on. */
    static int wrong(int d, int n) { return Math.min(n, (d % 3 == 0 ? 2 : 1) + d / 20); }

    /** Answer time of the k-th review of day d: quicker in recent days. */
    static long ms(int d, int k) { return (d < 7 ? 2200 : 3100) + (k % 5) * 400 + d * 10; }

    /** Noon, d days ago. */
    static long noon(int d) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DATE, -d);
        return c.getTimeInMillis();
    }

    /** The Monday of the week t falls in, as the calendar counts weeks from Monday. */
    static long monday(long t) {
        Calendar c = Calendar.getInstance();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        return c.getTimeInMillis();
    }

    /** The cards that were missed, in the order Stats must list them: most lapses, then least stable. */
    static final int[] LAPSES = {6, 5, 5, 4, 4, 4, 3, 3, 2, 2, 2, 1, 1, 1};
    static final double[] STAB = {9, 1.5, 4, 0.8, 2.2, 7, 3, 12, 0.5, 1, 30, 2, 5, 40};
    static final int[] AT = {17, 30, 22, 41, 12, 35, 8, 26, 50, 19, 44, 33, 5, 28};   // path positions

    static void seed(Main m) {
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        p.cards.clear();
        for (int i = 0; i < 60; i++) {
            Progress.Card k = new Progress.Card();
            boolean known = i % 3 != 0;
            k.s = known ? 60 : 3; k.d = 5; k.reps = 4; k.first = now - 60 * DAY;
            k.last = known ? now - DAY : now - 3600_000L; k.due = now + 2 * DAY; k.grade = 3;
            p.cards.put(PathData.items.get(i).h, k);
        }
        for (int j = 0; j < LAPSES.length; j++) {
            Progress.Card k = p.cards.get(PathData.items.get(AT[j]).h);
            k.lapses = LAPSES[j];
            k.s = STAB[j];
        }
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet()) p.putCard(e.getKey(), e.getValue());
        SQLiteDatabase db = p.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int d = 59; d >= 0; d--) {
                int n = count(d), w = wrong(d, n);
                for (int k = 0; k < n; k++) {
                    ContentValues v = new ContentValues();
                    v.put("at", noon(d) + k * 60_000L);
                    v.put("h", PathData.items.get((d * 13 + k) % 60).h);
                    v.put("grade", k < w ? 1 : k % 4 == 0 ? 2 : 3);
                    v.put("mode", k % 3 == 0 ? "pick" : "choose");
                    v.put("ms", ms(d, k));
                    v.put("r", 0.9); v.put("s", 5.0); v.put("d", 5.0);
                    db.insert("reviews", null, v);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static <T extends View> T first(View v, Class<T> k) { return HomeTest.first(v, k); }

    /** The hard rows, top to bottom: the touchable rows that say how often they were missed. */
    static List<String> hardRows(View v, List<String> out) {
        CharSequence d = v.getContentDescription();
        if (v.hasOnClickListeners() && d != null && d.toString().contains(", missed ")) out.add(d.toString());
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) hardRows(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    @Test
    public void stats() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        HomeTest shots = new HomeTest();

        // from home, the row under the garden
        ((View) WalkTest.find(m.root, "STATS").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("stats", m.screen);
        shots.shot(m, "s1-empty");
        assertNotNull(WalkTest.find(m.root, "Nothing reviewed yet"));
        assertNotNull(WalkTest.find(m.root, "No answers yet to count."));
        assertNotNull(WalkTest.find(m.root, "Nothing met yet"));
        assertNotNull(WalkTest.find(m.root, "Nothing to miss yet."));
        assertNotNull(WalkTest.find(m.root, "No answers timed"));
        assertNull("no empty charts", first(m.root, WeekView.class));
        assertNull(first(m.root, Stats.RetentionView.class));
        assertTrue(m.back());
        assertEquals("home", m.screen);

        // sixty days of reviews, fourteen cards missed
        seed(m);
        m.openStats();
        ShadowLooper.idleMainLooper();
        shots.shot(m, "s2-stats");

        // 1. per day: the last thirty, today last and marked
        WeekView wv = first(m.root, WeekView.class);
        int[] want = new int[30];
        int total = 0;
        for (int d = 0; d < 30; d++) { want[29 - d] = count(d); total += count(d); }
        assertArrayEquals(want, wv.counts);
        assertEquals(29, wv.today);
        assertEquals("today", wv.days[29]);
        assertNotNull(WalkTest.find(m.root, total + " in 30 days · " + String.format(Locale.ROOT, "%.1f", total / 30.0) + " a day"));

        // 2. answered right, by calendar week from Monday: the last eight
        long now = System.currentTimeMillis();
        Map<Long, int[]> byWeek = new HashMap<>();
        long all = 0, right = 0;
        for (int d = 0; d < 60; d++) {
            int n = count(d), w = wrong(d, n);
            all += n; right += n - w;
            int[] x = byWeek.get(monday(noon(d)));
            if (x == null) byWeek.put(monday(noon(d)), x = new int[2]);
            x[0] += n; x[1] += n - w;
        }
        List<Long> mondays = new ArrayList<>(byWeek.keySet());
        Collections.sort(mondays);
        List<Long> last8 = new ArrayList<>();
        for (long mo : mondays) if (mo >= monday(now) - 7 * 7 * DAY - 3600_000L && byWeek.get(mo)[0] > 0) last8.add(mo);
        Stats.RetentionView rv = first(m.root, Stats.RetentionView.class);
        assertEquals(last8.size(), rv.v.length);
        for (int i = 0; i < last8.size(); i++) {
            int[] x = byWeek.get(last8.get(i));
            assertEquals("week of " + rv.labels[i], x[1] / (double) x[0], rv.v[i], 1e-9);
            String full = new java.text.SimpleDateFormat("d MMM", Locale.ENGLISH).format(new java.util.Date(last8.get(i))).toLowerCase(Locale.ROOT);
            assertTrue("week of " + full + ": " + rv.labels[i], full.startsWith(rv.labels[i]) && (i > 0 || rv.labels[i].equals(full)));
        }
        assertEquals("this week's dot marked", rv.v.length - 1, rv.current);
        assertNotNull(WalkTest.find(m.root, Math.round(100.0 * right / all) + "% overall · " + all + " answers"));

        // 3. the path
        int known = 0;
        for (String h : m.prog.cards.keySet()) if (m.prog.strength(h, now) >= Progress.KNOWN) known++;
        Stats.PathBar bar = first(m.root, Stats.PathBar.class);
        assertEquals(known, bar.known);
        assertEquals(60 - known, bar.learning);
        assertNotNull(WalkTest.find(m.root, "60 of " + PathData.items.size() + " met"));
        assertNotNull(WalkTest.find(m.root, known + " known"));

        // 4. the hardest: ten, most lapses first, then the least stable
        Integer[] order = new Integer[LAPSES.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> LAPSES[a] != LAPSES[b] ? LAPSES[b] - LAPSES[a] : Double.compare(STAB[a], STAB[b]));
        List<String> rows = hardRows(m.root, new ArrayList<>());
        assertEquals(10, rows.size());
        for (int i = 0; i < 10; i++) {
            Item it = PathData.items.get(AT[order[i]]);
            int n = LAPSES[order[i]];
            assertEquals(it.h + " " + it.reading() + " " + it.en + ", missed " + (n == 1 ? "once" : n == 2 ? "twice" : n + " times"), rows.get(i));
        }
        assertEquals("6 lapses first", PathData.items.get(17).h, rows.get(0).split(" ")[0]);
        assertEquals("of the fives, the less stable", PathData.items.get(30).h, rows.get(1).split(" ")[0]);
        assertNotNull(WalkTest.find(m.root, "missed 6 times"));

        // 5. time: the median this week and last
        long mon = monday(now);
        List<Long> a = new ArrayList<>(), b = new ArrayList<>();
        for (int d = 0; d < 60; d++)
            for (int k = 0; k < count(d); k++) {
                long at = noon(d) + k * 60_000L;
                if (at >= mon) a.add(ms(d, k)); else if (at >= mon - 7 * DAY + 3 * 3600_000L) b.add(ms(d, k));
            }
        if (!a.isEmpty()) assertNotNull(WalkTest.find(m.root, Stats.secs(Stats.median(a)) + " · " + a.size() + " answers"));
        assertNotNull(WalkTest.find(m.root, Stats.secs(Stats.median(b)) + " · " + b.size() + " answers"));
        Collections.sort(b);
        assertTrue("the median is the middle one", b.size() % 2 == 0 ? Stats.median(b) == (b.get(b.size() / 2 - 1) + b.get(b.size() / 2)) / 2 : Stats.median(b) == b.get(b.size() / 2));

        // a hard one opens; back comes to stats, then home
        String h = PathData.items.get(17).h;
        View row = null;
        List<View> all2 = new ArrayList<>();
        collect(m.root, all2);
        for (View v : all2) if (v.getContentDescription().toString().startsWith(h + " ")) row = v;
        assertNotNull(row);
        row.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertTrue(m.learn.lookup);
        assertEquals(h, m.learn.items.get(0));
        shots.shot(m, "s3-hard-open");
        assertTrue(m.back());
        assertEquals("back to stats", "stats", m.screen);
        assertNotNull(first(m.root, WeekView.class));
        assertTrue(m.back());
        assertEquals("home", m.screen);
        shots.shot(m, "s4-home");
        assertNotNull(WalkTest.find(m.root, "STATS"));
    }

    static void collect(View v, List<View> out) {
        CharSequence d = v.getContentDescription();
        if (v.hasOnClickListeners() && d != null && d.toString().contains(", missed ")) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        HomeTest shots = new HomeTest();
        m.openStats();
        shots.shot(m, "sn1-empty");
        seed(m);
        m.openStats();
        shots.shot(m, "sn2-stats");
    }
}
