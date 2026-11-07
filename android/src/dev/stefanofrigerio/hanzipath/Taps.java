package dev.stefanofrigerio.hanzipath;

import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.BaseMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * Tap any character: in a card's words, its sentence, its why-line, the Read round's sentence
 * once shown, the Tell-them-apart line, each character on the path opens its card (Main.lookup),
 * and back returns to where you were, as it was (the same card, the same sentence still shown).
 *
 * The characters keep their look: no underline, no link colour, their ink and highlighter as
 * they were. Under the finger a character takes the same wash of the highlighter as anything
 * you can touch (Ink.press; Ink.pressed(hl) on the highlighter), at once. Only a press that
 * starts on a character is taken: anywhere else the tap goes to what's under the text (a word's
 * row says it), and a drag goes to the scroll.
 */
final class Taps {
    private Taps() {}

    /** A character you can tap: opens its card; draws as the text round it does. */
    static final class Tap extends ClickableSpan {
        final Main m;
        final String h;

        Tap(Main m, String h) { this.m = m; this.h = h; }

        @Override
        public void onClick(View v) { open(m, h); }

        @Override
        public void updateDrawState(TextPaint p) {}   // the character's own ink: no link colour, no underline
    }

    /** The wash under a character while it's pressed. */
    static final class Wash extends BackgroundColorSpan {
        Wash(int c) { super(c); }
    }

    /** Where a tapped card came from: the screen as it was, built again on back. */
    static final class Back {
        Learn was, opened;
        Runnable redraw;
    }

    /** Make every character of t's text on the path tappable, but its card's own (own). */
    static void link(Main m, TextView t, String... own) {
        CharSequence s = t.getText();
        SpannableStringBuilder b = s instanceof SpannableStringBuilder ? (SpannableStringBuilder) s : new SpannableStringBuilder(s);
        List<String> mine = Arrays.asList(own);
        boolean any = false;
        for (int i = 0; i < b.length(); ) {
            int cp = Character.codePointAt(b, i), n = Character.charCount(cp);
            String c = new String(Character.toChars(cp));
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN && !mine.contains(c) && PathData.get(c) != null) {
                b.setSpan(new Tap(m, c), i, i + n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                any = true;
            }
            i += n;
        }
        if (!any) return;
        t.setText(b, TextView.BufferType.SPANNABLE);
        t.setMovementMethod(new Press());
        // the movement method makes the text clickable and focusable: it isn't, only its characters
        t.setClickable(false);
        t.setLongClickable(false);
        t.setFocusable(false);
        t.setHighlightColor(0);
    }

    /** A tap's card: looked up, and where it came from kept for back. */
    static void open(Main m, String h) {
        Back b = new Back();
        b.was = m.learn;
        b.redraw = m.redraw;
        m.lookup(h);
        b.opened = m.learn;
        m.taps.push(b);
    }

    /** Back from a card a tap opened: the screen it was opened from, as it was. */
    static boolean back(Main m) {
        Back b = m.taps.peek();
        if (b == null || !"learn".equals(m.screen) || m.learn != b.opened) return false;
        m.taps.pop();
        m.learn = b.was;
        b.redraw.run();
        return true;
    }

    /** The character under (x, y) in t, its Tap; null off the text or off a character. */
    static Tap at(TextView t, Spannable s, float x, float y) {
        Layout l = t.getLayout();
        if (l == null) return null;
        x -= t.getTotalPaddingLeft() - t.getScrollX();
        y -= t.getTotalPaddingTop() - t.getScrollY();
        if (y < 0 || y > l.getHeight()) return null;
        int line = l.getLineForVertical((int) y);
        if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return null;
        int off = l.getOffsetForHorizontal(line, x);
        if (off > l.getLineStart(line) && l.getPrimaryHorizontal(off) > x) off--;
        if (off >= s.length()) return null;
        for (Tap p : s.getSpans(off, off + 1, Tap.class))
            if (s.getSpanStart(p) <= off && s.getSpanEnd(p) > off) return p;
        return null;
    }

    /** The touch on a text of tappable characters: a press on one washes it, a lift on it opens
     *  it; a press anywhere else isn't taken, and a drag (the scroll's cancel) lets it go. */
    static final class Press extends BaseMovementMethod {
        Tap down;
        Wash wash;

        @Override
        public boolean onTouchEvent(TextView t, Spannable s, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = at(t, s, e.getX(), e.getY());
                    if (down == null) return false;
                    press(s, down);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (down == null) return false;
                    if (at(t, s, e.getX(), e.getY()) != down) release(s);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (down == null) return false;
                    Tap p = down;
                    boolean on = at(t, s, e.getX(), e.getY()) == p && wash != null;
                    release(s);
                    down = null;
                    if (on) p.onClick(t);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    release(s);
                    down = null;
                    return false;
                default:
                    return down != null;
            }
        }

        void press(Spannable s, Tap p) {
            int a = s.getSpanStart(p), z = s.getSpanEnd(p);
            boolean lit = false;
            for (BackgroundColorSpan b : s.getSpans(a, z, BackgroundColorSpan.class)) lit |= b.getBackgroundColor() == Ink.hl;
            wash = new Wash(lit ? Ink.pressed(Ink.hl) : Ink.press);
            s.setSpan(wash, a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        void release(Spannable s) {
            if (wash != null) s.removeSpan(wash);
            wash = null;
        }

        @Override
        public boolean canSelectArbitrarily() { return false; }
    }
}
