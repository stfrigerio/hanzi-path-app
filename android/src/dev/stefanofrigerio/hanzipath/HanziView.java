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
 * drawn from its strokes (Glyph). It shows the character, or animates its stroke order
 * (each stroke filled along its median, as a brush would), or steps through it a stroke at a
 * time: the strokes so far in ink, the newest in the highlighter as it lands, its number by
 * its start, the rest a faint outline to come. Or it's written: traced stroke by stroke with
 * a finger (quiz); a stroke near enough its median, in its direction, fills in; a miss counts,
 * and after hintAfter misses on one stroke it flashes where it goes, in the highlighter; two
 * more and the stroke fills itself (a miss each), so it never gets stuck. It learns the learner's hand
 * as the learner goes (fromHand): a character written small or off centre is still read.
 * Writing and stepping don't mix: writing leaves stepping, and stepping, the stroke order or
 * the whole character shown leave writing.
 */
class HanziView extends View {
    interface Done { void complete(int mistakes); }
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

    // writing
    boolean quizzing, outline;   // tracing; the character's outline under it to trace over
    int next, mistakes, strokeMisses, hintAfter = 3;
    final java.util.List<float[]> hand = new java.util.ArrayList<>();   // {drawn x, y, glyph x, y}: accepted strokes' ends
    Done done;
    Runnable onTrace;            // told when writing starts or ends (Learn's pills)
    ValueAnimator hintAnim;
    int hintStroke = -1;
    float hintAlpha;
    final Matrix toGlyph = new Matrix();
    final Path trail = new Path();
    final List<float[]> touch = new ArrayList<>();

