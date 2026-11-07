package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
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
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (gapDp > 0) l.setDividerDrawable(space(gapDp));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
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
        t.setPadding(Ink.dp(20), 0, Ink.dp(20), 0);
        style(t, kind);
        t.setOnClickListener(on);
        return t;
    }

    static void style(TextView t, int kind) {
        int fill = kind == SOLID ? Ink.ink : kind == HIGHLIGHT ? Ink.hl : Ink.paper;
        int stroke = kind == HIGHLIGHT ? Ink.hlInk : Ink.ink;
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ink.night ? 0x33ffffff : 0x22000000),
                shape(fill, stroke, 999, false), null));
        t.setTextColor(kind == SOLID ? Ink.paper : kind == HIGHLIGHT ? Ink.hlInk : Ink.ink);
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
        t.setBackground(shape(Ink.paper, Ink.ink, 999, false));
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
        v.setBackground(shape(Ink.paper, Ink.ink, 999, false));
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

    static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2);
        p.weight = w;
        return p;
    }
}
