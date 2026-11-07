package dev.stefanofrigerio.hanzipath;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The family tree: from 想's card, "Family tree ›" opens 想 → 相 心, 相 → 木 目, down to parts
 * with no parts; every node at its level, on its parent's branch, inside the card. Under it
 * the 相 and 心 families: every item on the path with that part anywhere in its tree (checked
 * against a walk of the whole path), with their counts. A node and a family member open their
 * cards, back returns to the tree, back again to 想's card. A deep one (7 levels) fits at
 * 412 dp. Renders: f*.png (light), fn*.png (night).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class TreeTest {
    final PickTest pt = new PickTest();

    static <T extends View> List<T> all(View v, Class<T> c) {
        List<T> out = new ArrayList<>();
        collect(v, c, out);
        return out;
    }

    static <T extends View> void collect(View v, Class<T> c, List<T> out) {
        if (c.isInstance(v)) out.add(c.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), c, out);
    }

    /** Whether part is anywhere in h's tree, walked here on its own. */
    static boolean contains(String h, String part, Set<String> seen) {
        Item it = PathData.get(h);
        if (it == null || !seen.add(h)) return false;
        for (String p : it.parts) if (p.equals(part) || contains(p, part, seen)) return true;
        return false;
    }

    static Tree.Row row(Main m, String h) {
        for (Tree.Row r : all(m.root, Tree.Row.class)) if (r.n.h.equals(h) && r.n.level > 0) return r;
        return null;
    }

    static Tree.FamilyView family(Main m, String part) {
        for (Tree.FamilyView f : all(m.root, Tree.FamilyView.class))
            if (f.getContentDescription().toString().startsWith("The " + part + " family")) return f;
        return null;
    }

    /** The deepest tree on the path, and its depth. */
    static String deepest() {
        String best = null;
        int most = -1;
        for (Item it : PathData.items) {
            int d = 0;
            for (Tree.Node n : Tree.nodes(it.h)) d = Math.max(d, n.level);
            if (d > most) { most = d; best = it.h; }
        }
        return best;
    }

    void walk(String prefix) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        int met = Math.min(300, PathData.items.size() / 3);
        WordsTest.know(m, met);
        long now = System.currentTimeMillis();

        // 想's card: Made of, with the link
        m.lookup("想");
        ShadowLooper.idleMainLooper();
        Learn card = m.learn;
        assertTrue(PickTest.shows(m.root, "MADE OF"));
        pt.shot(m, prefix + "0-card");
        WalkTest.tap(m, "Family tree");
        assertEquals("tree", m.screen);
        assertEquals("想", m.tree.h);

        // the tree: each node's parts right under it, one level down, to the leaves
        List<Tree.Node> nodes = Tree.nodes("想");
        assertEquals("想", nodes.get(0).h);
        assertEquals(0, nodes.get(0).level);
        List<String> order = new ArrayList<>();
        for (Tree.Node n : nodes) order.add(n.level + n.h);
        assertTrue(order.toString(), order.containsAll(Arrays.asList("1相", "1心", "2木", "2目")));
        assertTrue("相's parts before 心", order.indexOf("2木") < order.indexOf("1心") && order.indexOf("2目") < order.indexOf("1心"));
        int depth = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Tree.Node n = nodes.get(i);
            depth = Math.max(depth, n.level);
            List<String> kids = new ArrayList<>();
            for (int j = i + 1; j < nodes.size() && nodes.get(j).level > n.level; j++)
                if (nodes.get(j).level == n.level + 1) kids.add(nodes.get(j).h);
            List<String> want = new ArrayList<>();
            for (String p : PathData.get(n.h).parts) if (PathData.get(p) != null) want.add(p);
            assertEquals("the parts of " + n.h, want, kids);
            assertEquals("a leaf has no parts: " + n.h, want.isEmpty(), !n.kids);
            if (i > 0) assertTrue("one level at a time", n.level <= nodes.get(i - 1).level + 1);
        }
        assertTrue("想 goes 3 levels down or more: " + depth, depth >= 3);
        // on screen: a row per node, indented by level, inside the card
        List<Tree.Row> rows = all(m.root, Tree.Row.class);
        assertEquals(nodes.size(), rows.size());
        int[] a = new int[2], b = new int[2];
        View cardView = (View) rows.get(0).getParent().getParent();
        cardView.getLocationInWindow(b);
        for (int i = 1; i < rows.size(); i++) {
            Tree.Row r = rows.get(i);
            assertEquals(nodes.get(i).h, r.n.h);
            r.getChildAt(0).getLocationInWindow(a);
            assertEquals("indented by level: " + r.n.h, b[0] + cardView.getPaddingLeft() + Tree.left(r.n.level), a[0], 2);
            View text = r.getChildAt(1);
            assertTrue("the meaning has room at level " + r.n.level + ": " + text.getWidth(), text.getWidth() > Ink.dp(120));
            text.getLocationInWindow(a);
            assertTrue("inside the card", a[0] + text.getWidth() <= b[0] + cardView.getWidth());
        }
        assertNotNull(WalkTest.find(m.root, Ui.pinyin(PathData.get("相").py[0]).toString()));
        assertNotNull(WalkTest.find(m.root, PathData.get("相").en));

        // the families: 相's and 心's, complete and true, in path order
        Set<String> next = new HashSet<>(m.prog.nextNew(m.prog.batch));
        for (String part : new String[]{"相", "心"}) {
            List<Integer> fam = Tree.family(part);
            List<Integer> want = new ArrayList<>();
            for (Item it : PathData.items) if (contains(it.h, part, new HashSet<>())) want.add(it.at);
            assertEquals("the " + part + " family", want, fam);
            assertTrue(part + "'s family has 想", fam.contains(PathData.get("想").at));
            int known = 0;
            for (int i : fam) if (Tree.state(m.prog, PathData.items.get(i).h, now, next) == GardenView.KNOWN) known++;
            assertNotNull("the count", WalkTest.find(m.root, fam.size() + " on the path, " + known + " known"));
            Tree.FamilyView fv = family(m, part);
            assertNotNull(part + "'s family on screen", fv);
            assertEquals(Math.min(fam.size(), Tree.FOLD_ROWS * fv.cols), fv.count());
        }
        // 氵's family: what has it, and only that
        List<Integer> water = Tree.family("氵");
        for (String h : new String[]{"法", "海"}) if (PathData.get(h) != null) assertTrue(h + " is in 氵's", water.contains(PathData.get(h).at));
        assertFalse(water.isEmpty());
        for (int i : water) assertTrue(PathData.items.get(i).h + " has 氵", contains(PathData.items.get(i).h, "氵", new HashSet<>()));
        assertFalse("想 has no 氵", water.contains(PathData.get("想").at));
        pt.shot(m, prefix + "1-xiang");

        // a node opens its card; back is the tree
        Tree t = m.tree;
        row(m, "目").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals("目", m.learn.items.get(0));
        assertTrue(m.back());
        assertEquals("tree", m.screen);
        assertSame(t, m.tree);
        // a family member does too
        Tree.FamilyView fv = family(m, "心");
        int j = fv.members.size() > 1 && PathData.items.get(fv.members.get(0)).h.equals("想") ? 1 : 0;
        String member = PathData.items.get(fv.members.get(j)).h;
        fv.open(j);
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals(member, m.learn.items.get(0));
        WalkTest.tap(m, "Done");
        assertEquals("tree", m.screen);
        // back again: 想's card, as it was; then the path
        assertTrue(m.back());
        assertEquals("learn", m.screen);
        assertSame(card, m.learn);
        assertTrue(PickTest.shows(m.root, PathData.get("想").en));
        ShadowLooper.idleMainLooper();   // the card laid out (its strokes start) before it's left
        assertTrue(m.back());
        assertEquals("home", m.screen);

        // a big family folds, and unfolds
        String big = null;
        for (Item it : PathData.items) if (Tree.parts(it.h).contains("口") && Tree.family("口").size() > 40) { big = it.h; break; }
        assertNotNull(big);
        ShadowLooper.idleMainLooper();
        m.lookup(big);
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Family tree");
        Tree.FamilyView mouth = family(m, "口");
        assertEquals(Tree.FOLD_ROWS * mouth.cols, mouth.count());
        int folded = mouth.getHeight();
        WalkTest.tap(m, "Show all " + Tree.family("口").size());
        ShadowLooper.idleMainLooper();
        assertEquals(Tree.family("口").size(), mouth.count());
        assertTrue(mouth.getHeight() > folded);
        assertNotNull(WalkTest.find(m.root, "Fold the family"));
        pt.shot(m, prefix + "2-big-family");

        // the deepest tree on the path, at a phone's width
        String deep = deepest();
        m.home();
        ShadowLooper.idleMainLooper();
        m.lookup(deep);
        ShadowLooper.idleMainLooper();
        WalkTest.tap(m, "Family tree");
        for (Tree.Row r : all(m.root, Tree.Row.class)) {
            View text = r.getChildAt(1);
            assertTrue(r.n.h + " at level " + r.n.level + " has room: " + text.getWidth(), text.getWidth() > Ink.dp(60));
        }
        pt.shot(m, prefix + "3-deep");
    }

    @Test
    public void xiang() throws Exception { walk("f"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void xiangAtNight() throws Exception { walk("fn"); }

    /** Parts that lead back to themselves don't loop: each branch stops at a part already on it. */
    @Test
    public void cycles() {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Item a = new Item(), b = new Item();
        a.h = "甲甲"; b.h = "乙乙";
        a.parts = new String[]{"乙乙"}; b.parts = new String[]{"甲甲", "口"};
        a.used = b.used = new String[0];
        PathData.at.put(a.h, a);
        PathData.at.put(b.h, b);
        try {
            List<String> got = new ArrayList<>();
            for (Tree.Node n : Tree.nodes(a.h)) got.add(n.level + n.h);
            assertEquals("0甲甲", got.get(0));
            assertEquals("1乙乙", got.get(1));
            assertEquals("2口", got.get(2));
            assertFalse(got.contains("2甲甲"));
            Tree.below = new java.util.HashMap<>();
            assertTrue(Tree.below(a.h, new HashSet<>()).contains("口"));
        } finally {
            PathData.at.remove(a.h);
            PathData.at.remove(b.h);
            Tree.families = null;
        }
        assertNotNull(m);
    }
}
