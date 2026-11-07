package dev.stefanofrigerio.hanzipath;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The path at a glance: today in a few lines (what's due, what's new left, days in a row,
 * what's known) with Review and Learn under them, the week's reviews as a bar strip, and the
 * garden, folded to the rows around where you are, the rest a tap away.
 */
final class Home {
    /** A folded garden longer than this many rows from the top starts at your last rows met
     *  instead, with a line counting what's above them. */
    static final int SHORT = 10;

    private Home() {}

    static void build(Main m, LinearLayout col) {
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        List<String> due = p.due(now);
        int known = 0, met = 0;
        for (Item it : PathData.items) {
            if (p.strength(it.h, now) >= Progress.KNOWN) known++;
            if (p.cards.containsKey(it.h)) met++;
        }
        int left = p.newLeftToday();
        int batch = Math.min(p.batch, left);
        List<String> next = Words.next(p, batch > 0 ? batch : p.batch, now);
        boolean words = !next.isEmpty() && Words.is(next.get(0));   // this batch is words (Words)
        int streak = p.streak();
        int reviewed = p.todayCount("reviews");

        // masthead
        LinearLayout name = Ui.row(m, 12);
        name.setGravity(Gravity.BOTTOM);
        name.addView(Ui.text(m, "汉字之路", 44, Ink.brush, Ink.ink));
        TextView en = Ui.text(m, "HANZI PATH", 13, Ink.monoMedium, Ink.ink2);
        en.setLetterSpacing(0.12f);
        en.setPadding(0, 0, 0, Ink.dp(10));
        name.addView(en);
        col.addView(name);

        // today: a short ledger, then what to do
        LinearLayout card = Ui.card(m);
        LinearLayout th = Ui.row(m, 12);
        th.addView(Ui.label(m, "Today"), Ui.weight(1));
        th.addView(Ui.label(m, new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date(now))));
        card.addView(th);
        if (!p.introDone && !p.cards.isEmpty()) { p.introDone = true; p.saveOpts(); }   // the first batch learnt: put away
        boolean intro = !p.introDone;
        if (intro) card.addView(intro(m));
        LinearLayout ledger = Ui.column(m, 0);
        ledger.addView(line(m, "Due now", due.size() + (reviewed > 0 ? " · " + reviewed + " done today" : ""), intro));
        ledger.addView(line(m, "New today", left + " of " + p.perDay + " left", true));
        ledger.addView(line(m, "Days in a row", streak > 0 ? String.valueOf(streak) : "none yet", true));
        ledger.addView(line(m, "Known", known + " of " + met + " met", true));
        int wordsMet = 0, wordsKnown = 0, wordsReady = Words.fresh(p, -1, now).size();
        for (String h : p.cards.keySet())
            if (Words.is(h)) { wordsMet++; if (p.strength(h, now) >= Progress.KNOWN) wordsKnown++; }
        if (wordsMet > 0 || wordsReady > 0) {
            ledger.addView(line(m, "Words known", (wordsMet > 0 ? wordsKnown + " of " + wordsMet + " met" : "")
                    + (wordsMet > 0 && wordsReady > 0 ? " · " : "") + (wordsReady > 0 ? wordsReady + " ready" : ""), true));
        }
        card.addView(ledger);
        LinearLayout acts = Ui.row(m, 10);
        TextView rv = Ui.pill(m, due.isEmpty() ? "Nothing due" : "Review " + due.size(), Ui.SOLID, v -> m.startReview());
        Ui.enable(rv, !due.isEmpty(), Ui.SOLID);
        acts.addView(rv, Ui.weight(1));
        int nb = Math.min(batch, next.size());
        String ln = next.isEmpty() ? "Path walked" : batch > 0 ? (words ? "Learn " + nb + (nb == 1 ? " word" : " words") : "Learn next " + nb) : "Enough new for today";
        TextView lb = Ui.pill(m, ln, Ui.OUTLINE, v -> m.startLearn());
        Ui.enable(lb, batch > 0 && !next.isEmpty(), Ui.OUTLINE);
        acts.addView(lb, Ui.weight(1));
        card.addView(acts);
        if (!next.isEmpty()) {
            SpannableStringBuilder b = new SpannableStringBuilder(words ? "Next, words   " : "Next on the path   ");
            int at = b.length();
            List<String> shown = new java.util.ArrayList<>();   // a word never broken across lines (U+2060 joins)
            for (String h : next) shown.add(android.text.TextUtils.join("\u2060", Words.chars(h)));
            b.append(android.text.TextUtils.join(words ? "   " : "  ", shown));
            b.setSpan(new TypefaceSpan(Ink.han), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Ink.ink), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new android.text.style.RelativeSizeSpan(1.3f), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView nt = Ui.text(m, b, 14, Ink.sans, Ink.ink2);
            nt.setGravity(Gravity.CENTER_HORIZONTAL);
            card.addView(nt);
        }
        col.addView(card);

        // the week: reviews falling due on each of the next seven days
        int[] week = week(p, now);
        int sum = 0;
        for (int n : week) sum += n;
        LinearLayout ws = Ui.column(m, 6);
        ws.setPadding(0, Ink.dp(8), 0, 0);
        LinearLayout wh = Ui.row(m, 12);
        wh.addView(Ui.label(m, "The week"), Ui.weight(1));
        wh.addView(small(m, sum + " due in 7 days"));
        ws.addView(wh);
        if (sum == 0) {
            ws.addView(Ui.text(m, p.cards.isEmpty() ? "Nothing to review yet: what you learn comes back here, day by day."
                    : "Nothing falls due this week.", 14, Ink.sans, Ink.ink2));
        } else {
            WeekView wv = new WeekView(m);
            String[] days = new String[7];
            Calendar d = Calendar.getInstance();
            for (int i = 0; i < 7; i++, d.add(Calendar.DATE, 1))
                days[i] = i == 0 ? "today" : d.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            wv.set(week, days);
            ws.addView(wv, new LinearLayout.LayoutParams(-1, -2));
        }
        col.addView(ws);

        // the garden: the rows around where you are, the rest folded
        GardenView g = new GardenView(m);
        int[] st = new int[PathData.items.size()];
        int firstNew = -1, lastMet = -1, wilting = 0;
        int k = batch > 0 ? batch : p.batch;
        for (int i = 0; i < st.length; i++) {
            Item it = PathData.items.get(i);
            Progress.Card c = p.cards.get(it.h);
            if (c != null) {
                lastMet = i;
                double s = p.strength(it.h, now);
                st[i] = s >= Progress.KNOWN ? GardenView.KNOWN : c.due <= now && c.reps > 1 ? GardenView.WILT : GardenView.YOUNG;
                if (st[i] == GardenView.WILT) wilting++;
            } else {
                if (firstNew < 0) firstNew = i;
                st[i] = i < firstNew + k ? GardenView.NEXT : i < firstNew + 40 ? GardenView.NEAR : GardenView.FAR;
            }
        }
        g.set(st);
        g.tap = i -> m.lookup(PathData.items.get(i).h);
        int cols = GardenView.columns(m.contentWidth());
        int focus = Math.max(lastMet, firstNew < 0 ? st.length - 1 : firstNew + k - 1);
        int fold = Math.min(st.length, (focus / cols + 3) * cols);   // your row and two ahead
        // hundreds met: from the last two rows of them, not from the top
        int from = 0;
        if (fold > SHORT * cols) from = Math.max(0, (firstNew < 0 ? focus : firstNew) / cols - 2) * cols;
        int aboveKnown = 0, aboveYoung = 0, aboveWilt = 0;
        for (int i = 0; i < from; i++) {
            if (st[i] == GardenView.KNOWN) aboveKnown++;
            else if (st[i] == GardenView.WILT) aboveWilt++;
            else if (st[i] == GardenView.YOUNG) aboveYoung++;
        }
        LinearLayout gs = Ui.column(m, 10);
        gs.setPadding(0, Ink.dp(8), 0, 0);
        LinearLayout gh = Ui.row(m, 12);
        gh.addView(Ui.label(m, "The path"), Ui.weight(1));
        gh.addView(small(m, met + " of " + st.length + " met"));
        gs.addView(gh);
        gs.addView(Search.entry(m));
        LinearLayout lg = Ui.row(m, 12);
        lg.addView(legend(m, Ink.hl, Ink.hl, "next"));
        lg.addView(legend(m, Ink.young, Ink.young, "learning"));
        if (wilting > 0) lg.addView(legend(m, Ink.paper2, Ink.young, "wilting"));
        lg.addView(legend(m, Ink.known, Ink.known, "known"));
        lg.addView(legend(m, 0, Ink.rule, "ahead"));
        gs.addView(lg);
        final int start = from;
        TextView above = null;
        if (from > 0) {
            above = small(m, aboveKnown + " known" + (aboveYoung > 0 ? ", " + aboveYoung + " learning" : "")
                    + (aboveWilt > 0 ? ", " + aboveWilt + " wilting" : "") + " before these rows");
            above.setVisibility(m.gardenOpen ? View.GONE : View.VISIBLE);
            gs.addView(above);
        }
        final TextView before = above;
        g.show(m.gardenOpen ? 0 : start, m.gardenOpen ? Integer.MAX_VALUE : fold);
        gs.addView(g, new LinearLayout.LayoutParams(-1, -2));
        if (from > 0 || fold < st.length) {
            int more = st.length - (fold - from);
            TextView t = Ui.pill(m, "", Ui.OUTLINE, null);
            Runnable label = () -> t.setText(m.gardenOpen ? "Fold the path" : "Show the whole path (" + more + " more)");
            label.run();
            t.setOnClickListener(v -> {
                m.gardenOpen = !m.gardenOpen;
                g.show(m.gardenOpen ? 0 : start, m.gardenOpen ? Integer.MAX_VALUE : fold);
                if (before != null) before.setVisibility(m.gardenOpen ? View.GONE : View.VISIBLE);
                label.run();
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.gravity = Gravity.CENTER_HORIZONTAL;
            tp.topMargin = Ink.dp(4);
            gs.addView(t, tp);
        }
        col.addView(gs);

        col.addView(Stats.entry(m));

        // settings
        LinearLayout s = Ui.row(m, 8);
        s.setPadding(0, Ink.dp(16), 0, Ink.dp(8));
        s.setBackground(topRule(Ink.rule));
        s.addView(Ui.label(m, "Settings and progress"), Ui.weight(1));
        s.addView(Ui.text(m, "›", 20, Ink.sans, Ink.ink));
        s.setMinimumHeight(Ink.dp(48));
        s.setOnClickListener(v -> m.openSettings());
        col.addView(s);
    }

    /** The first run's few lines, in the Today card: how the path goes, and a small Got it
     *  that puts them away for good (Progress.introDone; learning the first batch does too). */
    static View intro(Main m) {
        LinearLayout c = Ui.column(m, 6);
        String[] lines = {
                "Parts first: 木 and 目, then 相, which is made of them.",
                "Each character is said aloud, and comes back just before you'd forget it.",
                "Words follow once you know their characters.",
        };
        for (String s : lines) {
            SpannableStringBuilder b = new SpannableStringBuilder(s);
            for (int i = 0; i < s.length(); i++)
                if (Character.UnicodeScript.of(s.codePointAt(i)) == Character.UnicodeScript.HAN) {
                    b.setSpan(new TypefaceSpan(Ink.han), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    b.setSpan(new ForegroundColorSpan(Ink.ink), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            TextView t = Ui.text(m, b, 14, Ink.sans, Ink.ink2);
            t.setLineSpacing(0, 1.15f);
            c.addView(t);
        }
        TextView ok = Ui.pill(m, "Got it", Ui.OUTLINE, v -> {
            m.prog.introDone = true;
            m.prog.saveOpts();
            m.home();
        });
        ok.setMinHeight(Ink.dp(36));
        ok.setTextSize(14);
        ok.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
        op.gravity = Gravity.END;
        c.addView(ok, op);
        return c;
    }

    /** Reviews due on each of the next seven days; today's counts what's overdue too. */
    static int[] week(Progress p, long now) {
        long[] ends = new long[7];
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 7; i++) { c.add(Calendar.DATE, 1); ends[i] = c.getTimeInMillis(); }
        int[] out = new int[7];
        for (java.util.Map.Entry<String, Progress.Card> e : p.cards.entrySet()) {
            if (!Words.onPath(e.getKey())) continue;
            long due = e.getValue().due;
            for (int i = 0; i < 7; i++) if (due < ends[i]) { out[i]++; break; }
        }
        return out;
    }

    /** A ledger line: what on the left, the figure on the right, a faint rule above. */
    static View line(Main m, String what, String value, boolean rule) {
        LinearLayout r = Ui.row(m, 12);
        r.setMinimumHeight(Ink.dp(38));
        if (rule) r.setBackground(topRule(Ink.grid));
        r.addView(Ui.text(m, what, 15, Ink.sans, Ink.ink), Ui.weight(1));
        r.addView(stat(m, value));
        return r;
    }

    /** A section's figure beside its label: mono, small, muted. */
    static TextView small(Main m, String s) {
        TextView t = stat(m, s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        return t;
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

    static android.graphics.drawable.Drawable topRule() { return topRule(Ink.rule); }

    /** A hairline along a view's top. */
    static android.graphics.drawable.Drawable topRule(int color) {
        android.graphics.drawable.GradientDrawable line = Ui.shape(color, 0, 0, false);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{line});
        l.setLayerHeight(0, Math.max(1, Ink.dp(1)));
        l.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        return l;
    }
}
