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
 * weights): a stability S in days, the time after which the item would be recalled nine
 * times out of ten, and a difficulty D, 1-10. Grades are the garden's three: 1 again,
 * 2 unsure, 3 knew. Learning an item isn't a grade: the round after a batch is.
 *
 * Kept in SQLite (databases/hanzi.db, in Android's backup), held in memory and written
 * through on every change:
 *   cards    one row an item met, or a word (keyed by the word: Words)
 *   stories  what you wrote for an item
 *   meta     opts, today, days: settings and small state, each a JSON value
 *   reviews  every grade given, as given (for fitting FSRS's weights to you later); beside
 *            them, sentences read (mode "read") and practice answers (mode "practice-<kind>"),
 *            neither of them a grade: GRADED leaves them out
 */
final class Progress extends SQLiteOpenHelper {
    static final long DAY = 86_400_000L;
    static final double[] W = {0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367,
            1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755};
    static final double F = 19.0 / 81, C = -0.5;
    static final long RELEARN = 10 * 60_000L;   // an item you didn't know comes back this soon
    static final double MAX_S = 365, MATURE_D = 10, KNOWN = 0.5;
    /** The rows of the review log that are FSRS grades: not a sentence read, not a practice answer. */
    static final String GRADED = "(mode IS NULL OR (mode <> 'read' AND mode NOT LIKE 'practice-%'))";
    static final int PRACTICE = 20;       // items in a practice round

    static class Card {
        double s, d;
        long reps, lapses, first, last, due, grade;

        Card copy() {
            Card c = new Card();
            c.s = s; c.d = d; c.reps = reps; c.lapses = lapses; c.first = first; c.last = last; c.due = due; c.grade = grade;
            return c;
        }
    }

    /** What undo needs: the card as it was (null: there wasn't one). */
    static class Undo {
        String h;
        Card before;
    }

    final Map<String, Card> cards = new HashMap<>();
    final Map<String, String> stories = new HashMap<>();
    int batch = 5, perDay = 15;
    boolean say = true;
    int remind = -1;                      // the daily reminder: minutes after midnight, -1 off
    int round = 40;                       // reviews per round (Settings), 0 all that are due
    boolean introDone;                    // home's first-run lines put away ("Got it", or a batch learnt)
    String todayDate = "";
    int todayNew = 0;
    JSONObject days = new JSONObject();   // {"2026-11-06": {"reviews": 12, "learnt": 5}}
    int sinceWords = Words.EVERY;         // character batches since the last word batch (Words): the first can be words

    Progress(Context ctx) {
        super(ctx, "hanzi.db", null, 1);
        load();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cards (h TEXT PRIMARY KEY, s REAL, d REAL, reps INTEGER, lapses INTEGER,"
                + " first INTEGER, last INTEGER, due INTEGER, grade INTEGER)");
        db.execSQL("CREATE TABLE stories (h TEXT PRIMARY KEY, text TEXT, at INTEGER)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, h TEXT,"
                + " grade INTEGER, mode TEXT, ms INTEGER, r REAL, s REAL, d REAL)");
        db.execSQL("CREATE INDEX reviews_h ON reviews (h)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) { /* version 1 */ }

    // ── the scheduler ──
    static double recall(Card c, long now) {
        return Math.pow(1 + F * Math.max(0, now - c.last) / (double) DAY / Math.max(c.s, 0.01), C);
    }

    static double d0(int g) { return Math.min(10, Math.max(1, W[4] - (g - 3) * W[5])); }

    static double nextD(double d, int g) {
        d = d - W[6] * (g - 3);
        d = W[7] * d0(3) + (1 - W[7]) * d;
        return Math.min(10, Math.max(1, d));
    }

    static double recallS(double s, double d, double r, int g) {
        return s * (1 + Math.exp(W[8]) * (11 - d) * Math.pow(s, -W[9]) * (Math.exp(W[10] * (1 - r)) - 1) * (g == 2 ? W[15] : 1));
    }

    static double lapseS(double s, double d, double r) {
        return Math.min(s, W[11] * Math.pow(d, -W[12]) * (Math.pow(s + 1, W[13]) - 1) * Math.exp(W[14] * (1 - r)));
    }

    Undo grade(String h, int g, String how, long ms) {
        long now = System.currentTimeMillis();
        Undo u = new Undo();
        u.h = h;
        Card c = cards.get(h);
        u.before = c == null ? null : c.copy();
        double r = 0;
        if (c == null) {
            c = new Card();
            c.s = W[g - 1];
            c.d = d0(g);
            c.first = now;
            cards.put(h, c);
        } else {
            r = recall(c, now);
            if (g == 1) {
                c.s = Math.max(0.05, lapseS(c.s, c.d, r));
                c.lapses += 1;
            } else {
                c.s = Math.max(recallS(c.s, c.d, r, g), c.reps > 0 ? 0 : W[g - 1]);
            }
            c.d = nextD(c.d, g);
        }
        c.s = Math.min(c.s, MAX_S);
        c.reps += 1;
        c.last = now;
        c.grade = g;
        c.due = now + (g == 1 ? RELEARN : (long) (c.s * DAY));
        putCard(h, c);
        ContentValues v = new ContentValues();
        v.put("at", now); v.put("h", h); v.put("grade", g); v.put("mode", how); v.put("ms", ms);
        v.put("r", r); v.put("s", c.s); v.put("d", c.d);
        getWritableDatabase().insert("reviews", null, v);
        bumpDay("reviews", 1);
        return u;
    }

    void ungrade(Undo u) {
        if (u.before != null) { cards.put(u.h, u.before); putCard(u.h, u.before); }
        else { cards.remove(u.h); getWritableDatabase().delete("cards", "h = ?", new String[]{u.h}); }
        getWritableDatabase().execSQL("DELETE FROM reviews WHERE id = (SELECT MAX(id) FROM reviews WHERE " + GRADED + ")");
        bumpDay("reviews", -1);
    }

    /** 0..1: 0 for an item never met, up with every review that holds, down as it goes overdue. */
    double strength(String h, long now) {
        Card c = cards.get(h);
        if (c == null) return 0;
        double fresh = Math.pow(Math.min(1, recall(c, now) / 0.9), 2);
        return (1 - Math.exp(-c.s / MATURE_D)) * fresh;
    }

    /** What's due, the most overdue for what it holds first. */
    List<String> due(long now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Card> e : cards.entrySet())
            if (e.getValue().due <= now && Words.onPath(e.getKey())) out.add(e.getKey());
        Collections.sort(out, (a, b) -> {
            Card x = cards.get(a), y = cards.get(b);
            return Double.compare((now - y.last) / y.s, (now - x.last) / x.s);
        });
        return out;
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
                .put("round", round).put("intro", introDone);
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

    // ── practice (Quiz "practice"): met items asked again, logged, never graded ──
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

    /** A practice answer, in the log as "practice-<kind>" with its grade, the card untouched. */
    void practised(String h, int g, String kind, long ms) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis()); v.put("h", h); v.put("grade", g); v.put("mode", "practice-" + kind); v.put("ms", ms);
        v.put("r", 0); v.put("s", 0); v.put("d", 0);
        getWritableDatabase().insert("reviews", null, v);
        bumpDay("practice", 1);
    }

    /** The last practice answer taken back out of the log. */
    void unpractised() {
        getWritableDatabase().execSQL("DELETE FROM reviews WHERE id = (SELECT MAX(id) FROM reviews WHERE mode LIKE 'practice-%')");
        bumpDay("practice", -1);
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
            try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k = 'opts'", null)) {
                if (c.moveToNext()) p.remind = new JSONObject(c.getString(0)).optInt("remind", -1);
            }
            try (Cursor c = db.rawQuery("SELECT h FROM cards", null)) {
                while (c.moveToNext()) p.met.add(c.getString(0));
            }
            final Map<String, Double> late = new HashMap<>();
            try (Cursor c = db.rawQuery("SELECT h, s, last FROM cards WHERE due <= ?", new String[]{String.valueOf(now)})) {
                while (c.moveToNext())
                    if (Words.onPath(c.getString(0)))
                        late.put(c.getString(0), (now - c.getLong(2)) / Math.max(c.getDouble(1), 0.01));
            }
            p.due.addAll(late.keySet());
            Collections.sort(p.due, (a, b) -> Double.compare(late.get(b), late.get(a)));
        } catch (Exception e) { /* unreadable: nothing to say */ }
        return p;
    }

    // ── the database ──
    void load() {
        cards.clear();
        stories.clear();
        round = 40;          // what a copy from before these settings doesn't say: their defaults
        introDone = false;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT h, s, d, reps, lapses, first, last, due, grade FROM cards", null)) {
            while (c.moveToNext()) {
                Card k = new Card();
                k.s = c.getDouble(1); k.d = c.getDouble(2); k.reps = c.getLong(3); k.lapses = c.getLong(4);
                k.first = c.getLong(5); k.last = c.getLong(6); k.due = c.getLong(7); k.grade = c.getLong(8);
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
                    } else if (k.equals("today")) {
                        JSONObject o = new JSONObject(c.getString(1));
                        todayDate = o.optString("d");
                        todayNew = o.optInt("n");
                    } else if (k.equals("days")) {
                        days = new JSONObject(c.getString(1));
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
        getWritableDatabase().insertWithOnConflict("cards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void putMeta(String k, String json) {
        ContentValues v = new ContentValues();
        v.put("k", k); v.put("v", json);
        getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ── a copy out and back in (Settings) ──
    /** Everything as text: {v: 2, cards, stories, meta, reviews}, as the web version wrote it. */
    String export() throws Exception {
        JSONObject cs = new JSONObject(), st = new JSONObject(), meta = new JSONObject();
        for (Map.Entry<String, Card> e : cards.entrySet()) {
            Card c = e.getValue();
            cs.put(e.getKey(), new JSONObject().put("s", c.s).put("d", c.d).put("reps", c.reps).put("lapses", c.lapses)
                    .put("first", c.first).put("last", c.last).put("due", c.due).put("grade", c.grade));
        }
        for (Map.Entry<String, String> e : stories.entrySet()) st.put(e.getKey(), e.getValue());
        meta.put("opts", opts());
        meta.put("today", obj("d", todayDate, "n", todayNew));
        meta.put("days", days);
        meta.put("words", obj("since", sinceWords));
        JSONArray rv = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at, h, grade, mode, ms, r, s, d FROM reviews ORDER BY id", null)) {
            while (c.moveToNext()) rv.put(new JSONObject().put("at", c.getLong(0)).put("h", c.getString(1)).put("g", c.getLong(2))
                    .put("mode", c.getString(3)).put("ms", c.getLong(4)).put("r", c.getDouble(5)).put("s", c.getDouble(6)).put("d", c.getDouble(7)));
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
