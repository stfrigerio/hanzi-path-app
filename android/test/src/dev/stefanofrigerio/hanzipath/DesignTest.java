package dev.stefanofrigerio.hanzipath;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The design rules on every main screen, light and dark: the bar's
 * label, every label the same (12 sp serif bold capitals, 0.14, ink2), every section on a page
 * with its hairline and its title (13 sp serif bold capitals, 0.16, ink), every "›" row alike, two pill sizes, figures in the serif, notes in one style,
 * the round's end with its seal, and no colour that isn't Ink's (none of the faint ones on
 * words). Renders d*.png (dn*.png at night).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class DesignTest {
    final List<String> seen = new ArrayList<>();
    final List<Integer> entryHeights = new ArrayList<>();

    @Test
    public void light() throws Exception { walk("d"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception { walk("dn"); }

    void walk(String p) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        check(m, "home, first run", true);
        WalkTest.tap(m, "Learn next 5");
        check(m, "learn", false);
        for (int i = 0; i < 4; i++) WalkTest.tap(m, "Next");
        WalkTest.tap(m, "Check yourself");
        check(m, "check", false);
        for (int i = 0; i < 5; i++) {
            String q = m.quiz.queue.get(0);
            WalkTest.options(m).stream().filter(t -> t.getText().toString().endsWith(PathData.get(q).en)).findFirst().get().performClick();
            ShadowLooper.idleMainLooper();
            if (i == 0) check(m, "an answer", false);
            WalkTest.tap(m, i < 4 ? "Next" : "Finish");
        }
        check(m, "the check's end", false);
        seal(m, "the check's end");
        new WalkTest().shot(m, p + "1-done");

        StatsTest.seed(m);
        m.home();
        check(m, "home", true);
        new WalkTest().shot(m, p + "2-home");
        m.openStats();
        check(m, "stats", false);
        new WalkTest().shot(m, p + "3-stats");
        m.openLevels();
        check(m, "levels", false);
        m.openSettings();
        check(m, "settings", false);
        new WalkTest().shot(m, p + "4-settings");
        m.openSearch();
        SearchTest.type(m, "shui");
        check(m, "search", false);
        SearchTest.type(m, "zzzz");
        check(m, "search, nothing", false);
        m.home();
        m.lookup("想");
        check(m, "a card", false);
        Tree.open(m, "想");
        check(m, "tree", false);
        new WalkTest().shot(m, p + "5-tree");
        // far along: words and sentences ready, the Read line in the Today card
        Sentences.load(m);
        WordsTest.know(m, 400);
        m.home();
        check(m, "home, far along", true);
        m.startRead();
        assertEquals("read", m.screen);
        check(m, "read", false);
        WalkTest.tap(m, "Show");
        check(m, "read, shown", false);
        while (m.screen.equals("read")) WalkTest.tap(m, WalkTest.find(m.root, "Next") != null ? "Next" : WalkTest.find(m.root, "Show") != null ? "Show" : "Finish");
        check(m, "the read round's end", false);
        seal(m, "the read round's end");

        // every way into a screen as tall as the others
        assertTrue("the sections were seen: " + seen, seen.size() >= 20);
        assertTrue("the › rows were seen", entryHeights.size() >= 3);
        for (int h : entryHeights) assertEquals("every › row the same height", (int) entryHeights.get(0), h);
    }

    // ---- the rules ----

    void check(Main m, String where, boolean home) {
        ShadowLooper.idleMainLooper();
        LinearLayout col = (LinearLayout) ((ScrollView) m.root.getChildAt(0)).getChildAt(0);
        if (!home && !where.contains("end")) bar(col, where);
        List<TextView> all = new ArrayList<>();
        texts(col, all);
        for (TextView t : all) {
            colour(t, where);
            if (label(t)) labelStyle(t, where);
            if ("›".equals(t.getText().toString())) entry(t, where);
            pill(t, where);
            note(t, where);
        }
        sections(col, where);
    }

    /** The bar: a label on the left (its words as given, shown in capitals), × at the right. */
    void bar(LinearLayout col, String where) {
        ViewGroup bar = (ViewGroup) col.getChildAt(0);
        TextView name = (TextView) bar.getChildAt(0);
        assertTrue(where + ": the bar's words in capitals", name.isAllCaps() || name.getText().toString().equals(name.getText().toString().toUpperCase()));
        labelStyle(name, where + ", the bar");
        TextView x = (TextView) bar.getChildAt(bar.getChildCount() - 1);
        assertEquals(where + ": × at the bar's end", "×", x.getText().toString());
    }

    /** A label: serif bold, letter-spaced (the masthead's HANZI PATH is the logo's, not one). */
    static boolean label(TextView t) {
        return t.getLetterSpacing() > 0.11f && !t.getText().toString().equals("HANZI PATH");
    }

    /** A section's title (Ui.title): 13 sp serif bold capitals, 0.16, in ink, only as the
     *  head of a section on a page (sections() checks that every head is one). */
    static boolean title(TextView t) {
        return Math.abs(t.getTextSize() - Ink.px(t.getContext(), 13)) < 0.5f && t.getLetterSpacing() > 0.15f;
    }

    void titleStyle(TextView t, String where) {
        String s = where + ": title '" + t.getText() + "'";
        assertEquals(s + " is 13 sp", Ink.px(t.getContext(), 13), t.getTextSize(), 0.5f);
        assertEquals(s + " is spaced 0.16", 0.16f, t.getLetterSpacing(), 0.001f);
        assertSame(s + " is the serif bold", Ink.serifBold, t.getTypeface());
        assertEquals(s + " is in ink", Ink.ink, t.getCurrentTextColor());
        String text = t.getText().toString();
        assertTrue(s + " is in capitals", text.equals(text.toUpperCase()));
        View sec = (View) t.getParent();
        if (sec instanceof Ui.Pair) sec = (View) sec.getParent();
        assertEquals(s + " heads a section (a hairline in Ink.rule above)", Ink.rule, rule(sec.getBackground()));
    }

    void labelStyle(TextView t, String where) {
        if (title(t)) { titleStyle(t, where); return; }
        String s = where + ": label '" + t.getText() + "'";
        assertEquals(s + " is 12 sp", Ink.px(t.getContext(), 12), t.getTextSize(), 0.5f);
        assertEquals(s + " is spaced 0.14", 0.14f, t.getLetterSpacing(), 0.001f);
        assertSame(s + " is the serif bold", Ink.serifBold, t.getTypeface());
        int c = t.getCurrentTextColor();
        assertTrue(s + " is muted (or Right / Not that one in their tone)", c == Ink.ink2 || c == Ink.tone[2] || c == Ink.tone[4]);
        String text = t.getText().toString();
        assertTrue(s + " is in capitals", t.isAllCaps() || text.equals(text.toUpperCase()));
    }

    /** Every section straight on a page: a hairline in Ink.rule above, 18 dp over its title. */
    void sections(LinearLayout col, String where) {
        for (int i = 0; i < col.getChildCount(); i++) {
            View v = col.getChildAt(i);
            if (!(v instanceof LinearLayout) || ((LinearLayout) v).getChildCount() == 0) continue;
            if (v.getBackground() instanceof GradientDrawable) continue;   // a card
            if (hasText(v, "›") || hasText(v, "×")) continue;               // the ways in, the bar
            View first = ((LinearLayout) v).getChildAt(0);
            TextView head = first instanceof TextView ? (TextView) first
                    : first instanceof Ui.Pair ? (TextView) ((Ui.Pair) first).getChildAt(0) : null;
            if (head == null || !label(head)) continue;
            String s = where + ": section " + head.getText();
            seen.add(s);
            assertEquals(s + " has 18 dp over its title", Ink.dp(18), v.getPaddingTop());
            titleStyle(head, where);
            assertEquals(s + " has a hairline in Ink.rule", Ink.rule, rule(v.getBackground()));
        }
    }

    /** A "›": the row of a way into a screen, 48 dp at least, the › at 20 sp in ink, a hairline
     *  above and the wash under the finger (a state list), a tap. Read's line in the Today
     *  card is a ledger line with a › (a faint rule): its › the same size. */
    void entry(TextView arrow, String where) {
        String s = where + ": a › row";
        assertEquals(s + ": the › at 20 sp", Ink.px(arrow.getContext(), 20), arrow.getTextSize(), 0.5f);
        assertEquals(s + ": the › in ink", Ink.ink, arrow.getCurrentTextColor());
        View row = (View) arrow.getParent();
        assertTrue(s + " is tapped", row.hasOnClickListeners());
        assertTrue(s + " takes the wash", row.getBackground() instanceof StateListDrawable);
        TextView first = (TextView) ((ViewGroup) row).getChildAt(0);
        if (!label(first)) return;   // the ledger's Read line
        assertTrue(s + " is 48 dp at least", row.getHeight() >= Ink.dp(48));
        assertEquals(s + " has 12 dp above", Ink.dp(12), row.getPaddingTop());
        assertEquals(s + " has 12 dp below", Ink.dp(12), row.getPaddingBottom());
        assertEquals(s + "'s hairline is Ink.rule", Ink.rule, rule(((StateListDrawable) row.getBackground()).getCurrent()));
        entryHeights.add(row.getHeight());
    }

    /** A pill (something tapped, round-ended, 30 to 50 dp): the full one (44 dp, 15 sp) or the
     *  small (36 dp, 14 sp). Not the round × (fixed 40 dp), the answers (52), the tones (72),
     *  the pick grid (96). */
    void pill(TextView t, String where) {
        int min = t.getMinHeight();
        if (!t.hasOnClickListeners() || min < Ink.dp(30) || min > Ink.dp(50)) return;
        String s = where + ": pill '" + t.getText() + "'";
        float sp = t.getTextSize() / Ink.px(t.getContext(), 1);
        boolean full = min == Ink.dp(44) && Math.abs(sp - 15) < 0.1, small = min == Ink.dp(36) && Math.abs(sp - 14) < 0.1;
        assertTrue(s + " is a full pill or a small one: " + min + " px min, " + sp + " sp", full || small);
        if (small) assertEquals(s + ": a small pill is outlined", Ink.ink, t.getCurrentTextColor());
    }

    /** A sentence saying there's nothing (yet): a note, 14 sp sans in ink2. Not a pill (Nothing due). */
    void note(TextView t, String where) {
        String x = t.getText().toString();
        if (!(x.startsWith("Nothing ") || x.startsWith("No ")) || t.getMinHeight() > 0 || label(t)) return;
        String s = where + ": '" + x + "'";
        assertEquals(s + " is 14 sp", Ink.px(t.getContext(), 14), t.getTextSize(), 0.5f);
        assertEquals(s + " is muted", Ink.ink2, t.getCurrentTextColor());
        assertSame(s + " is the sans", Ink.sans, t.getTypeface());
    }

    /** The round's end: its card opens on the seal, the brush in pink. */
    void seal(Main m, String where) {
        LinearLayout col = (LinearLayout) ((ScrollView) m.root.getChildAt(0)).getChildAt(0);
        ViewGroup card = (ViewGroup) col.getChildAt(0);
        assertTrue(where + ": a card", card.getBackground() instanceof GradientDrawable);
        TextView seal = (TextView) card.getChildAt(0);
        assertSame(where + ": the seal in the brush", Ink.brush, seal.getTypeface());
        assertEquals(where + ": the seal in pink", Ink.tone[4], seal.getCurrentTextColor());
        assertEquals(where + ": the seal 60 sp", Ink.px(m, 60), seal.getTextSize(), 0.5f);
    }

    /** Every text's colour, and every coloured span's, is one of Ink's; none of the faint ones
     *  (rule, grid) on words. */
    void colour(TextView t, String where) {
        Set<Integer> inks = inks();
        String s = where + ": '" + t.getText() + "'";
        int c = t.getCurrentTextColor();
        assertTrue(s + " has a colour of Ink's: " + Integer.toHexString(c), inks.contains(c));
        assertTrue(s + " is too faint to read", c != Ink.rule && c != Ink.grid);
        if (t.getText() instanceof Spanned) {
            Spanned sp = (Spanned) t.getText();
            for (ForegroundColorSpan f : sp.getSpans(0, sp.length(), ForegroundColorSpan.class))
                assertTrue(s + " has a span in a colour of Ink's: " + Integer.toHexString(f.getForegroundColor()), inks.contains(f.getForegroundColor()));
        }
    }

    static Set<Integer> inks() {
        Set<Integer> s = new HashSet<>();
        for (int c : new int[]{Ink.paper, Ink.paper2, Ink.ink, Ink.ink2, Ink.hl, Ink.hlInk, Ink.known, Ink.young, Ink.accent}) s.add(c);
        for (int i = 1; i <= 5; i++) s.add(Ink.tone[i]);
        return s;
    }

    // ---- helpers ----

    /** The colour of a hairline made by Ui.topRule, or 0. */
    static int rule(Drawable d) {
        if (!(d instanceof LayerDrawable)) return 0;
        Drawable line = ((LayerDrawable) d).getDrawable(((LayerDrawable) d).getNumberOfLayers() - 1);
        if (!(line instanceof GradientDrawable) || ((GradientDrawable) line).getColor() == null) return 0;
        return ((GradientDrawable) line).getColor().getDefaultColor();
    }

    static void texts(View v, List<TextView> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    static boolean hasText(View v, String s) {
        if (v instanceof TextView) return s.equals(((TextView) v).getText().toString());
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (hasText(((ViewGroup) v).getChildAt(i), s)) return true;
        return false;
    }
}
