package dev.stefanofrigerio.hanzipath;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The path (assets/path.json, made by tools/build.py): every item in the order it's taught.
 * Stroke shapes are in assets/strokes-N.json, CHUNK items each, read when first needed.
 */
final class PathData {
    static final List<Item> items = new ArrayList<>();
    static final Map<String, Item> at = new HashMap<>();
    static int chunk = 60;
    static final Map<Integer, JSONObject> chunks = new HashMap<>();
    static final Map<String, Glyph> glyphs = new HashMap<>();

    private PathData() {}

    static synchronized void load(Context ctx) throws Exception {
        if (!items.isEmpty()) return;
        JSONObject p = new JSONObject(read(ctx, "path.json"));
        chunk = p.getInt("chunk");
        JSONArray a = p.getJSONArray("items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Item it = new Item();
            it.h = o.getString("h");
            it.kind = o.getString("k");
            it.en = o.optString("en");
            it.py = strings(o.optJSONArray("py"));
            it.parts = strings(o.optJSONArray("parts"));
            it.used = strings(o.optJSONArray("used"));
            it.strokes = o.optInt("n");
            JSONArray w = o.optJSONArray("w");
            it.words = new String[w == null ? 0 : w.length()][];
            for (int j = 0; j < it.words.length; j++) {
                JSONArray x = w.getJSONArray(j);
                it.words[j] = new String[]{x.getString(0), x.getString(1), x.getString(2), String.valueOf(x.getInt(3))};
            }
            JSONObject et = o.optJSONObject("et");
            if (et != null) {
                it.etType = et.optString("type", null);
                it.etSemantic = et.optString("semantic", null);
                it.etPhonetic = et.optString("phonetic", null);
                it.why = et.optString("why", null);
            }
            it.at = i;
            items.add(it);
            at.put(it.h, it);
        }
    }

    static Item get(String h) { return at.get(h); }

    /** The stroke shapes of an item, read from its chunk the first time. */
    static synchronized Glyph glyph(Context ctx, String h) {
        Glyph g = glyphs.get(h);
        if (g != null) return g;
        Item it = at.get(h);
        if (it == null) return null;
        int n = it.at / chunk;
        try {
            JSONObject c = chunks.get(n);
            if (c == null) chunks.put(n, c = new JSONObject(read(ctx, "strokes-" + n + ".json")));
            JSONObject o = c.optJSONObject(h);
            if (o == null) return null;
            g = new Glyph(o.getJSONArray("strokes"), o.getJSONArray("medians"));
            glyphs.put(h, g);
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    static String[] strings(JSONArray a) {
        if (a == null) return new String[0];
        String[] s = new String[a.length()];
        for (int i = 0; i < s.length; i++) s[i] = a.optString(i);
        return s;
    }

    static String read(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) { return Updater.text(in); }
    }
}
