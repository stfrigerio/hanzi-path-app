package dev.stefanofrigerio.hanzipath;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import java.util.List;

import static org.junit.Assert.*;

/**
 * Look it up: search opened from home, queries typed (a character, pinyin bare, marked and
 * numbered, a meaning, a word in pinyin, nothing), the order of what's found, a result
 * opened and back to the search, back home; light and dark. Renders are s*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class SearchTest {
    static final String OUT = System.getProperty("shots");

    /** The window as Android lays it out (no re-measuring), each screenful down the scroll. */
    void shot(Main m, String name) throws Exception {
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS);
        View w = m.getWindow().getDecorView();
        ScrollView sv = (ScrollView) m.root.getChildAt(0);
        int content = sv.getChildAt(0).getHeight(), view = sv.getHeight(), n = 0;
        for (int y = 0; n == 0 || (y < content && n < 3); y += view, n++) {
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

    static View byDescription(View v, String d) {
        if (d.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View t = byDescription(((ViewGroup) v).getChildAt(i), d);
            if (t != null) return t;
        }
        return null;
    }

    static void type(Main m, String q) {
        EditText f = HomeTest.first(m.root, EditText.class);
        assertNotNull("a search field", f);
        f.setText(q);
        ShadowLooper.idleMainLooper();
    }

    /** The result rows as shown, by their character. */
    static List<String> shown(Main m) {
        List<String> out = new java.util.ArrayList<>();
        collect(m.search.results, out);
        return out;
    }

    static void collect(View v, List<String> out) {
        if (v.hasOnClickListeners() && v.getContentDescription() != null) out.add(v.getContentDescription().toString().split(" ")[0]);
        else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    static Main open(Main m) {
        View e = byDescription(m.root, "Search the path");
        assertNotNull("a way into search on home", e);
        e.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("search", m.screen);
        return m;
    }

    @Test
    public void search() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        shot(m, "s0-home");
        // the entry sits in the path's section, above the garden
        View entry = byDescription(m.root, "Search the path");
        int[] at = new int[2], g = new int[2];
        entry.getLocationInWindow(at);
        HomeTest.first(m.root, GardenView.class).getLocationInWindow(g);
        assertTrue("the entry is above the garden", at[1] < g[1]);

        // the index: the whole path, quick
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) Search.find(i % 2 == 0 ? "s" : "to");
        long ms = (System.nanoTime() - t0) / 1_000_000 / 200;
        assertTrue("a keystroke's search is quick: " + ms + " ms", ms < 20);

        open(m);
        EditText f = HomeTest.first(m.root, EditText.class);
        assertTrue("the field has the focus", f.hasFocus());
        shot(m, "s1-empty");
        assertNotNull("a hint when empty", WalkTest.find(m.root, "A character, its pinyin"));
        assertTrue(shown(m).isEmpty());

        // the character itself, first
        type(m, "水");
        assertEquals("水", shown(m).get(0));
        // pinyin bare, marked, numbered: 水 and 氵 (shuǐ) before 说 (shuō) or anything else
        for (String q : new String[]{"shui", "shuǐ", "shui3", " Shui 3 "}) {
            List<String> l = Search.hs(Search.find(q));
            assertEquals(q + ": " + l, "水", l.get(0));
            assertEquals(q + ": " + l, "氵", l.get(1));
            assertFalse(q + " is not shuō", l.contains("说"));
        }
        assertTrue("a wrong tone finds neither", Search.find("shui4").isEmpty());
        assertTrue("shu starts shuǐ and shuō", Search.hs(Search.find("shu")).containsAll(java.util.Arrays.asList("水", "说")));
        type(m, "shui3");
        shot(m, "s2-shui3");
        assertEquals(java.util.Arrays.asList("水", "氵"), shown(m).subList(0, 2));

        // a word of the meaning: whole words before what only starts that way
        type(m, "water");
        assertEquals(java.util.Arrays.asList("水", "氵"), shown(m).subList(0, 2));
        type(m, "sun");
        assertEquals("日", shown(m).get(0));
        List<Item> mo = Search.find("mo");
        Item moon = PathData.get("月");
        assertTrue("moon by its meaning's start, after mo's syllables", mo.contains(moon));
        for (int i = mo.indexOf(moon); i < mo.size(); i++)
            assertFalse("the syllable mo comes before prefixes: " + Search.show(mo), Search.py(mo.get(i).reading(), true).base.equals("mo"));
        for (int i = 1; i < mo.size(); i++)
            if (!Search.py(mo.get(i - 1).reading(), true).base.equals("mo") || Search.py(mo.get(i).reading(), true).base.equals("mo"))
                assertTrue("path order inside a tier", mo.get(i - 1).at < mo.get(i).at);
        // a word spelled in pinyin: its characters, in order
        String[] w = PathData.items.get(4).words.length > 0 ? PathData.items.get(4).words[0] : null;   // 人口 rén kǒu
        if (w != null) {
            String bare = java.text.Normalizer.normalize(w[1], java.text.Normalizer.Form.NFD).replaceAll("[^a-z]", "");
            List<String> l = Search.hs(Search.find(bare));
            assertTrue(bare + ": " + l, l.indexOf(String.valueOf(w[0].charAt(0))) >= 0 && l.indexOf(String.valueOf(w[0].charAt(1))) >= 0);
            assertEquals(Search.hs(Search.find(w[1])), l);
        }
        // a few in path order, with a count over them
        type(m, "ren");
        shot(m, "s3-ren");
        assertEquals("人", shown(m).get(0));
        assertNotNull(WalkTest.find(m.root, shown(m).size() + " found"));
        // a short query: a long list, cut short
        type(m, "s");
        assertTrue(shown(m).size() <= Search.MAX);

        // nothing: said plainly
        type(m, "zzz");
        shot(m, "s4-none");
        assertNotNull(WalkTest.find(m.root, "Nothing on the path matches “zzz”"));
        assertTrue(shown(m).isEmpty());

        // a tap opens it; back is the search, the query kept; back again is home
        type(m, "water");
        View row = byDescription(m.root, "水 shuǐ water; liquid, ahead");
        if (row == null) row = m.search.results.getChildAt(1);
        row.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertTrue(m.learn.lookup);
        assertEquals("水", m.learn.items.get(0));
        shot(m, "s5-lookup");
        assertTrue(m.back());
        ShadowLooper.idleMainLooper();
        assertEquals("search", m.screen);
        assertEquals("water", HomeTest.first(m.root, EditText.class).getText().toString());
        assertEquals("水", shown(m).get(0));
        // Done on the item goes back to the search too
        m.search.results.getChildAt(1).performClick();
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Done");
        assertEquals("search", m.screen);
        assertTrue(m.back());
        assertEquals("home", m.screen);
        assertNull(m.search);
        // and the × goes home
        open(m);
        byDescription(m.root, "Back to the path").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("home", m.screen);
    }

    /** States: known, learning, wilting, next, ahead, each in the garden's swatch. */
    @Test
    public void states() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        HomeTest.seed(m);
        open(m);
        type(m, "m");
        shot(m, "s6-states");
        Search s = m.search;
        long now = System.currentTimeMillis();
        Item first = PathData.items.get(1), learning = PathData.items.get(6), wilting = PathData.items.get(60);
        assertEquals("known", s.state(first, now));
        assertEquals("learning", s.state(learning, now));
        assertEquals("wilting", s.state(wilting, now));
        assertEquals("ahead", s.state(PathData.items.get(PathData.items.size() - 1), now));
        assertEquals("next", s.state(PathData.get(m.prog.nextNew(1).get(0)), now));
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        HomeTest.seed(m);
        shot(m, "sn0-home");
        open(m);
        shot(m, "sn1-empty");
        type(m, "shui");
        shot(m, "sn2-shui");
        type(m, "m");
        shot(m, "sn3-states");
        type(m, "zzz");
        shot(m, "sn4-none");
    }
}
