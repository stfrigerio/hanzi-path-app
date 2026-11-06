package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Children left to right, wrapping to the next line: the chips. */
class Flow extends ViewGroup {
    final int gap;

    Flow(Context c, int gapDp) {
        super(c);
        gap = Ink.dp(gapDp);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int max = MeasureSpec.getSize(ws), x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += line + gap; line = 0; }
            x += v.getMeasuredWidth() + gap;
            line = Math.max(line, v.getMeasuredHeight());
        }
        setMeasuredDimension(max, y + line);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l, x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += line + gap; line = 0; }
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            x += v.getMeasuredWidth() + gap;
            line = Math.max(line, v.getMeasuredHeight());
        }
    }
}
