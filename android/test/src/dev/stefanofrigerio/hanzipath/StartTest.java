package dev.stefanofrigerio.hanzipath;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Fast to open: the start, timed and held to a budget, and what it moved off the main thread
 * or put off still the same when it's wanted.
 *
 * The start as it was (before "Fast to open") is rebuilt here on the main thread, step by
 * step, as the app did it: path.json and sentences.json parsed whole with org.json, the
 * progress read, home built in onCreate and built again by the onResume after it. The start
 * as it is runs Main itself. Both cold (every static the path and its indexes keep cleared),
 * the best of several runs, on a year's progress (900 characters met, 300 words). The table
 * goes to shots/start.txt.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class StartTest {
    static final long DAY = 86_400_000L;
    static final int RUNS = 7;
    static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();

    /** As before the app was opened: nothing of the path read, no index built. */
    static void cold() {
        waitPreload();
        PathData.items.clear(); PathData.at.clear(); PathData.chunks.clear(); PathData.glyphs.clear();
        Words.built = -1;
        Sentences.all.clear(); Sentences.loaded = false;
        Search.index = null; Search.wordsByBase = null;
        Tree.families = null; Tree.below = null; Tree.indexed = -1;
        Levels.hsk = null;
    }

    /** Main.preload's threads, done (they hold the locks while they work). */
    static void waitPreload() {
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getName().equals("path") || t.getName().equals("sentences")) {
                try { t.join(10_000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
    }

    /** A year in: the first 900 items met (most known, some due), 300 words. */
    static void seed(Progress p) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 900; i++) {
            Progress.Card k = new Progress.Card();
            k.s = i % 5 == 0 ? 3 : 60; k.d = 5; k.reps = 4; k.grade = 3;
            k.first = now - 200 * DAY; k.last = now - DAY; k.due = now + (i % 7 - 1) * DAY;
            p.putCard(PathData.items.get(i).h, k);
        }
        Words.build();
        for (int i = 0; i < 300; i++) {
            Progress.Card k = new Progress.Card();
            k.s = 30; k.d = 5; k.reps = 3; k.grade = 3;
            k.first = now - 100 * DAY; k.last = now - DAY; k.due = now + (i % 9) * DAY;
            p.putCard(Words.use.get(i)[0], k);
        }
        p.introDone = true;
        p.saveOpts();
        p.load();
    }

    // ---- the start as it was, for the comparison and as the reference for what's read ----

    /** path.json as PathData.load read it before: the whole file a string, then org.json. */
    static List<Item> oldPath(Main m) throws Exception {
        List<Item> out = new ArrayList<>();
        JSONObject p = new JSONObject(PathData.read(m, "path.json"));
        JSONArray a = p.getJSONArray("items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Item it = new Item();
            it.h = o.getString("h");
            it.kind = o.getString("k");
            it.en = o.optString("en");
            it.py = PathData.strings(o.optJSONArray("py"));
            it.parts = PathData.strings(o.optJSONArray("parts"));
            it.used = PathData.strings(o.optJSONArray("used"));
            it.strokes = o.optInt("n");
            JSONArray w = o.optJSONArray("w");
            it.words = new String[w == null ? 0 : w.length()][];
            for (int j = 0; j < it.words.length; j++) {
                JSONArray x = w.getJSONArray(j);
                it.words[j] = new String[]{x.getString(0), x.getString(1), x.getString(2), String.valueOf(x.getInt(3)),
                        String.valueOf(x.optInt(4))};   // 1: one of the extra words
            }
            JSONObject et = o.optJSONObject("et");
            if (et != null) {
                it.etType = et.optString("type", null);
                it.etSemantic = et.optString("semantic", null);
                it.etPhonetic = et.optString("phonetic", null);
                it.why = et.optString("why", null);
            }
            it.at = i;
            out.add(it);
        }
        assertEquals("the chunk", p.getInt("chunk"), PathData.chunk);
        return out;
    }

    /** sentences.json as Sentences.load read it before. */
    static List<Sentences.S> oldSentences(Main m) throws Exception {
        List<Sentences.S> out = new ArrayList<>();
        JSONArray a = new JSONObject(PathData.read(m, "sentences.json")).getJSONArray("s");
        for (int i = 0; i < a.length(); i++) {
            JSONArray x = a.getJSONArray(i);
            Sentences.S s = new Sentences.S();
            s.zh = x.getString(0);
            s.py = x.getString(1).split(" ");
            s.en = x.getString(2);
            s.at = x.getInt(3);
            s.han = Sentences.han(s.zh);
            if (s.han.length == s.py.length) out.add(s);
        }
        return out;
    }

    static String show(Item it) {
        StringBuilder b = new StringBuilder();
        b.append(it.at).append('|').append(it.h).append('|').append(it.kind).append('|').append(it.en).append('|')
                .append(Arrays.toString(it.py)).append(Arrays.toString(it.parts)).append(Arrays.toString(it.used)).append('|')
                .append(it.strokes).append('|').append(it.etType).append('|').append(it.etSemantic).append('|')
                .append(it.etPhonetic).append('|').append(it.why).append('|');
        for (String[] w : it.words) b.append(Arrays.toString(w));
        return b.toString();
    }

    static String show(Sentences.S s) {
        return s.at + "|" + s.zh + "|" + Arrays.toString(s.py) + "|" + s.en + "|" + Arrays.toString(s.han);
    }

    /** The path read now is the path read before: every item, every field, in order. */
    @Test
    public void samePath() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().get();
        List<Item> was = oldPath(m);
        assertEquals("2288 items, or however many the path has", was.size(), PathData.items.size());
        for (int i = 0; i < was.size(); i++) {
            assertEquals(show(was.get(i)), show(PathData.items.get(i)));
            assertSame(PathData.items.get(i), PathData.get(was.get(i).h));
        }
        List<Sentences.S> sw = oldSentences(m);
        Sentences.load(m);
        assertEquals("the sentences", sw.size(), Sentences.all.size());
        for (int i = 0; i < sw.size(); i++) assertEquals(show(sw.get(i)), show(Sentences.all.get(i)));
    }

    /** What's put off (Search's index, the families, the HSK lists, the sentences) is the
     *  same, first used cold, built on the main thread or by the start's threads, and a
     *  screen asking before a thread is done waits for it instead of seeing half. */
    @Test
    public void lazyTheSame() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().get();
        seed(m.prog);
        long now = System.currentTimeMillis();
        String[] queries = {"水", "shui", "shui3", "water", "hao", "nihao", "ni3hao3", "wo", "tree", "mu", "zhong", "人口", "xue"};
        List<String> found = new ArrayList<>();
        String[] parts = {"木", "氵", "口", "心", "人", "日"};
        List<String> fams = new ArrayList<>();
        String[] cards = {"人", "好", "我", "是", "中国", "学生"};
        List<String> sent = new ArrayList<>();

        // the start's threads built them
        waitPreload();
        for (String q : queries) found.add(q + Search.show(Search.find(q)));
        for (String p : parts) fams.add(p + Tree.family(p));
        String levels = levels(m, now);
        for (String h : cards) { Sentences.S s = Sentences.forCard(m, h); sent.add(h + (s == null ? "-" : s.zh)); }
        assertTrue("search finds 水 first", Search.find("水").get(0).h.equals("水"));
        assertTrue("search finds water", Search.hs(Search.find("water")).contains("水"));
        assertTrue("木's family has 林", Tree.family("木").contains(PathData.get("林").at));
        assertNotNull("人 has a sentence", Sentences.forCard(m, "人"));

        // cold, each built on the main thread on first use
        cold();
        PathData.load(m);
        for (int i = 0; i < queries.length; i++) assertEquals(found.get(i), queries[i] + Search.show(Search.find(queries[i])));
        for (int i = 0; i < parts.length; i++) assertEquals(fams.get(i), parts[i] + Tree.family(parts[i]));
        assertEquals(levels, levels(m, now));
        for (int i = 0; i < cards.length; i++) {
            Sentences.S s = Sentences.forCard(m, cards[i]);
            assertEquals(sent.get(i), cards[i] + (s == null ? "-" : s.zh));
        }

        // cold, asked for at once while the start's threads are at work on them
        for (int round = 0; round < 3; round++) {
            cold();
            Main.preload(m.getApplicationContext());
            PathData.load(m);
            assertEquals(levels, levels(m, now));
            for (int i = 0; i < cards.length; i++) {
                Sentences.S s = Sentences.forCard(m, cards[i]);
                assertEquals(sent.get(i), cards[i] + (s == null ? "-" : s.zh));
            }
            for (int i = 0; i < queries.length; i++) assertEquals(found.get(i), queries[i] + Search.show(Search.find(queries[i])));
        }
        waitPreload();
    }

    static String levels(Main m, long now) {
        StringBuilder b = new StringBuilder();
        for (Levels.Level l : Levels.levels(m, m.prog, now))
            b.append(l.name()).append(": ").append(l.line()).append(", ").append(l.learning).append(" learning, ").append(l.chars).append('\n');
        return b.toString();
    }

    /** Home is built once on opening, not again by the onResume right after; at rest, as it
     *  was when the second build cancelled the first's fade. */
    @Test
    public void homeOnce() {
        ActivityController<Main> c = Robolectric.buildActivity(Main.class).create();
        Main m = c.get();
        android.view.View first = m.root.getChildAt(0);
        c.start().postCreate(null).resume().visible();
        ShadowLooper.idleMainLooper();
        assertSame("home not built again on the first resume", first, m.root.getChildAt(0));
        assertEquals(1f, first.getAlpha(), 0.001f);
        assertEquals("home", m.screen);
        // coming back to it later still builds it again (a new day, a round's due)
        c.pause().resume();
        assertNotSame("built again when coming back", first, m.root.getChildAt(0));
    }

    static double ms(long ns) { return ns / 1e6; }

    /** The start, before and after, timed: the best of RUNS cold starts, wall time and the
     *  main thread's own CPU time (the work in the first frame's way). Before is Main as it
     *  is, with what it did then on the main thread put back around it: path.json and
     *  sentences.json parsed whole with org.json first, the words indexed, and home built
     *  once more by the onResume. */
    @Test
    public void budget() throws Exception {
        Main seedM = Robolectric.buildActivity(Main.class).setup().get();
        seed(seedM.prog);
        long tid = Thread.currentThread().getId();
        long[] best = new long[12];
        Arrays.fill(best, Long.MAX_VALUE);
        StringBuilder log = new StringBuilder();
        for (int run = 0; run < RUNS; run++) {
            // the steps one by one, as they were
            cold();
            long t = System.nanoTime();
            Ink.init(seedM);
            long ink = System.nanoTime() - t;
            t = System.nanoTime();
            for (Item it : oldPath(seedM)) { PathData.items.add(it); PathData.at.put(it.h, it); }
            long load = System.nanoTime() - t;
            t = System.nanoTime();
            new Progress(seedM).close();
            long prog = System.nanoTime() - t;
            t = System.nanoTime();
            Words.build();
            long words = System.nanoTime() - t;
            t = System.nanoTime();
            Sentences.all.addAll(oldSentences(seedM));
            Sentences.loaded = true;
            long sent = System.nanoTime() - t;
            t = System.nanoTime();
            seedM.home();
            long home = System.nanoTime() - t;

            // before, whole
            cold();
            long w0 = System.nanoTime(), c0 = CPU.getThreadCpuTime(tid);
            for (Item it : oldPath(seedM)) { PathData.items.add(it); PathData.at.put(it.h, it); }
            Words.build();
            Sentences.all.addAll(oldSentences(seedM));
            Sentences.loaded = true;
            ActivityController<Main> c = Robolectric.buildActivity(Main.class).create().start().postCreate(null).resume();
            c.get().home();
            long oldWall = System.nanoTime() - w0, oldCpu = CPU.getThreadCpuTime(tid) - c0;
            ShadowLooper.idleMainLooper();
            c.pause().stop().destroy();

            // after: Main as it is, cold
            cold();
            w0 = System.nanoTime();
            c0 = CPU.getThreadCpuTime(tid);
            c = Robolectric.buildActivity(Main.class).create();
            long create = System.nanoTime() - w0;
            c.start().postCreate(null).resume();
            long newWall = System.nanoTime() - w0, newCpu = CPU.getThreadCpuTime(tid) - c0;
            ShadowLooper.idleMainLooper();
            c.pause().stop().destroy();

            // what's put off, built cold on first use
            cold();
            PathData.load(seedM);
            t = System.nanoTime();
            Search.build();
            Tree.index();
            Levels.hsk(seedM);
            long later = System.nanoTime() - t;

            long[] got = {ink, load, prog, words, sent, home, oldWall, oldCpu, create, newWall, newCpu, later};
            for (int i = 0; i < got.length; i++) best[i] = Math.min(best[i], got[i]);
            log.append(String.format(Locale.ROOT, "run %d: before %.1f ms (main CPU %.1f), after %.1f ms (main CPU %.1f)%n",
                    run, ms(oldWall), ms(oldCpu), ms(newWall), ms(newCpu)));
        }
        String table = String.format(Locale.ROOT,
                "The start, cold, best of %d (ms, Robolectric on the JVM)%n"
                        + "before, on the main thread, step by step:%n"
                        + "  faces %.1f, path.json (org.json) %.1f, progress %.1f, words %.1f, sentences.json (org.json) %.1f, home %.1f, and home again (onResume)%n"
                        + "  to the first frame: %.1f wall, %.1f main-thread CPU%n"
                        + "after (Main.onCreate to onResume):%n"
                        + "  onCreate %.1f; to the first frame: %.1f wall, %.1f main-thread CPU%n"
                        + "put off to first use (or the start's low-priority thread): search, families, HSK lists %.1f%n%s",
                RUNS, ms(best[0]), ms(best[1]), ms(best[2]), ms(best[3]), ms(best[4]), ms(best[5]),
                ms(best[6]), ms(best[7]), ms(best[8]), ms(best[9]), ms(best[10]), ms(best[11]), log);
        System.out.println(table);
        String out = System.getProperty("shots");
        if (out != null) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(out, "start.txt"))) { o.write(table.getBytes("UTF-8")); }
        // The main thread's own work is the budget: wall time here also counts the start's
        // threads waiting for a core on a busy machine (the tests run beside other builds),
        // which a phone's eight don't; it's held only to a generous ceiling.
        assertTrue("the first frame's main-thread work, under 60% of before: " + table, best[10] < 0.6 * best[7]);
        assertTrue("the start, under 1.5 s even here: " + table, best[9] < 1_500_000_000L);
    }
}
