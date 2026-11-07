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
 * you know. Folded, it shows only the cells from {@code from} to {@code shown} (whole rows: the
 * last rows met and the rows ahead); open, all of them. Next is the highlighter, learning pink, known blue, a wilting one (due and
 * slipping) outlined in pink; the next forty ahead outlined, the rest faint. A part has a
 * dot in its corner. A tap opens the item; the cell under the finger takes the press (its fill
 * as a pill's turns, Ink.pressed, and a ring of ink) once it's a tap, not a scroll.
 */
class GardenView extends View {
    static final int FAR = 0, NEAR = 1, NEXT = 2, YOUNG = 3, KNOWN = 4, WILT = 5;
    interface Tap { void open(int index); }

    int[] state = new int[0];
    int cols = 10, from = 0, shown = Integer.MAX_VALUE;
    int[] pick;            // the places on the path drawn, in order, instead of a run of them (Levels); null: the run
    float cell, gap;
    Tap tap;
    int pressed = -1;      // the cell under the finger
    final Runnable unpress = () -> press(-1);
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
            public void onShowPress(MotionEvent e) { press(at(e)); }   // held still: a tap, not a scroll

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int i = at(e);
                press(i);
                if (i >= 0 && tap != null) { playSoundEffect(android.view.SoundEffectConstants.CLICK); tap.open(i); }
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
    void show(int n) { show(0, n); }

    /** Show the cells from a (the start of a row) up to b (folded), or every one (0, Integer.MAX_VALUE). */
    void show(int a, int b) {
        from = a;
        shown = b;
        requestLayout();
        invalidate();
    }

    /** The first cell drawn, and how many are. */
    int first() { return pick != null ? 0 : Math.min(from, state.length); }

    int count() { return pick != null ? Math.min(shown, pick.length) : Math.min(shown, state.length) - first(); }

    /** The place on the path of the j-th cell drawn. */
    int index(int j) { return pick != null ? pick[j] : first() + j; }

    /** The cell under a touch (its index on the path), or -1. */
    int at(MotionEvent e) {
        int col = (int) (e.getX() / (cell + gap)), row = (int) (e.getY() / (cell + gap));
        int i = row * cols + col;
        return e.getX() >= 0 && e.getY() >= 0 && col < cols && i < count() ? index(i) : -1;
    }

    void press(int i) {
        removeCallbacks(unpress);
        if (i == pressed) return;
        pressed = i;
        invalidate();
    }

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
        for (int j = 0, end = count(); j < end; j++) {
            int i = index(j);
            Item it = PathData.items.get(i);
            float x = (j % cols) * (cell + gap), y = (j / cols) * (cell + gap);
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
            if (i == pressed) { bg = Ink.pressed(bg); line = Ink.ink; }
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
    public boolean onTouchEvent(MotionEvent e) {
        boolean used = taps.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_CANCEL) press(-1);
        else if (a == MotionEvent.ACTION_UP && pressed >= 0) postDelayed(unpress, 120);   // seen, then gone
        else if (a == MotionEvent.ACTION_MOVE && pressed >= 0 && at(e) != pressed) press(-1);
        return used || super.onTouchEvent(e);
    }
}
