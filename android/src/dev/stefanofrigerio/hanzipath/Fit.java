package dev.stefanofrigerio.hanzipath;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FSRS's weights fitted to the learner's own log: the formulas stay Progress's, only the
 * seventeen numbers they take change.
 *
 * Each card's grades are replayed in order, as Progress.grade gave them; at every grade after
 * a card's first, the recall the weights predicted is scored against what happened (2 or 3:
 * remembered), as log loss. Adam walks the weights down that loss from the defaults, inside the
 * FSRS-4.5 optimizer's bounds, with the gradient carried forward through the replay (exact, one
 * pass a step: a few seconds on a phone for thousands of reviews).
 *
 * A fifth of the cards (by their character, so always the same ones) are held out: the fitted
 * weights are used only if they predict those better than the defaults by MARGIN. Otherwise
 * the defaults stay and the result says why.
 *
 * When: after a graded round, once the log holds MIN_REVIEWS grades of which MIN_SCORED are
 * second or later looks; again at most every EVERY. Off the main thread.
 */
final class Fit {
    static final int MIN_REVIEWS = 400, MIN_SCORED = 100;
    static final long EVERY = 7 * Progress.DAY;
    static final double MARGIN = 0.015;    // the held-out loss must fall by 1.5% for the fit to be used
    static final int STEPS = 300;
    static final long BUDGET = 10_000;     // ms: a slow phone stops here with the best so far
    /** The FSRS-4.5 optimizer's bounds. w[3] (easy's first stability) and w[16] (easy's bonus)
     *  aren't fitted: there's no fourth grade here. */
    static final double[] LO = {0.1, 0.1, 0.1, 0.1, 1, 0.1, 0.1, 0, 0, 0.1, 0.01, 0.5, 0.01, 0.01, 0.01, 0, 1};
    static final double[] HI = {100, 100, 100, 100, 10, 5, 5, 0.5, 3, 0.8, 2.5, 5, 0.2, 0.9, 2, 1, 4};
    static final int[] FREE = {0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    static final int N = 17;
    /** A light pull towards the defaults, as if each weight had this many reviews' worth of
     *  evidence for its default: a small log can't fling a weight to its bound on noise. */
    static final double PRIOR = 3;

    /** The log as the fit reads it: card i's grades are at start[i] .. start[i + 1] - 1. */
    static final class Data {
        int[] start = new int[1];
        double[] days = new double[0];   // since the card's grade before (0 for its first)
        int[] g = new int[0];
        int cards, reviews, scored;
    }

    /** Is a card held out of the fit, to judge it? A fifth of them, always the same. */
    static boolean held(String h) { return (h.hashCode() & 0x7fffffff) % 5 == 0; }

    /** Each card's grades in order, from the log (oldest first): all of them, the fit's (not
     *  held), the held-out ones. */
    static Data[] split(List<Progress.Logged> log) {
        Map<String, List<Progress.Logged>> by = new LinkedHashMap<>();
        for (Progress.Logged l : log) {
            if (l.grade < 1 || l.grade > 3) continue;
            List<Progress.Logged> a = by.get(l.h);
            if (a == null) by.put(l.h, a = new ArrayList<>());
            a.add(l);
        }
        List<List<Progress.Logged>> all = new ArrayList<>(), fit = new ArrayList<>(), out = new ArrayList<>();
        for (Map.Entry<String, List<Progress.Logged>> e : by.entrySet()) {
            all.add(e.getValue());
            (held(e.getKey()) ? out : fit).add(e.getValue());
        }
        return new Data[]{data(all), data(fit), data(out)};
    }

    static Data data(List<List<Progress.Logged>> cards) {
        Data d = new Data();
        int n = 0;
        for (List<Progress.Logged> c : cards) n += c.size();
        d.start = new int[cards.size() + 1];
        d.days = new double[n];
        d.g = new int[n];
        int i = 0, k = 0;
        for (List<Progress.Logged> c : cards) {
            d.start[k++] = i;
            long last = 0;
            for (int j = 0; j < c.size(); j++, i++) {
                Progress.Logged l = c.get(j);
                d.days[i] = j == 0 ? 0 : Math.max(0, l.at - last) / (double) Progress.DAY;
                d.g[i] = l.grade;
                last = l.at;
            }
        }
        d.start[k] = i;
        d.cards = cards.size();
        d.reviews = n;
        d.scored = n - cards.size();
        return d;
    }

    /** The mean log loss of the recall w predicts at every grade after a card's first, the
     *  cards replayed as Progress.grade would; and its gradient in grad (null: not wanted). */
    static double loss(double[] w, Data data, double[] grad) {
        boolean dg = grad != null;
        if (dg) java.util.Arrays.fill(grad, 0);
        double[] ds = new double[N], dd = new double[N], dr = new double[N], ns = new double[N], nd = new double[N];
        double sum = 0;
        int scored = 0;
        for (int c = 0; c < data.cards; c++) {
            int i = data.start[c], end = data.start[c + 1];
            // the first grade: where the card starts
            int g = data.g[i];
            double s = w[g - 1], d;
            double raw = w[4] - (g - 3) * w[5];
            d = Math.min(10, Math.max(1, raw));
            if (dg) {
                java.util.Arrays.fill(ds, 0);
                java.util.Arrays.fill(dd, 0);
                ds[g - 1] = 1;
                if (raw > 1 && raw < 10) { dd[4] = 1; dd[5] = -(g - 3); }
            }
            if (s > Progress.MAX_S) { s = Progress.MAX_S; if (dg) java.util.Arrays.fill(ds, 0); }
            for (i++; i < end; i++) {
                g = data.g[i];
                double t = data.days[i];
                double q = 1 + Progress.F * t / s;
                double r = Math.pow(q, Progress.C);
                double drds = Progress.C * r / q * (-Progress.F * t / (s * s));
                double y = g >= 2 ? 1 : 0;
                double rc = Math.min(1 - 1e-6, Math.max(1e-6, r));
                sum -= y * Math.log(rc) + (1 - y) * Math.log(1 - rc);
                scored++;
                if (dg) {
                    double dl = rc != r ? 0 : -(y / r - (1 - y) / (1 - r));
                    for (int k = 0; k < N; k++) { dr[k] = drds * ds[k]; grad[k] += dl * dr[k]; }
                }
                // the stability after it
                double s2;
                if (g == 1) {
                    double ex = Math.exp(w[14] * (1 - r)), dp = Math.pow(d, -w[12]), sp = Math.pow(s + 1, w[13]);
                    double a = w[11] * dp * (sp - 1) * ex;
                    if (a < s) {
                        s2 = a;
                        if (dg) {
                            double as = w[11] * dp * ex * w[13] * sp / (s + 1), ad = -w[12] * a / d, ar = -w[14] * a;
                            for (int k = 0; k < N; k++) ns[k] = as * ds[k] + ad * dd[k] + ar * dr[k];
                            ns[11] += a / w[11];
                            ns[12] += -Math.log(d) * a;
                            ns[13] += w[11] * dp * ex * sp * Math.log(s + 1);
                            ns[14] += (1 - r) * a;
                        }
                    } else {
                        s2 = s;
                        if (dg) System.arraycopy(ds, 0, ns, 0, N);
                    }
                    if (s2 < 0.05) { s2 = 0.05; if (dg) java.util.Arrays.fill(ns, 0); }
                } else {
                    double h = g == 2 ? w[15] : 1, e8 = Math.exp(w[8]), sp = Math.pow(s, -w[9]), e10 = Math.exp(w[10] * (1 - r));
                    double base = e8 * (11 - d) * sp * h, k1 = base * (e10 - 1);
                    s2 = s * (1 + k1);
                    if (dg) {
                        double as = 1 + k1 * (1 - w[9]), ad = -s * e8 * sp * (e10 - 1) * h, ar = -s * base * w[10] * e10;
                        for (int k = 0; k < N; k++) ns[k] = as * ds[k] + ad * dd[k] + ar * dr[k];
                        ns[8] += s * k1;
                        ns[9] += -s * k1 * Math.log(s);
                        ns[10] += s * base * (1 - r) * e10;
                        if (g == 2) ns[15] += s * e8 * (11 - d) * sp * (e10 - 1);
                    }
                }
                // the difficulty after it
                double d1 = d - w[6] * (g - 3), d03 = Math.min(10, Math.max(1, w[4]));
                double d2 = w[7] * d03 + (1 - w[7]) * d1, d3 = Math.min(10, Math.max(1, d2));
                if (dg) {
                    boolean free = d2 > 1 && d2 < 10, w4 = w[4] > 1 && w[4] < 10;
                    for (int k = 0; k < N; k++) nd[k] = free ? (1 - w[7]) * dd[k] : 0;
                    if (free) {
                        nd[6] += -(1 - w[7]) * (g - 3);
                        nd[7] += d03 - d1;
                        if (w4) nd[4] += w[7];
                    }
                }
                d = d3;
                s = s2;
                if (s > Progress.MAX_S) { s = Progress.MAX_S; if (dg) java.util.Arrays.fill(ns, 0); }
                if (dg) {
                    double[] x = ds; ds = ns; ns = x;
                    x = dd; dd = nd; nd = x;
                }
            }
        }
        if (scored == 0) return 0;
        if (dg) for (int k = 0; k < N; k++) grad[k] /= scored;
        return sum / scored;
    }

    /** The weights that best predict the data, from the defaults: Adam inside the bounds, each
     *  weight's step in proportion to its size, the step shrinking to nothing by the end, with
     *  PRIOR's pull. The best seen is kept. */
    static double[] fit(Data data, long budget) {
        double[] w = Progress.W.clone(), best = w.clone(), g = new double[N], m1 = new double[N], m2 = new double[N];
        double bestLoss = Double.MAX_VALUE;
        long t0 = System.currentTimeMillis();
        double b1 = 0.9, b2 = 0.999;
        for (int step = 1; step <= STEPS; step++) {
            double l = loss(w, data, g);
            if (data.scored > 0) for (int k : FREE) {
                double size = Math.max(Math.abs(Progress.W[k]), 0.1), z = (w[k] - Progress.W[k]) / size, lam = PRIOR / data.scored;
                l += lam * z * z;
                g[k] += 2 * lam * z / size;
            }
            if (l < bestLoss) { bestLoss = l; best = w.clone(); }
            if (System.currentTimeMillis() - t0 > budget) break;
            double lr = 0.04 * 0.5 * (1 + Math.cos(Math.PI * (step - 1) / STEPS));
            for (int k : FREE) {
                m1[k] = b1 * m1[k] + (1 - b1) * g[k];
                m2[k] = b2 * m2[k] + (1 - b2) * g[k] * g[k];
                double mh = m1[k] / (1 - Math.pow(b1, step)), vh = m2[k] / (1 - Math.pow(b2, step));
                double size = Math.max(Math.abs(Progress.W[k]), 0.1);
                w[k] = Math.min(HI[k], Math.max(LO[k], w[k] - lr * size * mh / (Math.sqrt(vh) + 1e-8)));
            }
        }
        double l = loss(w, data, null);
        if (l < bestLoss) best = w.clone();
        return best;
    }

    /** A fit of the log (graded rows, oldest first): null when it's too small to fit, else what
     *  meta "fsrs" keeps, with w only when the fit predicts the held-out cards better. */
    static JSONObject run(List<Progress.Logged> log, long now) throws Exception { return run(log, now, BUDGET); }

    static JSONObject run(List<Progress.Logged> log, long now, long budget) throws Exception {
        Data[] d = split(log);
        if (d[0].reviews < MIN_REVIEWS || d[0].scored < MIN_SCORED) return null;
        long t0 = System.currentTimeMillis();
        double[] w = fit(d[1], budget);
        double before = loss(Progress.W, d[2], null), after = loss(w, d[2], null);
        JSONObject o = new JSONObject().put("n", d[0].reviews).put("at", now)
                .put("before", round(before)).put("after", round(after))
                .put("ms", System.currentTimeMillis() - t0);
        if (d[2].scored < 20) o.put("why", "too few held out");
        else if (!(after < before * (1 - MARGIN))) o.put("why", "no better");
        else {
            JSONArray a = new JSONArray();
            for (double x : w) a.put(round(x));
            o.put("w", a);
        }
        return o;
    }

    static double round(double x) { return Math.round(x * 1e5) / 1e5; }

    /** Is a fit due: not running, not put back by hand, enough in the log, a week since the last. */
    static boolean due(Progress p, long now) {
        if (p.fitting != null) return false;
        if (p.fsrs != null && (p.fsrs.optBoolean("reset") || now - p.fsrs.optLong("at") < EVERY)) return false;
        long[] n = p.logCounts();
        return n[0] >= MIN_REVIEWS && n[0] - n[1] >= MIN_SCORED;
    }

    /** A fit in the background when one is due (or asked for: force), its result kept on the
     *  main thread, then after (may be null). */
    static boolean start(Progress p, long now, boolean force, Runnable after) {
        if (p.fitting != null) return false;
        if (!force && !due(p, now)) return false;
        final Handler main = new Handler(Looper.getMainLooper());
        final int gen = p.loads;
        Thread t = new Thread(() -> {
            JSONObject r = null;
            try { r = run(p.reviewsSince(0), now); } catch (Throwable e) { /* the weights stay as they are */ }
            final JSONObject res = r;
            main.post(() -> {
                p.fitting = null;
                if (res != null && p.loads == gen) p.putFit(res);
                if (after != null) after.run();
            });
        }, "fsrs-fit");
        t.setPriority(Thread.MIN_PRIORITY);
        p.fitting = t;
        t.start();
        return true;
    }
}
