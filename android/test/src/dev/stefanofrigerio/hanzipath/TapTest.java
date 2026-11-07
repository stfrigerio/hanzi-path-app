package dev.stefanofrigerio.hanzipath;

import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.view.MotionEvent;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tap any character (Taps): on a card, a character in its words, its sentence, its why-line
 * opens that character's card, and back returns to the card it was tapped on; the card's own
 * character does nothing. In a Read round, once shown, the same, back to the same sentence
 * still shown. In a question nothing is tappable before the answer; after it, the answer's
 * are, and back returns to the answered question. A character pressed, light and dark:
 * renders tap*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class TapTest {
    final HomeTest shots = new HomeTest();

    static void texts(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    static Taps.Tap tapAt(TextView t, int at) {
        if (!(t.getText() instanceof Spanned)) return null;
        Spanned s = (Spanned) t.getText();
        for (Taps.Tap p : s.getSpans(at, at + 1, Taps.Tap.class)) if (s.getSpanStart(p) <= at && s.getSpanEnd(p) > at) return p;
        return null;
    }

    static int taps(View root) {
        List<TextView> all = new ArrayList<>();
        texts(root, all);
        int n = 0;
        for (TextView t : all) if (t.getText() instanceof Spanned) n += ((Spanned) t.getText()).getSpans(0, t.length(), Taps.Tap.class).length;
        return n;
    }

    /** The TextView showing exactly this text. */
    static TextView exactly(View root, String text) {
        List<TextView> all = new ArrayList<>();
        texts(root, all);
        for (TextView t : all) if (t.getText().toString().equals(text)) return t;
        return null;
    }

    /** The first character of t's text on the path that isn't own. */
    static int other(TextView t, String own) {
        String s = t.getText().toString();
        for (int i = 0; i < s.length(); i++) {
            String c = s.substring(i, i + 1);
            if (Sentences.isHan(c) && !c.equals(own) && PathData.get(c) != null) return i;
        }
        return -1;
    }

    static void touch(TextView t, int action, float x, float y, long down) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        t.dispatchTouchEvent(e);
        e.recycle();
    }

    /** The middle of character at in t, in t's own coordinates. */
    static float[] where(TextView t, int at) {
        Layout l = t.getLayout();
        int line = l.getLineForOffset(at);
        float x = (l.getPrimaryHorizontal(at) + l.getPrimaryHorizontal(at + 1)) / 2;
        if (l.getPrimaryHorizontal(at + 1) <= l.getPrimaryHorizontal(at)) x = l.getPrimaryHorizontal(at) + 4;
        return new float[]{x + t.getTotalPaddingLeft(), (l.getLineTop(line) + l.getLineBottom(line)) / 2f + t.getTotalPaddingTop()};
    }

    /** A character with words, a why-line naming another of the path, and a sentence, far enough along. */
    static String rich(Main m) {
        for (int i = 60; i < 400; i++) {
            Item it = PathData.items.get(i);
            if (it.isPart() || it.words.length == 0 || it.why == null) continue;
            boolean why = false;
            for (int k = 0; k < it.why.length(); k++) {
                String c = it.why.substring(k, k + 1);
                if (Sentences.isHan(c) && !c.equals(it.h) && PathData.get(c) != null) why = true;
            }
            boolean word = false;
            for (String[] w : it.words) if (w[0].length() > 1) word = true;
            Sentences.S s = Sentences.forCard(m, it.h);
            if (why && word && s != null && s.han.length >= 6) return it.h;
        }
        throw new AssertionError("no character with words, a why-line and a sentence");
    }

    @Test
    public void card() throws Exception { card(Robolectric.buildActivity(Main.class).setup().visible().get(), "tap"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void cardNight() throws Exception { card(Robolectric.buildActivity(Main.class).setup().visible().get(), "tapn"); }

    void card(Main m, String prefix) throws Exception {
        Sentences.load(m);
        WordsTest.know(m, 400);
        String h = rich(m);
        Item it = PathData.get(h);
        m.lookup(h);
        ShadowLooper.idleMainLooper();
        Learn card = m.learn;

        // the sentence: the card's own on the highlighter, not tappable; another is
        Sentences.S s = Sentences.forCard(m, h);
        TextView zh = WalkTest.find(m.root, s.zh);
        assertNotNull(zh);
        int own = s.zh.indexOf(h);
        assertNull("the card's own character does nothing", tapAt(zh, own));
        int o = other(zh, h);
        assertTrue(o >= 0);
        Taps.Tap tap = tapAt(zh, o);
        assertNotNull("another character of the sentence is tappable", tap);
        assertEquals(s.zh.substring(o, o + 1), tap.h);
        assertFalse("the text isn't a button: only its characters are", zh.isClickable());
        // the look kept: no link colour, no underline
        TextPaint p = new TextPaint();
        p.setColor(0xff123456);
        tap.updateDrawState(p);
        assertEquals(0xff123456, p.getColor());
        assertFalse(p.isUnderlineText());

        // a press on it: the wash under the character, rendered; the lift opens its card
        float[] at = where(zh, o);
        long down = SystemClock.uptimeMillis();
        touch(zh, MotionEvent.ACTION_DOWN, at[0], at[1], down);
        assertEquals("pressed: a wash under it", 1, ((Spanned) zh.getText()).getSpans(o, o + 1, Taps.Wash.class).length);
        assertEquals(Ink.press, ((Spanned) zh.getText()).getSpans(o, o + 1, Taps.Wash.class)[0].getBackgroundColor());
        shots.shot(m, prefix + "1-pressed");
        touch(zh, MotionEvent.ACTION_UP, at[0], at[1], down);
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertNotSame(card, m.learn);
        assertTrue(m.learn.lookup);
        assertEquals("the tapped character's card", tap.h, m.learn.items.get(0));
        shots.shot(m, prefix + "2-opened");
        assertTrue(m.back());
        assertEquals("back: the card it was tapped on", "learn", m.screen);
        assertSame(card, m.learn);
        assertEquals(h, m.learn.items.get(0));
        zh = WalkTest.find(m.root, s.zh);
        assertEquals("the wash gone", 0, ((Spanned) zh.getText()).getSpans(0, zh.length(), Taps.Wash.class).length);

        // a press that drags (the scroll takes it): no card opened
        down = SystemClock.uptimeMillis();
        touch(zh, MotionEvent.ACTION_DOWN, at[0], at[1], down);
        touch(zh, MotionEvent.ACTION_CANCEL, at[0], at[1] + 200, down);
        ShadowLooper.idleMainLooper();
        assertSame("a cancelled press opens nothing", card, m.learn);
        assertEquals(0, ((Spanned) zh.getText()).getSpans(0, zh.length(), Taps.Wash.class).length);
        // a press beside the text, past the line's end: not taken
        Layout l = zh.getLayout();
        if (l.getLineRight(l.getLineCount() - 1) + 40 < zh.getWidth()) {
            down = SystemClock.uptimeMillis();
            float y = (l.getLineTop(l.getLineCount() - 1) + l.getLineBottom(l.getLineCount() - 1)) / 2f + zh.getTotalPaddingTop();
            assertFalse("off the characters the touch isn't taken", zh.onTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, zh.getWidth() - 4, y, 0)));
        }

        // a word: its other character opens its card; the card's own does nothing; the row still says it
        String[] w = null;
        for (String[] x : it.words) if (x[0].length() > 1 && other(new TextView(m) {{ setText(x[0]); }}, h) >= 0) { w = x; break; }
        assertNotNull(w);
        TextView wt = exactly(m.root, w[0]);
        assertNotNull("the word on the card", wt);
        assertNull("the card's own character in its word does nothing", tapAt(wt, w[0].indexOf(h)));
        int wo = other(wt, h);
        Taps.Tap wtap = tapAt(wt, wo);
        assertNotNull(wtap);
        assertTrue("the word's row still says it", ((View) wt.getParent()).hasOnClickListeners());
        wtap.onClick(wt);
        ShadowLooper.idleMainLooper();
        assertEquals(w[0].substring(wo, wo + 1), m.learn.items.get(0));
        // and on from there: a character of that card's sentence or why-line, back twice
        Learn second = m.learn;
        Taps.Tap further = null;
        TextView ft = null;
        List<TextView> all = new ArrayList<>();
        texts(m.root, all);
        for (TextView t : all) {
            int k = other(t, wtap.h);
            if (k >= 0 && tapAt(t, k) != null) { further = tapAt(t, k); ft = t; break; }
        }
        if (further != null) {
            further.onClick(ft);
            ShadowLooper.idleMainLooper();
            assertEquals(further.h, m.learn.items.get(0));
            assertTrue(m.back());
            assertSame("back one: the card tapped from", second, m.learn);
        }
        assertTrue(m.back());
        assertSame("back: the first card", card, m.learn);
        assertEquals("learn", m.screen);

        // the why-line: a part opens its card; the card's own does nothing
        TextView why = WalkTest.find(m.root, Cards.whyText(it.why).toString());
        assertNotNull(why);
        int wi = why.getText().toString().indexOf(h);
        if (wi >= 0) assertNull(tapAt(why, wi));
        int yo = other(why, h);
        Taps.Tap ytap = tapAt(why, yo);
        assertNotNull("a part of the why-line is tappable", ytap);
        ytap.onClick(why);
        ShadowLooper.idleMainLooper();
        assertEquals(ytap.h, m.learn.items.get(0));
        assertTrue(m.back());
        assertSame(card, m.learn);

        // Done from a tapped card goes back the same way
        tapAt(zh = WalkTest.find(m.root, s.zh), o).onClick(zh);
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Done");
        assertSame("Done: back to the card", card, m.learn);

        // a word's card: the word's characters open theirs
        m.lookup(w[0]);
        ShadowLooper.idleMainLooper();
        Learn wc = m.learn;
        TextView big = null;
        all.clear();
        texts(m.root, all);
        for (TextView t : all) if (t.getText().toString().equals(w[0]) && t.getMaxLines() == 1) big = t;
        assertNotNull("the word, large", big);
        Taps.Tap bt = tapAt(big, 0);
        assertNotNull("its characters open their cards", bt);
        bt.onClick(big);
        ShadowLooper.idleMainLooper();
        assertEquals(w[0].substring(0, 1), m.learn.items.get(0));
        assertTrue(m.back());
        assertSame("back: the word's card", wc, m.learn);
        assertTrue(m.back());   // the word's card, looked up from the first: no tap, out to the path
    }

    @Test
    public void read() throws Exception { read(Robolectric.buildActivity(Main.class).setup().visible().get(), "tap"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void readNight() throws Exception { read(Robolectric.buildActivity(Main.class).setup().visible().get(), "tapn"); }

    void read(Main m, String prefix) throws Exception {
        Sentences.load(m);
        WordsTest.know(m, 400);
        m.startRead();
        ShadowLooper.idleMainLooper();
        assertEquals("read", m.screen);
        WalkTest.tap(m, "Show");
        WalkTest.tap(m, "Next");   // the second sentence: back must keep the place too
        Read r = m.read;
        Sentences.S s = r.queue.get(1);
        TextView zh = WalkTest.find(m.root, s.zh);
        assertEquals("before Show nothing is tappable: it's reading", 0, taps(m.root));
        WalkTest.tap(m, "Show");
        zh = WalkTest.find(m.root, s.zh);
        int o = other(zh, null);
        Taps.Tap tap = tapAt(zh, o);
        assertNotNull("once shown, each character opens its card", tap);
        float[] at = where(zh, o);
        long down = SystemClock.uptimeMillis();
        touch(zh, MotionEvent.ACTION_DOWN, at[0], at[1], down);
        shots.shot(m, prefix + "3-read-pressed");
        touch(zh, MotionEvent.ACTION_UP, at[0], at[1], down);
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals(tap.h, m.learn.items.get(0));
        int reads = SentencesTest.reads(m);
        assertTrue(m.back());
        assertEquals("back: the Read round", "read", m.screen);
        assertSame(r, m.read);
        assertEquals("the same sentence", 1, r.i);
        assertTrue("still shown", r.shown);
        assertNotNull("its English still there", WalkTest.find(m.root, s.en));
        assertNotNull(WalkTest.find(m.root, "Next"));
        assertEquals("not read again", reads, SentencesTest.reads(m));
        shots.shot(m, prefix + "4-read-back");
    }

    @Test
    public void question() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Sentences.load(m);
        WordsTest.know(m, 400);
        String h = rich(m);
        Words.build();
        String word = null;
        for (String[] x : PathData.get(h).words) if (x[0].length() > 1 && Words.get(x[0]) != null && (word == null || Sentences.forCard(m, x[0]) != null)) word = x[0];
        for (String q : word == null ? new String[]{h} : new String[]{word, h}) {
            m.startCheck(new ArrayList<>(Arrays.asList(q)));
            ShadowLooper.idleMainLooper();
            assertEquals("quiz", m.screen);
            assertEquals(q, m.quiz.queue.get(0));
            assertEquals("before the answer nothing is tappable: it would give it away", 0, taps(m.root));
            WalkTest.options(m).get(0).performClick();
            ShadowLooper.idleMainLooper();
            assertNotNull(m.quiz.picked);
            if (Words.is(q) && Sentences.forCard(m, q) == null) { m.home(); continue; }   // a word's answer: only its sentence is running text
            assertTrue("the answer's characters are tappable", taps(m.root) > 0);
            List<TextView> all = new ArrayList<>();
            texts(m.root, all);
            Taps.Tap tap = null;
            TextView on = null;
            for (TextView t : all) {
                int k = other(t, q);
                if (k >= 0 && tapAt(t, k) != null) { tap = tapAt(t, k); on = t; break; }
            }
            assertNotNull(tap);
            Quiz quiz = m.quiz;
            String picked = quiz.picked;
            tap.onClick(on);
            ShadowLooper.idleMainLooper();
            assertEquals("learn", m.screen);
            assertTrue(m.back());
            assertEquals("back: the answered question", "quiz", m.screen);
            assertSame(quiz, m.quiz);
            assertEquals(picked, m.quiz.picked);
            assertTrue(WalkTest.find(m.root, "Next") != null || WalkTest.find(m.root, "Finish") != null);
            m.home();
        }
    }
}
