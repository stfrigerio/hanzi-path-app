package dev.stefanofrigerio.hanzipath;

import android.graphics.Path;
import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * A character's strokes as Make Me a Hanzi draws them: each an outline (SVG path data: M, L,
 * Q, C, Z) and a median, the line a brush follows through it, in a 1024 box with y up
 * (the baseline at 900). HanziView maps them onto the screen.
 */
class Glyph {
    final Path[] outlines;
    final PointF[][] medians;

    Glyph(JSONArray strokes, JSONArray meds) throws JSONException {
        outlines = new Path[strokes.length()];
        medians = new PointF[meds.length()][];
        for (int i = 0; i < outlines.length; i++) outlines[i] = parse(strokes.getString(i));
        for (int i = 0; i < medians.length; i++) {
            JSONArray m = meds.getJSONArray(i);
            medians[i] = new PointF[m.length()];
            for (int j = 0; j < m.length(); j++) {
                JSONArray p = m.getJSONArray(j);
                medians[i][j] = new PointF((float) p.getDouble(0), (float) p.getDouble(1));
            }
        }
    }

    int count() { return Math.min(outlines.length, medians.length); }

    /** SVG path data, absolute commands as Make Me a Hanzi writes them. */
    static Path parse(String d) {
        Path p = new Path();
        String[] t = d.trim().split("[\\s,]+");
        char cmd = 'M';
        int i = 0;
        while (i < t.length) {
            String s = t[i];
            if (s.length() == 1 && Character.isLetter(s.charAt(0))) { cmd = s.charAt(0); i++; if (cmd == 'Z' || cmd == 'z') { p.close(); } continue; }
            switch (cmd) {
                case 'M': p.moveTo(f(t[i]), f(t[i + 1])); i += 2; cmd = 'L'; break;
                case 'L': p.lineTo(f(t[i]), f(t[i + 1])); i += 2; break;
                case 'Q': p.quadTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3])); i += 4; break;
                case 'C': p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5])); i += 6; break;
                default: i++;
            }
        }
        return p;
    }

    static float f(String s) { return Float.parseFloat(s); }
}
