package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The scrapbook look's pieces, made in code: pills (a hairline of ink, round ends), the
 * card (a hairline, 20 dp corners), labels in the mono face, chips, pinyin in the tones'
 * inks. Every screen is built from these.
 */
final class Ui {
    private Ui() {}

    static final ViewGroup.LayoutParams WRAP = new ViewGroup.LayoutParams(-2, -2);

    static LinearLayout column(Context c, int gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        if (gapDp > 0) l.setDividerDrawable(space(gapDp));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
    }

    static LinearLayout row(Context c, int gapDp) {
        LinearLayout l = new Row(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (gapDp > 0) l.setDividerDrawable(space(gapDp));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
    }

    /**
     * A row that never runs off its end. A LinearLayout with a weighted child (a spacer, a
     * label taking the rest) measures the others as if they had the whole line, so large text
     * pushes the last one out past the edge, or squeezes the weighted label till its words
     * break. When the children don't fit, or a label's word breaks, this measures again
     * with the weights off: each child in what the ones before it left, so a long one wraps
     * instead (a spacer stays at nothing, a weighted label at its own width).
     */
    static final class Row extends LinearLayout {
        Row(Context c) { super(c); }

        @Override
        protected void onMeasure(int ws, int hs) {
            super.onMeasure(ws, hs);
            if (getOrientation() != HORIZONTAL || MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) return;
            int used = getPaddingLeft() + getPaddingRight(), shown = 0;
            boolean weighted = false, squeezed = false;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                LayoutParams p = (LayoutParams) v.getLayoutParams();
                used += v.getMeasuredWidth() + p.leftMargin + p.rightMargin;
                shown++;
                weighted |= p.weight > 0;
                // a weighted label left so little that a word breaks ("REVIEWS PER D/AY")
                squeezed |= p.weight > 0 && v instanceof TextView && broken((TextView) v);
            }
            if ((getShowDividers() & SHOW_DIVIDER_MIDDLE) != 0 && shown > 1 && getDividerDrawable() != null)
                used += (shown - 1) * getDividerDrawable().getIntrinsicWidth();
            if (!weighted || used <= MeasureSpec.getSize(ws) && !squeezed) return;
            int n = getChildCount();
            float[] w = new float[n];
            int[] width = new int[n];
            for (int i = 0; i < n; i++) {
                LayoutParams p = (LayoutParams) getChildAt(i).getLayoutParams();
                w[i] = p.weight;
                width[i] = p.width;
                if (p.weight > 0) {
                    p.weight = 0;
                    if (p.width == 0 && (getChildAt(i) instanceof TextView || getChildAt(i) instanceof ViewGroup)) p.width = -2;
                }
            }
            super.onMeasure(ws, hs);
            for (int i = 0; i < n; i++) {
                LayoutParams p = (LayoutParams) getChildAt(i).getLayoutParams();
                p.weight = w[i];
                p.width = width[i];
            }
        }
    }

    /** A word of Latin letters or digits broken across two lines of t (a line ending inside it). */
    static boolean broken(TextView t) {
        android.text.Layout l = t.getLayout();
        CharSequence s = t.getText();
        if (l == null) return false;
        for (int i = 0; i < l.getLineCount() - 1; i++) {
            int e = l.getLineEnd(i);
            if (e > 0 && e < s.length() && latin(s.charAt(e - 1)) && latin(s.charAt(e))) return true;
        }
        return false;
    }

    static boolean latin(char c) {
        return Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN;
    }

    static GradientDrawable space(int dp) {
        GradientDrawable g = new GradientDrawable();
        g.setSize(Ink.dp(dp), Ink.dp(dp));
        g.setColor(0);
        return g;
    }

    static GradientDrawable shape(int fill, int stroke, float radiusDp, boolean dashed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(Ink.dp(radiusDp));
        if (stroke != 0) {
            if (dashed) g.setStroke(Math.max(1, Ink.dp(1)), stroke, Ink.dp(4), Ink.dp(3));
            else g.setStroke(Math.max(1, Ink.dp(1)), stroke);
        }
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        return t;
    }

