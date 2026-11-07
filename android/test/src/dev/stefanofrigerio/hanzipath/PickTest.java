package dev.stefanofrigerio.hanzipath;

import android.database.Cursor;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** The questions that offer four characters: pick (reading and meaning) and listen (said aloud). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class PickTest {
    static final String OUT = System.getProperty("shots");

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

    /** The four characters: the clickable HanziViews, named by their character. */
    static List<HanziView> cells(View v) {
        List<HanziView> out = new ArrayList<>();
        collect(v, out);
        return out;
    }

    static void collect(View v, List<HanziView> out) {
        if (v instanceof HanziView && ((HanziView) v).bare) out.add((HanziView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    static List<String> names(Main m) {
        List<String> out = new ArrayList<>();
        for (HanziView c : cells(m.root)) out.add((String) c.getTag());
        return out;
    }

    static void tapCell(Main m, String h) {
        for (HanziView c : cells(m.root)) if (h.equals(c.getTag())) {
            assertTrue("cell " + h + " can't be tapped", c.hasOnClickListeners());
            c.performClick();
            ShadowLooper.idleMainLooper();
            return;
        }
        fail("no cell " + h);
    }

    static String wrong(Main m, String h) {
        for (String s : names(m)) if (!s.equals(h)) return s;
        throw new AssertionError("no wrong answer");
    }

    static String[] lastLog(Main m) {
        try (Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT mode, grade FROM reviews ORDER BY id DESC LIMIT 1", null)) {
            assertTrue(c.moveToFirst());
            return new String[]{c.getString(0), String.valueOf(c.getInt(1))};
        }
    }

    static boolean shows(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (shows(((ViewGroup) v).getChildAt(i), text)) return true;
        return false;
    }

    /** A few characters with readings, well into the path (so there are close ones around them). */
    static List<String> some(int from, int n) {
        List<String> out = new ArrayList<>();
        for (int i = from; out.size() < n && i < PathData.items.size(); i++) {
            Item it = PathData.items.get(i);
            if (!it.isPart() && it.py.length > 0) out.add(it.h);
        }
        return out;
    }

    static Quiz forced(Main m, List<String> items, String as) {
        Quiz q = new Quiz(m, items, "review") {
            @Override String kindFor(String h) { return as; }
        };
        m.quiz = q;
        q.show();
        ShadowLooper.idleMainLooper();
        return q;
    }

    /** One round of a kind: a wrong tap, undo (the same four), a right one; shots on the way. */
    void round(Main m, String kind, String prefix, boolean slow) throws Exception {
        Quiz q = forced(m, some(120, 3), kind);
        String h = q.queue.get(0);
        Item it = PathData.get(h);
        shot(m, prefix + "-" + kind);
        List<String> before = names(m);
        assertEquals("four characters", 4, before.size());
        assertEquals("each once", 4, new HashSet<>(before).size());
        assertTrue("the right one among them", before.contains(h));
        assertNull("no character to read in the question", q.hv);
        if (kind.equals("listen")) {
            assertFalse("no pinyin shown before the tap", shows(m.root, it.py[0]));
            assertTrue("said aloud", h.equals(Speech.waiting) || Speech.ready);
            Set<String> heard = new HashSet<>();
            for (String s : before) heard.add(PathData.get(s).py[0]);
            assertEquals("four different sounds", 4, heard.size());
        } else {
            assertTrue("the reading in the question", shows(m.root, it.py[0]));
            assertTrue("the meaning in the question", shows(m.root, it.en));
        }
        // wrong: again, logged as this kind, the answer and Next shown
        tapCell(m, wrong(m, h));
        shot(m, prefix + "-" + kind + "-wrong");
        assertEquals(1, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{kind, "1"}, lastLog(m));
        assertTrue(shows(m.root, "NOT THAT ONE"));
        assertTrue(shows(m.root, "Next"));
        assertTrue("the reading in the answer", shows(m.root, it.py[0]));
        for (HanziView c : cells(m.root)) assertFalse("graded: no more taps", c.hasOnClickListeners());
        // undo: open again, the same kind and the same four
        WalkTest.tap(m, "Undo");
        assertNull("undo took the card back", m.prog.cards.get(h));
        assertEquals(kind, q.mode);
        assertEquals("undo keeps the same four", before, names(m));
        assertNull(q.picked);
        // right: knew (quick) or unsure (slow)
        if (slow) q.shown -= Quiz.SLOW + 1000;
        tapCell(m, h);
        shot(m, prefix + "-" + kind + "-right");
        assertEquals(slow ? 2 : 3, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{kind, slow ? "2" : "3"}, lastLog(m));
        assertTrue(shows(m.root, "RIGHT"));
        WalkTest.tap(m, "Next");
        assertNotEquals(h, q.queue.get(0));
        // undo after Next: back to it, as it was
        WalkTest.tap(m, "Undo");
        assertEquals(h, q.queue.get(0));
        assertEquals(kind, q.mode);
        assertEquals(before, names(m));
    }

    @Test
    public void pick() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        round(m, "pick", "p1", false);
    }

    @Test
    public void listen() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        round(m, "listen", "p2", true);
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        round(m, "pick", "pn1", false);
        round(m, "listen", "pn2", false);
    }

    /** Which kind: choose while new and in the check, then any of the three (listen only with
     *  the voice on). */
    @Test
    public void kinds() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        List<String> hs = some(120, 2);
        String a = hs.get(0), b = hs.get(1);
        Quiz review = new Quiz(m, hs, "review"), check = new Quiz(m, hs, "check");
        assertEquals("never seen", "choose", review.kindFor(a));
        m.prog.grade(a, 3, "choose", 1000);
        assertEquals("seen once", "choose", review.kindFor(a));
        m.prog.grade(a, 3, "choose", 1000);
        m.prog.grade(b, 3, "choose", 1000);
        m.prog.grade(b, 3, "choose", 1000);
        Set<String> got = new HashSet<>();
        for (int i = 0; i < 300; i++) { got.add(review.kindFor(a)); assertEquals("choose", check.kindFor(a)); }
        assertEquals(new HashSet<>(Arrays.asList("choose", "pick", "listen")), got);
        m.prog.say = false;
        got.clear();
        for (int i = 0; i < 300; i++) got.add(review.kindFor(b));
        assertEquals("no listening without the voice", new HashSet<>(Arrays.asList("choose", "pick")), got);
        // a real review round of seen items shows whichever it drew, and grades it
        m.prog.say = true;
        Quiz q = new Quiz(m, hs, "review");
        m.quiz = q;
        q.show();
        ShadowLooper.idleMainLooper();
        String h = q.queue.get(0);
        if (q.mode.equals("choose")) WalkTest.options(m).get(0).performClick();
        else tapCell(m, h);
        ShadowLooper.idleMainLooper();
        assertEquals(q.mode, lastLog(m)[0]);
    }
}
