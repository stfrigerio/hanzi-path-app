package dev.stefanofrigerio.hanzipath;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A character on the practice square (米字格: the cross and the diagonals it's learnt on),
 * drawn from its strokes (Glyph). It shows the character, or animates its stroke order
 * (each stroke filled along its median, as a brush would).
 */
class HanziView extends View {
    Glyph glyph;
    Path[] outlines = new Path[0];
    PointF[][] medians = new PointF[0][];   // the glyph's own units (1024 box, y up)
    Path[] medianPaths = new Path[0];       // on the screen
    float[] lengths = new float[0], fill = new float[0];
    final Matrix toScreen = new Matrix();
    float scale = 1, size;
    boolean bare;           // just the character: no 米字格, no frame (an answer's cell)
    float pad = 0.06f;      // of the side, round the character
    ValueAnimator anim;

    final Paint inkP = new Paint(Paint.ANTI_ALIAS_FLAG), brushP = new Paint(Paint.ANTI_ALIAS_FLAG),
            gridP = new Paint(Paint.ANTI_ALIAS_FLAG), frameP = new Paint(Paint.ANTI_ALIAS_FLAG);

    HanziView(Context c) {
        super(c);
        inkP.setColor(Ink.ink);
        brushP.setColor(Ink.ink);
        brushP.setStyle(Paint.Style.STROKE);
        brushP.setStrokeCap(Paint.Cap.ROUND);
        brushP.setStrokeJoin(Paint.Join.ROUND);
        gridP.setColor(Ink.grid);
        gridP.setStrokeWidth(Math.max(1, Ink.dp(1)));
        frameP.setColor(Ink.rule);
        frameP.setStyle(Paint.Style.STROKE);
        frameP.setStrokeWidth(Math.max(1, Ink.dp(1)));
    }

    void setGlyph(Glyph g) {
        stop();
        glyph = g;
        int n = g == null ? 0 : g.count();
        fill = new float[n];
        medians = g == null ? new PointF[0][] : g.medians;
        layoutGlyph();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { layoutGlyph(); }

    void layoutGlyph() {
        size = Math.min(getWidth(), getHeight());
        if (glyph == null || size == 0) return;
        float pad = size * this.pad;
        scale = (size - 2 * pad) / 1024f;
        toScreen.reset();
        toScreen.setScale(1, -1);
        toScreen.postTranslate(0, 900);
        toScreen.postScale(scale, scale);
        toScreen.postTranslate(pad + (getWidth() - size) / 2f, pad + (getHeight() - size) / 2f);
        int n = glyph.count();
        outlines = new Path[n];
        medianPaths = new Path[n];
        lengths = new float[n];
        for (int i = 0; i < n; i++) {
            outlines[i] = new Path();
            glyph.outlines[i].transform(toScreen, outlines[i]);
            Path m = new Path();
            for (int j = 0; j < medians[i].length; j++) {
                float[] p = {medians[i][j].x, medians[i][j].y};
                toScreen.mapPoints(p);
                if (j == 0) m.moveTo(p[0], p[1]); else m.lineTo(p[0], p[1]);
            }
            medianPaths[i] = m;
            lengths[i] = new PathMeasure(m, false).getLength();
        }
        brushP.setStrokeWidth(260 * scale);
        invalidate();
    }

    // ── what it shows ──
    void show() { stop(); java.util.Arrays.fill(fill, 1); invalidate(); }

    void stop() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    /** The stroke order: each stroke in turn, its time by its length. */
    void animateStrokes() {
        if (glyph == null) return;
        stop();
        final int n = fill.length;
        final long[] start = new long[n], dur = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            start[i] = t;
            dur[i] = (long) (220 + (scale > 0 ? lengths[i] / scale : 400) * 0.75f);
            t += dur[i] + 140;
        }
        java.util.Arrays.fill(fill, 0);
        final long total = Math.max(1, t);
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(total);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            long now = (long) (a.getAnimatedFraction() * total);
            for (int i = 0; i < n; i++) fill[i] = Math.max(0, Math.min(1, (now - start[i]) / (float) dur[i]));
            invalidate();
        });
        anim.start();
    }

    // ── drawing ──
    @Override
    protected void onDraw(Canvas c) {
        float x0 = (getWidth() - size) / 2f, y0 = (getHeight() - size) / 2f, x1 = x0 + size, y1 = y0 + size;
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        if (!bare) drawGrid(c, x0, y0, x1, y1, mx, my);
        if (glyph == null) return;
        Path seg = new Path();
        for (int i = 0; i < outlines.length; i++) {
            if (fill[i] >= 1) c.drawPath(outlines[i], inkP);
            else if (fill[i] > 0) {
                c.save();
                c.clipPath(outlines[i]);
                seg.reset();
                new PathMeasure(medianPaths[i], false).getSegment(0, lengths[i] * fill[i], seg, true);
                c.drawPath(seg, brushP);
                c.restore();
            }
        }
    }

    void drawGrid(Canvas c, float x0, float y0, float x1, float y1, float mx, float my) {
        c.drawLine(mx, y0, mx, y1, gridP);
        c.drawLine(x0, my, x1, my, gridP);
        c.drawLine(x0, y0, x1, y1, gridP);
        c.drawLine(x1, y0, x0, y1, gridP);
        float h = frameP.getStrokeWidth() / 2;
        c.drawRect(x0 + h, y0 + h, x1 - h, y1 - h, frameP);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
