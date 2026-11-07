package dev.stefanofrigerio.hanzipath;

import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * One rule for an item's garden state (Progress.state), the same on every screen that colours
 * items: a card on its learning steps (the hour, the day) or relearning after a miss, and due,
 * is learning, never wilting; a graduated card overdue and slipping is wilting; a known card
 * known; the next batch next, the rest ahead. Checked on home's garden, Tree, the HSK levels,
 * Search, the parts, and Stats' path bar. Renders gs*.png (light), gsn*.png (night).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class StateTest {
    static final long DAY = Progress.DAY, HOUR = 3_600_000L, MIN = 60_000L;
    final HomeTest shots = new HomeTest();

    /** The cards: learning at its hour step and at its day step, relearning at its day step
     *  (all due a minute ago), a graduated one two days overdue, a known one. */
    String step2, step3, relearn, wilt, known, next;

    Main seed() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        Progress p = m.prog;
        p.cards.clear();
        long now = System.currentTimeMillis();
        List<Item> chars = new ArrayList<>();
        for (Item it : PathData.items) if (!it.isPart() && Search.find(it.h).size() > 0) chars.add(it);
        step2 = chars.get(0).h; step3 = chars.get(1).h; relearn = chars.get(2).h;
        wilt = chars.get(3).h; known = chars.get(4).h;
        card(p, step2, 2, 2, 3.7, now - HOUR - MIN, now - MIN);
        card(p, step3, 3, 3, 4.5, now - DAY - MIN, now - MIN);
        card(p, relearn, -2, 6, 0.6, now - DAY - MIN, now - MIN);
        card(p, wilt, 0, 4, 2, now - 9 * DAY, now - 2 * DAY);
        card(p, known, 0, 5, 60, now - DAY, now + 30 * DAY);
        for (Item it : PathData.items) if (!p.cards.containsKey(it.h)) { next = it.h; break; }
        m.home();
        ShadowLooper.idleMainLooper();
        return m;
    }

    static void card(Progress p, String h, int step, long reps, double s, long last, long due) {
        Progress.Card c = new Progress.Card();
        c.s = s; c.d = 5; c.reps = reps; c.lapses = step < 0 ? 1 : 0; c.grade = 3; c.step = step;
        c.first = last - 3 * DAY; c.last = last; c.due = due;
        p.cards.put(h, c);
        p.putCard(h, c);
    }

    static int at(String h) { return PathData.get(h).at; }

    static String name(int s) { return GardenView.stateName(s); }

    /** Every view under v of a kind. */
    static <T extends View> void all(View v, Class<T> k, List<T> out) {
        if (k.isInstance(v)) out.add(k.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), k, out);
    }

    /** The first view whose description starts with this. */
    static View described(View v, String start) {
        CharSequence d = v.getContentDescription();
        if (d != null && d.toString().startsWith(start)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = described(((ViewGroup) v).getChildAt(i), start);
            if (f != null) return f;
        }
        return null;
    }

    /** What each card should show, everywhere. */
    void expect(String where, java.util.function.Function<String, String> shown) {
        assertEquals(where + ": learning at its hour step", "learning", shown.apply(step2));
        assertEquals(where + ": learning at its day step", "learning", shown.apply(step3));
        assertEquals(where + ": relearning", "learning", shown.apply(relearn));
        assertEquals(where + ": graduated and overdue", "wilting", shown.apply(wilt));
        assertEquals(where + ": known", "known", shown.apply(known));
    }

    @Test
    public void everywhere() throws Exception { walk("gs"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception { walk("gsn"); }

    void walk(String pre) throws Exception {
        Main m = seed();
        Progress p = m.prog;
        long now = System.currentTimeMillis();

        // the rule itself
        expect("Progress.state", h -> name(p.state(h, now, false)));
        assertEquals(GardenView.NEXT, p.state(next, now, true));
        assertEquals(GardenView.NEAR, p.state(next, now, false));

        // home's garden: its cells' states, the next batch on the highlighter, the far ones faint
        GardenView g = HomeTest.first(m.root, GardenView.class);
        assertNotNull(g);
        expect("home", h -> name(g.state[at(h)]));
        assertEquals(GardenView.NEXT, g.state[at(next)]);
        assertEquals(GardenView.FAR, g.state[g.state.length - 1]);
        assertNotNull("home's legend has wilting", textIn(m.root, "wilting"));
        shots.shot(m, pre + "1-home");

        // Tree: the character's own square, as its root row says
        for (String h : new String[]{step2, step3, relearn, wilt, known}) {
            Tree.open(m, h);
            ShadowLooper.idleMainLooper();
            View root = described(m.root, h + " ");
            assertNotNull("tree root of " + h, root);
            String d = root.getContentDescription().toString();
            String want = h.equals(wilt) ? "wilting" : h.equals(known) ? "known" : "learning";
            assertTrue("tree " + h + ": " + d, d.contains(", " + want + "."));
            if (h.equals(step3)) shots.shot(m, pre + "2-tree");
        }
        expect("Tree.state", h -> name(Tree.state(p, h, now, java.util.Collections.<String>emptySet())));

        // the HSK levels: the states the screen draws its gardens in
        m.home();
        m.openLevels();
        ShadowLooper.idleMainLooper();
        List<GardenView> gs = new ArrayList<>();
        all(m.root, GardenView.class, gs);
        assertFalse(gs.isEmpty());
        final int[] lst = gs.get(0).state;
        expect("levels", h -> name(lst[at(h)]));
        assertNotNull("levels' legend has wilting", textIn(m.root, "wilting"));
        shots.shot(m, pre + "3-levels");

        // Search: each card's row says its state
        m.home();
        m.openSearch();
        ShadowLooper.idleMainLooper();
        for (String h : new String[]{step2, step3, relearn, wilt, known}) {
            m.search.field.setText(h);
            ShadowLooper.idleMainLooper();
            View row = described(m.root, h + " ");
            assertNotNull("search row of " + h, row);
            String d = row.getContentDescription().toString();
            String want = h.equals(wilt) ? "wilting" : h.equals(known) ? "known" : "learning";
            assertTrue("search " + h + ": " + d, d.endsWith(", " + want));
            if (h.equals(step3)) shots.shot(m, pre + "4-search");
        }

        // the parts: its states, as of its show
        m.home();
        m.openParts();
        ShadowLooper.idleMainLooper();
        expect("parts", h -> name(m.parts.st[at(h)]));
        shots.shot(m, pre + "5-parts");

        // Stats' path bar: the known one known, the rest of the five learning
        m.home();
        m.openStats();
        ShadowLooper.idleMainLooper();
        List<Stats.PathBar> bars = new ArrayList<>();
        all(m.root, Stats.PathBar.class, bars);
        assertFalse(bars.isEmpty());
        assertEquals(1, bars.get(0).known);
        assertEquals(4, bars.get(0).learning);
    }

    /** The first TextView whose text contains this, or null. */
    static View textIn(View v, String s) {
        if (v instanceof android.widget.TextView && ((android.widget.TextView) v).getText().toString().contains(s)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = textIn(((ViewGroup) v).getChildAt(i), s);
            if (f != null) return f;
        }
        return null;
    }
}
