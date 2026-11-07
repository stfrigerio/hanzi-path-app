package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Children left to right, wrapping to the next line: the chips, the legend, pills that don't
 * fit side by side once the text is large. Each child sits in the middle of its line's
 * height; centred, each line is in the middle of the width too.
 */
class Flow extends ViewGroup {
    final int gap;
    boolean center;

    Flow(Context c, int gapDp) {
        super(c);
        gap = Ink.dp(gapDp);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int max = MeasureSpec.getSize(ws), x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += line + gap; line = 0; }
            x += v.getMeasuredWidth() + gap;
            line = Math.max(line, v.getMeasuredHeight());
        }
        setMeasuredDimension(max, y + line);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l, n = getChildCount(), first = 0, x = 0, y = 0;
        while (first < n) {
            // one line: as many as fit, then laid out on its height
            int end = first, w = 0, line = 0;
            for (; end < n; end++) {
                View v = getChildAt(end);
                if (v.getVisibility() == GONE) continue;
                if (w > 0 && w + v.getMeasuredWidth() > max) break;
                w += v.getMeasuredWidth() + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
            if (end == first) end++;   // one too wide for any line: alone on its own
            x = center ? Math.max(0, (max - (w - gap)) / 2) : 0;
            for (int i = first; i < end; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int top = y + (line - v.getMeasuredHeight()) / 2;
                v.layout(x, top, x + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap;
            }
            y += line + gap;
            first = end;
        }
    }
}