    final Paint inkP = new Paint(Paint.ANTI_ALIAS_FLAG), brushP = new Paint(Paint.ANTI_ALIAS_FLAG),
            gridP = new Paint(Paint.ANTI_ALIAS_FLAG), frameP = new Paint(Paint.ANTI_ALIAS_FLAG),
            ghostP = new Paint(Paint.ANTI_ALIAS_FLAG), hlP = new Paint(Paint.ANTI_ALIAS_FLAG),
            edgeP = new Paint(Paint.ANTI_ALIAS_FLAG), numP = new Paint(Paint.ANTI_ALIAS_FLAG),
            haloP = new Paint(Paint.ANTI_ALIAS_FLAG), trailP = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        trailP.setColor(Ink.ink2);          // the finger's line: the second ink, apart from the strokes it fills
        trailP.setStyle(Paint.Style.STROKE);
        trailP.setStrokeWidth(Ink.dp(5));
        trailP.setStrokeCap(Paint.Cap.ROUND);
        trailP.setStrokeJoin(Paint.Join.ROUND);
    }

    void setGlyph(Glyph g) {
        stop();
        endTrace();
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
        numFor = -1;
        invalidate();
    }

    // ── what it shows ──
    void show() { stop(); unstep(); endTrace(); java.util.Arrays.fill(fill, 1); invalidate(); }

    /** A stroke's time by its length (400 units while the square isn't laid out yet: the card
     *  can be left in the frame it was built, before its strokes were measured). */
    long duration(int i) { return (long) (220 + (scale > 0 && i < lengths.length ? lengths[i] / scale : 400) * 0.75f); }

    /** The next stroke: the ones before it in ink, it landing in the highlighter, the rest to
     *  come. After the last, the first again. */
    void step() {
        final int n = fill.length;
        if (glyph == null || n == 0) return;
        stop();
        endTrace();
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
        endTrace();
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

    /** Write it: from nothing (over its outline, with outline), stroke by stroke; done is told
     *  the mistakes once the last stroke is in. Leaves stepping. */
    void quiz(int hintAfter, boolean outline, Done done) {
        stop();
        unstep();
        if (hintAnim != null) hintAnim.cancel();
        hintAnim = null;
        hintStroke = -1;
        java.util.Arrays.fill(fill, 0);
        this.hintAfter = hintAfter;
        this.outline = outline;
        this.done = done;
        next = mistakes = strokeMisses = 0;
        hand.clear();
        trail.reset();
        quizzing = glyph != null && fill.length > 0;
        invalidate();
        if (onTrace != null) onTrace.run();
    }

    /** Out of writing: no trail, no outline, no hint; the strokes as they are. */
    void endTrace() {
        boolean was = quizzing || outline;
        if (hintAnim != null) hintAnim.cancel();
        hintAnim = null;
        hintStroke = -1;
        quizzing = outline = false;
        done = null;
        trail.reset();
        if (was) {
            invalidate();
            if (onTrace != null) onTrace.run();
        }
    }

    // ── drawing ──
    @Override
    protected void onDraw(Canvas c) {
        float x0 = (getWidth() - size) / 2f, y0 = (getHeight() - size) / 2f, x1 = x0 + size, y1 = y0 + size;
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        if (!bare) drawGrid(c, x0, y0, x1, y1, mx, my);
        if (glyph == null) return;
        Path seg = new Path();
        if (outline) for (Path p : outlines) c.drawPath(p, ghostP);
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
        if (hintStroke >= 0 && hintStroke < outlines.length && hintAlpha > 0) {
            // where the stroke goes: the highlighter with its ink edge, as a step lands
            hlP.setAlpha(Math.round(255 * hintAlpha));
            edgeP.setAlpha(Math.round(255 * hintAlpha));
            c.drawPath(outlines[hintStroke], hlP);
            c.drawPath(outlines[hintStroke], edgeP);
            hlP.setAlpha(255);
            edgeP.setAlpha(255);
        }
        c.drawPath(trail, trailP);
    }

    // ── writing ──
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!quizzing) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
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
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                trail.reset();
                touch.clear();
                break;
        }
        invalidate();
        return true;
    }

    void add(float x, float y) {
        trail.lineTo(x, y);
        touch.add(new float[]{x, y});
    }

    /** Was that the next stroke? Compared in the glyph's own units (1024 to the side) against
     *  its median (fits). A tap (under 25 units drawn) isn't a stroke, and isn't a miss. */
    void judge() {
        if (!quizzing || next >= fill.length || touch.size() < 2) return;
        float[][] u = new float[touch.size()][];
        for (int i = 0; i < u.length; i++) {
            u[i] = touch.get(i).clone();
            toGlyph.mapPoints(u[i]);
        }
        float[][] raw = new float[u.length][];
        for (int i = 0; i < u.length; i++) raw[i] = u[i].clone();
        float drawn = 0;
        for (int i = 1; i < u.length; i++) drawn += dist(u[i - 1][0], u[i - 1][1], u[i][0], u[i][1]);
        if (drawn < 25) return;
        // the learner's hand as it's been so far: drawn a little small or off centre, read as the glyph's
        for (float[] p : u) { float[] q = fromHand(p[0], p[1]); p[0] = q[0]; p[1] = q[1]; }
        drawn = 0;
        for (int i = 1; i < u.length; i++) drawn += dist(u[i - 1][0], u[i - 1][1], u[i][0], u[i][1]);
        if (fits(u, drawn, medians[next]) || nearest(u, drawn)) {
            hand.add(new float[]{raw[0][0], raw[0][1], medians[next][0].x, medians[next][0].y});
            PointF e = medians[next][medians[next].length - 1];
            hand.add(new float[]{raw[raw.length - 1][0], raw[raw.length - 1][1], e.x, e.y});
            take();
        } else {
            mistakes++;
            if (++strokeMisses >= hintAfter + 2) take();          // never stuck: shown, then it fills itself
            else if (strokeMisses >= hintAfter) flashHint(next);
        }
    }

    /** The next stroke in: filled with the brush; the last one ends the writing. */
    void take() {
        final int i = next++;
        strokeMisses = 0;
        if (hintAnim != null) hintAnim.cancel();
        hintAnim = null;
        hintStroke = -1;
        ValueAnimator f = ValueAnimator.ofFloat(0, 1);
        f.setDuration(180);
        f.addUpdateListener(v -> { fill[i] = (float) v.getAnimatedValue(); invalidate(); });
        f.start();
        if (next >= fill.length) {
            quizzing = false;
            final Done d = done;
            final int k = mistakes;
            postDelayed(() -> {
                if (d == null || done != d) return;   // left meanwhile (endTrace)
                done = null;
                outline = false;
                invalidate();
                if (onTrace != null) onTrace.run();
                d.complete(k);
            }, 220);
        }
    }

    /** A point as the learner draws, in the glyph's units, moved and scaled as the learner's strokes so far were
     *  off the glyph's (least squares on their ends; scale only from two strokes, 0.7 to 1.6). */
    float[] fromHand(float x, float y) {
        int n = hand.size();
        if (n == 0) return new float[]{x, y};
        float ux = 0, uy = 0, gx = 0, gy = 0;
        for (float[] h : hand) { ux += h[0]; uy += h[1]; gx += h[2]; gy += h[3]; }
        ux /= n; uy /= n; gx /= n; gy /= n;
        float num = 0, den = 0;
        for (float[] h : hand) { num += (h[0] - ux) * (h[2] - gx) + (h[1] - uy) * (h[3] - gy); den += (h[0] - ux) * (h[0] - ux) + (h[1] - uy) * (h[1] - uy); }
        float k = n < 4 || den < 1 ? 1 : Math.max(0.7f, Math.min(1.6f, num / den));
        return new float[]{k * (x - ux) + gx, k * (y - uy) + gy};
    }

    /** A near miss taken: within the looser rule (fits, twice as far), and closer to the next
     *  stroke than to any stroke after it, so a wrong stroke still isn't. */
    boolean nearest(float[][] u, float drawn) {
        if (hand.isEmpty() && shape(u, drawn)) return true;
        if (!fits(u, drawn, medians[next], 300, 330, 0)) return false;
        return closest(u);
    }

    /** Nothing of the learner's hand known yet (the first stroke): its shape, moved to start where the
     *  stroke starts (up to 300 units off), by the strict rule, and closest to it. */
    boolean shape(float[][] u, float drawn) {
        PointF a = medians[next][0];
        float dx = a.x - u[0][0], dy = a.y - u[0][1];
        if (Math.hypot(dx, dy) > 300) return false;
        float[][] v = new float[u.length][];
        for (int i = 0; i < u.length; i++) v[i] = new float[]{u[i][0] + dx, u[i][1] + dy};
        return fits(v, drawn, medians[next]) && closest(v);
    }

    /** Closer to the next stroke than to any stroke after it. */
    boolean closest(float[][] u) {
        float d = avg(u, medians[next]);
        for (int j = next + 1; j < medians.length; j++) if (avg(u, medians[j]) < d) return false;
        return true;
    }

    static float avg(float[][] u, PointF[] m) {
        float a = 0;
        for (float[] p : u) a += toPolyline(p[0], p[1], m);
        return a / u.length;
    }

    /** The judge's rule, on points u in the glyph's units (drawn: their length) against the
     *  median m: near it on average (150), starting by its start and ending by its end (260),
     *  going its way (for a stroke longer than a dot), and about as long. */
    static boolean fits(float[][] u, float drawn, PointF[] m) { return fits(u, drawn, m, 150, 260, 0.3f); }

    static boolean fits(float[][] u, float drawn, PointF[] m, float near, float ends, float way) {
        if (m.length == 0) return false;
        float len = 0;
        for (int i = 1; i < m.length; i++) len += dist(m[i - 1].x, m[i - 1].y, m[i].x, m[i].y);
        float avg = 0;
        for (float[] p : u) avg += toPolyline(p[0], p[1], m);
        avg /= u.length;
        PointF a = m[0], b = m[m.length - 1];
        float[] s = u[0], t = u[u.length - 1];
        return avg < near
                && dist(s[0], s[1], a.x, a.y) < ends && dist(t[0], t[1], b.x, b.y) < ends
                && (len < 120 || cos(t[0] - s[0], t[1] - s[1], b.x - a.x, b.y - a.y) > way)
                && drawn > 0.3f * len && drawn < 3f * len + 150;
    }

    /** Where stroke i goes, in the highlighter: in, a moment, out. */
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
        if (hintAnim != null) hintAnim.cancel();
        super.onDetachedFromWindow();
    }
}
