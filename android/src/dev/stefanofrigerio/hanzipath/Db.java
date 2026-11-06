package dev.stefanofrigerio.hanzipath;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * The progress, in SQLite (databases/hanzi.db, kept by Android's backup):
 *   cards    one row an item met: FSRS's stability s (days) and difficulty d, and when
 *   stories  what you wrote for an item
 *   meta     settings and small state, each a JSON value (opts, today, days)
 *   reviews  every grade given, as it was given: for a later fit of FSRS's weights to you
 * The page keeps a copy in memory and writes each change through here (Bridge).
 */
class Db extends SQLiteOpenHelper {
    static final String[] CARD = {"s", "d", "reps", "lapses", "first", "last", "due", "grade"};

    Db(Context ctx) { super(ctx, "hanzi.db", null, 1); }

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

    JSONObject all() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject out = new JSONObject(), cards = new JSONObject(), stories = new JSONObject(), meta = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT h, s, d, reps, lapses, first, last, due, grade FROM cards", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("s", c.getDouble(1)).put("d", c.getDouble(2)).put("reps", c.getLong(3)).put("lapses", c.getLong(4))
                        .put("first", c.getLong(5)).put("last", c.getLong(6)).put("due", c.getLong(7)).put("grade", c.getLong(8));
                cards.put(c.getString(0), o);
            }
        }
        try (Cursor c = db.rawQuery("SELECT h, text FROM stories", null)) {
            while (c.moveToNext()) stories.put(c.getString(0), c.getString(1));
        }
        try (Cursor c = db.rawQuery("SELECT k, v FROM meta", null)) {
            while (c.moveToNext()) meta.put(c.getString(0), new JSONTokenerValue(c.getString(1)).value());
        }
        return out.put("cards", cards).put("stories", stories).put("meta", meta);
    }

    JSONArray reviews() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at, h, grade, mode, ms, r, s, d FROM reviews ORDER BY id", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("at", c.getLong(0)).put("h", c.getString(1)).put("g", c.getLong(2)).put("mode", c.getString(3))
                        .put("ms", c.getLong(4)).put("r", c.getDouble(5)).put("s", c.getDouble(6)).put("d", c.getDouble(7));
                out.put(o);
            }
        }
        return out;
    }

    void putCard(SQLiteDatabase db, String h, JSONObject o) {
        ContentValues v = new ContentValues();
        v.put("h", h);
        v.put("s", o.optDouble("s", 0));
        v.put("d", o.optDouble("d", 5));
        for (String k : new String[]{"reps", "lapses", "first", "last", "due", "grade"}) v.put(k, o.optLong(k, 0));
        db.insertWithOnConflict("cards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void putStory(SQLiteDatabase db, String h, String text) {
        if (text == null || text.trim().isEmpty()) {
            db.delete("stories", "h = ?", new String[]{h});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("h", h);
        v.put("text", text);
        v.put("at", System.currentTimeMillis());
        db.insertWithOnConflict("stories", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void putMeta(SQLiteDatabase db, String k, String json) {
        ContentValues v = new ContentValues();
        v.put("k", k);
        v.put("v", json);
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void addReview(SQLiteDatabase db, JSONObject o) {
        ContentValues v = new ContentValues();
        v.put("at", o.optLong("at"));
        v.put("h", o.optString("h"));
        v.put("grade", o.optLong("g"));
        v.put("mode", o.optString("mode"));
        v.put("ms", o.optLong("ms"));
        v.put("r", o.optDouble("r", 0));
        v.put("s", o.optDouble("s", 0));
        v.put("d", o.optDouble("d", 0));
        db.insert("reviews", null, v);
    }

    /** Everything replaced by a copy (Settings → Load pasted), in one transaction. */
    void replace(JSONObject all) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String t : new String[]{"cards", "stories", "meta", "reviews"}) db.delete(t, null, null);
            JSONObject cards = all.optJSONObject("cards");
            if (cards != null) for (Iterator<String> i = cards.keys(); i.hasNext(); ) {
                String h = i.next();
                putCard(db, h, cards.getJSONObject(h));
            }
            JSONObject stories = all.optJSONObject("stories");
            if (stories != null) for (Iterator<String> i = stories.keys(); i.hasNext(); ) {
                String h = i.next();
                putStory(db, h, stories.optString(h));
            }
            JSONObject meta = all.optJSONObject("meta");
            if (meta != null) for (Iterator<String> i = meta.keys(); i.hasNext(); ) {
                String k = i.next();
                Object v = meta.get(k);
                putMeta(db, k, v instanceof String ? JSONObject.quote((String) v) : v.toString());
            }
            JSONArray reviews = all.optJSONArray("reviews");
            if (reviews != null) for (int i = 0; i < reviews.length(); i++) addReview(db, reviews.getJSONObject(i));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A JSON value of any kind (object, array, string, number), as org.json reads one. */
    static class JSONTokenerValue {
        final String text;

        JSONTokenerValue(String text) { this.text = text; }

        Object value() {
            try {
                return new org.json.JSONTokener(text).nextValue();
            } catch (JSONException e) {
                return JSONObject.NULL;
            }
        }
    }
}
