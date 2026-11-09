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
            if (p.state(it.h, now, false) == GardenView.KNOWN) known++;
            if (p.cards.containsKey(it.h)) met++;
        }
        int left = p.newLeftToday();
        int batch = Math.min(p.batch, left);
        Learning learning = learning(p, now, false);
        List<String> next = learning.items;
        boolean words = learning.words;   // this batch is words (Words)
        int streak = p.streak();
        int reviewed = p.todayCount("reviews");

        // masthead
        TextView logo = Ui.text(m, "汉字之路", 44, Ink.brush, Ink.ink);
        TextView en = Ui.text(m, "HANZI PATH", 13, Ink.monoMedium, Ink.ink2);
        en.setLetterSpacing(0.12f);
        en.setPadding(0, 0, 0, Ink.dp(10));
        Ui.Pair name = new Ui.Pair(m, 12, false);   // large text: the English goes under the brush
        name.bottom = true;
        name.start = true;
        name.addView(logo);
        name.addView(en);
        name.setContentDescription("汉字之路, Hanzi Path");
        name.setScreenReaderFocusable(true);
        col.addView(name);

        // today: a short ledger, then what to do
        LinearLayout card = Ui.card(m);
        card.addView(Ui.pair(m, 12, Ui.label(m, "Today"), Ui.label(m, new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date(now)))));
        if (!p.introDone && !p.cards.isEmpty()) { p.introDone = true; p.saveOpts(); }   // the first batch learnt: put away
        boolean intro = !p.introDone;
        if (intro) card.addView(intro(m));
        LinearLayout ledger = Ui.column(m, 0);
        ledger.addView(line(m, "Reviews due", (due.isEmpty() ? "none" : String.valueOf(due.size())) + (reviewed > 0 ? " · " + reviewed + " done today" : ""), intro));
        boolean backlog = p.backlog(due.size());
        if (backlog) ledger.addView(back(m, due.size(), p.daysAway(), p.roundOf(due.size())));
        int learnt = p.newToday();   // past New per day on a good day: what was learnt, and the day's number
        ledger.addView(learnt > p.perDay ? line(m, "New today", learnt + " learnt, goal " + p.perDay, true)
                : line(m, "New left today", left + " of " + p.perDay, true));
        ledger.addView(line(m, "Days in a row", streak > 0 ? String.valueOf(streak) : "none yet", true));
        ledger.addView(line(m, "Characters known", known + " of " + met + " seen", true));
        int wordsMet = 0, wordsKnown = 0, wordsReady = Words.fresh(p, -1, now).size();
        for (String h : p.cards.keySet())
            if (Words.is(h)) { wordsMet++; if (p.strength(h, now) >= Progress.KNOWN) wordsKnown++; }
        if (wordsMet > 0 || wordsReady > 0) {
            ledger.addView(wordsMet == 0 ? line(m, "Words to learn", String.valueOf(wordsReady), true)
                    : line(m, "Words known", wordsKnown + " of " + wordsMet + " seen" + (wordsReady > 0 ? " · " + wordsReady + " to learn" : ""), true));
        }
        Cards.add(ledger, Read.line(m, now));
        Cards.add(ledger, Translate.line(m, now));
        card.addView(ledger);
        // nothing due: practise the weakest met instead (outlined: nothing asks for it)
        int practise = due.isEmpty() ? p.weakest(Progress.PRACTICE, now).size() : 0;
        TextView rv;
        if (practise > 0) {
            rv = Ui.pill(m, "Practice " + practise, Ui.OUTLINE, v -> m.startPractice());
        } else {
            rv = Ui.pill(m, due.isEmpty() ? "Nothing due" : backlog ? "Review " + p.roundOf(due.size()) + " of " + due.size()
                    : "Review " + due.size(), Ui.SOLID, v -> m.startReview());
            Ui.enable(rv, !due.isEmpty(), Ui.SOLID);
        }
        TextView lb = Ui.pill(m, learning.label, Ui.OUTLINE, v -> m.startLearn());
        Ui.enable(lb, learning.open, Ui.OUTLINE);
        card.addView(Ui.even(m, 10, rv, lb));
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
        LinearLayout ws = Ui.section(m, "The week", small(m, sum + " due in 7 days"));
        if (sum == 0) {
            ws.addView(Ui.note(m, p.cards.isEmpty() ? "Nothing to review yet: what you learn comes back here, day by day."
                    : "Nothing falls due this week."));
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
            if (p.cards.containsKey(it.h)) {
                lastMet = i;
                st[i] = p.state(it.h, now, false);
                if (st[i] == GardenView.WILT) wilting++;
            } else {
                if (firstNew < 0) firstNew = i;
                st[i] = p.state(it.h, now, i < firstNew + k);
                if (st[i] == GardenView.NEAR && i >= firstNew + 40) st[i] = GardenView.FAR;
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
        LinearLayout gs = Ui.section(m, "The path", small(m, met + " of " + st.length + " seen"));
        gs.addView(Search.entry(m));
        Flow lg = new Flow(m, 12);   // large text: the legend wraps onto a second line
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
            TextView t = Ui.small(m, "", null);
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

        // the ways into the other screens, one under the other as a list
        LinearLayout ways = Ui.column(m, 0);
        ways.addView(Parts.entry(m));
        ways.addView(Stats.entry(m));
        ways.addView(Ui.entry(m, "Settings and progress", null, v -> m.openSettings()));
        col.addView(ways);
    }

    /** What Learn would do now, decided in one place for home's button, Main.startLearn and a
     *  perfect round's "Learn next N" (Quiz.finish). */
    static final class Learning {
        List<String> items;   // the batch it starts (or would, once open); empty: the path is walked
        boolean words;        // the batch is words (Words)
        boolean open;         // it can be tapped
        String label;         // home's button
    }

    /** Learn's state: open while today's new are below New per day; past it, open again for
     *  a batch more on a good day (Progress.goodDay: ten answers today, nine in ten right) or
     *  straight after a round with no misses (perfect), and closed ("Enough new for today")
     *  otherwise. Each condition that holds learning back is a step here, checked before the
     *  ones that open it: a backlog of reviews (Progress.pauseNew) closes it first, perfect
     *  round or not: "Reviews first". */
    static Learning learning(Progress p, long now, boolean perfect) {
        Learning l = new Learning();
        int left = p.newLeftToday();
        l.items = Words.next(p, left > 0 ? Math.min(p.batch, left) : p.batch, now);
        l.words = !l.items.isEmpty() && Words.is(l.items.get(0));
        int n = l.items.size();
        String what = l.words ? n + (n == 1 ? " word" : " words") : "next " + n;
        if (l.items.isEmpty()) l.label = "Path walked";
        else if (p.pauseNew(p.due(now).size())) l.label = "Reviews first";   // a backlog: a round of reviews before anything new
        else if (left > 0) { l.open = true; l.label = "Learn " + what; }
        else if (perfect || p.goodDay()) { l.open = true; l.label = "Learn " + n + " more" + (l.words ? (n == 1 ? " word" : " words") : ""); }
        else l.label = "Enough new for today";
        return l;
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
            c.addView(Ui.note(m, b));
        }
        TextView ok = Ui.small(m, "Got it", v -> {
            m.prog.introDone = true;
            m.prog.saveOpts();
            m.home();
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
        op.gravity = Gravity.END;
        c.addView(ok, op);
        return c;
    }

    /** A backlog's one calm line, under Due now: how many, after how long away, and how they
     *  come back ("312 due after 6 days away. Rounds of 40 bring them back, what's slipping
     *  first."). The days only when it's been two or more. */
    static View back(Main m, int due, int away, int round) {
        String s = due + " due" + (away >= 2 ? " after " + away + " days away" : "")
                + ". Rounds of " + round + " bring them back, what's slipping first.";
        TextView t = Ui.note(m, s);
        t.setPadding(0, 0, 0, Ink.dp(8));
        return t;
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
        Ui.Pair r = Ui.pair(m, 12, Ui.text(m, what, 15, Ink.sans, Ink.ink), stat(m, value));
        r.setMinimumHeight(Ink.dp(38));
        if (rule) r.setBackground(topRule(Ink.grid));
        r.setContentDescription(what + ": " + value);   // one line for TalkBack, not two
        r.setScreenReaderFocusable(true);
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
    static android.graphics.drawable.Drawable topRule(int color) { return Ui.topRule(color); }
}
