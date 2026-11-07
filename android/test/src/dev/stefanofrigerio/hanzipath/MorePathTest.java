package dev.stefanofrigerio.hanzipath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

/**
 * The path's second thousand (HSK 4-6): the first 1251 items where they were, the new ones
 * after them, each with its strokes (the chunks past the old last one), and a learn card for
 * one far along (the 1500th: 犹), light and dark. Renders are more*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class MorePathTest {
    final HomeTest shots = new HomeTest();

    @Test
    public void path() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        assertTrue("about 2000 characters: " + PathData.items.size(), PathData.items.size() > 2200);
        // where the first path ended, and the first item after it
        assertEquals("一", PathData.items.get(0).h);
        assertEquals("镇", PathData.items.get(1251).h);
        int chars = 0;
        for (Item it : PathData.items) {
            if (!it.isPart()) chars++;
            assertNotNull(it.h + " has a meaning", it.en);
            assertFalse(it.h + ": the dictionary's housekeeping: " + it.en,
                    it.en.contains("Taiwan pr") || it.en.contains("classifier") || it.en.contains("variant of"));
        }
        assertTrue("characters: " + chars, chars > 1900);
        // a new item's strokes load from a chunk the first path didn't have
        Item last = PathData.items.get(PathData.items.size() - 1);
        assertNotNull(last.h + "'s strokes", PathData.glyph(m, last.h));
        card(m, "more");
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void pathNight() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        card(m, "moren");
    }

    void card(Main m, String prefix) throws Exception {
        Item it = PathData.items.get(1500);
        m.lookup(it.h);
        ShadowLooper.idleMainLooper();
        assertNotNull(it.h + "'s meaning on its card", WalkTest.find(m.root, it.en));
        shots.shot(m, prefix + "1-learn-" + it.at);
        assertTrue(m.back());
    }
}
