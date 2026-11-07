package dev.stefanofrigerio.hanzipath;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Round 4's polish: which ready words come first (useful ones, numbers last), pick mode's
 * answer (not the question again), and the garden folded to where you are once hundreds are
 * met. Renders are x*.png (light) and xn*.png (dark).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class PolishTest {
    final PickTest pt = new PickTest();

    /** TextViews whose whole text is s. */
    static int count(View v, String s) {
        int n = v instanceof TextView && ((TextView) v).getText().toString().equals(s) ? 1 : 0;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) n += count(((ViewGroup) v).getChildAt(i), s);
        return n;
    }

    static int speakers(View v) {
        int n = v.getContentDescription() != null && v.getContentDescription().toString().startsWith("Say it") ? 1 : 0;   // listen.s: "Say it again"
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) n += speakers(((ViewGroup) v).getChildAt(i));
        return n;
    }

    // which words first

    void words(Main m, String prefix) throws Exception {
        long now = System.currentTimeMillis();
        Set<String> known = WordsTest.know(m, 120);
        List<String> fresh = Words.fresh(m.prog, -1, now);
        int numbers = 0, firstNumber = -1;
        for (int i = 0; i < fresh.size(); i++) if (Words.number(fresh.get(i))) { numbers++; if (firstNumber < 0) firstNumber = i; }
        assertTrue("number compounds are ready too (一二, 十二...): " + fresh, numbers >= 3);
        assertTrue(fresh.size() > numbers + 5);
        for (String w : fresh.subList(0, 5)) {
            assertNotEquals("the first five are HSK words: " + fresh.subList(0, 5), "0", Words.get(w)[3]);
            assertFalse("no number compound among the first five: " + w, Words.number(w));
        }
        assertEquals("numbers last", fresh.size() - numbers, firstNumber);
        // HSK 1 … 6, then CC-CEDICT's, then numbers
        int was = 0;
        for (String w : fresh.subList(0, firstNumber)) {
            int r = Words.rank(Words.get(w));
            assertTrue("lower level first: " + w, r >= was);
            was = r;
        }
        assertTrue("一二 is among the last", fresh.indexOf("一二") >= firstNumber);
        // stable: more ready words come in among them, the ones ready keep their order
        String add = null;
        for (String[] w : Words.use) {
            List<String> unknown = new ArrayList<>();
            for (String c : Words.chars(w[0])) if (!known.contains(c)) unknown.add(c);
            if (unknown.size() == 1 && PathData.get(unknown.get(0)) != null) { add = unknown.get(0); break; }
        }
        assertNotNull(add);
        m.prog.cards.put(add, m.prog.cards.get(PathData.items.get(0).h).copy());
        List<String> more = Words.fresh(m.prog, -1, now);
        assertTrue(more.size() > fresh.size());
        List<String> kept = new ArrayList<>(more);
        kept.retainAll(fresh);
        assertEquals("the same order once more are ready", fresh, kept);
        m.prog.cards.remove(add);

        m.prog.sinceWords = Words.EVERY;
        m.home();
        ShadowLooper.idleMainLooper();
        assertEquals(fresh.subList(0, 5), Words.next(m.prog, 5, now));
        pt.shot(m, prefix + "1-words-home");
        WalkTest.tap(m, "Learn 5 words");
        assertEquals(fresh.subList(0, 5), m.learn.items);
        pt.shot(m, prefix + "1-words-learn");
    }

    @Test
    public void wordsFirst() throws Exception { words(Robolectric.buildActivity(Main.class).setup().visible().get(), "x"); }

    // pick mode's answer

    /** A character well into the path with parts, that leads on, and an etymology. */
    static String rich() {
        for (int i = 150; i < PathData.items.size(); i++) {
            Item it = PathData.items.get(i);
            if (!it.isPart() && it.py.length > 0 && it.parts.length > 0 && it.used.length > 0 && "pictophonetic".equals(it.etType) && it.etSemantic != null) return it.h;
        }
        throw new AssertionError("none");
    }

    void pick(Main m, String prefix) throws Exception {
        String h = rich();
        Item it = PathData.get(h);
        String py = Ui.pinyin(it.py[0]).toString();
        Quiz q = PickTest.forced(m, Arrays.asList(h), "pick");
        assertEquals("the reading asked", 1, count(m.root, py));
        assertEquals("the meaning asked", 1, count(m.root, it.en));
        assertEquals("no speaker before the tap", 0, speakers(m.root));
        PickTest.tapCell(m, PickTest.wrong(m, h));
        assertEquals("the reading once, not again in the answer", 1, count(m.root, py));
        assertEquals("the meaning once, not again in the answer", 1, count(m.root, it.en));
        assertEquals("the speaker, once", 1, speakers(m.root));
        assertTrue(PickTest.shows(m.root, "MADE OF"));
        assertTrue(PickTest.shows(m.root, "LEADS TO"));
        assertTrue(PickTest.shows(m.root, "WHY IT LOOKS LIKE THIS"));
        pt.shot(m, prefix + "2-pick-answer");
        // listen didn't ask them: its answer has them, once
        WalkTest.tap(m, "Undo");
        m.prog.cards.remove(h);
        PickTest.forced(m, Arrays.asList(h), "listen");
        assertEquals(0, count(m.root, it.en));
        PickTest.tapCell(m, h);
        assertEquals("listen's answer: the reading", 1, count(m.root, py));
        assertEquals("listen's answer: the meaning", 1, count(m.root, it.en));
        assertEquals("the question's speaker and the answer's", 2, speakers(m.root));

        // a word, picked
        long now = System.currentTimeMillis();
        WordsTest.know(m, 120);
        String w = null;
        for (String x : Words.fresh(m.prog, -1, now)) if (Words.chars(x).length == 2) { w = x; break; }
        WordsTest.seen(m, w);
        String[] wd = Words.get(w);
        String wpy = Ui.pinyin(wd[1]).toString();
        q = PickTest.forced(m, Arrays.asList(w), "word-pick");
        assertEquals(1, count(m.root, wpy));
        assertEquals(1, count(m.root, wd[2]));
        WordsTest.tapAnswer(m, q, w);
        assertEquals("the word's reading once", 1, count(m.root, wpy));
        assertEquals("the word's meaning once", 1, count(m.root, wd[2]));
        assertEquals(1, speakers(m.root));
        assertTrue(PickTest.shows(m.root, "ITS CHARACTERS"));
        pt.shot(m, prefix + "3-word-pick-answer");
    }

    @Test
    public void pickAnswer() throws Exception { pick(Robolectric.buildActivity(Main.class).setup().visible().get(), "x"); }

    // the garden, folded with hundreds met

    void garden(Main m, String prefix) throws Exception {
        int met = 400;
        WordsTest.know(m, met);
        m.gardenOpen = false;
        m.home();
        ShadowLooper.idleMainLooper();
        GardenView g = HomeTest.first(m.root, GardenView.class);
        int rows = (g.count() + g.cols - 1) / g.cols;
        assertTrue("folded to a few rows: " + rows, rows <= 8);
        assertTrue("from the rows met, not the top", g.first() > 0);
        assertEquals("whole rows", 0, g.first() % g.cols);
        assertTrue("two rows of what's met over the next", met - g.first() >= 2 * g.cols && met - g.first() < 3 * g.cols);
        assertTrue("the next batch and rows ahead", g.first() + g.count() >= met + m.prog.batch + 2 * g.cols);
        TextView line = WalkTest.find(m.root, g.first() + " known before these rows");
        assertNotNull("the line counting what's above", line);
        assertEquals(View.VISIBLE, line.getVisibility());
        int[] a = new int[2], b = new int[2];
        line.getLocationInWindow(a);
        g.getLocationInWindow(b);
        assertTrue("above the garden", a[1] < b[1]);
        assertNotNull(WalkTest.find(m.root, "Show the whole path (" + (PathData.items.size() - g.count()) + " more)"));
        int folded = HomeTest.contentHeight(m);
        assertTrue("home stays about a screen and a bit", folded < 3 * ((ScrollView) m.root.getChildAt(0)).getHeight() / 2);
        pt.shot(m, prefix + "4-garden-folded");

        WalkTest.tap(m, "Show the whole path");
        ShadowLooper.idleMainLooper();
        assertSame(g, HomeTest.first(m.root, GardenView.class));
        assertEquals(0, g.first());
        assertEquals("open: all of it", PathData.items.size(), g.count());
        assertEquals(View.GONE, line.getVisibility());
        assertTrue(HomeTest.contentHeight(m) > folded);
        pt.shot(m, prefix + "5-garden-open");
        WalkTest.tap(m, "Fold the path");
        ShadowLooper.idleMainLooper();
        assertTrue(g.first() > 0);
        assertEquals(View.VISIBLE, line.getVisibility());
        assertEquals(folded, HomeTest.contentHeight(m));

        // a tap on a folded cell opens that item, not the one at the top of the path
        g.tap.open(g.first());
        ShadowLooper.idleMainLooper();
        assertTrue("opens " + PathData.items.get(g.first()).h, PickTest.shows(m.root, PathData.items.get(g.first()).en));
    }

    @Test
    public void foldedGarden() throws Exception { garden(Robolectric.buildActivity(Main.class).setup().visible().get(), "x"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        words(Robolectric.buildActivity(Main.class).setup().visible().get(), "xn");
        pick(Robolectric.buildActivity(Main.class).setup().visible().get(), "xn");
        garden(Robolectric.buildActivity(Main.class).setup().visible().get(), "xn");
    }
}
