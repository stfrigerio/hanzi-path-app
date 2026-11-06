package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/** The path: what's known, what's due, what's next, and every item as a garden. */
final class Home {
    private Home() {}

    static void build(Main m, LinearLayout col) {
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        List<String> due = p.due(now);
        int known = 0;
        for (Item it : PathData.items) if (p.strength(it.h, now) >= Progress.KNOWN) known++;
        int left = p.newLeftToday();
        int batch = Math.min(p.batch, left);
        List<String> next = p.nextNew(batch > 0 ? batch : p.batch);
        Calendar end = Calendar.getInstance();
        end.add(Calendar.DATE, 2);
        end.set(Calendar.HOUR_OF_DAY, 0); end.set(Calendar.MINUTE, 0); end.set(Calendar.SECOND, 0);
        int tomorrow = 0;
        for (Progress.Card c : p.cards.values()) if (c.due > now && c.due <= end.getTimeInMillis()) tomorrow++;
        int streak = p.streak();

        // masthead
        LinearLayout head = Ui.column(m, 4);
        LinearLayout name = Ui.row(m, 12);
        name.setGravity(Gravity.BOTTOM);
        name.addView(Ui.text(m, "汉字之路", 44, Ink.brush, Ink.ink));
        TextView en = Ui.text(m, "HANZI PATH", 13, Ink.monoMedium, Ink.ink2);
        en.setLetterSpacing(0.12f);
        en.setPadding(0, 0, 0, Ink.dp(10));
        name.addView(en);
        head.addView(name);
        head.addView(stat(m, known + " known · " + p.cards.size() + " of " + PathData.items.size() + " met"));
        head.addView(stat(m, (streak > 0 ? streak + (streak == 1 ? " day" : " days") + " in a row" : "no streak yet")
                + " · " + p.todayCount("reviews") + " reviews today · " + tomorrow + " due by tomorrow"));
        col.addView(head);

        // what to do
        LinearLayout acts = Ui.row(m, 10);
        TextView rv = Ui.pill(m, due.isEmpty() ? "Nothing due" : "Review " + due.size(), Ui.SOLID, v -> m.startReview());
        Ui.enable(rv, !due.isEmpty(), Ui.SOLID);
        acts.addView(rv);
        String ln = next.isEmpty() ? "Path walked" : batch > 0 ? "Learn next " + Math.min(batch, next.size()) : "Enough new for today";
        TextView lb = Ui.pill(m, ln, Ui.OUTLINE, v -> m.startLearn());
        Ui.enable(lb, batch > 0 && !next.isEmpty(), Ui.OUTLINE);
        acts.addView(lb);
        col.addView(acts);
        if (!next.isEmpty()) {
            SpannableStringBuilder b = new SpannableStringBuilder("Next on the path:  ");
            int at = b.length();
            b.append(android.text.TextUtils.join("  ", next));
            b.setSpan(new TypefaceSpan(Ink.han), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new android.text.style.RelativeSizeSpan(1.25f), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            col.addView(Ui.text(m, b, 14, Ink.sans, Ink.ink2));
        }

        // the garden
        LinearLayout gh = Ui.row(m, 12);
        gh.setPadding(0, Ink.dp(12), 0, 0);
        gh.addView(Ui.label(m, "The path"), Ui.weight(1));
        gh.addView(legend(m, 0, Ink.rule, "ahead"));
        gh.addView(legend(m, Ink.young, Ink.young, "learning"));
        gh.addView(legend(m, Ink.known, Ink.known, "known"));
        col.addView(gh);
        GardenView g = new GardenView(m);
        int[] st = new int[PathData.items.size()];
        int firstNew = -1;
        for (int i = 0; i < st.length; i++) {
            Item it = PathData.items.get(i);
            Progress.Card c = p.cards.get(it.h);
            if (c != null) {
                double s = p.strength(it.h, now);
                st[i] = s >= Progress.KNOWN ? GardenView.KNOWN : c.due <= now && c.reps > 1 ? GardenView.WILT : GardenView.YOUNG;
            } else {
                if (firstNew < 0) firstNew = i;
                int k = batch > 0 ? batch : p.batch;
                st[i] = i < firstNew + k ? GardenView.NEXT : i < firstNew + 40 ? GardenView.NEAR : GardenView.FAR;
            }
        }
        g.set(st);
        g.tap = i -> m.lookup(PathData.items.get(i).h);
        col.addView(g, new LinearLayout.LayoutParams(-1, -2));

        // settings
        LinearLayout s = Ui.row(m, 8);
        s.setPadding(0, Ink.dp(16), 0, Ink.dp(8));
        s.setBackground(topRule());
        s.addView(Ui.label(m, "Settings and progress"), Ui.weight(1));
        s.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));
        s.setMinimumHeight(Ink.dp(48));
        s.setOnClickListener(v -> m.openSettings());
        col.addView(s);
    }

    /** "12 known · 30 of 700 met": the numbers in ink, the words muted. */
    static TextView stat(Main m, String s) {
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("\\d+").matcher(s);
        while (x.find()) {
            b.setSpan(new ForegroundColorSpan(Ink.ink), x.start(), x.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new TypefaceSpan(Ink.monoMedium), x.start(), x.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return Ui.text(m, b, 14, Ink.mono, Ink.ink2);
    }

    static View legend(Main m, int fill, int line, String s) {
        LinearLayout r = Ui.row(m, 5);
        View sw = new View(m);
        sw.setBackground(Ui.shape(fill, line, 0, false));
        r.addView(sw, new LinearLayout.LayoutParams(Ink.dp(10), Ink.dp(10)));
        r.addView(Ui.text(m, s, 12, Ink.mono, Ink.ink2));
        return r;
    }

    static android.graphics.drawable.Drawable topRule() {
        android.graphics.drawable.GradientDrawable line = Ui.shape(Ink.rule, 0, 0, false);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{line});
        l.setLayerHeight(0, Math.max(1, Ink.dp(1)));
        l.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        return l;
    }
}
