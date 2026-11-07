package dev.stefanofrigerio.hanzipath;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The home-screen widget: what it shows (next to learn, the most overdue, a word), the tap,
 *  the manifest, and its renders at 2×2 and 4×2, light and dark (w*.png). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class WidgetTest {
    static final String OUT = System.getProperty("shots");

    static int id(Context c, String name) { return c.getResources().getIdentifier(name, "id", c.getPackageName()); }

    static String text(View root, String name) {
        TextView t = root.findViewById(id(root.getContext(), name));
        assertNotNull("no view " + name, t);
        return t.getText().toString();
    }

    static boolean shown(View root, String name) { return root.findViewById(id(root.getContext(), name)).getVisibility() == View.VISIBLE; }

    /** A card met and graded, then due (or not) as asked: lateDays back from now, s days stable. */
    static void card(Main m, String h, boolean due, double lateDays) {
        m.prog.grade(h, 3, "test", 0);
        Progress.Card c = m.prog.cards.get(h);
        long now = System.currentTimeMillis();
        c.s = 1;
        c.last = now - (long) ((1 + lateDays) * Progress.DAY);
        c.due = due ? now - (long) (lateDays * Progress.DAY) - 60_000 : now + Progress.DAY;
        m.prog.putCard(h, c);
    }

    /** The widget as a launcher would hold it, wDp × hDp, on a plain wallpaper, to a PNG. */
    static View render(Context c, RemoteViews rv, int wDp, int hDp, String name) throws Exception {
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * d), h = Math.round(hDp * d), pad = Math.round(16 * d);
        FrameLayout host = new FrameLayout(c);
        View v = rv.apply(c, host);
        host.addView(v, new FrameLayout.LayoutParams(-1, -1));
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        k.drawColor(0xff7f8c87);   // a wallpaper: grey-green, neither paper
        k.translate(pad, pad);
        host.draw(k);
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        StringBuilder s = new StringBuilder();
        WalkTest.dump(host, "", s);
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".txt"))) { o.write(s.toString().getBytes("UTF-8")); }
        return v;
    }

    /** Both sizes: 2×2 and 4×2 as on a phone's home screen. */
    static void renders(Context c, String name) throws Exception {
        Widget.Show s = Widget.show(c, System.currentTimeMillis());
        View small = render(c, Widget.views(c, s, false), 176, 176, name + "-2x2");
        View wide = render(c, Widget.views(c, s, true), 368, 176, name + "-4x2");
        // nothing cut: the character and the meaning fit inside the card
        for (View v : new View[]{small, wide}) {
            TextView han = v.findViewById(id(c, "han"));
            assertTrue("the character has its room", han.getHeight() > 0 && han.getLayout().getEllipsisCount(0) == 0);
            assertTrue("inside the card", han.getBottom() <= ((View) han.getParent()).getHeight());
        }
    }

    @Test
    public void widget() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Application app = RuntimeEnvironment.getApplication();

        // in the manifest: a widget provider with its info, updated every 30 minutes, 2×2
        List<ResolveInfo> rs = app.getPackageManager().queryBroadcastReceivers(
                new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.getPackageName()), android.content.pm.PackageManager.GET_META_DATA);
        assertTrue("registered", rs.stream().anyMatch(r -> r.activityInfo.name.endsWith(".Widget")));
        ActivityInfo ai = app.getPackageManager().getReceiverInfo(new ComponentName(app, Widget.class), android.content.pm.PackageManager.GET_META_DATA);
        assertNotEquals(0, ai.metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER));

        ShadowAppWidgetManager wm = shadowOf(AppWidgetManager.getInstance(app));
        wm.setAlwaysRecreateViewsDuringUpdate(true);
        int small = app.getResources().getIdentifier("widget_small", "layout", app.getPackageName());
        int wid = wm.createWidget(Widget.class, small);

        // nothing met: nothing due, the first on the path, next to learn
        View v = wm.getViewFor(wid);
        assertEquals("NEXT", text(v, "what"));
        assertEquals("0", text(v, "count"));
        assertEquals("一", text(v, "han"));
        assertEquals("yī", text(v, "py0"));
        assertFalse(shown(v, "py1"));
        assertEquals("one; single; a", text(v, "meaning"));
        assertNotNull("on the highlighter", v.findViewById(id(app, "han")).getBackground());
        assertNotNull("the 2×2 layout when there's no size", v.findViewById(id(app, "count_word")));
        Widget.Show s = Widget.show(app, System.currentTimeMillis());
        assertTrue(s.fresh);
        assertEquals("the next five after it", java.util.Arrays.asList("二", "三", "十", "人", "口"), s.then);
        renders(app, "w1-next");

        // the tap opens the app
        v.performClick();
        ShadowLooper.idleMainLooper();
        Intent open = shadowOf(app).getNextStartedActivity();
        assertNotNull("a tap starts something", open);
        assertEquals(Main.class.getName(), open.getComponent().getClassName());

        // some met, two due: the most overdue first, a review, not on the highlighter
        for (String h : new String[]{"一", "二", "三", "十"}) card(m, h, false, 0);
        card(m, "人", true, 1);
        card(m, "口", true, 4);
        Widget.refresh(app);
        v = wm.getViewFor(wid);
        assertEquals("REVIEW", text(v, "what"));
        assertEquals("2", text(v, "count"));
        assertEquals("口", text(v, "han"));
        assertEquals("kǒu", text(v, "py0"));
        assertEquals("mouth", text(v, "meaning"));
        assertNull("no highlighter under a review", v.findViewById(id(app, "han")).getBackground());
        assertEquals("the same count as the reminder's", Progress.peek(app, System.currentTimeMillis()).due.size(), 2);
        s = Widget.show(app, System.currentTimeMillis());
        assertEquals(java.util.Arrays.asList("日", "月", "山", "水", "火"), s.then);
        renders(app, "w2-due");

        // only a word due: the word, its pinyin a syllable each in its tone's ink
        assertTrue(Words.is("人口"));
        card(m, "人", false, 0);
        card(m, "口", false, 0);
        card(m, "人口", true, 1);
        m.home();   // back on the path: the widget is drawn again
        v = wm.getViewFor(wid);
        assertEquals("1", text(v, "count"));
        assertEquals("人口", text(v, "han"));
        assertEquals("rén ", text(v, "py0"));
        assertEquals("kǒu", text(v, "py1"));
        assertFalse(shown(v, "py2"));
        TextView py0 = v.findViewById(id(app, "py0")), py1 = v.findViewById(id(app, "py1"));
        assertEquals("tone 2's ink", app.getColor(app.getResources().getIdentifier("tone2", "color", app.getPackageName())), py0.getCurrentTextColor());
        assertEquals("tone 3's ink", app.getColor(app.getResources().getIdentifier("tone3", "color", app.getPackageName())), py1.getCurrentTextColor());
        renders(app, "w3-word");
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Application app = RuntimeEnvironment.getApplication();
        renders(app, "wn1-next");
        for (String h : new String[]{"一", "二", "三", "十", "人"}) card(m, h, false, 0);
        card(m, "口", true, 2);
        card(m, "十", true, 1);
        renders(app, "wn2-due");
    }
}
