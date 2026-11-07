package dev.stefanofrigerio.hanzipath;

import android.database.Cursor;
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
 * The tone question (Tones): five pills in the tones' inks, a right and a wrong tap (grades,
 * mode "tone", undo), and when it's drawn: only in a review, for a character with a toned
 * reading you've missed or been slow on, about one in six, with the voice on. Then the reading
 * counts: Stats' line, and the Read round's end with its seal. Light and dark; renders t*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class TonesTest {
    static final long DAY = Progress.DAY;
    final PickTest pt = new PickTest();

    void shot(Main m, String name) throws Exception { pt.shot(m, name); }

    /** A card seen three times, due now, missed lapses times, last graded g. */
    static void card(Main m, String h, int lapses, int g) {
        long now = System.currentTimeMillis();
        Progress.Card k = new Progress.Card();
        k.s = 3; k.d = 5; k.reps = 3; k.lapses = lapses; k.grade = g;
        k.first = now - 20 * DAY; k.last = now - 4 * DAY; k.due = now - 60_000;
        m.prog.cards.put(h, k);
        m.prog.putCard(h, k);
    }

    /** Characters well into the path whose main reading has a tone (1-4). */
    static List<String> toned(int from, int n) {
        List<String> out = new ArrayList<>();
        for (int i = from; out.size() < n && i < PathData.items.size(); i++) {
            Item it = PathData.items.get(i);
            if (!it.isPart() && it.py.length > 0 && Ink.toneOf(it.py[0]) < 5) out.add(it.h);
        }
        return out;
    }

    static View desc(View v, String d) {
        if (v.getContentDescription() != null && v.getContentDescription().toString().equals(d)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View x = desc(((ViewGroup) v).getChildAt(i), d);
            if (x != null) return x;
        }
        return null;
    }

    static TextView pill(Main m, int t) {
        View v = desc(m.root, Tones.NAME[t] + (t == 5 ? "" : ", " + Tones.MARK[t]));
        assertNotNull("the " + Tones.NAME[t] + " pill", v);
        return (TextView) v;
    }

    static String[] lastLog(Main m) { return PickTest.lastLog(m); }

    @Test
    public void question() throws Exception { question(Robolectric.buildActivity(Main.class).setup().visible().get(), "t"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void questionNight() throws Exception { question(Robolectric.buildActivity(Main.class).setup().visible().get(), "tn"); }

    void question(Main m, String prefix) throws Exception {
        List<String> items = toned(120, 3);
        for (String h : items) card(m, h, 1, 3);
        Quiz q = PickTest.forced(m, items, "tone");
        String h = q.queue.get(0);
        int tone = Tones.answer(h);
        assertNotNull("the question", WalkTest.find(m.root, "WHICH TONE IS IT?"));
        assertNotNull("the character shown", q.hv);
        assertTrue("said aloud", h.equals(Speech.waiting) || Speech.ready);
        assertFalse("no reading before the tap", PickTest.shows(m.root, PathData.get(h).py[0]));
        for (int t = 1; t <= 5; t++) {
            TextView p = pill(m, t);
            assertTrue("pill " + t + " can be tapped", p.hasOnClickListeners());
            assertTrue("pill " + t + " shows its mark", p.getText().toString().startsWith(Tones.MARK[t]));
            android.text.style.ForegroundColorSpan[] fg = ((android.text.Spanned) p.getText())
                    .getSpans(0, 1, android.text.style.ForegroundColorSpan.class);
            assertEquals("pill " + t + " in its tone's ink", Ink.tone[t], fg[0].getForegroundColor());
        }
        assertTrue(pill(m, 5).getText().toString().contains("neutral"));
        // the five side by side, equal, inside the card
        int w = pill(m, 1).getWidth();
        for (int t = 2; t <= 5; t++) assertEquals("equal widths", w, pill(m, t).getWidth(), 1);
        assertTrue("wide enough to hit", w >= Ink.dp(48));
        shot(m, prefix + "1-tone");

        // wrong: again, logged as tone, the answer in full
        int wrong = tone == 1 ? 2 : 1;
        pill(m, wrong).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{"tone", "1"}, lastLog(m));
        assertTrue(PickTest.shows(m.root, "NOT THAT ONE"));
        assertTrue("the reading in the answer", PickTest.shows(m.root, PathData.get(h).py[0]));
        assertTrue(PickTest.shows(m.root, "Next"));
        for (int t = 1; t <= 5; t++) assertFalse("graded: no more taps", pill(m, t).hasOnClickListeners());
        int other = 1;
        while (other == tone || other == wrong) other++;
        assertTrue("the rest fade", pill(m, other).getAlpha() < 1);
        assertTrue("the right one ringed", q.ringed.contains(pill(m, tone)));
        assertTrue("the wrong pick ringed", q.ringed.contains(pill(m, wrong)));
        assertFalse(q.ringed.contains(pill(m, other)));
        shot(m, prefix + "2-tone-wrong");

        // undo: open again, the same kind; then right, quick: knew it
        WalkTest.tap(m, "Undo");
        assertEquals("tone", q.mode);
        assertEquals(h, q.queue.get(0));
        assertEquals("the wrong grade taken back", 3, m.prog.cards.get(h).grade);
        assertEquals("the lapse taken back", 1, m.prog.cards.get(h).lapses);
        try (Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews", null)) {
            c.moveToNext();
            assertEquals("and its log row", 0, c.getInt(0));
        }
        assertTrue(pill(m, tone).hasOnClickListeners());
        pill(m, tone).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(3, m.prog.cards.get(h).grade);
        assertArrayEquals(new String[]{"tone", "3"}, lastLog(m));
        assertTrue(PickTest.shows(m.root, "RIGHT"));
        shot(m, prefix + "3-tone-right");

        // the next, right but slow: unsure
        WalkTest.tap(m, "Next");
        String h2 = q.queue.get(0);
        assertNotEquals(h, h2);
        q.shown -= Quiz.SLOW + 1000;
        pill(m, Tones.answer(h2)).performClick();
        ShadowLooper.idleMainLooper();
        assertArrayEquals(new String[]{"tone", "2"}, lastLog(m));
    }

    /** kindFor, many times over: how often it's tone. */
    static int tones(Quiz q, String h, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) if (q.kindFor(h).equals("tone")) k++;
        return k;
    }

    @Test
    public void when() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Words.build();
        List<String> t = toned(120, 4);
        String missed = t.get(0), slow = t.get(1), quick = t.get(2), fresh = t.get(3);
        card(m, missed, 2, 3);
        card(m, slow, 0, 2);
        card(m, quick, 0, 3);
        Quiz review = new Quiz(m, t, "review"), check = new Quiz(m, t, "check");
        m.prog.say = true;

        int n = 1200, got = tones(review, missed, n);
        assertTrue("missed before: about one in six, got " + got, got > n / 10 && got < n / 4);
        assertTrue("slow last time: tone too", tones(review, slow, 600) > 0);
        assertEquals("never missed and quick: never", 0, tones(review, quick, 600));
        assertEquals("never in the check", 0, tones(check, missed, 600));

        // new (seen once): choose, as before
        Progress.Card once = m.prog.cards.get(missed).copy();
        once.reps = 1;
        m.prog.cards.put(fresh, once);
        assertEquals("seen once: never", 0, tones(review, fresh, 600));

        // neutral main reading: there's no tone to ask
        String neutral = null;
        for (Item it : PathData.items) if (!it.isPart() && it.py.length > 0 && Ink.toneOf(it.py[0]) == 5) { neutral = it.h; break; }
        if (neutral != null) {
            card(m, neutral, 2, 1);
            assertEquals("a neutral reading: never", 0, tones(review, neutral, 600));
        }

        // a part: never
        String part = null;
        for (Item it : PathData.items) if (it.isPart() && it.py.length > 0 && Ink.toneOf(it.py[0]) < 5) { part = it.h; break; }
        if (part != null) {
            card(m, part, 2, 1);
            assertEquals("a part: never", 0, tones(review, part, 600));
        }

        // a word, missed: never
        String w = Words.all.get(0)[0];
        card(m, w, 2, 1);
        assertEquals("a word: never", 0, tones(review, w, 600));

        // the voice off: never
        m.prog.say = false;
        assertEquals("voice off: never", 0, tones(review, missed, 600));
        m.prog.say = true;

        // a real review round with them due: it does come up, with the five
        Quiz q = null;
        for (int i = 0; i < 60 && (q == null || !"tone".equals(q.mode)); i++) {
            q = new Quiz(m, Arrays.asList(missed, slow), "review");
            m.quiz = q;
            q.show();
            ShadowLooper.idleMainLooper();
        }
        assertEquals("drawn in a real round", "tone", q.mode);
        assertEquals(Tones.OPTIONS, q.options);
        assertNotNull(pill(m, 5));
    }

    @Test
    public void reading() throws Exception { reading(Robolectric.buildActivity(Main.class).setup().visible().get(), "t"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void readingNight() throws Exception { reading(Robolectric.buildActivity(Main.class).setup().visible().get(), "tn"); }

    void reading(Main m, String prefix) throws Exception {
        Sentences.load(m);
        // nothing read: Stats says so
        m.openStats();
        ShadowLooper.idleMainLooper();
        assertNotNull(WalkTest.find(m.root, "READING"));
        assertNotNull(WalkTest.find(m.root, "No sentences read yet"));
        assertTrue(m.back());

        // a Read round to its end: the seal, 好
        WordsTest.know(m, 400);
        m.home();
        m.startRead();
        ShadowLooper.idleMainLooper();
        assertEquals("read", m.screen);
        for (int i = 0; i < Read.ROUND; i++) {
            WalkTest.tap(m, "Show");
            WalkTest.tap(m, i + 1 < Read.ROUND ? "Next" : "Finish");
        }
        assertEquals("read-done", m.screen);
        TextView seal = WordsTest.text(m.root, "好");
        assertNotNull("the seal", seal);
        assertEquals("in the brush", Ink.brush, seal.getTypeface());
        assertEquals(Ink.tone[4], seal.getCurrentTextColor());
        assertNotNull(WalkTest.find(m.root, Read.ROUND + " sentences read"));
        int[] a = new int[2], b = new int[2];
        seal.getLocationInWindow(a);
        WalkTest.find(m.root, Read.ROUND + " sentences read").getLocationInWindow(b);
        assertTrue("the seal above what was read", a[1] < b[1]);
        shot(m, prefix + "4-read-done");

        // one read last week too: all of them, and this week's
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("at", Stats.weekStart(System.currentTimeMillis(), 0) - DAY); v.put("h", "我们来了。"); v.put("grade", 0);
        v.put("mode", "read"); v.put("ms", 1000); v.put("r", 0); v.put("s", 0); v.put("d", 0);
        m.prog.getWritableDatabase().insert("reviews", null, v);
        long[] before = m.prog.reviewTotals();
        assertEquals("reads aren't grades", 0, before[0]);
        m.openStats();
        ShadowLooper.idleMainLooper();
        TextView line = WalkTest.find(m.root, "Sentences read");
        assertNotNull("Stats' line", line);
        String want = (Read.ROUND + 1) + " in all · " + Read.ROUND + " this week";
        assertNotNull("the counts: " + want, WordsTest.text(m.root, want));
        assertNull(WalkTest.find(m.root, "No sentences read yet"));
        shot(m, prefix + "5-stats");
    }
}
