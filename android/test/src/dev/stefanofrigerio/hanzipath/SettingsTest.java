package dev.stefanofrigerio.hanzipath;

import android.view.View;
import android.view.ViewGroup;
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
 * Settings, grouped: its sections (SECTIONS) in that order, each a hairline and a label, every
 * row inside its own section and in its order, the explanations first under their labels,
 * the credits last in small text. Laid out without faults (AccessTest's check) and rendered,
 * scrolled, at normal and 1.5× text, light and dark: s*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class SettingsTest {
    static final String[] SECTIONS = {"Learning", "Voice", "Reminder", "Progress",
    };

    /** What each section holds, top to bottom: the start of a row's text. */
    static final String[][] ROWS = {
            {"New at a time", "New per day", "Reviews per round"},
            {"Say it aloud", "Voice speed", "No Chinese voice"},
            {"Reminder"},
            {"Kept in the app's database", "Copy progress", "Load from clipboard"},
    };

    /** The column a section label heads. */
    static LinearLayout section(Main m, String name) {
        TextView label = exact(m.root, name.toUpperCase());
        assertNotNull("no section " + name, label);
        assertEquals("a section starts with its label", 0, ((ViewGroup) label.getParent()).indexOfChild(label));
        return (LinearLayout) label.getParent();
    }

    static TextView exact(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            TextView t = exact(((ViewGroup) v).getChildAt(i), text);
            if (t != null) return t;
        }
        return null;
    }

    /** The section's child that holds a row. */
    static int indexIn(LinearLayout sec, View row) {
        View v = row;
        while (v.getParent() != sec) {
            assertTrue("not in its section: " + row, v.getParent() instanceof View);
            v = (View) v.getParent();
        }
        return sec.indexOfChild(v);
    }

    static int top(View v) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return at[1];
    }

    void check(String tag) throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        Speech.close();   // no Chinese voice: the line under the voice rows shows
        Speech.heard(m, android.speech.tts.TextToSpeech.SUCCESS, android.speech.tts.TextToSpeech.LANG_MISSING_DATA);
        m.openSettings();
        ShadowLooper.idleMainLooper();
        m.settings.remindNote("Notifications aren't allowed, so the reminder is off. Allow them for 汉字之路 in Android's settings, then pick a time again.");
        ShadowLooper.idleMainLooper();

        // the six sections, in order, down the page, each a hairline over its label
        LinearLayout page = (LinearLayout) section(m, SECTIONS[0]).getParent();
        int last = -1, lastY = -1;
        for (String s : SECTIONS) {
            LinearLayout sec = section(m, s);
            assertSame(s + " is on the page", page, sec.getParent());
            int at = page.indexOfChild(sec);
            assertTrue(s + " after the one before", at > last);
            assertTrue(s + " below the one before", top(sec) > lastY);
            assertNotNull(s + " has its hairline", sec.getBackground());
            last = at;
            lastY = top(sec);
        }

        // every row in its section, in its order; nothing of one section in another
        for (int i = 0; i < SECTIONS.length; i++) {
            LinearLayout sec = section(m, SECTIONS[i]);
            int prev = 1;   // under the label
            for (String r : ROWS[i]) {
                TextView t = WalkTest.find(sec, r);
                assertNotNull(r + " is under " + SECTIONS[i], t);
                int at = indexIn(sec, t);
                assertTrue(r + " in its order in " + SECTIONS[i], at >= prev);   // two pills can share a line
                prev = at;
                for (int j = 0; j < SECTIONS.length; j++)
                    if (j != i && !(r.equals("Reminder") && j == 2))
                        assertNull(r + " is not under " + SECTIONS[j], WalkTest.find(section(m, SECTIONS[j]), r));
            }
        }
        assertTrue(WalkTest.find(section(m, "Voice"), "No Chinese voice").isShown());
        assertNotNull("the reminder's line in Reminder", WalkTest.find(section(m, "Reminder"), "Notifications aren"));

        // explanations: first under their labels, in ink2
        for (String[] e : new String[][]{
                {"Progress", "Kept in the app's database"},
        }) {
            LinearLayout sec = section(m, e[0]);
            TextView t = WalkTest.find(sec, e[1]);
            assertEquals(e[1] + " right under " + e[0], 1, sec.indexOfChild(t));
            assertEquals(Ink.ink2, t.getCurrentTextColor());
        }

        // the credits: last on the page, smaller than the explanations, under a hairline
        TextView credits = WalkTest.find(m.root, "Characters, strokes and etymology");
        assertSame(page, credits.getParent());
        assertEquals(page.getChildCount() - 1, page.indexOfChild(credits));
        assertTrue(credits.getTextSize() < WalkTest.find(m.root, "Kept in the app's database").getTextSize());
        assertEquals(Ink.ink2, credits.getCurrentTextColor());
        assertNotNull(credits.getBackground());

        // the rows still step, as before
        TextView round = LevelsTest.setting(m, "Reviews per round");
        String was = round.getText().toString();
        round.performClick();
        assertNotEquals(was, round.getText().toString());
        round.performClick(); round.performClick(); round.performClick();
        assertEquals(was, round.getText().toString());

        // laid out without faults, and rendered down the scroll
        AccessTest a = new AccessTest();
        a.look(m, "s" + tag + "-settings");
        Speech.close();
    }

    static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            EditText t = findEdit(((ViewGroup) v).getChildAt(i));
            if (t != null) return t;
        }
        return null;
    }

    @Test
    public void grouped() throws Exception { check("1"); }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void groupedNight() throws Exception { check("1n"); }

    @Test @Config(fontScale = 1.5f)
    public void large15() throws Exception { check("15"); }

    @Test @Config(fontScale = 1.5f, qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void large15Night() throws Exception { check("15n"); }
}
