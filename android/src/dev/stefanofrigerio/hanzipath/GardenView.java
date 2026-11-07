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
 * TalkBack reaches every cell shown as a virtual view of its own (Cells), in path order.
 */
class GardenView extends View {
    static final int FAR = 0, NEAR = 1, NEXT = 2, YOUNG = 3, KNOWN = 4, WILT = 5;
    interface Tap { void open(int index); }

    int[] state = new int[0];
    int cols = 10, from = 0, shown = Integer.MAX_VALUE;
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
        if (nodes != null) sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);   // the cells TalkBack reaches changed
        requestLayout();
        invalidate();
    }

    /** The first cell drawn, and how many are. */
    int first() { return Math.min(from, state.length); }

    int count() { return Math.min(shown, state.length) - first(); }

    /** The cell under a touch (its index on the path), or -1. */
    int at(MotionEvent e) {
        int col = (int) (e.getX() / (cell + gap)), row = (int) (e.getY() / (cell + gap));
        int i = row * cols + col;
        return e.getX() >= 0 && e.getY() >= 0 && col < cols && i < count() ? first() + i : -1;
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
        for (int i = first(), end = first() + count(); i < end; i++) {
            Item it = PathData.items.get(i);
            int j = i - first();
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

    // ── TalkBack: every cell shown is a virtual view of its own, in path order, that says what
    //    it is and where it stands ("水, shuǐ, water: known"), and opens it on a double tap ──
    int focused = -1;       // the cell TalkBack's focus is on (a path index), or -1
    int hovered = -1;       // the cell under a finger exploring by touch
    android.view.accessibility.AccessibilityNodeProvider nodes;

    static String stateName(int s) {
        switch (s) {
            case NEXT: return "next";
            case YOUNG: return "learning";
            case KNOWN: return "known";
            case WILT: return "wilting";
            default: return "ahead";
        }
    }

    /** What TalkBack says for a cell: "水, shuǐ, water: known"; a part says it's one. */
    String spoken(int i) {
        Item it = PathData.items.get(i);
        return (it.isPart() ? "part " : "") + it.h + (it.reading().isEmpty() ? "" : ", " + it.reading()) + ", " + it.en
                + ": " + stateName(state[i]);
    }

    /** Where cell i is drawn, in this view. */
    android.graphics.Rect bounds(int i) {
        int j = i - first();
        int x = Math.round((j % cols) * (cell + gap)), y = Math.round((j / cols) * (cell + gap));
        return new android.graphics.Rect(x, y, Math.round(x + cell), Math.round(y + cell));
    }

    boolean showing(int i) { return i >= first() && i < first() + count(); }

    @Override
    public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (nodes == null) nodes = new Cells();
        return nodes;
    }

    final class Cells extends android.view.accessibility.AccessibilityNodeProvider {
        @Override
        public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            android.view.accessibility.AccessibilityNodeInfo n;
            if (id == HOST_VIEW_ID) {
                n = new android.view.accessibility.AccessibilityNodeInfo(GardenView.this);
                onInitializeAccessibilityNodeInfo(n);
                for (int i = first(), end = first() + count(); i < end; i++) n.addChild(GardenView.this, i);
                return n;
            }
            if (!showing(id)) return null;
            n = new android.view.accessibility.AccessibilityNodeInfo(GardenView.this, id);
            n.setPackageName(getContext().getPackageName());
            n.setClassName("android.widget.Button");
            n.setParent(GardenView.this);
            n.setContentDescription(spoken(id));
            android.graphics.Rect b = bounds(id);
            n.setBoundsInParent(b);
            int[] at = new int[2];
            getLocationOnScreen(at);
            b.offset(at[0], at[1]);
            n.setBoundsInScreen(b);
            n.setVisibleToUser(isShown());
            n.setEnabled(true);
            n.setFocusable(true);
            n.setClickable(tap != null);
            if (tap != null) n.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            n.setAccessibilityFocused(focused == id);
            n.addAction(focused == id ? android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            return n;
        }

        @Override
        public boolean performAction(int id, int action, android.os.Bundle args) {
            if (id == HOST_VIEW_ID) return GardenView.this.performAccessibilityAction(action, args);
            if (!showing(id)) return false;
            switch (action) {
                case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK:
                    if (tap == null) return false;
                    send(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                    tap.open(id);
                    return true;
                case android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    if (focused == id) return false;
                    focused = id;
                    press(id);         // the focused cell ringed, as a finger's press is
                    send(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    return true;
                case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focused != id) return false;
                    focused = -1;
                    press(-1);
                    send(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    return true;
            }
            return false;
        }
    }

    /** An accessibility event from cell i, sent up as its own. */
    void send(int i, int type) {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled() || getParent() == null) return;
        android.view.accessibility.AccessibilityEvent e = new android.view.accessibility.AccessibilityEvent(type);
        e.setPackageName(getContext().getPackageName());
        e.setClassName("android.widget.Button");
        e.setSource(this, i);
        e.setContentDescription(spoken(i));
        e.setEnabled(true);
        getParent().requestSendAccessibilityEvent(this, e);
    }

    /** Exploring by touch: the cell under the finger is announced as it's entered. */
    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isTouchExplorationEnabled()) return super.dispatchHoverEvent(e);
        int i = e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? -1 : at(e);
        if (i != hovered) {
            if (i >= 0) send(i, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (hovered >= 0) send(hovered, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            hovered = i;
        }
        return true;
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
