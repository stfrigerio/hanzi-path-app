package dev.stefanofrigerio.hanzipath;

import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Reading what you just learnt (Read): a Learn batch's end offers "Read sentences with these"
 * once three readable sentences have the batch in them, and not before; that round's sentences
 * each have a batch character in them, on the highlighter; home's Read line counts the ones
 * with today's new and the round takes them first; each sentence shown says its level, the
 * highest HSK level among its characters (none when one is beyond HSK). Light and dark;
 * renders are n*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class ReadNewTest {
    final HomeTest shots = new HomeTest();

    @Test
    public void light() throws Exception { walk("n"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception { walk("nn"); }

    /** A batch learnt and checked, every answer right: the round's end. */
    static List<String> batch(Main m) {
        m.home();
        m.startLearn();
        ShadowLooper.idleMainLooper();
        List<String> items = m.learn.items;
        while (WalkTest.find(m.root, "Check yourself") == null) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        RoundTest.answer(m);
        return items;
    }

    static int with(Main m, List<String> hs) {
        return Read.with(Sentences.readable(m, Read.reach(m.prog, System.currentTimeMillis(), hs)), hs).size();
    }

    static boolean marked(TextView t, String zh, List<String> hs) {
        Spanned sp = (Spanned) t.getText();
        for (String h : hs)
            for (int at = zh.indexOf(h); at >= 0; at = zh.indexOf(h, at + 1)) {
                BackgroundColorSpan[] b = sp.getSpans(at, at + h.length(), BackgroundColorSpan.class);
                if (b.length > 0 && b[0].getBackgroundColor() == Ink.hl) return true;
            }
        return false;
    }

    /** The label at the top right of the read card: "HSK n", or null. */
    static String level(View v) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith("HSK ")) return ((TextView) v).getText().toString();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            String s = level(((ViewGroup) v).getChildAt(i));
            if (s != null) return s;
        }
        return null;
    }

    void walk(String p) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        Sentences.load(m);

        // the first batch: nothing to read yet, no offer
        List<String> first = batch(m);
        assertTrue("too few with the first batch: " + with(m, first), with(m, first) < Read.WITH);
        assertNull("not offered without enough", WalkTest.find(m.root, "Read sentences with these"));

        // far along, a batch of characters: enough to read, offered (small) at its end
        WordsTest.know(m, 300);
        m.prog.sinceWords = 0;   // characters, not words, this batch
        List<String> b = batch(m);
        int n = with(m, b);
        assertTrue("enough with this batch: " + n + " for " + b, n >= Read.WITH);
        TextView offer = WalkTest.find(m.root, "Read sentences with these");
        assertNotNull("offered", offer);
        assertEquals("a small pill", Ink.dp(36), offer.getMinHeight());
        shots.shot(m, p + "1-done");

        // its round: only those with the batch in them, each with them on the highlighter
        offer.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("read", m.screen);
        assertEquals(Math.min(Read.ROUND, n), m.read.queue.size());
        Map<String, Integer> of = Levels.levelOf(m);
        boolean levelled = false;
        for (int i = 0; i < m.read.queue.size(); i++) {
            Sentences.S s = m.read.queue.get(i);
            assertTrue(s.zh + " has the batch in it", Read.has(s, b));
            TextView zh = WalkTest.find(m.root, s.zh);
            assertNotNull(zh);
            assertTrue(s.zh + ": the batch's on the highlighter", marked(zh, s.zh, b));
            assertNull("no level before Show", level(m.root));
            if (i == 0) shots.shot(m, p + "2-read");
            WalkTest.tap(m, "Show");
            int top = 0;
            for (String c : s.han) top = of.get(c) == null ? -1 : top < 0 ? -1 : Math.max(top, of.get(c));
            assertEquals(s.zh + ": its level", top > 0 ? "HSK " + top : null, level(m.root));
            if (top > 0 && !levelled) { levelled = true; shots.shot(m, p + "3-shown"); }
            WalkTest.tap(m, i + 1 < m.read.queue.size() ? "Next" : "Finish");
        }
        assertTrue("a level was shown", levelled);
        assertEquals("read-done", m.screen);

        // a sentence with a character beyond HSK: no level
        Sentences.S beyond = null;
        for (Sentences.S s : Sentences.all) if (Read.level(s, of) == 0) { beyond = s; break; }
        if (beyond != null) {
            m.startRead();
            m.read.queue.set(0, beyond);
            m.read.shown = false;
            m.read.show();
            WalkTest.tap(m, "Show");
            assertNull(beyond.zh + ": beyond HSK, no level", level(m.root));
        }

        // home: the Read line counts the ones with today's new, and the round takes them first
        m.home();
        List<String> today = Read.todayNew(m.prog, System.currentTimeMillis());
        assertTrue("today's new hold the batch just learnt (know() set the first batch as long known): " + today, today.containsAll(b));
        int fresh = Math.min(Read.ROUND, with(m, today));
        assertTrue(fresh > 0);
        TextView line = WalkTest.find(m.root, "Read 10 sentences");
        assertNotNull(line);
        assertNotNull("the line counts today's new", WalkTest.find(m.root, fresh + " with today's new"));
        shots.shot(m, p + "4-home");
        ((View) line.getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("read", m.screen);
        for (int i = 0; i < fresh; i++) {
            Sentences.S s = m.read.queue.get(i);
            assertTrue(s.zh + ": today's new first", Read.has(s, today));
            if (i == 0) assertTrue("on the highlighter too", marked(WalkTest.find(m.root, s.zh), s.zh, today));
        }

        // nothing new today: the line says how many you can read, as before
        WordsTest.know(m, 300);
        m.home();
        assertNull(WalkTest.find(m.root, "with today's new"));
        assertNotNull(WalkTest.find(m.root, "Read 10 sentences"));
    }
}
