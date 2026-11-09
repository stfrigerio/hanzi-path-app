package dev.stefanofrigerio.hanzipath;

import android.database.Cursor;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Translate: home's line opens a round of sentences you can read, into English (pick one of
 * four) and into Chinese (tap the tiles in order, Check) in turn. A right answer and a wrong
 * one each way, logged as mode "translate" and left out of the grades; the end's seal and
 * Another round, which starts with the ones not translated yet. Light and dark; renders tr*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class TranslateTest {
    final HomeTest shots = new HomeTest();

    @Test
    public void light() throws Exception { walk("tr"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception { walk("trn"); }

    /** The first enabled view described exactly d. */
    static View exact(View v, String d) {
        if (v.isEnabled() && v.getContentDescription() != null && d.contentEquals(v.getContentDescription())) return v;
        if (v instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                View f = exact(((android.view.ViewGroup) v).getChildAt(i), d);
                if (f != null) return f;
            }
        return null;
    }

    static void tapDescribed(Main m, String d) {
        View v = exact(m.root, d);
        assertNotNull("no " + d, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    /** Into Chinese: its pieces' tiles, tapped in order (or the first two swapped, to miss). */
    static void build(Main m, Translate t, boolean right) {
        List<String> p = Translate.pieces(t.queue.get(t.i));
        if (!right && p.size() >= 2 && !p.get(0).equals(p.get(1))) { String a = p.get(0); p.set(0, p.get(1)); p.set(1, a); }
        for (String s : p) tapDescribed(m, "Tile " + s);
        WalkTest.tap(m, "Check");
    }

    void walk(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        Sentences.load(m);
        // not enough to read yet: no line
        assertNull(WalkTest.find(m.root, "Translate "));

        WordsTest.know(m, 300);
        m.home();
        ShadowLooper.idleMainLooper();
        TextView line = WalkTest.find(m.root, "Translate 10 sentences");
        assertNotNull("home's line", line);
        shots.shot(m, prefix + "1-home");
        ((View) line.getParent()).performClick();
        ShadowLooper.idleMainLooper();
        Translate t = m.translate;
        assertNotNull(t);
        assertEquals("translate", m.screen);
        assertEquals(Translate.ROUND, t.queue.size());
        Set<String> zhs = new HashSet<>();
        for (Sentences.S s : t.queue) zhs.add(s.zh);
        assertEquals("ten different sentences", Translate.ROUND, zhs.size());

        int wrongEn = -1, wrongZh = -1, zhWays = 0;
        for (int k = 0; k < t.queue.size(); k++) {
            assertEquals(k, t.i);
            Sentences.S s = t.queue.get(k);
            if (t.tiles != null) {
                zhWays++;
                assertNotNull(WalkTest.find(m.root, "INTO CHINESE"));
                assertNotNull("the English asked", WalkTest.find(m.root, s.en));
                assertEquals("its pieces and two decoys", Translate.pieces(s).size() + Translate.DECOYS, t.tiles.size());
                boolean miss = wrongZh < 0 && Translate.pieces(s).size() >= 2 && !Translate.pieces(s).get(0).equals(Translate.pieces(s).get(1));
                if (zhWays == 1) {
                    tapDescribed(m, "Tile " + Translate.pieces(s).get(0));
                    shots.shot(m, prefix + "2-into-chinese");
                    tapDescribed(m, "Placed " + Translate.pieces(s).get(0) + ", tap to take back");   // taken back
                    assertTrue(t.placed.isEmpty());
                }
                build(m, t, !miss);
                assertTrue(t.checked);
                if (miss) { wrongZh = k; assertNotNull(WalkTest.find(m.root, "NOT QUITE")); shots.shot(m, prefix + "3-into-chinese-missed"); }
                else assertNotNull(WalkTest.find(m.root, "RIGHT"));
            } else {
                assertNotNull(WalkTest.find(m.root, "INTO ENGLISH"));
                assertEquals("four to pick from", 4, t.options.size());
                assertTrue(t.options.contains(s.en));
                assertEquals("each different", 4, new HashSet<>(t.options).size());
                if (k == 0) shots.shot(m, prefix + "4-into-english");
                String pick = s.en;
                if (wrongEn < 0) { wrongEn = k; for (String o : t.options) if (!o.equals(s.en)) { pick = o; break; } }
                for (View v : WalkTest.options(m)) if (pick.equals(v.getTag())) { v.performClick(); break; }
                ShadowLooper.idleMainLooper();
                assertEquals(pick, t.picked);
                if (k == wrongEn) { assertNotNull(WalkTest.find(m.root, "NOT QUITE")); shots.shot(m, prefix + "5-into-english-missed"); }
            }
            WalkTest.tap(m, k + 1 < t.queue.size() ? "Next" : "Finish");
        }
        assertTrue("both ways in a round", zhWays >= 3);
        assertTrue(wrongZh >= 0 && wrongEn >= 0);
        assertEquals(Translate.ROUND - 2, t.right);

        // the log: ten "translate" rows, two wrong; not grades
        try (Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*), SUM(grade = 1) FROM reviews WHERE mode = 'translate'", null)) {
            c.moveToFirst();
            assertEquals(Translate.ROUND, c.getInt(0));
            assertEquals(2, c.getInt(1));
        }
        try (Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews WHERE mode = 'translate' AND " + Progress.GRADED, null)) {
            c.moveToFirst();
            assertEquals("not a card's grade", 0, c.getInt(0));
        }

        // the end
        assertEquals("translate-done", m.screen);
        assertNotNull(WalkTest.find(m.root, "8 of 10 translated right."));
        shots.shot(m, prefix + "6-done");
        WalkTest.tap(m, "Another round");
        Set<String> next = new HashSet<>();
        for (Sentences.S s : m.translate.queue) next.add(s.zh);
        next.retainAll(zhs);
        assertTrue("the next round starts with ones not translated yet", next.isEmpty());
    }

    /** Pieces: the longest words the path knows, else characters; joined, the sentence. */
    @Test
    public void pieces() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Sentences.load(m);
        int multi = 0;
        for (Sentences.S s : Sentences.all) {
            List<String> p = Translate.pieces(s);
            assertEquals(s.zh, Translate.joined(s), String.join("", p));
            for (String x : p) if (x.length() > 1) { multi++; assertNotNull(x, Words.get(x)); }
        }
        assertTrue("words make some of the tiles", multi > 1000);
    }
}
