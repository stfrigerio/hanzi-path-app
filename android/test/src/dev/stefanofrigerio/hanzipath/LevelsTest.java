package dev.stefanofrigerio.hanzipath;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The voice's speed (Settings: steps, kept, in a copy, older settings load as normal, Speech
 * given the rate) and the HSK levels screen from Stats (its counts against a count made here
 * from the path's words, a level folded and unfolded, a cell opened, back to the levels, then
 * Stats). Renders v*.png (settings) and l*.png (levels), light and dark.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class LevelsTest {
    static final long DAY = 24 * 3600_000L;

    /** The pill beside a setting's name. */
    static TextView setting(Main m, String name) {
        TextView label = WalkTest.find(m.root, name);
        assertNotNull("no setting " + name, label);
        return (TextView) ((ViewGroup) label.getParent()).getChildAt(1);
    }

    @Test
    public void speed() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        HomeTest shots = new HomeTest();
        assertEquals("normal by default", "normal", m.prog.speed);
        m.say("人");
        assertEquals(Speech.NORMAL, Speech.rate, 1e-6);
        assertEquals(0.85f, Speech.NORMAL, 1e-6);

        m.openSettings();
        ShadowLooper.idleMainLooper();
        TextView b = setting(m, "Voice speed");
        assertEquals("normal  ›", b.getText().toString());
        shots.shot(m, "v1-settings");
        b.performClick();
        assertEquals("slower  ›", b.getText().toString());
        assertEquals("slower", m.prog.speed);
        shots.shot(m, "v2-settings-slower");

        // kept: the database read again, and the settings screen built again
        m.prog.load();
        assertEquals("slower", m.prog.speed);
        m.openSettings();
        assertEquals("slower  ›", setting(m, "Voice speed").getText().toString());

        // said at it: a character, a word, a sentence
        for (String s : new String[]{"人", "大人", "我是中国人。"}) {
            m.say(s);
            assertEquals(s, Speech.SLOWER, Speech.rate, 1e-6);
        }
        assertEquals(0.6f, Speech.SLOWER, 1e-6);

        // in the copy, and back from it
        String copy = m.prog.export();
        assertEquals("slower", new JSONObject(copy).getJSONObject("meta").getJSONObject("opts").getString("speed"));
        setting(m, "Voice speed").performClick();
        assertEquals("normal", m.prog.speed);
        m.prog.replace(copy);
        assertEquals("slower", m.prog.speed);

        // a step more goes round to normal
        m.openSettings();
        setting(m, "Voice speed").performClick();
        assertEquals("normal", m.prog.speed);
        m.say("人");
        assertEquals(Speech.NORMAL, Speech.rate, 1e-6);

        // settings saved before there was a speed: normal (in the database, and in a v 1 copy)
        m.prog.speed = "slower";
        m.prog.saveOpts();
        m.prog.putMeta("opts", "{\"batch\":5,\"perDay\":15,\"say\":true,\"remind\":-1,\"round\":40}");
        m.prog.load();
        assertEquals("normal", m.prog.speed);
        assertEquals(40, m.prog.round);
        m.prog.speed = "slower";
        m.prog.saveOpts();
        m.prog.replace("{\"v\":1,\"cards\":{},\"opts\":{\"batch\":8,\"perDay\":20,\"say\":false}}");
        assertEquals("normal", m.prog.speed);
        assertEquals(8, m.prog.batch);
        m.say("人");
        assertEquals(Speech.NORMAL, Speech.rate, 1e-6);
    }

    /** HSK words, each at its lowest level: counted here, straight from the lists in the APK. */
    static Map<String, Integer> words() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        JSONObject ls = new JSONObject(PathData.read(c, "hsk.json")).getJSONObject("levels");
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int l = 6; l >= 1; l--) {
            org.json.JSONArray a = ls.getJSONArray(String.valueOf(l));
            for (int i = 0; i < a.length(); i++) out.put(a.getJSONArray(i).getString(0), l);   // lower levels overwrite
        }
        return out;
    }

    /** Each path character's lowest HSK level, among every word it's in (one-character words too). */
    static Map<String, Integer> levelOf() throws Exception {
        Map<String, Integer> lv = new HashMap<>();
        for (Map.Entry<String, Integer> w : words().entrySet()) {
            int l = w.getValue();
            String s = w.getKey();
            for (int i = 0; i < s.length(); ) {
                String c = s.substring(i, i + Character.charCount(s.codePointAt(i)));
                i += c.length();
                if (PathData.get(c) != null && (!lv.containsKey(c) || l < lv.get(c))) lv.put(c, l);
            }
        }
        return lv;
    }

    static Progress.Card card(boolean known, long now) {
        Progress.Card k = new Progress.Card();
        k.s = known ? 60 : 3; k.d = 5; k.reps = 3; k.first = now - 30 * DAY;
        k.last = known ? now - DAY : now - 3600_000L; k.due = now + 2 * DAY; k.grade = 3;
        return k;
    }

    /** Most of HSK 1 met (two in three known), a few of HSK 2 and 3, some HSK 1 words known. */
    static void seed(Main m) throws Exception {
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        Map<String, Integer> lv = levelOf();
        int[] seen = new int[7];
        for (Item it : PathData.items) {
            Integer l = lv.get(it.h);
            if (l == null || l > 3) continue;
            int n = seen[l]++;
            if (l == 1 && n < 60) p.cards.put(it.h, card(n % 3 != 0, now));
            else if (l > 1 && n < 12) p.cards.put(it.h, card(n % 2 == 0, now));
        }
        int k = 0;
        for (Map.Entry<String, Integer> w : words().entrySet())
            if (w.getValue() == 1 && Words.is(w.getKey()) && k++ < 14) p.cards.put(w.getKey(), card(k % 4 != 0, now));
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet()) p.putCard(e.getKey(), e.getValue());
    }

    static void all(View v, Class<?> k, List<View> out) {
        if (k.isInstance(v)) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), k, out);
    }

    @Test
    public void levels() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        HomeTest shots = new HomeTest();
        seed(m);
        long now = System.currentTimeMillis();

        // from Stats, the row at its top
        m.openStats();
        ShadowLooper.idleMainLooper();
        TextView entry = WalkTest.find(m.root, "HSK LEVELS");
        assertNotNull(entry);
        ((View) entry.getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("levels", m.screen);
        shots.shot(m, "l1-levels");

        // the counts, against the path's words counted here
        Map<String, Integer> lv = levelOf();
        assertEquals("the app's levels are the count's", lv, Levels.levelOf(m));
        for (String c : new String[]{"你", "很", "的"})
            if (PathData.get(c) != null) assertEquals(c + ": a one-character HSK 1 word", Integer.valueOf(1), lv.get(c));
        int[] chars = new int[7], known = new int[7], learning = new int[7], words = new int[7], wordsKnown = new int[7];
        for (Item it : PathData.items) {
            Integer l = lv.get(it.h);
            if (l == null && it.isPart()) continue;
            int i = l == null ? 0 : l;
            chars[i]++;
            if (m.prog.strength(it.h, now) >= Progress.KNOWN) known[i]++;
            else if (m.prog.cards.containsKey(it.h)) learning[i]++;
        }
        for (Map.Entry<String, Integer> w : words().entrySet()) {
            words[w.getValue()]++;
            if (m.prog.strength(w.getKey(), now) >= Progress.KNOWN) wordsKnown[w.getValue()]++;
        }
        int total = 0;
        for (int i = 0; i <= 6; i++) total += chars[i];
        assertTrue("every character once", total <= PathData.items.size());
        assertTrue("HSK 1 seeded", known[1] > 0 && learning[1] > 0 && wordsKnown[1] > 0);
        for (int i = 1; i <= 6; i++) {
            assertTrue("HSK " + i + " has characters", chars[i] > 0);
            String line = known[i] + " of " + chars[i] + " characters known · " + wordsKnown[i] + " of " + words[i] + " words";
            assertNotNull("HSK " + i + ": " + line, WalkTest.find(m.root, line));
            assertNotNull(WalkTest.find(m.root, "HSK " + i));
        }
        if (chars[0] > 0) assertNotNull(WalkTest.find(m.root, known[0] + " of " + chars[0] + " characters known"));
        List<View> bars = new ArrayList<>();
        all(m.root, Stats.PathBar.class, bars);
        Stats.PathBar b1 = (Stats.PathBar) bars.get(0);
        assertEquals(known[1], b1.known);
        assertEquals(learning[1], b1.learning);
        assertEquals(chars[1], b1.size);

        // HSK 1's garden: its characters, in path order, folded to three rows
        List<View> gs = new ArrayList<>();
        all(m.root, GardenView.class, gs);
        GardenView g = (GardenView) gs.get(0);
        assertEquals(chars[1], g.pick.length);
        for (int j = 0; j < g.pick.length; j++) {
            assertEquals(Integer.valueOf(1), lv.get(PathData.items.get(g.pick[j]).h));
            if (j > 0) assertTrue("path order", g.pick[j] > g.pick[j - 1]);
        }
        int fold = 3 * g.cols;
        assertTrue("more than three rows", chars[1] > fold);
        assertEquals(fold, g.count());
        int folded = g.getHeight();
        WalkTest.tap(m, "Show all (" + (chars[1] - fold) + " more)");
        ShadowLooper.idleMainLooper();
        assertEquals(chars[1], g.count());
        assertTrue("taller unfolded", g.getHeight() > folded);
        shots.shot(m, "l2-levels-open");
        WalkTest.tap(m, "Fold HSK 1");
        ShadowLooper.idleMainLooper();
        assertEquals(fold, g.count());
        assertEquals(folded, g.getHeight());

        // a cell tapped opens its character; back to the levels, then Stats, then home
        WalkTest.tap(m, "Show all (" + (chars[1] - fold) + " more)");
        ShadowLooper.idleMainLooper();
        int j = 4;
        float x = (j % g.cols) * (g.cell + g.gap) + g.cell / 2, y = (j / g.cols) * (g.cell + g.gap) + g.cell / 2;
        long t = SystemClock.uptimeMillis();
        g.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        g.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertTrue(m.learn.lookup);
        assertEquals(PathData.items.get(g.pick[j]).h, m.learn.items.get(0));
        assertTrue(m.back());
        assertEquals("back to the levels", "levels", m.screen);
        List<View> again = new ArrayList<>();
        all(m.root, GardenView.class, again);
        assertEquals("still unfolded", chars[1], ((GardenView) again.get(0)).count());
        assertTrue(m.back());
        assertEquals("then Stats", "stats", m.screen);
        assertTrue(m.back());
        assertEquals("home", m.screen);
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        HomeTest shots = new HomeTest();
        m.openSettings();
        shots.shot(m, "vn1-settings");
        seed(m);
        m.openStats();
        shots.shot(m, "ln0-stats");
        m.openLevels();
        shots.shot(m, "ln1-levels");
    }
}
