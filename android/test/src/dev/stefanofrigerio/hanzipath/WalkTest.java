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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WalkTest {
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
        dump(m.root, "", s);
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".txt"))) { o.write(s.toString().getBytes("UTF-8")); }
    }

    static void dump(View v, String pad, StringBuilder b) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        b.append(pad).append(v.getClass().getSimpleName()).append(" ").append(at[0]).append(",").append(at[1])
                .append(" ").append(v.getWidth()).append("x").append(v.getHeight())
                .append(v.getVisibility() != View.VISIBLE ? " HIDDEN" : "")
                .append(v instanceof TextView ? " '" + ((TextView) v).getText() + "'" : "").append("\n");
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), pad + "  ", b);
    }

    /** The answers: the card's options column, its children. */
    static java.util.List<TextView> options(Main m) {
        java.util.List<TextView> out = new java.util.ArrayList<>();
        collect(m.root, out);
        return out;
    }

    static void collect(View v, java.util.List<TextView> out) {
        if (v instanceof TextView && v.hasOnClickListeners() && ((TextView) v).getMinHeight() == (int) (52 * v.getResources().getDisplayMetrics().density + 0.5f)) out.add((TextView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    static TextView find(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(text)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            TextView t = find(((ViewGroup) v).getChildAt(i), text);
            if (t != null) return t;
        }
        return null;
    }

    static void tap(Main m, String text) {
        TextView t = find(m.root, text);
        assertNotNull("no button " + text, t);
        assertTrue(text + " is disabled", t.isEnabled());
        t.performClick();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void walk() throws Exception {
        ActivityController<Main> ac = Robolectric.buildActivity(Main.class).setup().visible();
        Main m = ac.get();
        shot(m, "1-home");
        tap(m, "Learn next 5");
        shot(m, "2-learn");
        // the stroke data reached the view
        assertNotNull(m.learn.hv.glyph);
        assertEquals(1, m.learn.hv.glyph.count());
        for (int i = 0; i < 4; i++) tap(m, "Next");
        tap(m, "Check yourself");
        shot(m, "3-check");
        // four answers, each meaning once, the right one among them
        java.util.List<TextView> opts = options(m);
        java.util.List<String> before = new java.util.ArrayList<>();
        for (TextView t : opts) before.add(t.getText().toString());
        assertEquals(4, opts.size());
        String h = m.quiz.queue.get(0);
        Item it = PathData.get(h);
        assertEquals(1, opts.stream().filter(t -> t.getText().toString().endsWith(it.en)).count());
        // a wrong pick: again, the answer shown, Undo there
        opts.stream().filter(t -> !t.getText().toString().endsWith(it.en)).findFirst().get().performClick();
        ShadowLooper.idleMainLooper();
        shot(m, "4-wrong");
        assertNotNull(find(m.root, "Not that one".toUpperCase()));
        assertEquals(1, m.prog.cards.get(h).grade);
        tap(m, "Undo");
        assertNull("undo took the card back", m.prog.cards.get(h));
        assertNotNull(find(m.root, "Which one is it?".toUpperCase()));
        java.util.List<String> after = new java.util.ArrayList<>();
        for (TextView t : options(m)) after.add(t.getText().toString());
        assertEquals("undo keeps the same four", before, after);
        for (int i = 0; i < 5; i++) {
            String q = m.quiz.queue.get(0);
            options(m).stream().filter(t -> t.getText().toString().endsWith(PathData.get(q).en)).findFirst().get().performClick();
            ShadowLooper.idleMainLooper();
            if (i == 0) shot(m, "4-right");
            assertEquals(3, m.prog.cards.get(q).grade);
            tap(m, i < 4 ? "Next" : "Finish");
        }
        shot(m, "5-done");
        tap(m, "Back to the path");
        assertEquals(5, m.prog.cards.size());
        shot(m, "6-home-after");
        // a fresh Progress reads the same back from SQLite
        Progress again = new Progress(m);
        assertEquals(5, again.cards.size());
        assertEquals(5, again.todayCount("reviews"));
        m.lookup("人");
        shot(m, "7-lookup");
        m.openSettings();
        shot(m, "8-settings");
        tap(m, "Copy progress");
        assertTrue(find(m.root, "Copied") != null);
        m.lookup("二");
        shot(m, "9-lookup-er");
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        shot(m, "n1-home");
        tap(m, "Learn next 5");
        shot(m, "n2-learn");
        tap(m, "Next"); tap(m, "Next");
        shot(m, "n3-learn-third");
        tap(m, "Next"); tap(m, "Next"); tap(m, "Check yourself");
        shot(m, "n4-check");
        options(m).get(0).performClick();
        ShadowLooper.idleMainLooper();
        shot(m, "n5-answer");
    }
}
