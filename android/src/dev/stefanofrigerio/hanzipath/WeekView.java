package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The week ahead on the home screen: a bar for each of the next seven days, as tall as the
 * reviews that fall due that day, the count above it and the day below. Today's bar (what's
 * due now included) is the highlighter with a hairline of ink; the days after are the
 * garden's blue. A day with nothing due is a short hairline on the baseline, no figure.
 */
class WeekView extends View {
    int[] counts = new int[7];
    String[] days = new String[7];
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            num = new Paint(Paint.ANTI_ALIAS_FLAG), day = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeekView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, Ink.dp(1)));
        num.setTextAlign(Paint.Align.CENTER);
        num.setTextSize(Ink.px(c, 12));
        day.setTextAlign(Paint.Align.CENTER);
        day.setTextSize(Ink.px(c, 11));
        day.setLetterSpacing(0.08f);
    }

    void set(int[] counts, String[] days) {
        this.counts = counts;
        this.days = days;
        StringBuilder s = new StringBuilder("Reviews due this week:");
        for (int i = 0; i < counts.length; i++) s.append(i == 0 ? " " : ", ").append(days[i]).append(' ').append(counts[i]);
        setContentDescription(s);
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), Ink.dp(104));
    }

    @Override
    protected void onDraw(Canvas c) {
        int max = 1;
        for (int n : counts) max = Math.max(max, n);
        float w = getWidth(), slot = w / counts.length, bar = Math.min(slot * 0.5f, Ink.dp(26));
        float top = Ink.dp(20), base = getHeight() - Ink.dp(24), hair = line.getStrokeWidth();
        Paint.FontMetrics nf = num.getFontMetrics(), df = day.getFontMetrics();

        // the baseline, faint, under every day
        fill.setColor(Ink.grid);
        c.drawRect(0, base, w, base + hair, fill);

        for (int i = 0; i < counts.length; i++) {
            float cx = slot * i + slot / 2, l = cx - bar / 2, r = cx + bar / 2;
            boolean today = i == 0;
            int n = counts[i];
            float h = n == 0 ? 0 : Math.max(Ink.dp(3), (base - top) * n / max);
            if (n == 0) {
                fill.setColor(Ink.rule);
                c.drawRect(l, base - hair, r, base + hair, fill);
            } else {
                fill.setColor(today ? Ink.hl : Ink.known);
                c.drawRect(l, base - h, r, base, fill);
                if (today) {
                    line.setColor(Ink.hlInk);
                    c.drawRect(l + hair / 2, base - h + hair / 2, r - hair / 2, base - hair / 2, line);
                }
            }
            if (n > 0) {
                num.setTypeface(today ? Ink.monoMedium : Ink.mono);
                num.setColor(Ink.ink);
                c.drawText(String.valueOf(n), cx, base - h - Ink.dp(5) - nf.descent, num);
            }
            day.setTypeface(today ? Ink.monoMedium : Ink.mono);
            day.setColor(today ? Ink.ink : Ink.ink2);
            c.drawText(days[i] == null ? "" : days[i], cx, base + Ink.dp(8) - df.ascent, day);
        }
    }
}