    /** A small uppercase label, letter-spaced: section heads, counts. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, Ink.monoMedium, Ink.ink2);
        t.setLetterSpacing(0.12f);
        return t;
    }

    static final int OUTLINE = 0, SOLID = 1, HIGHLIGHT = 2;

    static TextView pill(Context c, String s, int kind, View.OnClickListener on) {
        TextView t = text(c, s, 15, Ink.sansMedium, kind == SOLID ? Ink.paper : kind == HIGHLIGHT ? Ink.hlInk : Ink.ink);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ink.dp(44));
        t.setPadding(Ink.dp(20), Ink.dp(6), Ink.dp(20), Ink.dp(6));   // room above and below once large text wraps it
        style(t, kind);
        t.setOnClickListener(on);
        return t;
    }

    static void style(TextView t, int kind) {
        int fill = kind == SOLID ? Ink.ink : kind == HIGHLIGHT ? Ink.hl : Ink.paper;
        int stroke = kind == HIGHLIGHT ? Ink.hlInk : Ink.ink;
        touch(t, fill, stroke, 999);
        t.setTextColor(kind == SOLID ? Ink.paper : kind == HIGHLIGHT ? Ink.hlInk : Ink.ink);
    }

    /** Something you can touch: a hairline shape whose fill turns under the finger (Ink.pressed:
     *  paper takes a wash of the highlighter, ink lifts), at once: no fade, which would hold the
     *  old fill over the new one while the finger is already down. */
    static void touch(View v, int fill, int stroke, float radiusDp) { touch(v, fill, stroke, radiusDp, false); }

