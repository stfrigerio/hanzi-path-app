package dev.stefanofrigerio.hanzipath;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How well each item is known, and the scheduler that decides when it comes back.
 *
 * A card is a small FSRS (FSRS-4.5, its default
 * weights until the learner's own log is big enough to fit them: Fit): a stability S in days, the time after which the item would be recalled nine
 * times out of ten, and a difficulty D, 1-10. Grades are the garden's three: 1 again,
 * 2 unsure, 3 knew. Learning an item isn't a grade: the round after a batch is.
 *
 * Kept in SQLite (databases/hanzi.db, in Android's backup), held in memory and written
 * through on every change:
 *   cards    one row an item met, or a word (keyed by the word: Words)
 *   stories  what you wrote for an item
 *   meta     opts, today, days: settings and small state, each a JSON value; fsrs: the
 *            weights fitted to the learner's log (Fit), which new grades use
 *   reviews  every grade given, as given (Fit fits FSRS's weights to them): a review's
 *            mode is its kind of question, a practice answer's "practice-<kind>" (graded too:
 *            a real answer; rows from before practice was graded have r = s = d = 0); beside
 *            them, sentences read (mode "read"), not a grade: GRADED leaves them out. Each
 *            answer keeps what was tapped (picked; null in rows from before version 2): a
 *            wrong one that is another character is a confusion
 *
 * Learning steps, around FSRS (as Anki's): a new item isn't left to its first stability
 * straight after the check. A right check brings it back in an hour, a right answer then
 * the next day, and only after that does it graduate to FSRS's interval; a miss on the way
 * starts it over at 10 minutes (RELEARN). A graduated item missed (a lapse) relearns: 10
 * minutes, then a day, then FSRS again. The card's step says where it is: 0 graduated (every
 * card from before the steps), k > 0 waiting at LEARN[k - 1], -k at LAPSE[k - 1]. The steps
 * only set when it's due: S and D take FSRS's formulas on every grade, as before (a review
 * an hour on, at a recall near 1, adds little to S).
 *
 * Version 2 added reviews.picked (onUpgrade: the column added, every row kept); version 3
 * cards.step (the column added, 0 for every card kept: graduated, as they were).
 */
final class Progress extends SQLiteOpenHelper {
    static final long DAY = 86_400_000L;
    static final double[] W = {0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367,
            1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755};
    static final double F = 19.0 / 81, C = -0.5;
    static final long RELEARN = 10 * 60_000L;   // an item you didn't know comes back this soon
    static final double MAX_S = 365, MATURE_D = 10, KNOWN = 0.5;
    /** The rows of the review log that are FSRS grades: reviews, checks and practice answers
     *  (all real answers), not a sentence read. */
    static final String GRADED = "(mode IS NULL OR mode <> 'read')";
    static final int PRACTICE = 20;       // items in a practice round
    static final int PRACTICE_POOL = 40;  // drawn at random from this many of the weakest
    static final int MORE_AFTER = 10;     // today's answers before a good day may learn past New per day
    static final double MORE_RIGHT = 0.9; // ... and how many of them right
    static final int VERSION = 3;
    /** A new item's steps: after a miss, after the check (or a miss's step) right, then the next day. */
    static final long[] LEARN = {RELEARN, 3_600_000L, DAY};
    /** A lapsed item's steps: after the miss, then the next day. */
    static final long[] LAPSE = {RELEARN, DAY};
    static final long CONFUSED = 60 * DAY;   // two confusions of a pair within this make it confusable
    static final long APART = 7 * DAY;       // a pair's "Tell them apart" card, at most once in this

    static class Card {
        double s, d;
        long reps, lapses, first, last, due, grade;
        /** Its learning step: 0 graduated (FSRS's interval), k > 0 at LEARN[k - 1], -k at LAPSE[k - 1]. */
        int step;

        Card copy() {
            Card c = new Card();
            c.s = s; c.d = d; c.reps = reps; c.lapses = lapses; c.first = first; c.last = last; c.due = due; c.grade = grade;
            c.step = step;
            return c;
        }

        boolean learning() { return step != 0; }
    }

    /** What undo needs: the card as it was (null: there wasn't one). */
    static class Undo {
        String h;
        Card before;
        boolean practice;   // a practice answer: the day's practice count goes back too
    }

    final Map<String, Card> cards = new HashMap<>();
    final Map<String, String> stories = new HashMap<>();
    int batch = 5, perDay = 15;
    boolean say = true;
    int remind = -1;                      // the daily reminder: minutes after midnight, -1 off
    int round = 40;                       // reviews per round (Settings), 0 all that are due
    String speed = "normal";              // the voice's speed (Settings): normal, slower
    boolean introDone;                    // home's first-run lines put away ("Got it", or a batch learnt)
    String todayDate = "";
    int todayNew = 0;
    JSONObject days = new JSONObject();   // {"2026-11-06": {"reviews": 12, "learnt": 5}}
    int sinceWords = Words.EVERY;         // character batches since the last word batch (Words): the first can be words
    JSONObject apartShown = new JSONObject();   // {"人|入": when its "Tell them apart" card was last shown}

    Progress(Context ctx) {
        super(ctx, "hanzi.db", null, VERSION);
        load();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cards (h TEXT PRIMARY KEY, s REAL, d REAL, reps INTEGER, lapses INTEGER,"
                + " first INTEGER, last INTEGER, due INTEGER, grade INTEGER, step INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE stories (h TEXT PRIMARY KEY, text TEXT, at INTEGER)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, h TEXT,"
                + " grade INTEGER, mode TEXT, ms INTEGER, r REAL, s REAL, d REAL, picked TEXT)");
        db.execSQL("CREATE INDEX reviews_h ON reviews (h)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // 1 → 2: what was tapped, beside each answer; the rows from before keep null
        if (from < 2) db.execSQL("ALTER TABLE reviews ADD COLUMN picked TEXT");
        // 2 → 3: each card's learning step; every card kept is graduated (0), as it was
        if (from < 3) db.execSQL("ALTER TABLE cards ADD COLUMN step INTEGER NOT NULL DEFAULT 0");
    }

    // ── the scheduler ──
    // The formulas are FSRS-4.5's; the weights they take are W, or the ones fitted to the learner (Fit).
    static double recall(Card c, long now) { return recall(c.s, Math.max(0, now - c.last) / (double) DAY); }

    /** The chance of recall after so many days at a stability. */
    static double recall(double s, double days) { return Math.pow(1 + F * days / Math.max(s, 0.01), C); }

    static double d0(double[] w, int g) { return Math.min(10, Math.max(1, w[4] - (g - 3) * w[5])); }

    static double nextD(double[] w, double d, int g) {
        d = d - w[6] * (g - 3);
        d = w[7] * d0(w, 3) + (1 - w[7]) * d;
        return Math.min(10, Math.max(1, d));
    }

    static double recallS(double[] w, double s, double d, double r, int g) {
        return s * (1 + Math.exp(w[8]) * (11 - d) * Math.pow(s, -w[9]) * (Math.exp(w[10] * (1 - r)) - 1) * (g == 2 ? w[15] : 1));
    }

    static double lapseS(double[] w, double s, double d, double r) {
        return Math.min(s, w[11] * Math.pow(d, -w[12]) * (Math.pow(s + 1, w[13]) - 1) * Math.exp(w[14] * (1 - r)));
    }

    // ── the weights fitted to the learner (Fit): meta "fsrs" ──
    /** The weights new grades use: W, or the fitted ones while they're in use. */
    double[] w = W;
    /** What the last fit said (meta "fsrs"; null: none yet): {w (only while in use), n, at,
     *  before, after (held-out log loss, defaults and fitted), why (when the defaults stayed),
     *  reset (true: put back by hand, not refitted until asked)}. */
    JSONObject fsrs;
    /** A fit running in the background (Fit.start), or null. */
    volatile Thread fitting;
    /** Times the progress was (re)loaded: a fit started before a copy came in isn't kept. */
    int loads;

    /** The weights a fit result names, or null when it doesn't name 17 usable ones. */
    static double[] weights(JSONObject f) {
        JSONArray a = f == null || f.optBoolean("reset") ? null : f.optJSONArray("w");
        if (a == null || a.length() != W.length) return null;
        double[] out = new double[W.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.optDouble(i, Double.NaN);
            if (Double.isNaN(out[i]) || Double.isInfinite(out[i])) return null;
        }
        return out;
    }

    /** A fit's result kept, and its weights in use if it has them. */
    void putFit(JSONObject f) {
        fsrs = f;
        double[] fw = weights(f);
        w = fw == null ? W : fw;
        putMeta("fsrs", f.toString());
    }

    /** No fit kept: the defaults, and the next round's end may fit again. */
    void clearFit() {
        fsrs = null;
        w = W;
        getWritableDatabase().delete("meta", "k = ?", new String[]{"fsrs"});
    }

    /** Back to the default weights by hand: kept so until "Fit to me" (Settings). */
    void resetFit(long now) {
        JSONObject f = fsrs == null ? new JSONObject() : fsrs;
        try { f.remove("w"); f.put("reset", true).put("at", now).put("why", "reset"); } catch (Exception e) { /* */ }
        putFit(f);
    }

    Undo grade(String h, int g, String how, long ms) { return grade(h, g, how, ms, null); }

    /** A grade, and the answer tapped for it (picked: a card, or a tone; null when none was). */
    Undo grade(String h, int g, String how, long ms, String picked) {
        long now = System.currentTimeMillis();
        Undo u = new Undo();
        u.h = h;
        Card c = cards.get(h);
        u.before = c == null ? null : c.copy();
        double r = 0;
        boolean met = c != null;
        if (c == null) {
            c = new Card();
            c.s = w[g - 1];
            c.d = d0(w, g);
            c.first = now;
            cards.put(h, c);
        } else {
            r = recall(c, now);
            if (g == 1) {
                c.s = Math.max(0.05, lapseS(w, c.s, c.d, r));
                c.lapses += 1;
            } else {
                c.s = Math.max(recallS(w, c.s, c.d, r, g), c.reps > 0 ? 0 : w[g - 1]);
            }
            c.d = nextD(w, c.d, g);
        }
        c.s = Math.min(c.s, MAX_S);
        c.reps += 1;
        c.last = now;
        c.grade = g;
        step(c, met, g, now);
        putCard(h, c);
        ContentValues v = new ContentValues();
        v.put("at", now); v.put("h", h); v.put("grade", g); v.put("mode", how); v.put("ms", ms);
        v.put("r", r); v.put("s", c.s); v.put("d", c.d); v.put("picked", picked);
        getWritableDatabase().insert("reviews", null, v);
        bumpDay("reviews", 1);
        if (how != null && how.startsWith("practice-")) bumpDay("practice", 1);
        u.practice = how != null && how.startsWith("practice-");
        return u;
    }

    /** Where a grade leaves the card on its steps, and when it's due: FSRS's interval once
     *  graduated, a step's while learning (the class's note). S is already updated. */
    static void step(Card c, boolean met, int g, long now) {
        int k = met ? c.step : 1;   // the check after learning: as if at the first step
        long[] steps = k < 0 ? LAPSE : LEARN;
        if (g == 1) {
            // a miss: a graduated card lapses, a learning one starts over; 10 minutes either way
            c.step = k > 0 ? 1 : -1;
            c.due = now + steps[0];
            return;
        }
        int at = Math.abs(k);   // the step just passed (1-based); 0: a graduated review
        if (at > 0 && at < steps.length) {
            c.step = k > 0 ? at + 1 : -(at + 1);
            c.due = now + steps[at];
            return;
        }
        // graduated, or past the last step: FSRS's interval, no sooner than the last step
        long ivl = (long) (c.s * DAY);
        c.due = now + (at > 0 ? Math.max(ivl, steps[steps.length - 1]) : ivl);
        c.step = 0;
    }

    void ungrade(Undo u) {
        if (u.before != null) { cards.put(u.h, u.before); putCard(u.h, u.before); }
        else { cards.remove(u.h); getWritableDatabase().delete("cards", "h = ?", new String[]{u.h}); }
        getWritableDatabase().execSQL("DELETE FROM reviews WHERE id = (SELECT MAX(id) FROM reviews WHERE " + GRADED + ")");
        bumpDay("reviews", -1);
        if (u.practice) bumpDay("practice", -1);
    }

    /** 0..1: 0 for an item never met, up with every review that holds, down as it goes overdue. */
    double strength(String h, long now) {
        Card c = cards.get(h);
        if (c == null) return 0;
        double fresh = Math.pow(Math.min(1, recall(c, now) / 0.9), 2);
        return (1 - Math.exp(-c.s / MATURE_D)) * fresh;
    }

    /** What's due, the most overdue for what it holds first; in a backlog, ordered for
     *  coming back (order). */
    List<String> due(long now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Card> e : cards.entrySet())
            if (e.getValue().due <= now && Words.onPath(e.getKey())) out.add(e.getKey());
        order(out, cards, now, round);
        return out;
    }

    // ── a gentle return: after days away, hundreds can be due at once ──
    static final int BACKLOG = 3;          // more rounds than this due: a backlog (home's line, the order)
    static final int PAUSE = 2;            // more rounds than this due: new items wait for a round
    static final double SLIPPING = 3;      // learned: a stability of at least this many days
    static final double FRESH = 1;         // new-ish: a stability under this many days

    /** The due items in the order a round takes them: the most overdue for what it holds
     *  first. In a backlog (more than BACKLOG rounds of round), first the learned items
     *  slipping (stability SLIPPING days or more, the lowest recall first: still worth
     *  saving, closest to gone), then the rest, the new-ish (under FRESH) last. Shared by
     *  due() and peek(), so the reminder and the widget read the same list. */
    static void order(List<String> out, Map<String, Card> cs, long now, int round) {
        Collections.sort(out, (a, b) -> {
            Card x = cs.get(a), y = cs.get(b);
            return Double.compare((now - y.last) / Math.max(y.s, 0.01), (now - x.last) / Math.max(x.s, 0.01));
        });
        if (!backlog(out.size(), round)) return;
        List<String> slipping = new ArrayList<>(), rest = new ArrayList<>(), fresh = new ArrayList<>();
        for (String h : out) {   // recall falls as (now - last) / s grows: the order above is lowest recall first
            double s = cs.get(h).s;
            (s >= SLIPPING ? slipping : s < FRESH ? fresh : rest).add(h);
        }
        out.clear();
        out.addAll(slipping);
        out.addAll(rest);
        out.addAll(fresh);
    }

    /** More than BACKLOG rounds due ("all" in a round is never a backlog). */
    static boolean backlog(int due, int round) { return round > 0 && due > BACKLOG * round; }

    boolean backlog(int due) { return backlog(due, round); }

    /** New items wait: more than PAUSE rounds due, and no round of reviews done today yet. */
    boolean pauseNew(int due) { return round > 0 && due > PAUSE * round && todayCount("reviews") < round; }

    /** Days since the last day with something done (0: today; -1: never). */
    int daysAway() {
        if (days.length() == 0) return -1;   // nothing logged (a fresh start, an old copy): no walk back through ten years
        Calendar c = Calendar.getInstance();
        for (int n = 0; n <= 3660; n++, c.add(Calendar.DATE, -1)) if (active(c)) return n;
        return -1;
    }

    List<String> nextNew(int n) {
        List<String> out = new ArrayList<>();
        for (Item it : PathData.items) {
            if (out.size() >= n) break;
            if (!cards.containsKey(it.h)) out.add(it.h);
        }
        return out;
    }

    // ── the day ──
    static String date(Calendar c) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(c.getTime()); }

    static String today() { return date(Calendar.getInstance()); }

    /** Learnt today: past New per day too, when a good day learnt more. */
    int newToday() { newLeftToday(); return todayNew; }

    /** Today's graded answers: {how many, how many right (2 or 3)}. */
    long[] todayAnswers() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        try (Cursor k = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(grade >= 2), 0) FROM reviews WHERE at >= ? AND " + GRADED,
                new String[]{String.valueOf(c.getTimeInMillis())})) {
            return k.moveToNext() ? new long[]{k.getLong(0), k.getLong(1)} : new long[2];
        }
    }

    /** A good day: at least MORE_AFTER answers today, MORE_RIGHT of them right. New per day
     *  is soft then: home offers a batch more (Home.learning). */
    boolean goodDay() {
        long[] a = todayAnswers();
        return a[0] >= MORE_AFTER && a[1] >= MORE_RIGHT * a[0];
    }

    int newLeftToday() {
        if (!today().equals(todayDate)) { todayDate = today(); todayNew = 0; }
        return Math.max(0, perDay - todayNew);
    }

    void learnt(int n) {
        newLeftToday();
        todayNew += n;
        putMeta("today", obj("d", todayDate, "n", todayNew).toString());
        bumpDay("learnt", n);
    }

    void bumpDay(String what, int n) {
        try {
            JSONObject d = days.optJSONObject(today());
            if (d == null) days.put(today(), d = new JSONObject());
            d.put(what, Math.max(0, d.optInt(what) + n));
            putMeta("days", days.toString());
        } catch (Exception e) { /* a count lost */ }
    }

    int todayCount(String what) {
        JSONObject d = days.optJSONObject(today());
        return d == null ? 0 : d.optInt(what);
    }

    /** Days in a row with something done, up to today (or yesterday, if today's still empty). */
    int streak() {
        Calendar c = Calendar.getInstance();
        if (!active(c)) c.add(Calendar.DATE, -1);
        int n = 0;
        while (active(c)) { n++; c.add(Calendar.DATE, -1); }
        return n;
    }

    boolean active(Calendar c) {
        JSONObject d = days.optJSONObject(date(c));
        return d != null && (d.optInt("reviews") > 0 || d.optInt("learnt") > 0 || d.optInt("practice") > 0);
    }

    // ── settings and stories ──
    void saveOpts() {
        try {
            putMeta("opts", opts().toString());
        } catch (Exception e) { /* unchanged */ }
    }

    /** The settings as they're kept (meta "opts"); a key missing from an older copy loads as its default. */
    JSONObject opts() throws org.json.JSONException {
        return new JSONObject().put("batch", batch).put("perDay", perDay).put("say", say).put("remind", remind)
                .put("round", round).put("intro", introDone).put("speed", speed);
    }

    /** How many reviews a round takes, of what's due. */
    int roundOf(int due) { return round <= 0 ? due : Math.min(round, due); }

    /** When the next review falls due after now (0: nothing is waiting). */
    long nextDue(long now) {
        long at = 0;
        for (Map.Entry<String, Card> e : cards.entrySet()) {
            long d = e.getValue().due;
            if (d > now && Words.onPath(e.getKey()) && (at == 0 || d < at)) at = d;
        }
        return at;
    }

    void story(String h, String text) {
        SQLiteDatabase db = getWritableDatabase();
        if (text == null || text.trim().isEmpty()) {
            stories.remove(h);
            db.delete("stories", "h = ?", new String[]{h});
            return;
        }
        stories.put(h, text.trim());
        ContentValues v = new ContentValues();
        v.put("h", h); v.put("text", text.trim()); v.put("at", System.currentTimeMillis());
        db.insertWithOnConflict("stories", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ── sentences read (Read): in the log beside the grades, mode "read", no grade ──
    /** A sentence read in a Read round, and how long it was looked at before Show (ms). */
    void read(String sentence, long ms) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis()); v.put("h", sentence); v.put("grade", 0); v.put("mode", "read"); v.put("ms", ms);
        v.put("r", 0); v.put("s", 0); v.put("d", 0);
        getWritableDatabase().insert("reviews", null, v);
        bumpDay("read", 1);
    }

    /** When each sentence was last read. */
    Map<String, Long> lastRead() {
        Map<String, Long> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT h, MAX(at) FROM reviews WHERE mode = 'read' GROUP BY h", null)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        }
        return out;
    }

    // ── practice (Quiz "practice"): met items asked again, graded as reviews are ──
    /** A practice round's items: PRACTICE drawn at random from the PRACTICE_POOL weakest, so
     *  one round after another isn't the same twenty. */
    List<String> practiceSet(long now, java.util.Random rnd) {
        List<String> pool = weakest(PRACTICE_POOL, now);
        Collections.shuffle(pool, rnd);
        return pool.size() > PRACTICE ? new ArrayList<>(pool.subList(0, PRACTICE)) : pool;
    }

    /** Up to n items met on the path, the weakest first: lowest strength, then most lapses,
     *  then the longest since they were seen. */
    List<String> weakest(int n, long now) {
        List<String> out = new ArrayList<>();
        final Map<String, Double> st = new HashMap<>();
        for (String h : cards.keySet()) if (Words.onPath(h)) { out.add(h); st.put(h, strength(h, now)); }
        Collections.sort(out, (a, b) -> {
            Card x = cards.get(a), y = cards.get(b);
            int c = Double.compare(st.get(a), st.get(b));
            if (c == 0) c = Long.compare(y.lapses, x.lapses);
            if (c == 0) c = Long.compare(x.last, y.last);
            return c;
        });
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** A practice answer logged without a grade, the card untouched: how practice was before
     *  its answers went through FSRS (Progress.grade, "practice-<kind>"); kept for the tests'
     *  old rows and the confusions. */
    void practised(String h, int g, String kind, long ms) { practised(h, g, kind, ms, null); }

    void practised(String h, int g, String kind, long ms, String picked) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis()); v.put("h", h); v.put("grade", g); v.put("mode", "practice-" + kind); v.put("ms", ms);
        v.put("r", 0); v.put("s", 0); v.put("d", 0); v.put("picked", picked);
        getWritableDatabase().insert("reviews", null, v);
        bumpDay("practice", 1);
    }

    /** How many sentences have been read: {all of them, those since a time}. */
    long[] readCounts(long since) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(at >= ?), 0) FROM reviews WHERE mode = 'read'",
                new String[]{String.valueOf(since)})) {
            return c.moveToNext() ? new long[]{c.getLong(0), c.getLong(1)} : new long[2];
        }
    }

    // ── confusions: a wrong answer that was another character (Quiz, Apart, Stats) ──
    /** Two characters taken for each other: a and b in the path's order, how often (either
     *  way round), and the last time. */
    static final class Pair {
        String a, b;
        int n;
        long last;

        String key() { return a + "|" + b; }
    }

    /** The log's confusions since a time: every wrong answer (graded or practice, not a tone,
     *  not a sentence) whose pick was another character or part on the path. */
    Map<String, Pair> confusions(long since) {
        Map<String, Pair> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT h, picked, at FROM reviews WHERE at >= ? AND grade = 1"
                + " AND picked IS NOT NULL AND picked <> h AND mode NOT LIKE '%tone' AND mode <> 'read'",
                new String[]{String.valueOf(since)})) {
            while (c.moveToNext()) {
                Item x = PathData.get(c.getString(0)), y = PathData.get(c.getString(1));
                if (x == null || y == null) continue;
                Item a = x.at < y.at ? x : y, b = a == x ? y : x;
                Pair p = out.get(a.h + "|" + b.h);
                if (p == null) { p = new Pair(); p.a = a.h; p.b = b.h; out.put(p.key(), p); }
                p.n++;
                p.last = Math.max(p.last, c.getLong(2));
            }
        }
        return out;
    }

    /** The confusable pairs: taken for each other twice or more within CONFUSED, the most
     *  confused first, then the latest. */
    List<Pair> pairs(long now) {
        List<Pair> out = new ArrayList<>();
        for (Pair p : confusions(now - CONFUSED).values()) if (p.n >= 2) out.add(p);
        Collections.sort(out, (x, y) -> x.n != y.n ? Integer.compare(y.n, x.n) : Long.compare(y.last, x.last));
        return out;
    }

    /** The characters h is confusable with, the most confused first. */
    List<String> confusables(String h, long now) {
        List<String> out = new ArrayList<>();
        for (Pair p : pairs(now)) {
            if (p.a.equals(h)) out.add(p.b);
            else if (p.b.equals(h)) out.add(p.a);
        }
        return out;
    }

    /** The pair a review round starts with: confusable, both met, its card not seen within
     *  APART. Null: none is due. */
    Pair apartDue(long now) {
        for (Pair p : pairs(now))
            if (cards.containsKey(p.a) && cards.containsKey(p.b) && now - apartShown.optLong(p.key(), 0) >= APART) return p;
        return null;
    }

    /** A pair's card seen: the week counts from now (meta "apart"). */
    void apartSeen(Pair p, long now) {
        try {
            apartShown.put(p.key(), now);
            putMeta("apart", apartShown.toString());
        } catch (Exception e) { /* it may come again sooner */ }
    }

    // ── the review log, read back (Stats) ──
    /** One grade as it was logged. */
    static final class Logged {
        long at, ms;
        String h, mode;
        int grade;
    }

    /** Every grade given since a time, oldest first. */
    List<Logged> reviewsSince(long since) {
        List<Logged> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at, h, grade, mode, ms FROM reviews WHERE at >= ? AND " + GRADED + " ORDER BY at, id",
                new String[]{String.valueOf(since)})) {
            while (c.moveToNext()) {
                Logged l = new Logged();
                l.at = c.getLong(0); l.h = c.getString(1); l.grade = c.getInt(2); l.mode = c.getString(3); l.ms = c.getLong(4);
                out.add(l);
            }
        }
        return out;
    }

    /** How many grades the log holds, and how many of them were right (2 or 3). */
    long[] reviewTotals() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), SUM(grade >= 2) FROM reviews WHERE " + GRADED, null)) {
            return c.moveToNext() ? new long[]{c.getLong(0), c.getLong(1)} : new long[2];
        }
    }

    /** The grades in the log, and the items they're of: the difference is the grades after
     *  an item's first, the ones a fit learns from. */
    long[] logCounts() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COUNT(DISTINCT h) FROM reviews WHERE " + GRADED, null)) {
            return c.moveToNext() ? new long[]{c.getLong(0), c.getLong(1)} : new long[2];
        }
    }

    // ── a look from outside the app (the reminder): the database opened read-only ──
    /** What the reminder and the widget need: the reminder's time (-1 off), what's due now on
     *  the path (most overdue first), and every item met (what's not met is still to learn). */
    static final class Peek {
        int remind = -1;
        final List<String> due = new ArrayList<>();
        final java.util.Set<String> met = new java.util.HashSet<>();
    }

    static Peek peek(Context ctx, long now) {
        Peek p = new Peek();
        java.io.File f = ctx.getDatabasePath("hanzi.db");
        if (!f.exists()) return p;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            int round = 40;   // reviews per round: what makes a backlog (order)
            try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k = 'opts'", null)) {
                if (c.moveToNext()) {
                    JSONObject o = new JSONObject(c.getString(0));
                    p.remind = o.optInt("remind", -1);
                    round = o.optInt("round", round);
                }
            }
            try (Cursor c = db.rawQuery("SELECT h FROM cards", null)) {
                while (c.moveToNext()) p.met.add(c.getString(0));
            }
            final Map<String, Card> late = new HashMap<>();
            try (Cursor c = db.rawQuery("SELECT h, s, last FROM cards WHERE due <= ?", new String[]{String.valueOf(now)})) {
                while (c.moveToNext())
                    if (Words.onPath(c.getString(0))) {
                        Card k = new Card();
                        k.s = c.getDouble(1);
                        k.last = c.getLong(2);
                        late.put(c.getString(0), k);
                    }
            }
            p.due.addAll(late.keySet());
            order(p.due, late, now, round);   // the order a round takes them, backlog or not
        } catch (Exception e) { /* unreadable: nothing to say */ }
        return p;
    }

    // ── the database ──
    void load() {
        cards.clear();
        stories.clear();
        round = 40;          // what a copy from before these settings doesn't say: their defaults
        speed = "normal";
        apartShown = new JSONObject();
        introDone = false;
        fsrs = null;
        w = W;
        loads++;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT h, s, d, reps, lapses, first, last, due, grade, step FROM cards", null)) {
            while (c.moveToNext()) {
                Card k = new Card();
                k.s = c.getDouble(1); k.d = c.getDouble(2); k.reps = c.getLong(3); k.lapses = c.getLong(4);
                k.first = c.getLong(5); k.last = c.getLong(6); k.due = c.getLong(7); k.grade = c.getLong(8);
                k.step = c.getInt(9);
                cards.put(c.getString(0), k);
            }
        }
        try (Cursor c = db.rawQuery("SELECT h, text FROM stories", null)) {
            while (c.moveToNext()) stories.put(c.getString(0), c.getString(1));
        }
        try (Cursor c = db.rawQuery("SELECT k, v FROM meta", null)) {
            while (c.moveToNext()) {
                try {
                    String k = c.getString(0);
                    if (k.equals("opts")) {
                        JSONObject o = new JSONObject(c.getString(1));
                        batch = o.optInt("batch", batch);
                        perDay = o.optInt("perDay", perDay);
                        say = o.optBoolean("say", say);
                        remind = o.optInt("remind", remind);
                        round = o.optInt("round", round);
                        introDone = o.optBoolean("intro", introDone);
                        speed = "slower".equals(o.optString("speed")) ? "slower" : "normal";
                    } else if (k.equals("today")) {
                        JSONObject o = new JSONObject(c.getString(1));
                        todayDate = o.optString("d");
                        todayNew = o.optInt("n");
                    } else if (k.equals("days")) {
                        days = new JSONObject(c.getString(1));
                    } else if (k.equals("apart")) {
                        apartShown = new JSONObject(c.getString(1));
                    } else if (k.equals("fsrs")) {
                        fsrs = new JSONObject(c.getString(1));
                        double[] fw = weights(fsrs);
                        w = fw == null ? W : fw;
                    } else if (k.equals("words")) {
                        sinceWords = new JSONObject(c.getString(1)).optInt("since", sinceWords);
                    }
                } catch (Exception e) { /* a bad value: its default */ }
            }
        }
    }

    void putCard(String h, Card c) {
        ContentValues v = new ContentValues();
        v.put("h", h); v.put("s", c.s); v.put("d", c.d); v.put("reps", c.reps); v.put("lapses", c.lapses);
        v.put("first", c.first); v.put("last", c.last); v.put("due", c.due); v.put("grade", c.grade);
        v.put("step", c.step);
        getWritableDatabase().insertWithOnConflict("cards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void putMeta(String k, String json) {
        ContentValues v = new ContentValues();
        v.put("k", k); v.put("v", json);
        getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ── a copy out and back in (Settings) ──
    /** Everything as text: {v: 2, cards, stories, meta, reviews}, as the web version wrote it;
     *  a review carries "picked" when it has one (a copy without it reads as null), a card its
     *  learning step (a copy without it reads as 0: graduated). */
    String export() throws Exception {
        JSONObject cs = new JSONObject(), st = new JSONObject(), meta = new JSONObject();
        for (Map.Entry<String, Card> e : cards.entrySet()) {
            Card c = e.getValue();
            cs.put(e.getKey(), new JSONObject().put("s", c.s).put("d", c.d).put("reps", c.reps).put("lapses", c.lapses)
                    .put("first", c.first).put("last", c.last).put("due", c.due).put("grade", c.grade).put("step", c.step));
        }
        for (Map.Entry<String, String> e : stories.entrySet()) st.put(e.getKey(), e.getValue());
        meta.put("opts", opts());
        meta.put("today", obj("d", todayDate, "n", todayNew));
        meta.put("days", days);
        meta.put("words", obj("since", sinceWords));
        meta.put("apart", apartShown);
        if (fsrs != null) meta.put("fsrs", fsrs);
        JSONArray rv = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at, h, grade, mode, ms, r, s, d, picked FROM reviews ORDER BY id", null)) {
            while (c.moveToNext()) rv.put(new JSONObject().put("at", c.getLong(0)).put("h", c.getString(1)).put("g", c.getLong(2))
                    .put("mode", c.getString(3)).put("ms", c.getLong(4)).put("r", c.getDouble(5)).put("s", c.getDouble(6)).put("d", c.getDouble(7))
                    .put("picked", c.isNull(8) ? null : c.getString(8)));   // null: no key (a row from before picked)
        }
        return new JSONObject().put("v", 2).put("cards", cs).put("stories", st).put("meta", meta).put("reviews", rv).toString();
    }

    /** The number of cards in a copy, or -1 when the text isn't one. */
    static int cardsIn(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (o.optInt("v") != 1 && o.optInt("v") != 2) return -1;
            return o.getJSONObject("cards").length();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Everything replaced by a copy, in one transaction (a v 1 copy has its settings at the top). */
    void replace(String text) throws Exception {
        JSONObject all = new JSONObject(text);
        JSONObject meta = all.optJSONObject("meta");
        if (meta == null) meta = new JSONObject().put("opts", all.optJSONObject("opts")).put("today", all.optJSONObject("today"));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String t : new String[]{"cards", "stories", "meta", "reviews"}) db.delete(t, null, null);
            JSONObject cs = all.getJSONObject("cards");
            for (Iterator<String> i = cs.keys(); i.hasNext(); ) {
                String h = i.next();
                JSONObject o = cs.getJSONObject(h);
                Card c = new Card();
                c.s = o.optDouble("s", 0); c.d = o.optDouble("d", 5); c.reps = o.optLong("reps"); c.lapses = o.optLong("lapses");
                c.first = o.optLong("first"); c.last = o.optLong("last"); c.due = o.optLong("due"); c.grade = o.optLong("grade");
                c.step = o.optInt("step");
                putCard(h, c);
            }
            JSONObject st = all.optJSONObject("stories");
            if (st != null) for (Iterator<String> i = st.keys(); i.hasNext(); ) {
                String h = i.next();
                ContentValues v = new ContentValues();
                v.put("h", h); v.put("text", st.optString(h)); v.put("at", System.currentTimeMillis());
                db.insert("stories", null, v);
            }
            for (Iterator<String> i = meta.keys(); i.hasNext(); ) {
                String k = i.next();
                Object v = meta.opt(k);
                if (v instanceof JSONObject) putMeta(k, v.toString());
            }
            JSONArray rv = all.optJSONArray("reviews");
            if (rv != null) for (int i = 0; i < rv.length(); i++) {
                JSONObject o = rv.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("at", o.optLong("at")); v.put("h", o.optString("h")); v.put("grade", o.optLong("g"));
                v.put("mode", o.optString("mode")); v.put("ms", o.optLong("ms"));
                v.put("r", o.optDouble("r", 0)); v.put("s", o.optDouble("s", 0)); v.put("d", o.optDouble("d", 0));
                if (o.has("picked")) v.put("picked", o.optString("picked")); else v.putNull("picked");
                db.insert("reviews", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        load();
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (Exception e) { /* */ }
        return o;
    }
}
