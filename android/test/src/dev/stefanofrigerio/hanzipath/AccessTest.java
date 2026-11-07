package dev.stefanofrigerio.hanzipath;

import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import static org.junit.Assert.*;

/**
 * Large text and TalkBack. Every screen (home fresh and with a history, a learn card, the
 * three kinds of question before and after a tap, a round's end, stats, settings) at
 * Android's font scales 1.3 and 1.5, light and dark: no text cut (its lines fit its height,
 * none ellipsized but where a one-line cut was meant, no word broken across lines), nothing
 * off the side, no two siblings overlapping. Then the spoken labels: every control says what
 * it is, the answers say "Answer: shuǐ, water", the cells to pick from only their place
 * until the tap, and the garden's cells are TalkBack's to reach and open. Renders a*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class AccessTest {
    final PickTest shots = new PickTest();

    // ── what "laid out without clipping" means, checked over the whole window ──

    /** Everything wrong with how the screen is laid out, one line each. */
    static List<String> faults(Main m) {
        List<String> out = new ArrayList<>();
        View w = m.getWindow().getDecorView();
        walk(m.root, w.getWidth(), out);
        return out;
    }

    static void walk(View v, int windowW, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        int[] at = new int[2];
        v.getLocationInWindow(at);
        String name = v.getClass().getSimpleName() + (v instanceof TextView ? " '" + ((TextView) v).getText() + "'" : "")
                + " at " + at[0] + "," + at[1] + " " + v.getWidth() + "x" + v.getHeight();
        if (v.getWidth() > 0 && (at[0] < -1 || at[0] + v.getWidth() > windowW + 1)) out.add("off the side: " + name);
        if (v instanceof TextView) text((TextView) v, name, out);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        boolean laidOut = g instanceof LinearLayout || g instanceof Flow || g instanceof Ui.Pair;
        List<View> kids = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0 || c.getHeight() == 0) continue;
            if (laidOut) {
                if (c.getLeft() < -1 || c.getRight() > g.getWidth() + 1 || c.getTop() < -1 || c.getBottom() > g.getHeight() + 1)
                    out.add("outside its " + g.getClass().getSimpleName() + " (" + g.getWidth() + "x" + g.getHeight() + "): "
                            + c.getClass().getSimpleName() + " " + c.getLeft() + "," + c.getTop() + "-" + c.getRight() + "," + c.getBottom()
                            + (c instanceof TextView ? " '" + ((TextView) c).getText() + "'" : ""));
                Rect r = new Rect(c.getLeft(), c.getTop(), c.getRight(), c.getBottom());
                for (View k : kids) {
                    Rect q = new Rect(k.getLeft(), k.getTop(), k.getRight(), k.getBottom());
                    if (Rect.intersects(r, q)) out.add("overlap in " + g.getClass().getSimpleName() + ": " + desc(k) + " and " + desc(c));
                }
                kids.add(c);
            }
            walk(c, windowW, out);
        }
    }

    static String desc(View v) {
        return v.getClass().getSimpleName() + (v instanceof TextView ? " '" + ((TextView) v).getText() + "'" : "")
                + " " + v.getLeft() + "," + v.getTop() + "-" + v.getRight() + "," + v.getBottom();
    }

    /** A text cut: lines taller than the view, an ellipsis nobody asked for, a word broken. */
    static void text(TextView t, String name, List<String> out) {
        CharSequence s = t.getText();
        if (s.length() == 0) return;
        Layout l = t.getLayout();
        if (l == null) { out.add("never laid out: " + name); return; }
        if (t.getWidth() <= t.getCompoundPaddingLeft() + t.getCompoundPaddingRight()) out.add("no room: " + name);
        int room = t.getHeight() - t.getCompoundPaddingTop() - t.getCompoundPaddingBottom();
        if (!(t instanceof EditText) && l.getHeight() > room + 1)
            out.add("cut at the bottom (" + l.getLineCount() + " lines, " + l.getHeight() + " > " + room + "): " + name);
        boolean meant = t.getEllipsize() != null && t.getMaxLines() > 0 && t.getMaxLines() < Integer.MAX_VALUE;
        if (!meant && t.getMaxLines() > 0 && l.getLineCount() > t.getMaxLines()) out.add("more lines than it shows: " + name);
        for (int i = 0; i < l.getLineCount(); i++) {
            if (!meant && l.getEllipsisCount(i) > 0) out.add("ellipsized: " + name);
            int e = l.getLineEnd(i);
            if (i < l.getLineCount() - 1 && e > 0 && e < s.length() && latin(s.charAt(e - 1)) && latin(s.charAt(e)))
                out.add("a word broken across lines: " + name);
        }
    }

    static boolean latin(char c) {
        return Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN;
    }

    /** The screen laid out as it should be, then rendered. */
    void look(Main m, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        List<String> f = faults(m);
        shots.shot(m, name);
        assertTrue(name + ":\n  " + String.join("\n  ", f), f.isEmpty());
        unnamed(m.root, name);
    }

    /** Every control TalkBack can land on says what it is: its words, or a spoken label. */
    static void unnamed(View v, String where) {
        if (!v.isShown()) return;
        if (v.isClickable() && v.hasOnClickListeners()) {
            CharSequence d = v.getContentDescription();
            boolean words = words(v);   // TalkBack reads a row's words when it has no label
            assertTrue(where + ": a control without a name: " + v.getClass().getSimpleName() + " " + v,
                    words || d != null && d.toString().trim().length() > 0);
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) unnamed(((ViewGroup) v).getChildAt(i), where);
    }

    static boolean words(View v) {
        if (v instanceof TextView && ((TextView) v).getText().toString().replace("›", "").trim().length() > 0) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (words(((ViewGroup) v).getChildAt(i))) return true;
        return false;
    }

    /** The walk at one font scale: tag is the scale and theme, in the renders' names. */
    void walk(String tag) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertTrue("the font scale took", m.getResources().getConfiguration().fontScale > 1.2f);
        look(m, "a" + tag + "-1-home");
        WalkTest.tap(m, "Learn next 5");
        look(m, "a" + tag + "-2-learn");
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        look(m, "a" + tag + "-3-choose");
        String h = m.quiz.queue.get(0);
        TextView wrong = WalkTest.options(m).stream().filter(t -> !h.equals(t.getTag())).findFirst().get();
        wrong.performClick();
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        look(m, "a" + tag + "-4-answered");
        for (String kind : new String[]{"pick", "listen"}) {
            Quiz q = PickTest.forced(m, PickTest.some(120, 3), kind);
            look(m, "a" + tag + "-5-" + kind);
            PickTest.tapCell(m, q.queue.get(0));
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
            look(m, "a" + tag + "-6-" + kind + "-answered");
        }
        m.quiz.finish();
        look(m, "a" + tag + "-7-done");
        StatsTest.seed(m);
        m.home();
        look(m, "a" + tag + "-8-home-history");
        m.openStats();
        look(m, "a" + tag + "-9-stats");
        m.openSettings();
        look(m, "a" + tag + "-10-settings");
    }

    @Test @Config(fontScale = 1.3f)
    public void large13() throws Exception { walk("13"); }

    @Test @Config(fontScale = 1.5f)
    public void large15() throws Exception { walk("15"); }

    @Test @Config(fontScale = 1.3f, qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void large13Night() throws Exception { walk("13n"); }

    @Test @Config(fontScale = 1.5f, qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void large15Night() throws Exception { walk("15n"); }

    /** The normal size passes the same checks: nothing here was only made to fit large text. */
    @Test
    public void normal() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertEquals(1f, m.getResources().getConfiguration().fontScale, 0.01f);
        ShadowLooper.idleMainLooper();
        List<String> f = faults(m);
        assertTrue(String.join("\n", f), f.isEmpty());
        StatsTest.seed(m);
        m.home();
        ShadowLooper.idleMainLooper();
        f = faults(m);
        assertTrue(String.join("\n", f), f.isEmpty());
    }

    // ── spoken labels ──

    static View byDescription(View v, String d) {
        if (v.isShown() && v.getContentDescription() != null && v.getContentDescription().toString().equals(d)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View x = byDescription(((ViewGroup) v).getChildAt(i), d);
            if (x != null) return x;
        }
        return null;
    }

    static List<View> tagged(View v, List<View> out) {
        if (v.isShown() && v.getTag() instanceof String && v.hasOnClickListeners()) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) tagged(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    @Test
    public void labels() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertNotNull("home's ledger, one line each", byDescription(m.root, "Due now: 0"));
        assertNotNull(byDescription(m.root, "Settings and progress"));
        WalkTest.tap(m, "Learn next 5");
        assertNotNull("the speaker", byDescription(m.root, "Say it"));
        assertNotNull("the way back", byDescription(m.root, "Back to the path"));
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");

        // choose: each answer says it's one, and what it is
        String h = m.quiz.queue.get(0);
        List<View> answers = tagged(m.root, new ArrayList<>());
        assertEquals(4, answers.size());
        for (View a : answers) {
            String o = (String) a.getTag();
            assertEquals("Answer: " + Quiz.spoken(o), a.getContentDescription().toString());
        }
        View right = answers.stream().filter(a -> h.equals(a.getTag())).findFirst().get();
        right.performClick();
        ShadowLooper.idleMainLooper();
        for (View a : tagged(m.root, new ArrayList<>()))
            if (h.equals(a.getTag())) assertEquals("Answer: " + Quiz.spoken(h) + ", the right one", a.getContentDescription().toString());
        assertNotNull("undo says what it undoes", byDescription(m.root, "Undo the last answer"));

        // pick and listen: the cells say their place, never the character, until the tap
        for (String kind : new String[]{"pick", "listen"}) {
            Quiz q = PickTest.forced(m, PickTest.some(120, 3), kind);
            String c = q.queue.get(0);
            List<View> cells = tagged(m.root, new ArrayList<>());
            assertEquals(4, cells.size());
            for (int i = 0; i < 4; i++) {
                String d = cells.get(i).getContentDescription().toString();
                assertEquals(kind, "Answer " + (i + 1) + " of 4", d);
                assertFalse(kind + " gives nothing away", d.contains((String) cells.get(i).getTag()) || d.contains(Quiz.py(c)));
            }
            if (kind.equals("listen")) assertNotNull("the big speaker", byDescription(m.root, "Say it again"));
            PickTest.tapCell(m, c);
            for (View v : tagged(m.root, new ArrayList<>())) {
                String o = (String) v.getTag();
                assertTrue("after the tap, what it is: " + v.getContentDescription(),
                        v.getContentDescription().toString().contains(o + ", " + Quiz.spoken(o)));
            }
            assertNotNull(byDescription(m.root, "Answer " + (q.options.indexOf(c) + 1) + " of 4: " + c + ", " + Quiz.spoken(c) + ", the right one"));
        }

        // a setting's pill says its value, and the new one once tapped
        m.openSettings();
        View batch = byDescription(m.root, "New at a time: 5. Tap to change.");
        assertNotNull(batch);
        batch.performClick();
        assertEquals("New at a time: 8. Tap to change.", batch.getContentDescription().toString());
        batch.performClick(); batch.performClick(); batch.performClick();   // back to 5
    }

    /** The garden for TalkBack: a virtual view for each cell shown, in path order, that says
     *  what it is and where it stands, and opens it when TalkBack clicks it. */
    @Test
    public void garden() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        GardenView g = HomeTest.first(m.root, GardenView.class);
        AccessibilityNodeProvider p = g.getAccessibilityNodeProvider();
        assertNotNull("the garden has virtual views", p);
        AccessibilityNodeInfo host = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
        assertEquals("one for each cell shown", g.count(), host.getChildCount());
        assertTrue(g.count() > 10);
        int first = g.first();
        AccessibilityNodeInfo n = p.createAccessibilityNodeInfo(first);
        Item it = PathData.items.get(first);
        assertEquals(it.h + ", " + it.reading() + ", " + it.en + ": next", n.getContentDescription().toString());
        assertTrue(n.isClickable());
        assertTrue(n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        Rect b = new Rect();
        n.getBoundsInParent(b);
        assertEquals("the first cell at the corner", 0, b.left);
        assertTrue(b.width() > 0 && b.width() == b.height());
        AccessibilityNodeInfo last = p.createAccessibilityNodeInfo(first + g.count() - 1);
        assertNotNull(last);
        last.getBoundsInParent(b);
        assertTrue("the last cell inside the garden", b.bottom <= g.getHeight() + 1 && b.right <= g.getWidth() + 1);
        assertNull("a folded cell isn't there", p.createAccessibilityNodeInfo(first + g.count()));
        // ahead, and a part says it's a part
        for (int i = first; i < first + g.count(); i++) if (PathData.items.get(i).isPart()) {
            assertTrue(p.createAccessibilityNodeInfo(i).getContentDescription().toString().startsWith("part " + PathData.items.get(i).h));
            break;
        }
        assertTrue(p.createAccessibilityNodeInfo(first + g.count() - 1).getContentDescription().toString().endsWith(": ahead"));
        // focus rings it, click opens it
        assertTrue(p.performAction(first + 2, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertEquals(first + 2, g.focused);
        assertTrue(p.createAccessibilityNodeInfo(first + 2).isAccessibilityFocused());
        assertTrue(p.performAction(first + 2, AccessibilityNodeInfo.ACTION_CLICK, null));
        ShadowLooper.idleMainLooper();
        assertEquals("learn", m.screen);
        assertEquals(PathData.items.get(first + 2).h, m.learn.items.get(0));
        // opened whole: every cell reachable
        m.back();
        m.gardenOpen = true;
        m.home();
        g = HomeTest.first(m.root, GardenView.class);
        assertEquals(PathData.items.size(), g.getAccessibilityNodeProvider()
                .createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
    }
}
