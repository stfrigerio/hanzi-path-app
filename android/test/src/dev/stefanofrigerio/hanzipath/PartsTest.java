package dev.stefanofrigerio.hanzipath;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The parts: "The parts ›" on home opens every part (the items of kind part, and the
 * characters in another item's parts), each counted here on its own (a walk of every
 * character's tree), most used first; the counts and the known on screen are those. The sort
 * pill turns it to fewest strokes first. Show more builds the next page; All N unfolds a
 * family; a row opens its part's card and a cell its character's, and back returns to the
 * list, in the same order, unfolded as it was, at the same scroll. Renders: p*.png (light),
 * pn*.png (night).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class PartsTest {
    final PickTest pt = new PickTest();

    /** The window as it is now, without scrolling it. */
    static void here(Main m, String name) throws Exception {
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS);
        View w = m.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(w.getWidth(), w.getHeight(), Bitmap.Config.ARGB_8888);
        w.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(PickTest.OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** The parts and how many characters each is in, counted on their own. */
    static Map<String, Integer> counts() {
        Set<String> used = new HashSet<>();
        for (Item it : PathData.items) for (String p : it.parts) if (!p.equals(it.h)) used.add(p);
        Map<String, Integer> out = new HashMap<>();
        for (Item part : PathData.items) {
            if (!part.isPart() && !used.contains(part.h)) continue;
            int n = 0;
            for (Item it : PathData.items)
                if (!it.isPart() && !it.h.equals(part.h) && TreeTest.contains(it.h, part.h, new HashSet<>())) n++;
            out.put(part.h, n);
        }
        return out;
    }

    static List<String> expected(Map<String, Integer> c, boolean strokes) {
        List<String> l = new ArrayList<>(c.keySet());
        Collections.sort(l, (a, b) -> {
            Item x = PathData.get(a), y = PathData.get(b);
            int u = c.get(b) - c.get(a), s = x.strokes - y.strokes;
            if (strokes) return s != 0 ? s : u != 0 ? u : x.at - y.at;
            return u != 0 ? u : s != 0 ? s : x.at - y.at;
        });
        return l;
    }

    static List<String> onScreen(Main m) {
        List<String> out = new ArrayList<>();
        LinearLayout rows = m.parts.rows;
        for (int i = 0; i < rows.getChildCount(); i++) out.add((String) rows.getChildAt(i).getTag());
        return out;
    }

    static View row(Main m, String h) {
        LinearLayout rows = m.parts.rows;
        for (int i = 0; i < rows.getChildCount(); i++) if (h.equals(rows.getChildAt(i).getTag())) return rows.getChildAt(i);
        return null;
    }

    static int known(Main m, String part, long now) {
        int k = 0;
        for (Item it : PathData.items)
            if (!it.isPart() && !it.h.equals(part) && TreeTest.contains(it.h, part, new HashSet<>())
                    && m.prog.strength(it.h, now) >= Progress.KNOWN) k++;
        return k;
    }

    void walk(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        WordsTest.know(m, 300);
        m.home();
        ShadowLooper.idleMainLooper();
        long now = System.currentTimeMillis();

        // from home
        TextView entry = WalkTest.find(m.root, "THE PARTS");
        assertNotNull("home's way in", entry);
        ((View) entry.getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("parts", m.screen);
        assertTrue(PickTest.shows(m.root, "Characters are built from these. Learn a part once, recognise it everywhere."));

        // every part, in the order of an independent count
        Map<String, Integer> c = counts();
        List<String> byUse = expected(c, false);
        List<String> got = new ArrayList<>();
        for (Parts.Part p : Parts.list(false)) got.add(p.it.h);
        assertEquals(byUse, got);
        for (Parts.Part p : Parts.list(false)) assertEquals(p.it.h, (int) c.get(p.it.h), p.chars.length);
        assertTrue("氵 is a part", c.containsKey("氵"));
        assertTrue("口, a character, is one too", c.containsKey("口"));
        assertFalse("想 is in no one's parts", c.containsKey("想"));
        assertEquals(byUse.subList(0, Parts.PAGE), onScreen(m));
        assertNotNull(WalkTest.find(m.root, c.size() + " parts, "));
        // the first rows' counts, known included, as counted here
        for (String h : byUse.subList(0, 6)) {
            int n = c.get(h), k = known(m, h, now);
            assertTrue(h + ": in " + n + ", " + k + " known",
                    PickTest.shows(row(m, h), "in " + n + (n == 1 ? " character, " : " characters, ") + k + " known"));
        }
        // a part the hanzi face has no glyph for is drawn from its strokes
        for (Parts.Part p : Parts.list(false)) {
            if (Parts.drawn(p.it.h) || PathData.glyph(m, p.it.h) == null) continue;
            View sq = m.parts.square(p.it, GardenView.NEAR);
            assertTrue(p.it.h + " from its strokes", sq instanceof HanziView);
        }
        pt.shot(m, prefix + "1-use");

        // the whole family, unfolded and folded
        String top = byUse.get(0);
        GardenView g = TreeTest.all(row(m, top), GardenView.class).get(0);
        int strip = g.count();
        assertTrue("a strip, one row: " + strip, strip >= 8 && strip < c.get(top));
        WalkTest.tap(m, "All " + c.get(top));
        assertEquals((int) c.get(top), g.count());
        assertNotNull(WalkTest.find(row(m, top), "Fold"));
        // its first cell opens that character; back returns, still unfolded
        g.tap.open(g.pick[0]);
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals(PathData.items.get(g.pick[0]).h, m.learn.items.get(0));
        assertTrue(m.back());
        assertEquals("parts", m.screen);
        g = TreeTest.all(row(m, top), GardenView.class).get(0);
        assertEquals((int) c.get(top), g.count());
        WalkTest.tap(m, "Fold");
        assertEquals(strip, g.count());

        // fewest strokes first
        Parts kept = m.parts;
        WalkTest.tap(m, "most used");
        assertTrue(m.parts.strokes);
        assertNotNull(WalkTest.find(m.root, "fewest strokes"));
        List<String> byStrokes = expected(c, true);
        assertEquals(byStrokes.subList(0, Parts.PAGE), onScreen(m));
        for (int i = 1; i < byStrokes.size(); i++)
            assertTrue(PathData.get(byStrokes.get(i - 1)).strokes <= PathData.get(byStrokes.get(i)).strokes);
        pt.shot(m, prefix + "2-strokes");

        // the next page
        WalkTest.tap(m, "Show " + Parts.PAGE + " more");
        assertEquals(byStrokes.subList(0, 2 * Parts.PAGE), onScreen(m));

        // far down the list: a row opens its part's card; back is the list, as it was
        ScrollView sv = (ScrollView) m.root.getChildAt(0);
        String far = byStrokes.get(Parts.PAGE + 20);
        View r = row(m, far);
        int y = r.getTop() + ((View) r.getParent()).getTop();
        sv.scrollTo(0, y);
        ShadowLooper.idleMainLooper();
        int was = sv.getScrollY();
        assertTrue("scrolled: " + was, was > Ink.dp(2000));
        here(m, prefix + "3-far");
        r.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals(far, m.learn.items.get(0));
        pt.shot(m, prefix + "4-card");
        assertTrue(m.back());
        ShadowLooper.idleMainLooper();
        assertEquals("parts", m.screen);
        assertSame(kept, m.parts);
        assertTrue("still by strokes", m.parts.strokes);
        assertEquals(byStrokes.subList(0, 2 * Parts.PAGE), onScreen(m));
        sv = (ScrollView) m.root.getChildAt(0);
        assertEquals("the same place", was, sv.getScrollY());
        here(m, prefix + "5-back");
        // the card's Done too
        row(m, far).performClick();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Done");
        ShadowLooper.idleMainLooper();
        assertEquals("parts", m.screen);
        assertEquals(was, ((ScrollView) m.root.getChildAt(0)).getScrollY());

        // back again: home
        assertTrue(m.back());
        assertEquals("home", m.screen);
        assertNull(m.parts);
    }

    @Test
    public void parts() throws Exception { walk("p"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void partsAtNight() throws Exception { walk("pn"); }
}
