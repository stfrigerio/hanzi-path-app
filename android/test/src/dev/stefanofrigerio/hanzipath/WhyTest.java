package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
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
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * "Why it looks like this" as plain sentences (tools/build.py's why), on learn cards; and
 * Stats' hardest with a word among the characters, opened as a word's card and back.
 * Light and dark. Renders are y*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WhyTest {
    static final long DAY = 24 * 3600_000L;
    final HomeTest shots = new HomeTest();

    static String why(String h) { return PathData.get(h).why; }

    @Test
    public void lines() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        // a sound part and a meaning part, the hint worked in as what the part means here
        assertEquals("匕 (sitting man) gives the meaning and 止 (zhǐ) the sound.", why("此"));
        assertEquals("心 (mind) gives the meaning and 相 (xiāng) the sound.", why("想"));
        assertEquals("土 (earth) gives the meaning and 才 (cái) the sound.", why("在"));
        // a picture, and a picture of parts whose last part also gives the sound
        assertEquals("Three mountain peaks.", why("山"));
        assertEquals("Two parallel lines. Compare 一 (one) and 三 (three).", why("二"));
        assertEquals("A person 亻 making something for the first time 乍 (zhà), which also gives the sound.", why("作"));
        assertEquals("A table 几 made of wood 木. 几 (jǐ) also gives the sound.", why("机"));
        // no hint: only what the parts give; no etymology at all: no line
        assertEquals("月 (yuè) gives the sound.", why("有"));
        assertEquals("日 (day) gives the meaning.", why("时"));
        assertNull(why("十"));
        assertNull(Cards.why(m, PathData.get("十")));

        Pattern lower = Pattern.compile("\\. \\p{Ll}");
        int lines = 0;
        for (Item it : PathData.items) {
            if (it.why == null) continue;
            lines++;
            String w = it.why;
            assertFalse(it.h + ": a sentence starting lowercase: " + w, lower.matcher(w).find());
            assertFalse(it.h + ": doubled spaces: " + w, w.contains("  "));
            assertFalse(it.h + ": a no-break space: " + w, w.contains(" "));
            assertEquals(it.h + ": trimmed: " + w, w.trim(), w);
            assertTrue(it.h + ": capitalised: " + w, !Character.isLowerCase(w.charAt(0)));
            assertTrue(it.h + ": ends a sentence: " + w, w.endsWith(".") || w.endsWith("\""));
            assertFalse(it.h + ": Make Me a Hanzi's wording: " + w, w.contains("provides the pronunciation") || w.contains("Visual abbreviation"));
            assertFalse(it.h + ": a part named twice in a row: " + w, w.matches(".*(\\S) \\1 .*"));
        }
        assertTrue("most of the path has a line: " + lines, lines > 1000);

        // the sound part and its reading in the tone's ink (止 zhǐ: the third)
        SpannableStringBuilder b = Cards.whyText(why("此"));
        int at = b.toString().indexOf("止"), py = b.toString().indexOf("zhǐ");
        assertEquals(Ink.tone[3], color(b, at));
        assertEquals(Ink.tone[3], color(b, py));
        assertEquals("the meaning part in the text's own ink", 0, color(b, b.toString().indexOf("匕")));

        cards(m, "y");
    }

    static int color(SpannableStringBuilder b, int at) {
        ForegroundColorSpan[] s = b.getSpans(at, at + 1, ForegroundColorSpan.class);
        return s.length == 0 ? 0 : s[s.length - 1].getForegroundColor();
    }

    /** Learn cards looked up: a sound-and-meaning one, a picture, one whose part gives the sound. */
    void cards(Main m, String prefix) throws Exception {
        String[] hs = {"此", "山", "作"};
        for (String h : hs) {
            m.lookup(h);
            ShadowLooper.idleMainLooper();
            assertNotNull(WalkTest.find(m.root, "WHY IT LOOKS LIKE THIS"));
            TextView line = WalkTest.find(m.root, Cards.whyText(why(h)).toString());
            assertNotNull(h + "'s line on its card", line);
            shots.shot(m, prefix + "1-why-" + PathData.get(h).at);   // by place: hanzi don't survive the file name
            assertTrue(m.back());
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void linesNight() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        cards(m, "yn");
        hardest(m, "yn");
    }

    @Test
    public void hardestWords() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        hardest(m, "y");
    }

    /** Seeded stats, plus two words missed: the most missed is a word, shown and opened as one. */
    void hardest(Main m, String prefix) throws Exception {
        StatsTest.seed(m);
        long now = System.currentTimeMillis();
        Words.build();
        String[] w1 = Words.use.get(0), w2 = Words.use.get(3);
        for (Object[] x : new Object[][]{{w1[0], 7}, {w2[0], 4}}) {
            Progress.Card k = new Progress.Card();
            k.s = 2; k.d = 6; k.reps = 5; k.first = now - 20 * DAY; k.last = now - DAY; k.due = now + DAY; k.grade = 3;
            k.lapses = (Integer) x[1];
            m.prog.cards.put((String) x[0], k);
            m.prog.putCard((String) x[0], k);
        }
        List<String> hard = Stats.hardest(m.prog, Stats.HARDEST);
        assertEquals("the word missed 7 times first", w1[0], hard.get(0));
        assertTrue("the other word among them", hard.contains(w2[0]));

        m.openStats();
        ShadowLooper.idleMainLooper();
        List<String> rows = StatsTest.hardRows(m.root, new ArrayList<>());
        assertEquals(10, rows.size());
        assertEquals(w1[0] + " " + w1[1] + " " + w1[2] + ", missed 7 times", rows.get(0));
        assertTrue(rows.contains(w2[0] + " " + w2[1] + " " + w2[2] + ", missed 4 times"));
        assertNotNull("the word written out", WalkTest.find(m.root, w1[0]));
        assertNotNull("its meaning", WalkTest.find(m.root, w1[2]));
        shots.shot(m, prefix + "2-hardest-words");

        // a tap opens it as a word's card, looked up: Done, not a check; back to stats
        List<View> all = new ArrayList<>();
        StatsTest.collect(m.root, all);
        View row = null;
        for (View v : all) if (v.getContentDescription().toString().startsWith(w1[0] + " ")) row = v;
        assertNotNull(row);
        row.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertTrue(m.learn.lookup);
        assertEquals(w1[0], m.learn.items.get(0));
        assertNotNull("a word's card", WalkTest.find(m.root, "WORD"));
        assertNotNull(WalkTest.find(m.root, "Its characters".toUpperCase()));
        assertNotNull("how well it's known, not a batch count", WalkTest.find(m.root, "known "));
        assertNull(WalkTest.find(m.root, "new word"));
        assertNull(WalkTest.find(m.root, "Check yourself"));
        shots.shot(m, prefix + "3-hard-word-open");
        WalkTest.tap(m, "Done");
        ShadowLooper.idleMainLooper();
        assertEquals("back to stats", "stats", m.screen);
        assertNotNull(HomeTest.first(m.root, WeekView.class));
    }
}