    static void touch(View v, int fill, int stroke, float radiusDp, boolean dashed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(Ink.pressed(fill), stroke, radiusDp, dashed));
        s.addState(new int[0], shape(fill, stroke, radiusDp, dashed));
        v.setBackground(s);
    }

    /** A row you can touch over a background of its own (a hairline, or none): under the
     *  finger it takes the same wash of the highlighter as the pills (Ink.press), at once. */
    static void press(View v, android.graphics.drawable.Drawable under) {
        StateListDrawable s = new StateListDrawable();
        android.graphics.drawable.Drawable wash = new android.graphics.drawable.ColorDrawable(Ink.press);
        s.addState(new int[]{android.R.attr.state_pressed}, under == null ? wash
                : new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{wash, under}));
        s.addState(new int[0], under == null ? new android.graphics.drawable.ColorDrawable(0) : under);
        v.setBackground(s);
    }

    static void enable(TextView pill, boolean on, int kind) {
        pill.setEnabled(on);
        if (on) { style(pill, kind); return; }
        pill.setBackground(shape(Ink.paper, Ink.rule, 999, false));
        pill.setTextColor(Ink.ink2);
    }

    /** The round × in a screen's top corner. */
    static TextView close(Context c, View.OnClickListener on) {
        TextView t = text(c, "×", 24, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER);
        touch(t, Ink.paper, Ink.ink, 999);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ink.dp(40), Ink.dp(40)));
        t.setContentDescription("Back to the path");
        t.setOnClickListener(on);
        return t;
    }

    /** The speaker: says the hanzi aloud. A cone and two arcs, drawn in ink (no emoji). */
    static View speaker(Context c, View.OnClickListener on) {
        View v = new View(c) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Path cone = new android.graphics.Path();

            @Override
            protected void onDraw(android.graphics.Canvas k) {
                float u = getWidth() / 24f;                    // drawn on a 24-unit grid
                k.save();
                k.translate(5 * u, 5 * u);
                k.scale(u * 14 / 24f, u * 14 / 24f);
                cone.reset();
                cone.moveTo(3, 9); cone.lineTo(7, 9); cone.lineTo(12, 4.5f); cone.lineTo(12, 19.5f);
                cone.lineTo(7, 15); cone.lineTo(3, 15); cone.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(Ink.ink);
                k.drawPath(cone, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.8f);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                k.drawArc(11, 8, 17, 16, -55, 110, false, p);
                k.drawArc(11, 4.5f, 21.5f, 19.5f, -60, 120, false, p);
                k.restore();
            }
        };
        touch(v, Ink.paper, Ink.ink, 999);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ink.dp(34), Ink.dp(34)));
        v.setContentDescription("Say it");
        v.setOnClickListener(on);
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c, 16);
        l.setBackground(shape(Ink.paper, Ink.ink, 20, false));
        l.setPadding(Ink.dp(18), Ink.dp(20), Ink.dp(18), Ink.dp(20));
        return l;
    }

    /** Pinyin, each syllable in its tone's ink. */
    static SpannableStringBuilder pinyin(String py) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] syl = py.trim().split("\\s+");
        for (int i = 0; i < syl.length; i++) {
            if (i > 0) b.append(' ');
            int at = b.length();
            b.append(syl[i]);
            b.setSpan(new ForegroundColorSpan(Ink.tone[Ink.toneOf(syl[i])]), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    static void hanSpan(SpannableStringBuilder b, int from) {
        b.setSpan(new TypefaceSpan(Ink.han), from, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    /** Space that takes the rest of a row. Zero high: a bare View asked to wrap takes all it's offered. */
    static View spacer(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 0);
        p.weight = 1;
        v.setLayoutParams(p);
        return v;
    }

    /** A ledger or setting row: what on the left (taking the rest of the line), the figure or
     *  pill on the right; stacked, the figure under it at the end, when large text won't fit
     *  both on one line. */
    static Pair pair(Context c, int gapDp, View left, View right) {
        Pair p = new Pair(c, gapDp, false);
        p.addView(left);
        p.addView(right);
        return p;
    }

    /** Two pills side by side, half the line each; stacked, full width, when either's
     *  words won't fit in half. */
    static Pair even(Context c, int gapDp, View a, View b) {
        Pair p = new Pair(c, gapDp, true);
        p.addView(a);
        p.addView(b);
        return p;
    }

    /**
     * Two views on one line if they fit there, else one under the other: what keeps a label
     * and its value from cutting each other off at Android's larger font sizes. Side by side
     * the first takes the rest of the line (even: each half), centred on the line's height
     * (bottom: on its foot, as the masthead's name); stacked the second goes under the first,
     * at the end (start: at the start; even: both full width).
     */
    static final class Pair extends ViewGroup {
        final int gap;
        final boolean even;
        boolean bottom, start, stacked;

        Pair(Context c, int gapDp, boolean even) {
            super(c);
            this.gap = Ink.dp(gapDp);
            this.even = even;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            if (getChildCount() != 2) throw new IllegalStateException("a Pair holds two views");
            View a = getChildAt(0), b = getChildAt(1);
            int w = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            a.measure(free, free);
            b.measure(free, free);
            int aw = a.getMeasuredWidth(), bw = b.getMeasuredWidth(), half = (w - gap) / 2;
            stacked = even ? Math.max(aw, bw) > half : aw + gap + bw > w;
            int h;
            if (!stacked) {
                int wa = even ? half : w - gap - bw, wb = even ? half : bw;
                a.measure(MeasureSpec.makeMeasureSpec(wa, MeasureSpec.EXACTLY), free);
                b.measure(MeasureSpec.makeMeasureSpec(wb, MeasureSpec.EXACTLY), free);
                h = Math.max(a.getMeasuredHeight(), b.getMeasuredHeight());
                if (even) {   // the two pills as tall as each other
                    a.measure(MeasureSpec.makeMeasureSpec(wa, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                    b.measure(MeasureSpec.makeMeasureSpec(wb, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                }
            } else {
                a.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), free);
                b.measure(MeasureSpec.makeMeasureSpec(w, even ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST), free);
                h = a.getMeasuredHeight() + (even ? gap : gap / 2) + b.getMeasuredHeight();
            }
            h += getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(MeasureSpec.getSize(ws), Math.max(h, getSuggestedMinimumHeight()));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int bt) {
            View a = getChildAt(0), b = getChildAt(1);
            int x = getPaddingLeft(), y = getPaddingTop(), w = r - l - getPaddingLeft() - getPaddingRight();
            int h = bt - t - getPaddingTop() - getPaddingBottom();
            if (!stacked) {
                place(a, x, y, h);
                place(b, x + w - b.getMeasuredWidth(), y, h);
            } else {
                int used = a.getMeasuredHeight() + (even ? gap : gap / 2) + b.getMeasuredHeight();
                int top = y + Math.max(0, (h - used) / 2);
                a.layout(x, top, x + a.getMeasuredWidth(), top + a.getMeasuredHeight());
                int bx = start || even ? x : x + w - b.getMeasuredWidth(), by = top + used - b.getMeasuredHeight();
                b.layout(bx, by, bx + b.getMeasuredWidth(), by + b.getMeasuredHeight());
            }
        }

        void place(View v, int x, int y, int h) {
            int top = bottom ? y + h - v.getMeasuredHeight() : y + (h - v.getMeasuredHeight()) / 2;
            v.layout(x, top, x + v.getMeasuredWidth(), top + v.getMeasuredHeight());
        }
    }

    static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2);
        p.weight = w;
        return p;
    }
}
