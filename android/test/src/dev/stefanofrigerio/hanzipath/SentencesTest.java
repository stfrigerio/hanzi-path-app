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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Sentences (Tatoeba, tools/build.py): the data; a card's sentence (a character's, a word's),
 * with the card's own marked, and none while none is readable; the Read round from home
 * (show, next, the log, the next round's order) and the threshold home offers it at.
 * Light and dark; renders are r*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class SentencesTest {
    final HomeTest shots = new HomeTest();

    @Test
    public void data() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Sentences.load(m);
        List<Sentences.S> all = Sentences.all;
        assertTrue("sentences: " + all.size(), all.size() > 1500);
        Map<Integer, Integer> per = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int last = -1;
        for (Sentences.S s : all) {
            assertTrue("each once: " + s.zh, seen.add(s.zh));
            assertTrue(s.zh + ": 4 to 20 characters", s.han.length >= 4 && s.han.length <= 20);
            int at = -1;
            for (String c : s.han) {
                Item it = PathData.get(c);
                assertNotNull(s.zh + ": " + c + " is on the path", it);
                at = Math.max(at, it.at);
            }
            assertEquals(s.zh + ": readable from its last character's place", at, s.at);
            assertTrue("in the path's order", s.at >= last);
            last = s.at;
            assertFalse(s.zh + ": a translation", s.en.trim().isEmpty());
            assertEquals(s.zh + ": a syllable per character", s.han.length, s.py.length);
            for (String p : s.py) assertTrue(s.zh + ": a syllable " + p, p.matches("[\"'(]*[a-zü\\u0100-\\u01dc\\u00e0-\\u00fc]+[,.?!:;\"')…—·]*"));
            for (String c : Words.chars(s.zh))
                assertTrue(s.zh + ": only characters and punctuation", Sentences.isHan(c) || "，。？！、：；“”‘’…《》（）「」—·".contains(c));
            per.put(s.at, per.getOrDefault(s.at, 0) + 1);
        }
        for (int n : per.values()) assertTrue("three a place at most", n <= 3);
        // read as the words in them say: the particles, a character's other reading in a word
        assertEquals("wǒ men lái le.", py("我们来了。"));
        assertEquals("bù shì wǒ de.", py("不是我的。"));
    }

    static String py(String zh) {
        for (Sentences.S s : Sentences.all) if (s.zh.equals(zh)) return android.text.TextUtils.join(" ", s.py);
        return null;
    }

    /** The first sentence readable from a place on the path at or after from, with h in it. */
    static Sentences.S with(String h, int from) {
        for (Sentences.S s : Sentences.all) if (s.at >= from && s.zh.contains(h)) return s;
        return null;
    }

    static View section(View v) { return WalkTest.find(v, "IN A SENTENCE"); }

    @Test
    public void cards() throws Exception { cards(Robolectric.buildActivity(Main.class).setup().visible().get(), "r"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void cardsNight() throws Exception { cards(Robolectric.buildActivity(Main.class).setup().visible().get(), "rn"); }

    void cards(Main m, String prefix) throws Exception {
        Sentences.load(m);
        // nothing met: no sentence on 人's card
        m.lookup("人");
        ShadowLooper.idleMainLooper();
        assertNull("none readable, none shown", section(m.root));
        assertTrue(m.back());

        // a long sentence's last character, everything before it known: its card shows it
        Sentences.S s = null;
        for (Sentences.S x : Sentences.all) if (x.han.length >= 14 && x.at < 400) { s = x; break; }
        assertNotNull(s);
        WordsTest.know(m, s.at);   // all but the sentence's last new character
        String h = PathData.items.get(s.at).h;
        Sentences.S got = Sentences.forCard(m, h);
        assertNotNull(got);
        assertTrue(got.zh.contains(h));
        m.lookup(h);
        ShadowLooper.idleMainLooper();
        assertNotNull("the sentence on the card", section(m.root));
        TextView zh = WalkTest.find(m.root, got.zh);
        assertNotNull(zh);
        Spanned sp = (Spanned) zh.getText();
        int at = got.zh.indexOf(h);
        BackgroundColorSpan[] hl = sp.getSpans(at, at + 1, BackgroundColorSpan.class);
        assertEquals("the card's own on the highlighter", 1, hl.length);
        assertEquals(Ink.hl, hl[0].getBackgroundColor());
        int other = at == 0 ? 1 : 0;
        if (!got.zh.substring(other, other + 1).equals(h))
            assertEquals("the rest not", 0, sp.getSpans(other, other + 1, BackgroundColorSpan.class).length);
        assertNotNull("its English", WalkTest.find(m.root, got.en));
        assertNotNull("the speaker", byDesc(m.root, "Say the sentence"));
        shots.shot(m, prefix + "1-card-" + s.at);
        assertTrue(m.back());

        // a character of it not met (and not the card's): no sentence with it
        String gone = null;
        for (String c : got.han) if (!c.equals(h)) gone = c;
        m.prog.cards.remove(gone);
        Sentences.S y = Sentences.forCard(m, h);
        if (y != null) for (String c : y.han) assertTrue(y.zh + ": every character met or the card's", c.equals(h) || m.prog.cards.containsKey(c));
        assertNotEquals("not the one with a character never met", got, y);

        // a word's card: a sentence with the word in it, all of it marked
        Words.build();
        String word = null;
        Sentences.S ws = null;
        for (Sentences.S x : Sentences.all) {
            for (String[] w : Words.all) if (x.zh.contains(w[0]) && x.han.length >= 8) { word = w[0]; break; }
            if (word != null) { ws = x; break; }
        }
        assertNotNull("a sentence with a word of the path", word);
        WordsTest.know(m, ws.at + 1);
        m.lookup(word);
        ShadowLooper.idleMainLooper();
        assertNotNull("a word's card", WalkTest.find(m.root, "WORD"));
        assertNotNull(section(m.root));
        Sentences.S wgot = Sentences.forCard(m, word);
        TextView wz = WalkTest.find(m.root, wgot.zh);
        assertNotNull(wz);
        int wa = wgot.zh.indexOf(word);
        Spanned wsp = (Spanned) wz.getText();
        for (int k = 0; k < word.length(); k++)
            assertEquals("the whole word marked", 1, wsp.getSpans(wa + k, wa + k + 1, BackgroundColorSpan.class).length);
        shots.shot(m, prefix + "2-word-card");
        assertTrue(m.back());
    }

    static View byDesc(View v, String d) {
        if (v.getContentDescription() != null && v.getContentDescription().toString().equals(d)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View x = byDesc(((ViewGroup) v).getChildAt(i), d);
            if (x != null) return x;
        }
        return null;
    }

    /** How far down the path you must know for n sentences to be readable. */
    static int knownFor(int n) {
        Set<String> known = new HashSet<>();
        for (int i = 0; i < PathData.items.size(); i++) {
            known.add(PathData.items.get(i).h);
            int c = 0;
            for (Sentences.S s : Sentences.all) {
                boolean ok = true;
                for (String x : s.han) ok &= known.contains(x);
                if (ok) c++;
            }
            if (c >= n) return i + 1;
        }
        return -1;
    }

    static int reads(Main m) {
        try (android.database.Cursor c = m.prog.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reviews WHERE mode = 'read'", null)) {
            c.moveToNext();
            return c.getInt(0);
        }
    }

    @Test
    public void round() throws Exception { round(Robolectric.buildActivity(Main.class).setup().visible().get(), "r"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void roundNight() throws Exception { round(Robolectric.buildActivity(Main.class).setup().visible().get(), "rn"); }

    void round(Main m, String prefix) throws Exception {
        Sentences.load(m);
        // the threshold: four readable, no line; five, the line
        int k = knownFor(Read.MIN);
        assertTrue(k > 0);
        WordsTest.know(m, k - 1);
        m.home();
        assertNull("fewer than five readable: no Read line", WalkTest.find(m.root, "Read "));
        WordsTest.know(m, k);
        m.home();
        assertNotNull("five readable: the Read line", WalkTest.find(m.root, "Read 5 sentences"));

        // far along: ten a round
        WordsTest.know(m, 400);
        m.home();
        int can = Sentences.readable(m, Sentences.known(m.prog, System.currentTimeMillis())).size();
        assertTrue(can > 2 * Read.ROUND);
        assertNotNull(WalkTest.find(m.root, "Read 10 sentences"));
        shots.shot(m, prefix + "3-home");
        long[] before = m.prog.reviewTotals();
        ((View) WalkTest.find(m.root, "Read 10 sentences").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("read", m.screen);
        assertEquals(Read.ROUND, m.read.queue.size());
        Set<String> first = new HashSet<>();
        for (Sentences.S s : m.read.queue) first.add(s.zh);
        Sentences.S s0 = m.read.queue.get(0);
        assertNotNull("the sentence", WalkTest.find(m.root, s0.zh));
        assertNull("its English not yet", WalkTest.find(m.root, s0.en));
        assertNull(WalkTest.find(m.root, "Next"));
        // a long one, to see it wrap
        int longest = 0;
        for (int i = 0; i < m.read.queue.size(); i++) if (m.read.queue.get(i).han.length > m.read.queue.get(longest).han.length) longest = i;
        shots.shot(m, prefix + "4-read");
        WalkTest.tap(m, "Show");
        assertNotNull("the English", WalkTest.find(m.root, s0.en));
        assertNotNull("the pinyin", WalkTest.find(m.root, android.text.TextUtils.join(" ", s0.py)));
        assertEquals("read once Show is tapped", 1, reads(m));
        shots.shot(m, prefix + "5-shown");
        for (int i = 1; i < Read.ROUND; i++) {
            WalkTest.tap(m, "Next");
            assertEquals(m.read.queue.get(i).zh, WalkTest.find(m.root, m.read.queue.get(i).zh).getText().toString());
            WalkTest.tap(m, "Show");
            if (i == longest) shots.shot(m, prefix + "6-long");
        }
        WalkTest.tap(m, "Finish");
        assertEquals("read-done", m.screen);
        assertEquals("each read in the log", Read.ROUND, reads(m));
        assertArrayEquals("reads aren't grades: stats count them apart", before, m.prog.reviewTotals());
        assertEquals(0, m.prog.reviewsSince(0).size());
        shots.shot(m, prefix + "7-done");

        // the next round: none read just now
        WalkTest.tap(m, "Another round");
        assertEquals("read", m.screen);
        for (Sentences.S s : m.read.queue) assertFalse("not read again so soon: " + s.zh, first.contains(s.zh));
        // the longest readable, to see a long one wrap
        Sentences.S lng = null;
        for (Sentences.S s : Sentences.readable(m, Sentences.known(m.prog, System.currentTimeMillis())))
            if (lng == null || s.zh.length() > lng.zh.length()) lng = s;
        m.read.queue.set(0, lng);
        m.read.show();
        WalkTest.tap(m, "Show");
        assertNotNull(WalkTest.find(m.root, lng.en));
        shots.shot(m, prefix + "8-longest");
        assertTrue(m.back());
        assertEquals("home", m.screen);
    }
}
