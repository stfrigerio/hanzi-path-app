package dev.stefanofrigerio.hanzipath;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.SizeF;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home-screen widget: a card in the garden's look with a character from the path, large
 * in the serif CJK with its pinyin (the tones' inks) and meaning. The most overdue review
 * when something is due; otherwise the next to learn, on the highlighter as in the garden.
 * At 2×2 a line above says which and how many are due; from about 4×2 the right half has
 * the due count large and the next few to learn. A tap opens the app.
 *
 * Laid out in res/layout (widget_small, widget_wide: RemoteViews take only framework views)
 * and coloured from res/values(-night)/ink.xml, Ink's palette as resources, so the launcher
 * picks day or night itself. The database is only read (Progress.peek). Updated when the app
 * goes to the back or comes home from a round (refresh), after a reboot or an update, and
 * every 30 minutes (res/xml/widget.xml).
 */
public class Widget extends AppWidgetProvider {
    static final String[] PY = {"py0", "py1", "py2", "py3"};
    /** From this wide (dp) the widget takes the two-halves layout. */
    static final float WIDE = 250;

    /** What the widget shows, worked out from the database read-only. */
    static final class Show {
        int due;
        String h = "", py = "", en = "";
        boolean fresh;                               // the next to learn, not a review
        final List<String> then = new ArrayList<>(); // the next to learn after it
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, views(ctx, show(ctx, System.currentTimeMillis())));
    }

    @Override
    public void onReceive(Context ctx, Intent i) {
        super.onReceive(ctx, i);
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) refresh(ctx);
    }

    /** Every widget on the home screen, drawn again from the database (none placed: nothing). */
    static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, Widget.class));
            if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, views(ctx, show(ctx, System.currentTimeMillis())));
        } catch (RuntimeException e) { /* a widget not drawn is not worth the app */ }
    }

    static Show show(Context ctx, long now) {
        Show s = new Show();
        try {
            PathData.load(ctx);
        } catch (Exception e) {
            return s;
        }
        Progress.Peek p = Progress.peek(ctx, now);
        s.due = p.due.size();
        String pick = null;
        for (String h : p.due) if (PathData.get(h) != null) { pick = h; break; }   // a character before a word
        if (pick == null && !p.due.isEmpty()) pick = p.due.get(0);
        List<String> fresh = new ArrayList<>();
        for (Item it : PathData.items) {
            if (fresh.size() >= 6) break;
            if (!p.met.contains(it.h)) fresh.add(it.h);
        }
        if (pick == null && !fresh.isEmpty()) {
            pick = fresh.get(0);
            s.fresh = true;
        }
        if (pick != null) {
            s.h = pick;
            Item it = PathData.get(pick);
            String[] w = it == null ? Words.get(pick) : null;
            if (it != null) { s.py = it.reading(); s.en = it.en; }
            else if (w != null) { s.py = w[1]; s.en = w[2]; }
        }
        for (String h : fresh) if (!h.equals(s.h) && s.then.size() < 5) s.then.add(h);
        return s;
    }

    /** Both sizes, Android picking the one the widget's room fits (API 31's responsive layouts). */
    static RemoteViews views(Context ctx, Show s) {
        Map<SizeF, RemoteViews> m = new HashMap<>();
        m.put(new SizeF(110, 110), views(ctx, s, false));
        m.put(new SizeF(WIDE, 110), views(ctx, s, true));
        return new RemoteViews(m);
    }

    static int res(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    static int id(Context ctx, String name) { return res(ctx, name, "id"); }

    static RemoteViews views(Context ctx, Show s, boolean wide) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), res(ctx, wide ? "widget_wide" : "widget_small", "layout"));
        boolean walked = s.h.isEmpty();
        v.setTextViewText(id(ctx, "what"), walked ? "THE PATH IS WALKED" : s.fresh ? (wide ? "NEXT TO LEARN" : "NEXT") : "REVIEW");
        v.setTextViewText(id(ctx, "count"), String.valueOf(s.due));
        if (!wide) v.setTextViewText(id(ctx, "count_word"), " due");

        // the character: on the highlighter when it's the next to learn
        int han = id(ctx, "han");
        int n = s.h.codePointCount(0, s.h.length());
        v.setTextViewText(han, walked ? "好" : s.h);
        v.setTextViewTextSize(han, TypedValue.COMPLEX_UNIT_SP, n <= 1 ? 52 : n == 2 ? 40 : n == 3 ? 30 : 24);
        // one character: a square (the garden's), a word as wide as it is
        if (n <= 1) v.setViewLayoutWidth(han, wide ? 84 : 76, TypedValue.COMPLEX_UNIT_DIP);
        else v.setViewLayoutWidth(han, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, TypedValue.COMPLEX_UNIT_PX);
        v.setInt(han, "setBackgroundResource", s.fresh ? res(ctx, "widget_next", "drawable") : 0);
        v.setColorStateList(han, "setTextColor", res(ctx, s.fresh ? "hl_ink" : "ink", "color"));

        // the pinyin, a syllable a view in its tone's ink
        String[] syl = s.py.trim().isEmpty() ? new String[0] : s.py.trim().split("\\s+");
        for (int i = 0; i < PY.length; i++) {
            int py = id(ctx, PY[i]);
            if (i >= syl.length) { v.setViewVisibility(py, android.view.View.GONE); continue; }
            String t = syl[i];
            if (i == PY.length - 1) for (int j = i + 1; j < syl.length; j++) t += " " + syl[j];
            v.setTextViewText(py, t + (i < syl.length - 1 && i < PY.length - 1 ? " " : ""));
            v.setColorStateList(py, "setTextColor", res(ctx, "tone" + Ink.toneOf(syl[i]), "color"));
            v.setViewVisibility(py, android.view.View.VISIBLE);
        }
        v.setTextViewText(id(ctx, "meaning"), walked ? "every step met" : s.en);

        if (wide) {
            v.setTextViewText(id(ctx, "then_what"), s.fresh ? "THEN" : "NEXT TO LEARN");
            v.setTextViewText(id(ctx, "then"), s.then.isEmpty() ? "—" : android.text.TextUtils.join(" ", s.then));
        }

        Intent open = new Intent(ctx, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(android.R.id.background,
                PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}
