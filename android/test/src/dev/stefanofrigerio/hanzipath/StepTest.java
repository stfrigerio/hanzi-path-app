package dev.stefanofrigerio.hanzipath;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Stroke by stroke: on a looked-up 我 (7 strokes) and a learn card, the Step pill under the
 * square shows the strokes one at a time: after each tap the ones before in ink (filled), the
 * newest landed, the rest still to come (empty); the pill counts "3 / 7", reads Again on the
 * last and starts over; tapping the square replays them all and leaves stepping. A single
 * stroke has no pill. Each stroke's number sits off the strokes. Renders: s*.png (light), sn*.png (night): steps 1, 4, 7, and 4 landing.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class StepTest {
    static final String OUT = System.getProperty("shots");

    static void settle() { ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS); }

    void shot(Main m, String name) throws Exception {
        View w = m.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(w.getWidth(), w.getHeight(), Bitmap.Config.ARGB_8888);
        w.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** The fill after `shown` steps: those before the newest whole, the newest landed, the rest none. */
    static void assertStep(HanziView hv, int shown) {
        assertEquals(shown, hv.step);
        for (int i = 0; i < hv.fill.length; i++)
            assertEquals("stroke " + (i + 1) + " after step " + shown, i < shown ? 1f : 0f, hv.fill[i], 0f);
    }

    static TextView pill(Main m, HanziView hv) {
        TextView t = null;
        for (String s : new String[]{"Step", "Again"}) if (t == null) t = WalkTest.find(m.root, s);
        if (t == null) t = WalkTest.find(m.root, hv.step + " / ");
        assertNotNull("the step pill", t);
        return t;
    }

    void walk(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        m.lookup("我");
        settle();
        HanziView hv = m.learn.hv;
        int n = hv.fill.length;
        assertEquals(7, n);
        assertEquals("not stepping: the whole character", -1, hv.step);
        for (float f : hv.fill) assertEquals(1f, f, 0f);
        TextView p = pill(m, hv);
        assertEquals("Step", p.getText().toString());
        // the pill sits under the square, centred on it
        int[] sq = new int[2], at = new int[2];
        hv.getLocationInWindow(sq);
        p.getLocationInWindow(at);
        assertTrue("under the square", at[1] >= sq[1] + hv.getHeight());
        assertEquals("centred under it", sq[0] + hv.getWidth() / 2f, at[0] + p.getWidth() / 2f, 2f);

        for (int k = 1; k <= n; k++) {
            p.performClick();
            if (k == 4) {   // as it lands: part of the way along its median
                assertEquals("it starts empty", 0f, hv.fill[3], 0f);
                hv.anim.setCurrentPlayTime(hv.duration(3) / 4);
                assertTrue("landing " + hv.fill[3] + " of " + hv.duration(3), hv.fill[3] > 0 && hv.fill[3] < 1);
                assertEquals(1f, hv.fill[2], 0f);
                assertEquals(0f, hv.fill[4], 0f);
                shot(m, prefix + "4-landing");
            }
            settle();
            assertStep(hv, k);
            assertEquals(k < n ? k + " / " + n : "Again", p.getText().toString());
            hv.placeNumber(k - 1);   // the stroke's number sits off every stroke, inside the square
            assertTrue("number " + k + " clear of the strokes", hv.clear(hv.numX, hv.numY, hv.numP.getTextSize() * 0.6f));
            assertTrue(hv.numX > hv.getWidth() / 2f - hv.size / 2 && hv.numX < hv.getWidth() / 2f + hv.size / 2);
            if (k == 1 || k == 4 || k == n) shot(m, prefix + k + "-step");
        }
        // Again: back to the first stroke
        p.performClick();
        settle();
        assertStep(hv, 1);
        assertEquals("1 / 7", p.getText().toString());
        p.performClick();
        settle();
        assertStep(hv, 2);
        // the square: the whole stroke order again, and out of stepping
        hv.performClick();
        assertEquals(-1, hv.step);
        assertEquals("Step", p.getText().toString());
        settle();
        for (float f : hv.fill) assertEquals("replayed whole", 1f, f, 0f);
        // and stepping starts from the first again
        p.performClick();
        settle();
        assertStep(hv, 1);
    }

    @Test
    public void stepsThroughWo() throws Exception { walk("s"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void stepsThroughWoAtNight() throws Exception { walk("sn"); }

    @Test
    public void onALearnCard() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        WalkTest.tap(m, "Learn next 5");
        settle();
        assertEquals("one stroke: nothing to step", 1, m.learn.hv.fill.length);
        assertNull(WalkTest.find(m.root, "Step"));
        // the first card with more than one stroke has it
        for (int i = 0; i < 4 && m.learn.hv != null && m.learn.hv.fill.length < 2; i++) { WalkTest.tap(m, "Next"); settle(); }
        HanziView hv = m.learn.hv;
        assertNotNull(hv);
        assertTrue("a card with strokes to step", hv.fill.length >= 2);
        TextView p = pill(m, hv);
        p.performClick();
        settle();
        assertStep(hv, 1);
        assertEquals("1 / " + hv.fill.length, p.getText().toString());
    }
}
