package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * The path on the home screen, every item a cell in order: the garden that fills with what
 * you know. Folded, it shows only its first {@code shown} cells (what's met and the rows
 * ahead); open, all of them. Next is the highlighter, learning pink, known blue, a wilting one (due and
 * slipping) outlined in pink; the next forty ahead outlined, the rest faint. A part has a
 * dot in its corner. A tap opens the item.
 */
class GardenView extends View {
    static final int FAR = 0, NEAR = 1, NEXT = 2, YOUNG = 3, KNOWN = 4, WILT = 5;
    interface Tap { void open(int index); }

    int[] state = new int[0];
    int cols = 10, shown = Integer.MAX_VALUE;
    float cell, gap;
    Tap tap;
    final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(Paint.ANTI_ALIAS_FLAG),
            textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final GestureDetector taps;

    GardenView(Context c) {
        super(c);
        gap = Ink.dp(2);
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(Math.max(1, Ink.dp(1)));
        textP.setTypeface(Ink.han);
        textP.setTextAlign(Paint.Align.CENTER);
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int col = (int) (e.getX() / (cell + gap)), row = (int) (e.getY() / (cell + gap));
                int i = row * cols + col;
                if (col < cols && i < count() && tap != null) { playSoundEffect(android.view.SoundEffectConstants.CLICK); tap.open(i); }
                return true;
            }
        });
        setContentDescription("The path: every character and part in the order they're taught");
    }

    void set(int[] state) {
        this.state = state;
        requestLayout();
        invalidate();
    }

    /** Show the first n cells (folded), or every one (Integer.MAX_VALUE). */
    void show(int n) {
        shown = n;
        requestLayout();
        invalidate();
    }

    int count() { return Math.min(shown, state.length); }

    /** How many cells fit in a row this wide: as many as stay at least 32 dp. */
    static int columns(int widthPx) {
        float gap = Ink.dp(2);
        return Math.max(8, (int) ((widthPx + gap) / (Ink.dp(32) + gap)));
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        cols = columns(w);
        cell = (w - gap * (cols - 1)) / cols;
        int rows = (count() + cols - 1) / cols;
        setMeasuredDimension(w, (int) Math.ceil(rows * cell + Math.max(0, rows - 1) * gap));
    }

    @Override
    protected void onDraw(Canvas c) {
        textP.setTextSize(cell * 0.56f);
        Paint.FontMetrics fm = textP.getFontMetrics();
        for (int i = 0; i < count(); i++) {
            Item it = PathData.items.get(i);
            float x = (i % cols) * (cell + gap), y = (i / cols) * (cell + gap);
            r.set(x, y, x + cell, y + cell);
            int bg = 0, line = 0, fg;
            switch (state[i]) {
                case NEXT: bg = Ink.hl; fg = Ink.hlInk; break;
                case YOUNG: bg = Ink.young; fg = Ink.paper; break;
                case KNOWN: bg = Ink.known; fg = Ink.paper; break;
                case WILT: bg = Ink.paper2; line = Ink.young; fg = Ink.young; break;
                case NEAR: line = Ink.rule; fg = Ink.ink2; break;
                default: fg = Ink.rule;
            }
            if (bg != 0) { fillP.setColor(bg); c.drawRect(r, fillP); }
            if (line != 0) {
                lineP.setColor(line);
                float h = lineP.getStrokeWidth() / 2;
                c.drawRect(x + h, y + h, x + cell - h, y + cell - h, lineP);
            }
            textP.setColor(fg);
            c.drawText(it.h, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, textP);
            if (it.isPart()) {
                fillP.setColor(fg);
                float d = Math.max(2, cell * 0.09f);
                c.drawRect(x + d, y + d, x + 2 * d, y + 2 * d, fillP);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) { return taps.onTouchEvent(e) || super.onTouchEvent(e); }
}
