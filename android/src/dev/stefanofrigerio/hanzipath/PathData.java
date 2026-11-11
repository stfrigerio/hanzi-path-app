package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The path (assets/path.json, made by tools/build.py): every item in the order it's taught.
 * Read once, streamed (JsonReader: no 600 KB string, no tree of objects), before the first
 * frame, since home draws all of it (Main starts it on a thread of its own, beside the
 * progress). Stroke shapes are in assets/strokes-N.json, CHUNK items each, read when first
 * needed.
 */
final class PathData {
    static final List<Item> items = new ArrayList<>();
    static final Map<String, Item> at = new HashMap<>();
    static int chunk = 60;
    static final Map<Integer, JSONObject> chunks = new HashMap<>();
    static final Map<String, Glyph> glyphs = new HashMap<>();
    static final String[] NONE = new String[0];
    static final String[][] NO_WORDS = new String[0][];

    private PathData() {}

    static synchronized void load(Context ctx) throws Exception {
        if (!items.isEmpty()) return;
        List<Item> read = new ArrayList<>(2400);
        int ch = chunk;
        try (JsonReader r = reader(ctx, "path.json")) {
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (k.equals("chunk")) ch = r.nextInt();
                else if (k.equals("items")) {
                    r.beginArray();
                    while (r.hasNext()) read.add(item(r));
                    r.endArray();
                } else r.skipValue();
            }
            r.endObject();
        }
        chunk = ch;
        for (int i = 0; i < read.size(); i++) {
            Item it = read.get(i);
            it.at = i;
            items.add(it);
            at.put(it.h, it);
        }
    }

    /** One item, as path.json has it: {h, k, py, en, parts, n, et, w, used}. */
    static Item item(JsonReader r) throws IOException {
        Item it = new Item();
        it.en = "";
        it.py = it.parts = it.used = NONE;
        it.words = NO_WORDS;
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
            switch (k) {
                case "h": it.h = r.nextString(); break;
                case "k": it.kind = r.nextString(); break;
                case "en": it.en = r.nextString(); break;
                case "py": it.py = strings(r); break;
                case "parts": it.parts = strings(r); break;
                case "used": it.used = strings(r); break;
                case "n": it.strokes = r.nextInt(); break;
                case "w": {
                    List<String[]> w = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) {
                        r.beginArray();
                        String[] x = {r.nextString(), r.nextString(), r.nextString(), String.valueOf(r.nextInt()), "0"};
                        if (r.hasNext()) x[4] = String.valueOf(r.nextInt());   // 1: one of the extra words
                        while (r.hasNext()) r.skipValue();
                        r.endArray();
                        w.add(x);
                    }
                    r.endArray();
                    it.words = w.toArray(NO_WORDS);
                    break;
                }
                case "et": {
                    r.beginObject();
                    while (r.hasNext()) {
                        String e = r.nextName();
                        if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
                        switch (e) {
                            case "type": it.etType = r.nextString(); break;
                            case "semantic": it.etSemantic = r.nextString(); break;
                            case "phonetic": it.etPhonetic = r.nextString(); break;
                            case "why": it.why = r.nextString(); break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    break;
                }
                default: r.skipValue();
            }
        }
        r.endObject();
        return it;
    }

    static String[] strings(JsonReader r) throws IOException {
        List<String> out = new ArrayList<>(4);
        r.beginArray();
        while (r.hasNext()) out.add(r.nextString());
        r.endArray();
        return out.isEmpty() ? NONE : out.toArray(NONE);
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
        try (InputStream in = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** An asset as a stream of JSON, read as it's parsed. */
    static JsonReader reader(Context ctx, String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        return new JsonReader(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536));
    }
}
