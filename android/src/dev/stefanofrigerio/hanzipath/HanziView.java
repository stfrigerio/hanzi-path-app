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
 * (each stroke filled along its median, as a brush would), or steps through it a stroke at a
 * time: the strokes so far in ink, the newest in the highlighter as it lands, its number by
 * its start, the rest a faint outline to come.
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
    int step = -1;          // stepping: how many strokes are shown (the newest is step - 1); -1 not stepping
    Runnable onStep;        // told when stepping starts, moves on or stops (the Step pill)
    int numFor = -1;        // the stroke whose number's place (numX, numY) is worked out
    float numX, numY;

    final Paint inkP = new Paint(Paint.ANTI_ALIAS_FLAG), brushP = new Paint(Paint.ANTI_ALIAS_FLAG),
            gridP = new Paint(Paint.ANTI_ALIAS_FLAG), frameP = new Paint(Paint.ANTI_ALIAS_FLAG),
            ghostP = new Paint(Paint.ANTI_ALIAS_FLAG), hlP = new Paint(Paint.ANTI_ALIAS_FLAG),
            edgeP = new Paint(Paint.ANTI_ALIAS_FLAG), numP = new Paint(Paint.ANTI_ALIAS_FLAG),
            haloP = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        ghostP.setColor(Ink.grid);
        hlP.setColor(Ink.hl);
        edgeP.setColor(Ink.hlInk);          // the highlighter's own ink edge: it reads on paper and on night
        edgeP.setStyle(Paint.Style.STROKE);
        edgeP.setStrokeJoin(Paint.Join.ROUND);
        edgeP.setStrokeWidth(Math.max(1, Ink.dp(1.5f)));
        numP.setColor(Ink.ink2);
        numP.setTypeface(Ink.mono);
        numP.setTextAlign(Paint.Align.CENTER);
        numP.setTextSize(Ink.px(c, 13));
        haloP.set(numP);
        haloP.setColor(Ink.paper);          // paper round the number, where it crosses a stroke
        haloP.setStyle(Paint.Style.STROKE);
        haloP.setStrokeJoin(Paint.Join.ROUND);
        haloP.setStrokeWidth(Ink.dp(4));
    }

    void setGlyph(Glyph g) {
        stop();
        step = -1;
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
        numFor = -1;
        invalidate();
    }

    // ── what it shows ──
    void show() { stop(); unstep(); java.util.Arrays.fill(fill, 1); invalidate(); }

    /** A stroke's time on the square, by its length. */
    long duration(int i) { return (long) (220 + (scale > 0 ? lengths[i] / scale : 400) * 0.75f); }

    /** The next stroke: the ones before it in ink, it landing in the highlighter, the rest to
     *  come. After the last, the first again. */
    void step() {
        final int n = fill.length;
        if (glyph == null || n == 0) return;
        stop();
        step = step < 0 || step >= n ? 1 : step + 1;
        final int k = step - 1;
        for (int i = 0; i < n; i++) fill[i] = i < k ? 1 : 0;
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(k < lengths.length ? duration(k) : 400);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            fill[k] = a.getAnimatedFraction();
            invalidate();
        });
        anim.start();
        invalidate();
        if (onStep != null) onStep.run();
    }

    /** "3 / 7" while stepping, "Again" on the last stroke, "Step" otherwise. */
    String stepLabel() {
        if (step < 0) return "Step";
        return step >= fill.length ? "Again" : step + " / " + fill.length;
    }

    void unstep() {
        if (step < 0) return;
        step = -1;
        if (onStep != null) onStep.run();
    }

    void stop() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    /** The stroke order: each stroke in turn, its time by its length. */
    void animateStrokes() {
        if (glyph == null) return;
        stop();
        unstep();
        final int n = fill.length;
        final long[] start = new long[n], dur = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            start[i] = t;
            dur[i] = duration(i);
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
        int newest = step - 1;
        for (int i = 0; i < outlines.length; i++) {
            if (i == newest) continue;
            if (step >= 0 && i > newest) c.drawPath(outlines[i], ghostP);
            else if (fill[i] >= 1) c.drawPath(outlines[i], inkP);
            else if (fill[i] > 0) {
                c.save();
                c.clipPath(outlines[i]);
                seg.reset();
                new PathMeasure(medianPaths[i], false).getSegment(0, lengths[i] * fill[i], seg, true);
                c.drawPath(seg, brushP);
                c.restore();
            }
        }
        if (newest >= 0 && newest < outlines.length) drawNewest(c, newest, seg);
    }

    /** The newest stroke as it lands: the highlighter with its ink edge, revealed along the
     *  median by the brush's reach; its number by where it starts. */
    void drawNewest(Canvas c, int i, Path seg) {
        if (fill[i] > 0) {
            c.save();
            if (fill[i] < 1) {
                seg.reset();
                new PathMeasure(medianPaths[i], false).getSegment(0, lengths[i] * fill[i], seg, true);
                Path reach = new Path();
                brushP.getFillPath(seg, reach);
                c.clipPath(reach);
            }
            c.drawPath(outlines[i], hlP);
            c.drawPath(outlines[i], edgeP);
            c.restore();
        }
        if (medians[i].length == 0) return;
        if (numFor != i) placeNumber(i);
        String s = String.valueOf(i + 1);
        float base = numY - (numP.ascent() + numP.descent()) / 2;
        c.drawText(s, numX, base, haloP);
        c.drawText(s, numX, base, numP);
    }

    /** Where stroke i's number sits: by its start, off every stroke if it can be. */
    void placeNumber(int i) {
        float[] p = {medians[i][0].x, medians[i][0].y, medians[i][0].x, medians[i][0].y};
        if (medians[i].length > 1) { p[2] = medians[i][1].x; p[3] = medians[i][1].y; }
        toScreen.mapPoints(p);
        float dx = p[0] - p[2], dy = p[1] - p[3], d = (float) Math.hypot(dx, dy);
        if (d < 1e-3f) { dx = -1; dy = -1; d = (float) Math.hypot(dx, dy); }
        // back from the start, off the stroke's head; turning round it until clear of every stroke
        float away = 90 * scale + numP.getTextSize() * 0.7f;
        float x0 = (getWidth() - size) / 2f, y0 = (getHeight() - size) / 2f, m = numP.getTextSize();
        float x = 0, y = 0;
        double[] turns = {0, -45, 45, -90, 90, -135, 135};
        for (int t = 0; t < turns.length * 2; t++) {
            double a = Math.toRadians(turns[t % turns.length]);
            float r = away * (t < turns.length ? 1 : 1.5f);
            float ux = (float) (dx / d * Math.cos(a) - dy / d * Math.sin(a)), uy = (float) (dx / d * Math.sin(a) + dy / d * Math.cos(a));
            float cx = Math.max(x0 + m, Math.min(x0 + size - m, p[0] + ux * r));
            float cy = Math.max(y0 + m, Math.min(y0 + size - m, p[1] + uy * r));
            if (t == 0) { x = cx; y = cy; }
            if (clear(cx, cy, m * 0.6f)) { x = cx; y = cy; break; }
        }
        numX = x;
        numY = y;
        numFor = i;
    }

    /** No stroke under a little square round (x, y): where the number can sit. */
    boolean clear(float x, float y, float r) {
        android.graphics.Region clip = new android.graphics.Region(0, 0, getWidth(), getHeight()), in = new android.graphics.Region();
        android.graphics.Rect box = new android.graphics.Rect((int) (x - r), (int) (y - r), (int) (x + r), (int) (y + r));
        for (Path o : outlines) {
            in.setPath(o, clip);
            if (in.quickReject(box) || !in.op(box, android.graphics.Region.Op.INTERSECT)) continue;
            return false;
        }
        return true;
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
