package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How it's going, from the review log and the cards: reviews a day for the last thirty days,
 * the share answered right week by week, how far along the path is, the items missed most,
 * the pairs mixed up (taken for each other twice lately; a tap shows them side by side),
 * how long an answer takes this week against last, and how many sentences you've read.
 * Read only: nothing here changes them.
 */
class Stats {
    static final int DAYS = 30, WEEKS = 8, HARDEST = 10;
    final Main m;

    Stats(Main m) { this.m = m; }

    void show() {
        LinearLayout col = m.page("stats", this::show);
        col.addView(Cards.bar(m, "STATS", Ui.close(m, v -> m.home())));
        col.addView(Levels.entry(m));
        Progress p = m.prog;
        long now = System.currentTimeMillis();
        List<Progress.Logged> log = p.reviewsSince(weekStart(now, WEEKS - 1));
        long[] all = p.reviewTotals();

        // 1. reviews per day, the last thirty
        int[] days = perDay(log, now, DAYS);
        int total = 0;
        for (int n : days) total += n;
        LinearLayout ds = section("Reviews per day",
                total == 0 ? null : total + " in " + DAYS + " days · " + String.format(Locale.ROOT, "%.1f", total / (double) DAYS) + " a day");
        if (total == 0) {
            ds.addView(empty(all[0] == 0 ? "Nothing reviewed yet: each day's reviews land here as a bar."
                    : "No reviews in the last " + DAYS + " days."));
        } else {
            WeekView wv = new WeekView(m);
            wv.sparse = true;
            wv.today = DAYS - 1;
            wv.title = "Reviews per day, the last " + DAYS + " days:";
            String[] labels = new String[DAYS];
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.DATE, -(DAYS - 1));
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.ENGLISH);
            for (int i = 0; i < DAYS; i++, c.add(Calendar.DATE, 1))
                if (i % 7 == 0 && i < DAYS - 7) labels[i] = f.format(c.getTime()).toLowerCase(Locale.ROOT);
            labels[DAYS - 1] = "today";
            wv.set(days, labels);
            ds.addView(wv, new LinearLayout.LayoutParams(-1, -2));
        }
        col.addView(ds);

        // 2. retention, week by week (only the weeks with reviews)
        int[][] wk = weeks(log, now, WEEKS);
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < WEEKS; i++) if (wk[i][0] > 0) shown.add(i);
        LinearLayout rs = section("Answered right",
                all[0] == 0 ? null : pct(all[1], all[0]) + "% overall · " + all[0] + " answers");
        if (shown.isEmpty()) {
            rs.addView(empty(all[0] == 0 ? "No answers yet to count." : "No reviews in the last " + WEEKS + " weeks."));
        } else {
            rs.addView(note("Unsure or knew, as a share of each week's answers."));
            RetentionView rv = new RetentionView(m);
            double[] v = new double[shown.size()];
            String[] l = new String[shown.size()];
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.ENGLISH);
            for (int j = 0; j < v.length; j++) {
                int i = shown.get(j);
                v[j] = wk[i][1] / (double) wk[i][0];
                l[j] = f.format(new Date(weekStart(now, WEEKS - 1 - i))).toLowerCase(Locale.ROOT);
            }
            // the month only where it changes: 14 sep, 21, 28, 5 oct
            for (int j = v.length - 1; j > 0; j--)
                if (l[j].substring(l[j].indexOf(' ')).equals(l[j - 1].substring(l[j - 1].indexOf(' ')))) l[j] = l[j].substring(0, l[j].indexOf(' '));
            rv.set(v, l, shown.get(shown.size() - 1) == WEEKS - 1 ? v.length - 1 : -1);
            rs.addView(rv, new LinearLayout.LayoutParams(-1, -2));
        }
        col.addView(rs);

        // 3. the path: met, learning, known
        int known = 0, met = 0;
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet()) {
            if (PathData.get(e.getKey()) == null) continue;
            met++;
            if (p.state(e.getKey(), now, false) == GardenView.KNOWN) known++;
        }
        int size = PathData.items.size();
        LinearLayout ps = section("The path", met + " of " + size + " seen · " + pct(met, size) + "%");
        if (met == 0) {
            ps.addView(empty("Nothing seen yet: the first batch starts it."));
        } else {
            PathBar bar = new PathBar(m);
            bar.set(known, met - known, size);
            ps.addView(bar, new LinearLayout.LayoutParams(-1, Ink.dp(12)));
            Flow lg = new Flow(m, 12);   // as home's legend: wraps once the text is large
            lg.addView(Home.legend(m, Ink.known, Ink.known, known + " known"));
            lg.addView(Home.legend(m, Ink.young, Ink.young, (met - known) + " learning"));
            lg.addView(Home.legend(m, Ink.paper, Ink.rule, (size - met) + " ahead"));
            ps.addView(lg);
        }
        col.addView(ps);

        // 4. the hardest: most missed, then the least stable
        List<String> hard = hardest(p, HARDEST);
        LinearLayout hs = section("Hardest", hard.isEmpty() ? null : "most missed");
        if (hard.isEmpty()) {
            hs.addView(empty(met == 0 ? "Nothing to miss yet." : "Nothing missed yet."));
        } else {
            LinearLayout rows = Ui.column(m, 0);
            List<TextView> hans = new ArrayList<>();
            for (int i = 0; i < hard.size(); i++) rows.addView(hardRow(hard.get(i), i > 0, hans));
            // one column for them all: readings start in line under a word as under a character
            float wide = Ink.dp(44);
            for (TextView t : hans) wide = Math.max(wide, t.getPaint().measureText(t.getText().toString()) + Ink.dp(4));
            for (TextView t : hans) t.setMinWidth((int) Math.ceil(wide));
            hs.addView(rows);
        }
        col.addView(hs);

        // 4b. mixed up: pairs taken for each other twice or more lately (Progress.pairs)
        List<Progress.Pair> pairs = p.pairs(now);
        LinearLayout ms = section("Mixed up", pairs.isEmpty() ? null : "last " + Progress.CONFUSED / Progress.DAY + " days");
        if (pairs.isEmpty()) {
            ms.addView(empty("Nothing mixed up. Two characters taken for each other twice show here, side by side."));
        } else {
            LinearLayout rows = Ui.column(m, 0);
            for (int i = 0; i < pairs.size(); i++) rows.addView(pairRow(pairs.get(i), i > 0));
            ms.addView(rows);
        }
        col.addView(ms);

        // 5. time: the median answer, this week against last
        long thisWeek = weekStart(now, 0), lastWeek = weekStart(now, 1);
        List<Long> a = new ArrayList<>(), b = new ArrayList<>();
        for (Progress.Logged r : log) {
            if (r.ms <= 0) continue;
            if (r.at >= thisWeek) a.add(r.ms);
            else if (r.at >= lastWeek) b.add(r.ms);
        }
        long ma = median(a), mb = median(b);
        LinearLayout ts = section("Time to answer", ma > 0 && mb > 0 && ma != mb
                ? secs(Math.abs(ma - mb)) + (ma < mb ? " quicker" : " slower") : null);
        if (a.isEmpty() && b.isEmpty()) {
            ts.addView(empty("No answers timed in the last two weeks."));
        } else {
            LinearLayout ledger = Ui.column(m, 0);
            ledger.addView(Home.line(m, "This week", a.isEmpty() ? "none yet" : secs(ma) + " · " + a.size() + " answers", false));
            ledger.addView(Home.line(m, "Last week", b.isEmpty() ? "none" : secs(mb) + " · " + b.size() + " answers", true));
            ts.addView(ledger);
            ts.addView(note("The median: half the answers came quicker."));
        }
        col.addView(ts);

        // 6. reading: sentences read in Read rounds (not grades: counted apart)
        long[] read = p.readCounts(thisWeek);
        LinearLayout rd = section("Reading", null);
        if (read[0] == 0) {
            rd.addView(empty("No sentences read yet: a Read round from home counts them here."));
        } else {
            LinearLayout ledger = Ui.column(m, 0);
            ledger.addView(Home.line(m, "Sentences read", read[0] + " in all · " + read[1] + " this week", false));
            rd.addView(ledger);
        }
        col.addView(rd);
    }

    /** A section as home's are: a label, its figure on the right, then what it shows. */
    LinearLayout section(String title, String figure) {
        return Ui.section(m, title, figure == null ? null : Home.small(m, figure));
    }

    TextView empty(String s) { return Ui.note(m, s); }

    TextView note(String s) { return Ui.note(m, s); }

    /** A hard one: the character or word, its reading and meaning, how often it was missed;
     *  a tap opens its card. */
    View hardRow(String h, boolean rule, List<TextView> hans) {
        Item it = PathData.get(h);
        String[] w = it == null ? Words.get(h) : null;
        String py = it != null ? it.reading() : w[1], mean = it != null ? it.en : w[2];
        Progress.Card c = m.prog.cards.get(h);
        LinearLayout r = Ui.row(m, 14);
        r.setMinimumHeight(Ink.dp(60));
        r.setPadding(Ink.dp(4), Ink.dp(6), Ink.dp(4), Ink.dp(6));
        Ui.press(r, rule ? Home.topRule(Ink.grid) : null);
        // a word as large as it fits: two characters at a character's size, four a little smaller
        TextView han = Ui.text(m, h, w == null || Words.chars(h).length < 4 ? 28 : 24, Ink.han, Ink.ink);
        han.setGravity(Gravity.CENTER);
        han.setMinWidth(Ink.dp(44));
        han.setMaxLines(1);
        hans.add(han);
        r.addView(han);
        LinearLayout t = Ui.column(m, 2);
        if (!py.isEmpty()) t.addView(Ui.text(m, Ui.pinyin(py), 15, Ink.serifBold, Ink.ink));
        TextView en = Ui.text(m, mean, 14, Ink.sans, Ink.ink2);
        en.setMaxLines(1);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);
        r.addView(t, Ui.weight(1));
        String missed = "missed " + (c.lapses == 1 ? "once" : c.lapses == 2 ? "twice" : c.lapses + " times");
        r.addView(Home.stat(m, missed));
        r.setOnClickListener(v -> m.lookup(h));
        r.setContentDescription(h + " " + py + " " + mean + ", " + missed);
        return r;
    }

    /** A pair mixed up, laid out as a hard one: the two characters, their readings and
     *  meanings, how often; a tap opens them side by side (Apart). */
    View pairRow(Progress.Pair pr, boolean rule) {
        Item a = PathData.get(pr.a), b = PathData.get(pr.b);
        LinearLayout r = Ui.row(m, 14);
        r.setMinimumHeight(Ink.dp(60));
        r.setPadding(Ink.dp(4), Ink.dp(6), Ink.dp(4), Ink.dp(6));
        Ui.press(r, rule ? Home.topRule(Ink.grid) : null);
        TextView han = Ui.text(m, a.h + " " + b.h, 28, Ink.han, Ink.ink);
        han.setGravity(Gravity.CENTER);
        han.setMaxLines(1);
        r.addView(han);
        LinearLayout t = Ui.column(m, 2);
        android.text.SpannableStringBuilder py = new android.text.SpannableStringBuilder();
        if (!a.reading().isEmpty()) py.append(Ui.pinyin(a.reading()));
        if (!a.reading().isEmpty() && !b.reading().isEmpty()) py.append(" / ");
        if (!b.reading().isEmpty()) py.append(Ui.pinyin(b.reading()));
        if (py.length() > 0) t.addView(Ui.text(m, py, 15, Ink.serifBold, Ink.ink2));
        TextView en = Ui.text(m, Apart.meaning(a.h) + " / " + Apart.meaning(b.h), 14, Ink.sans, Ink.ink2);
        en.setMaxLines(1);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);
        r.addView(t, Ui.weight(1));
        String n = Apart.times(pr.n);
        r.addView(Home.stat(m, n));
        r.setOnClickListener(v -> m.openApart(pr));
        r.setContentDescription(a.h + " " + a.reading() + " and " + b.h + " " + b.reading() + ", mixed up " + n + ". Tap to see them side by side.");
        return r;
    }

    /** The home screen's way in: a row like Settings', under the garden. */
    static View entry(Main m) { return Ui.entry(m, "Stats", null, v -> m.openStats()); }

    // ---- the figures, from the log ----

    /** Midnight starting the day t falls in. */
    static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Monday's midnight, back weeks weeks from the week now falls in. */
    static long weekStart(long now, int back) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart(now));
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // Monday 0 … Sunday 6
        c.add(Calendar.DATE, -dow - 7 * back);
        return c.getTimeInMillis();
    }

    /** Grades given on each of the last n days, oldest first, today last. */
    static int[] perDay(List<Progress.Logged> log, long now, int n) {
        long[] starts = new long[n + 1];
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart(now));
        c.add(Calendar.DATE, -(n - 1));
        for (int i = 0; i <= n; i++, c.add(Calendar.DATE, 1)) starts[i] = c.getTimeInMillis();
        int[] out = new int[n];
        for (Progress.Logged r : log)
            for (int i = 0; i < n; i++) if (r.at >= starts[i] && r.at < starts[i + 1]) { out[i]++; break; }
        return out;
    }

    /** For each of the last n weeks (Monday to Sunday, this one last): {answers, right}. */
    static int[][] weeks(List<Progress.Logged> log, long now, int n) {
        int[][] out = new int[n][2];
        for (Progress.Logged r : log)
            for (int i = n - 1; i >= 0; i--)
                if (r.at >= weekStart(now, n - 1 - i)) {
                    out[i][0]++;
                    if (r.grade >= 2) out[i][1]++;
                    break;
                }
        return out;
    }

    /** The cards of the path missed most, characters and words alike, the least stable first
     *  among equals. */
    static List<String> hardest(Progress p, int n) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Progress.Card> e : p.cards.entrySet())
            if (e.getValue().lapses > 0 && Words.onPath(e.getKey())) out.add(e.getKey());
        Collections.sort(out, (a, b) -> {
            Progress.Card x = p.cards.get(a), y = p.cards.get(b);
            if (x.lapses != y.lapses) return Long.compare(y.lapses, x.lapses);
            if (x.s != y.s) return Double.compare(x.s, y.s);
            return Integer.compare(place(a), place(b));
        });
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** Where a card is on the path: a character's place, a word's after every character. */
    static int place(String h) {
        Item it = PathData.get(h);
        return it != null ? it.at : PathData.items.size() + Words.pos.get(h);
    }

    static long median(List<Long> ms) {
        if (ms.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(ms);
        Collections.sort(s);
        int k = s.size();
        return k % 2 == 1 ? s.get(k / 2) : (s.get(k / 2 - 1) + s.get(k / 2)) / 2;
    }

    static String secs(long ms) { return String.format(Locale.ROOT, "%.1f s", ms / 1000.0); }

    static long pct(long a, long b) { return b == 0 ? 0 : Math.round(100.0 * a / b); }

    /**
     * The weekly share answered right: a dot a week, joined by a hairline of the garden's blue,
     * the figure over each dot and the week's Monday under it. To scale against hairlines at
     * 50, 75 and 100% (from 0 when a week fell under half). This week's dot is the highlighter.
     */
    static class RetentionView extends View {
        double[] v = new double[0];
        String[] labels = new String[0];
        int current = -1;
        double lo = 0.5;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                num = new Paint(Paint.ANTI_ALIAS_FLAG), axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        RetentionView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTextSize(Ink.px(c, 12));
            axis.setTextSize(Ink.px(c, 10));
            axis.setTypeface(Ink.serif);
        }

        void set(double[] v, String[] labels, int current) {
            this.v = v;
            this.labels = labels;
            this.current = current;
            lo = 0.5;
            for (double x : v) if (x < 0.5) lo = 0;
            StringBuilder s = new StringBuilder("Answered right, by week:");
            for (int i = 0; i < v.length; i++) s.append(i == 0 ? " " : ", ").append(labels[i]).append(' ').append(Math.round(v[i] * 100)).append('%');
            setContentDescription(s);
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), Ink.dp(132));
        }

        @Override
        protected void onDraw(Canvas c) {
            float hair = Math.max(1, Ink.dp(1));
            float gutter = axis.measureText("100%") + Ink.dp(8);
            float w = getWidth(), top = Ink.dp(26), base = getHeight() - Ink.dp(26);
            Paint.FontMetrics af = axis.getFontMetrics(), nf = num.getFontMetrics();

            // the scale: hairlines with their figures, at the left
            double[] marks = lo == 0 ? new double[]{0, 0.5, 1} : new double[]{0.5, 0.75, 1};
            for (double k : marks) {
                float y = (float) (base - (base - top) * (k - lo) / (1 - lo));
                fill.setColor(Ink.grid);
                c.drawRect(gutter, y, w, y + hair, fill);
                axis.setColor(Ink.ink2);
                axis.setTextAlign(Paint.Align.LEFT);
                c.drawText(Math.round(k * 100) + "%", 0, y - (af.ascent + af.descent) / 2, axis);
            }
            if (v.length == 0) return;

            float slot = (w - gutter) / v.length;
            float[] xs = new float[v.length], ys = new float[v.length];
            path.reset();
            for (int i = 0; i < v.length; i++) {
                xs[i] = gutter + slot * i + slot / 2;
                ys[i] = (float) (base - (base - top) * (v[i] - lo) / (1 - lo));
                if (i == 0) path.moveTo(xs[i], ys[i]); else path.lineTo(xs[i], ys[i]);
            }
            line.setStrokeWidth(Ink.dp(1.5f));
            line.setColor(Ink.known);
            if (v.length > 1) c.drawPath(path, line);

            float r = Ink.dp(4.5f);
            for (int i = 0; i < v.length; i++) {
                boolean now = i == current;
                fill.setColor(now ? Ink.hl : Ink.known);
                c.drawCircle(xs[i], ys[i], r, fill);
                if (now) {
                    line.setStrokeWidth(hair);
                    line.setColor(Ink.hlInk);
                    c.drawCircle(xs[i], ys[i], r, line);
                }
                num.setTypeface(now ? Ink.serifBold : Ink.serif);
                num.setColor(Ink.ink);
                c.drawText(Math.round(v[i] * 100) + "%", xs[i], ys[i] - r - Ink.dp(5) - nf.descent, num);
                axis.setTextAlign(Paint.Align.CENTER);
                axis.setTypeface(now ? Ink.serifBold : Ink.serif);
                axis.setColor(now ? Ink.ink : Ink.ink2);
                c.drawText(labels[i], xs[i], base + Ink.dp(10) - af.ascent, axis);
                axis.setTypeface(Ink.serif);
            }
        }
    }

    /** How far along the path: known in blue, learning in pink, the rest paper, in a hairline. */
    static class PathBar extends View {
        int known, learning, size = 1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();
        final RectF r = new RectF();

        PathBar(Context c) { super(c); }

        void set(int known, int learning, int size) {
            this.known = known;
            this.learning = learning;
            this.size = Math.max(1, size);
            setContentDescription(known + " known, " + learning + " learning, of " + size);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), hair = Math.max(1, Ink.dp(1));
            r.set(hair / 2, hair / 2, w - hair / 2, h - hair / 2);
            clip.reset();
            clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ink.paper);
            c.drawRect(0, 0, w, h, p);
            float k = w * known / size, l = w * learning / size;
            if (known > 0) k = Math.max(k, hair * 2);
            if (learning > 0) l = Math.max(l, hair * 2);
            p.setColor(Ink.known);
            c.drawRect(0, 0, k, h, p);
            p.setColor(Ink.young);
            c.drawRect(k, 0, k + l, h, p);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(hair);
            p.setColor(Ink.rule);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }
}
