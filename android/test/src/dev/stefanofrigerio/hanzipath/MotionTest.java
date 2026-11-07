package dev.stefanofrigerio.hanzipath;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The motion: a screen comes in and settles fully visible; with Android's animations off
 * nothing animates; a right and a wrong answer are felt (CONFIRM, REJECT) and their rings
 * settle; a pill and a garden cell take the press. Renders of the press are m*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class MotionTest {
    static final String OUT = System.getProperty("shots");

    static void settle() { ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS); }

    static ScrollView screen(Main m) { return (ScrollView) m.root.getChildAt(0); }

    static void assertSettled(Main m) {
        ScrollView sv = screen(m);
        assertEquals("the screen is fully there", 1f, sv.getAlpha(), 0f);
        assertEquals("the screen has landed", 0f, sv.getTranslationY(), 0f);
    }

    /** The window as Android lays it out, the top screenful. */
    void shot(Main m, String name) throws Exception {
        View w = m.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(w.getWidth(), w.getHeight(), Bitmap.Config.ARGB_8888);
        w.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    static GardenView garden(View v) {
        if (v instanceof GardenView) return (GardenView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            GardenView g = garden(((ViewGroup) v).getChildAt(i));
            if (g != null) return g;
        }
        return null;
    }

    static void toCheck(Main m) {
        WalkTest.tap(m, "Learn next 5");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
    }

    static TextView answer(Main m, boolean right) {
        String en = PathData.get(m.quiz.queue.get(0)).en;
        for (TextView t : WalkTest.options(m)) if (t.getText().toString().endsWith(en) == right) return t;
        throw new AssertionError("no " + (right ? "right" : "wrong") + " answer");
    }

    @Test
    public void screensComeInAndSettle() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertNull("the first screen is just there", m.motion);
        m.startLearn();                // no idling yet: the screen as it starts
        assertNotNull("a new screen comes in", m.motion);
        assertEquals(Motion.ENTER, m.motion.getDuration());
        ScrollView sv = screen(m);
        assertEquals("it starts unseen", 0f, sv.getAlpha(), 0.01f);
        assertEquals("and a little low", Ink.dp(Motion.RISE), sv.getTranslationY(), 0.5f);
        settle();
        assertSettled(m);
        assertFalse(m.motion.isRunning());
        WalkTest.tap(m, "Next");
        assertNotNull("the next item comes in too", m.motion);
        settle();
        assertSettled(m);
        m.back();
        assertEquals("going home fades in", 0f, screen(m).getAlpha(), 0.01f);
        assertEquals("and doesn't rise", 0f, screen(m).getTranslationY(), 0f);
        settle();
        assertSettled(m);
        // the same screen built again (a theme change, a setting) doesn't move
        m.redraw.run();
        assertNull(m.motion);
        assertEquals(1f, screen(m).getAlpha(), 0f);
    }

    @Test
    public void noMotionWhenAnimationsAreOff() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Settings.Global.putFloat(m.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        assertFalse(Motion.on(m));
        WalkTest.tap(m, "Learn next 5");
        assertNull("no animator", m.motion);
        assertSettled(m);
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        assertNull(m.motion);
        answer(m, true).performClick();
        assertNull("the answer is there at once", m.motion);
        for (View v : m.quiz.ringed) assertEquals(255, v.getBackground().getAlpha());
    }

    @Test
    public void answersAreFelt() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        toCheck(m);
        settle();
        answer(m, true).performClick();
        assertEquals("right: a light tick", HapticFeedbackConstants.CONFIRM, shadowOf(m.root).lastHapticFeedbackPerformed());
        assertNotNull("the rings come in", m.motion);
        settle();
        assertSettled(m);
        assertEquals(1, m.quiz.ringed.size());
        for (View v : m.quiz.ringed) {
            assertEquals("the ring is all there", 255, v.getBackground().getAlpha());
            assertEquals(1f, v.getScaleX(), 0f);
        }
        assertEquals(3, m.quiz.faded.size());
        for (View v : m.quiz.faded) assertEquals("the rest faded back", 0.45f, v.getAlpha(), 0.001f);
        WalkTest.tap(m, "Next");
        settle();
        answer(m, false).performClick();
        assertEquals("wrong: a firmer double", HapticFeedbackConstants.REJECT, shadowOf(m.root).lastHapticFeedbackPerformed());
        settle();
        assertEquals("the right one and the wrong pick ringed", 2, m.quiz.ringed.size());
        for (View v : m.quiz.ringed) assertEquals(255, v.getBackground().getAlpha());
        for (View v : m.quiz.faded) assertEquals(0.45f, v.getAlpha(), 0.001f);
    }

    void pressOnHome(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        settle();
        TextView pill = WalkTest.find(m.root, "Learn next 5");
        Drawable bg = pill.getBackground();
        Drawable up = bg.getCurrent();
        pill.setPressed(true);
        assertNotSame("the pill takes the press", up, bg.getCurrent());
        // the garden: a cell held still takes the press; a cancel (a scroll) lets it go
        GardenView g = garden(m.root);
        assertNotNull(g);
        long t = SystemClock.uptimeMillis();
        float x = g.cell * 2.5f + 2 * g.gap, y = g.cell / 2;
        g.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals("the cell under the finger", 2, g.pressed);
        shot(m, prefix + "1-press");
        g.dispatchTouchEvent(MotionEvent.obtain(t, t + 300, MotionEvent.ACTION_CANCEL, x, y, 0));
        assertEquals(-1, g.pressed);
        pill.setPressed(false);
        settle();
        assertSame(up, bg.getCurrent());
        // an answer takes it too
        toCheck(m);
        settle();
        TextView a = answer(m, false);
        Drawable ab = a.getBackground(), aup = ab.getCurrent();
        a.setPressed(true);
        assertNotSame(aup, ab.getCurrent());
        assertEquals(Ink.press, ((GradientDrawable) ab.getCurrent()).getColor().getDefaultColor());
        shot(m, prefix + "2-press-answer");
    }

    @Test
    public void pressStates() throws Exception { pressOnHome("m"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void pressStatesAtNight() throws Exception { pressOnHome("mn"); }
}
