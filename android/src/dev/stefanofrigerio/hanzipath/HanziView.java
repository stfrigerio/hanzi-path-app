package dev.stefanofrigerio.hanzipath;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * A character on the practice square (米字格: the cross and the diagonals it's learnt on),
 * drawn from its strokes (Glyph). It can show the character, animate its stroke order
 * (each stroke filled along its median, as a brush would), and quiz: you trace it stroke by
 * stroke with a finger; a stroke near enough its median, in its direction, fills in; a
 * miss counts, and after HINT misses on one stroke it flashes where it goes.
 */
class HanziView extends View {
    interface Done { void complete(int mistakes); }

    Glyph glyph;
    Path[] outlines = new Path[0];
    PointF[][] medians = new PointF[0][];   // the glyph's own units (1024 box, y up)
    Path[] medianPaths = new Path[0];       // on the screen
    float[] lengths = new float[0], fill = new float[0];
    final Matrix toScreen = new Matrix(), toGlyph = new Matrix();
    float scale = 1, size;
    boolean outline;
    ValueAnimator anim, hintAnim;
    int hintStroke = -1;
    float hintAlpha;

    boolean quizzing;
    int next, mistakes, strokeMisses, hintAfter = 3;
    Done done;
    final Path trail = new Path();
    final List<float[]> touch = new ArrayList<>();

    final Paint inkP = new Paint(Paint.ANTI_ALIAS_FLAG), outlineP = new Paint(Paint.ANTI_ALIAS_FLAG),
            brushP = new Paint(Paint.ANTI_ALIAS_FLAG), gridP = new Paint(Paint.ANTI_ALIAS_FLAG),
            frameP = new Paint(Paint.ANTI_ALIAS_FLAG), trailP = new Paint(Paint.ANTI_ALIAS_FLAG),
            hintP = new Paint(Paint.ANTI_ALIAS_FLAG);

    HanziView(Context c) {
        super(c);
        inkP.setColor(Ink.ink);
        outlineP.setColor(Ink.grid);
        brushP.setColor(Ink.ink);
        brushP.setStyle(Paint.Style.STROKE);
        brushP.setStrokeCap(Paint.Cap.ROUND);
        brushP.setStrokeJoin(Paint.Join.ROUND);
        gridP.setColor(Ink.grid);
        gridP.setStrokeWidth(Math.max(1, Ink.dp(1)));
        frameP.setColor(Ink.rule);
        frameP.setStyle(Paint.Style.STROKE);
        frameP.setStrokeWidth(Math.max(1, Ink.dp(1)));
        trailP.setColor(Ink.ink2);
        trailP.setStyle(Paint.Style.STROKE);
        trailP.setStrokeWidth(Ink.dp(4));
        trailP.setStrokeCap(Paint.Cap.ROUND);
        trailP.setStrokeJoin(Paint.Join.ROUND);
        hintP.setColor(Ink.night ? Ink.hl : 0xffe6c800);
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
        float pad = size * 0.06f;
        scale = (size - 2 * pad) / 1024f;
        toScreen.reset();
        toScreen.setScale(1, -1);
        toScreen.postTranslate(0, 900);
        toScreen.postScale(scale, scale);
        toScreen.postTranslate(pad + (getWidth() - size) / 2f, pad + (getHeight() - size) / 2f);
        toScreen.invert(toGlyph);
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

    void hide() { stop(); java.util.Arrays.fill(fill, 0); invalidate(); }

    void outline(boolean on) { outline = on; invalidate(); }

    void stop() {
        if (anim != null) anim.cancel();
        if (hintAnim != null) hintAnim.cancel();
        anim = hintAnim = null;
        hintStroke = -1;
        quizzing = false;
        trail.reset();
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

    /** Trace it: done is told the mistakes once the last stroke is in. */
    void quiz(int hintAfter, Done done) {
        stop();
        java.util.Arrays.fill(fill, 0);
        this.hintAfter = hintAfter;
        this.done = done;
        next = mistakes = strokeMisses = 0;
        quizzing = glyph != null;
        invalidate();
    }

    // ── drawing ──
    @Override
    protected void onDraw(Canvas c) {
        float x0 = (getWidth() - size) / 2f, y0 = (getHeight() - size) / 2f, x1 = x0 + size, y1 = y0 + size;
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        c.drawLine(mx, y0, mx, y1, gridP);
        c.drawLine(x0, my, x1, my, gridP);
        c.drawLine(x0, y0, x1, y1, gridP);
        c.drawLine(x1, y0, x0, y1, gridP);
        float h = frameP.getStrokeWidth() / 2;
        c.drawRect(x0 + h, y0 + h, x1 - h, y1 - h, frameP);
        if (glyph == null) return;
        if (outline) for (Path p : outlines) c.drawPath(p, outlineP);
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
        if (hintStroke >= 0 && hintStroke < outlines.length) {
            hintP.setAlpha((int) (255 * hintAlpha));
            c.drawPath(outlines[hintStroke], hintP);
        }
        c.drawPath(trail, trailP);
    }

    // ── tracing ──
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!quizzing) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                trail.reset();
                touch.clear();
                trail.moveTo(e.getX(), e.getY());
                touch.add(new float[]{e.getX(), e.getY()});
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getHistorySize(); i++) add(e.getHistoricalX(i), e.getHistoricalY(i));
                add(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_UP:
                judge();
                trail.reset();
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                trail.reset();
                break;
        }
        invalidate();
        return true;
    }

    void add(float x, float y) {
        trail.lineTo(x, y);
        touch.add(new float[]{x, y});
    }

    /** Was that the next stroke? Compared in the glyph's own units, against its median. */
    void judge() {
        if (next >= fill.length || touch.size() < 2) return;
        float[][] u = new float[touch.size()][];
        for (int i = 0; i < u.length; i++) {
            u[i] = touch.get(i).clone();
            toGlyph.mapPoints(u[i]);
        }
        float drawn = 0;
        for (int i = 1; i < u.length; i++) drawn += dist(u[i - 1][0], u[i - 1][1], u[i][0], u[i][1]);
        if (drawn < 25) return;                     // a tap, not a stroke
        PointF[] m = medians[next];
        float len = 0;
        for (int i = 1; i < m.length; i++) len += dist(m[i - 1].x, m[i - 1].y, m[i].x, m[i].y);
        float avg = 0;
        for (float[] p : u) avg += toPolyline(p[0], p[1], m);
        avg /= u.length;
        PointF a = m[0], b = m[m.length - 1];
        float[] s = u[0], t = u[u.length - 1];
        boolean ok = avg < 150
                && dist(s[0], s[1], a.x, a.y) < 260 && dist(t[0], t[1], b.x, b.y) < 260
                && (len < 120 || cos(t[0] - s[0], t[1] - s[1], b.x - a.x, b.y - a.y) > 0.3f)
                && drawn > 0.3f * len && drawn < 3f * len + 150;
        if (ok) {
            final int i = next++;
            strokeMisses = 0;
            hintStroke = -1;
            ValueAnimator f = ValueAnimator.ofFloat(0, 1);
            f.setDuration(180);
            f.addUpdateListener(v -> { fill[i] = (float) v.getAnimatedValue(); invalidate(); });
            f.start();
            if (next >= fill.length) {
                quizzing = false;
                if (done != null) postDelayed(() -> done.complete(mistakes), 220);
            }
        } else {
            mistakes++;
            if (++strokeMisses >= hintAfter) flashHint(next);
        }
    }

    void flashHint(int i) {
        if (hintAnim != null) hintAnim.cancel();
        hintStroke = i;
        hintAnim = ValueAnimator.ofFloat(0, 1, 1, 0);
        hintAnim.setDuration(1100);
        hintAnim.addUpdateListener(v -> { hintAlpha = (float) v.getAnimatedValue(); invalidate(); });
        hintAnim.start();
    }

    static float dist(float x, float y, float a, float b) { return (float) Math.hypot(x - a, y - b); }

    static float cos(float x, float y, float a, float b) {
        double n = Math.hypot(x, y) * Math.hypot(a, b);
        return n == 0 ? 0 : (float) ((x * a + y * b) / n);
    }

    static float toPolyline(float x, float y, PointF[] m) {
        float best = Float.MAX_VALUE;
        if (m.length == 1) return dist(x, y, m[0].x, m[0].y);
        for (int i = 1; i < m.length; i++) {
            float ax = m[i - 1].x, ay = m[i - 1].y, bx = m[i].x, by = m[i].y;
            float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            float k = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2));
            best = Math.min(best, dist(x, y, ax + k * dx, ay + k * dy));
        }
        return best;
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
