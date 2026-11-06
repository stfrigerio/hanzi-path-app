package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Locale;

/**
 * window.HanziApp: what the page asks of the phone. Its progress (Db), Android's Chinese
 * voice (the page's speechSynthesis isn't in a WebView), the bars' colour, and the updater's
 * token and state. Every call is synchronous from the page's side and quick.
 */
class Bridge {
    final Main main;
    final Db db;
    final SharedPreferences prefs;
    TextToSpeech tts;
    boolean ttsReady;

    Bridge(Main main) {
        this.main = main;
        this.db = new Db(main);
        this.prefs = main.getSharedPreferences("hanzi", Context.MODE_PRIVATE);
    }

    // ── progress ──
    @JavascriptInterface
    public String load() {
        try { return db.all().toString(); } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public String reviews() {
        try { return db.reviews().toString(); } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public void putCard(String h, String json) {
        try { db.putCard(db.getWritableDatabase(), h, new JSONObject(json)); } catch (Exception e) { /* bad card */ }
    }

    @JavascriptInterface
    public void delCard(String h) {
        db.getWritableDatabase().delete("cards", "h = ?", new String[]{h});
    }

    @JavascriptInterface
    public void putStory(String h, String text) { db.putStory(db.getWritableDatabase(), h, text); }

    @JavascriptInterface
    public void putMeta(String k, String json) { db.putMeta(db.getWritableDatabase(), k, json); }

    @JavascriptInterface
    public void addReview(String json) {
        try { db.addReview(db.getWritableDatabase(), new JSONObject(json)); } catch (Exception e) { /* bad review */ }
    }

    @JavascriptInterface
    public void popReview() {
        db.getWritableDatabase().execSQL("DELETE FROM reviews WHERE id = (SELECT MAX(id) FROM reviews)");
    }

    @JavascriptInterface
    public boolean replaceAll(String json) {
        try {
            db.replace(new JSONObject(json));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── the voice ──
    @JavascriptInterface
    public void speak(String text) {
        if (tts == null) {
            tts = new TextToSpeech(main, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    tts.setSpeechRate(0.85f);
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
                }
            });
            return;
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
    }

    // ── the window ──
    @JavascriptInterface
    public void setBars(String color, boolean dark) { main.bars(color, dark); }

    // ── updates ──
    @JavascriptInterface
    public String version() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", main.getPackageManager().getPackageInfo(main.getPackageName(), 0).getLongVersionCode());
            o.put("web", Web.version(main));
            o.put("token", !prefs.getString("token", "").isEmpty());
            o.put("checked", prefs.getLong("checked", 0));
            o.put("status", prefs.getString("status", ""));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void setToken(String token) {
        prefs.edit().putString("token", token == null ? "" : token.trim()).putLong("checked", 0).apply();
    }

    @JavascriptInterface
    public void checkUpdates() { Updater.maybe(main, true); }

    @JavascriptInterface
    public void reload() { main.reload(); }

    void close() {
        if (tts != null) tts.shutdown();
        db.close();
    }
}
