package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Look it up: the path searched as you type. A character (水), its pinyin with or without
 * tones and spaces (shui, shuǐ, shui3; ni hao, nihao for a word's characters), or a word of
 * its meaning (water). Exact character first, then exact pinyin, a word spelled out, a word of
 * the meaning, then what only starts that way; path order inside each. A tap opens the item;
 * back comes here, the query kept.
 */
class Search {
    final Main m;
    String query = "";
    EditText field;
    LinearLayout results;

    static final int MAX = 50;

    Search(Main m) { this.m = m; }

    void show() {
        LinearLayout col = m.page("search", this::show);
        col.addView(Cards.bar(m, "SEARCH THE PATH", Ui.close(m, v -> m.home())));

        field = new EditText(m);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setSingleLine(true);
        field.setTypeface(Ink.sans);
        field.setTextSize(17);
        field.setTextColor(Ink.ink);
        field.setHintTextColor(Ink.ink2);
        android.graphics.drawable.GradientDrawable caret = Ui.shape(Ink.ink, 0, 0, false);
        caret.setSize(Math.max(2, Ink.dp(2)), -1);
        field.setTextCursorDrawable(caret);
        field.setHint("水, shui, water");
        field.setBackground(Ui.shape(Ink.paper2, Ink.ink, 999, false));
        field.setPadding(Ink.dp(18), 0, Ink.dp(18), 0);
        field.setContentDescription("Search the path");
        field.setText(query);
        field.setSelection(query.length());
        col.addView(field, new LinearLayout.LayoutParams(-1, Ink.dp(52)));

        results = Ui.column(m, 0);
        col.addView(results);
        list();

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                list();
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            List<Item> found = find(query);
            if (!found.isEmpty()) { hideKeyboard(); m.lookup(found.get(0).h); }
            return true;
        });
        keyboard(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        field.requestFocus();
        field.post(() -> {
            InputMethodManager im = (InputMethodManager) m.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Leaving: the keyboard down. */
    void hideKeyboard() {
        if (field == null) return;
        InputMethodManager im = (InputMethodManager) m.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(field.getWindowToken(), 0);
        keyboard(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
    }

    /** The window's keyboard state, its adjust mode as it was (the insets lift the page). */
    void keyboard(int state) {
        int was = m.getWindow().getAttributes().softInputMode;
        m.getWindow().setSoftInputMode((was & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) | state);
    }

    void list() {
        results.removeAllViews();
        String q = query.trim();
        if (q.isEmpty()) {
            TextView t = Ui.note(m, "A character, its pinyin with or without tones (shui, shuǐ, shui3), or a word of its meaning (water).");
            t.setPadding(0, Ink.dp(4), 0, 0);
            results.addView(t);
            return;
        }
        List<Item> found = find(q);
        if (found.isEmpty()) {
            TextView t = Ui.note(m, "Nothing on the path matches “" + q + "”.");
            t.setPadding(0, Ink.dp(4), 0, 0);
            results.addView(t);
            return;
        }
        LinearLayout head = Ui.section(m, "On the path", Home.small(m, found.size() == 1 ? "1 found" : found.size() + " found"));
        head.setPadding(0, head.getPaddingTop(), 0, Ink.dp(6));
        results.addView(head);   // the rows under it, a list: no gap, a faint rule between
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(MAX, found.size()); i++) results.addView(row(found.get(i), i > 0, now));
        if (found.size() > MAX) {
            TextView t = Ui.note(m, (found.size() - MAX) + " more: type a little more to narrow it.");
            t.setPadding(0, Ink.dp(12), 0, 0);
            results.addView(t);
        }
    }

    /** A result: the character, its reading in the tones' inks and its meaning, its state on the path. */
    View row(Item it, boolean rule, long now) {
        LinearLayout r = Ui.row(m, 14);
        r.setMinimumHeight(Ink.dp(64));
        r.setPadding(Ink.dp(4), Ink.dp(8), Ink.dp(4), Ink.dp(8));
        Ui.press(r, rule ? Home.topRule(Ink.grid) : null);
        TextView han = Ui.text(m, it.h, 30, Ink.han, Ink.ink);
        han.setGravity(Gravity.CENTER);
        han.setMinWidth(Ink.dp(44));
        r.addView(han);
        LinearLayout t = Ui.column(m, 2);
        if (it.py.length > 0) {
            android.text.SpannableStringBuilder b = Ui.pinyin(it.py[0]);
            for (int i = 1; i < it.py.length; i++) b.append(", ").append(Ui.pinyin(it.py[i]));
            t.addView(Ui.text(m, b, 15, Ink.monoMedium, Ink.ink));
        }
        TextView en = Ui.text(m, it.en, 14, Ink.sans, Ink.ink2);
        en.setMaxLines(2);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);
        r.addView(t, Ui.weight(1));
        String st = state(it, now);
        r.addView(swatch(st));
        r.setOnClickListener(v -> { hideKeyboard(); m.lookup(it.h); });
        r.setContentDescription(it.h + " " + it.reading() + " " + it.en + ", " + st);
        return r;
    }

    /** The state as the home's legend draws it. */
    View swatch(String st) {
        switch (st) {
            case "known": return Home.legend(m, Ink.known, Ink.known, st);
            case "learning": return Home.legend(m, Ink.young, Ink.young, st);
            case "wilting": return Home.legend(m, Ink.paper2, Ink.young, st);
            case "next": return Home.legend(m, Ink.hl, Ink.hl, st);
            default: return Home.legend(m, 0, Ink.rule, st);
        }
    }

    /** Where an item is for you, in the garden's words: known, learning, wilting, next, ahead. */
    String state(Item it, long now) {
        Progress p = m.prog;
        Progress.Card c = p.cards.get(it.h);
        if (c != null) return p.strength(it.h, now) >= Progress.KNOWN ? "known" : c.due <= now && c.reps > 1 ? "wilting" : "learning";
        return p.nextNew(p.batch).contains(it.h) ? "next" : "ahead";
    }

    // ---- the index, built once ----

    /** An item as search reads it: its readings as bare letters and tones, its meaning's words. */
    static final class Entry {
        Item it;
        String[] base;      // "shui", "hao": letters only, ü as v
        int[][] tones;      // per reading, a tone per syllable (5 neutral)
        String en;          // " water liquid ": the meaning's words, lowercased, spaced
    }

    static Entry[] index;
    static Map<String, List<String>> wordsByBase;   // "nihao" → its words' characters, 你好

    static synchronized void build() {
        if (index != null && index.length == PathData.items.size()) return;
        Entry[] e = new Entry[PathData.items.size()];
        Map<String, List<String>> w = new HashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < e.length; i++) {
            Item it = PathData.items.get(i);
            Entry x = new Entry();
            x.it = it;
            x.base = new String[it.py.length];
            x.tones = new int[it.py.length][];
            for (int j = 0; j < it.py.length; j++) {
                Py p = py(it.py[j], true);
                x.base[j] = p.base;
                x.tones[j] = p.tones;
            }
            x.en = " " + TextUtils.join(" ", it.en.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")).trim() + " ";
            e[i] = x;
            for (String[] word : it.words) {
                if (word[0].length() < 2 || !seen.add(word[0])) continue;
                Py p = py(word[1], true);
                List<String> l = w.get(p.base);
                if (l == null) w.put(p.base, l = new ArrayList<>());
                l.add(word[0] + "\t" + toneString(p.tones));
            }
        }
        index = e;
        wordsByBase = w;
    }

    static final class Py { String base; int[] tones; boolean latin; }

    /**
     * Pinyin as bare letters and its tones in order: marks (ǎ) and digits (a3) both, ü, u: and
     * v the same, spaces and apostrophes dropped. A target's syllables (split on spaces) each get
     * a tone, 5 when unmarked; a query gets only the tones it states. latin: nothing but pinyin in it.
     */
    static Py py(String s, boolean target) {
        Py out = new Py();
        String d = Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT).replace("u:", "v"), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder();
        List<Integer> t = new ArrayList<>();
        boolean latin = true;
        int sylTone = 0;
        boolean inSyl = false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c >= 'a' && c <= 'z') { b.append(c); inSyl = true; }
            else if (c == '̈' && b.length() > 0 && b.charAt(b.length() - 1) == 'u') b.setCharAt(b.length() - 1, 'v');
            else if (c == '̄') sylTone = 1;
            else if (c == '́') sylTone = 2;
            else if (c == '̌') sylTone = 3;
            else if (c == '̀') sylTone = 4;
            else if (c >= '0' && c <= '5' && !target) { t.add(c == '0' ? 5 : c - '0'); sylTone = 0; }
            else if (c == ' ' || c == '\'' || c == '’' || c == '-' || c == ',') {
                if (target && inSyl) t.add(sylTone == 0 ? 5 : sylTone);
                else if (!target && sylTone != 0) t.add(sylTone);
                sylTone = 0;
                inSyl = false;
            } else latin = false;
            if (!target && sylTone != 0 && c >= '̀' && c <= '̌') { t.add(sylTone); sylTone = 0; }
        }
        if (target && inSyl) t.add(sylTone == 0 ? 5 : sylTone);
        out.base = b.toString();
        out.tones = new int[t.size()];
        for (int i = 0; i < out.tones.length; i++) out.tones[i] = t.get(i);
        out.latin = latin && b.length() > 0;
        return out;
    }

    /** The tones a query states, in order, all among a reading's (a query may leave some out). */
    static boolean tonesFit(int[] asked, int[] have) {
        int j = 0;
        for (int a : asked) {
            while (j < have.length && have[j] != a) j++;
            if (j == have.length) return false;
            j++;
        }
        return true;
    }

    static String toneString(int[] t) {
        StringBuilder b = new StringBuilder();
        for (int x : t) b.append(x);
        return b.toString();
    }

    static int[] tonesOf(String s) {
        int[] t = new int[s.length()];
        for (int i = 0; i < t.length; i++) t[i] = s.charAt(i) - '0';
        return t;
    }

    static final int CHAR = 0, SYLLABLE = 1, WORD = 2, MEANING = 3, PREFIX = 4, NONE = 9;

    /** The items a query finds, best first: see the class comment for the order. */
    static List<Item> find(String query) {
        build();
        String q = query.trim();
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        // characters typed: each one on the path, in the order typed
        Set<Item> chars = new LinkedHashSet<>();
        for (int i = 0; i < q.length(); ) {
            int cp = q.codePointAt(i);
            Item it = PathData.get(new String(Character.toChars(cp)));
            if (it != null) chars.add(it);
            i += Character.charCount(cp);
        }
        out.addAll(chars);
        // a word spelled in pinyin: its characters, in the word's order
        Py p = py(q, false);
        Set<Item> word = new LinkedHashSet<>();
        if (p.latin) {
            List<String> ws = wordsByBase.get(p.base);
            if (ws != null) for (String w : ws) {
                String[] x = w.split("\t");
                if (!tonesFit(p.tones, tonesOf(x[1]))) continue;
                for (int i = 0; i < x[0].length(); i++) {
                    Item it = PathData.get(String.valueOf(x[0].charAt(i)));
                    if (it != null && !chars.contains(it)) word.add(it);
                }
            }
        }
        String mq = q.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        boolean meaning = !mq.isEmpty() && mq.matches("[\\p{L}\\p{N} ]+") && !mq.matches(".*\\d.*");
        int[] rank = new int[index.length];
        for (Entry e : index) {
            int r = NONE;
            if (chars.contains(e.it)) { rank[e.it.at] = NONE; continue; }
            if (p.latin) for (int j = 0; j < e.base.length; j++) {
                if (!tonesFit(p.tones, e.tones[j])) continue;
                if (e.base[j].equals(p.base)) r = Math.min(r, SYLLABLE);
                else if (e.base[j].startsWith(p.base)) r = Math.min(r, PREFIX);
            }
            if (meaning && !mq.isEmpty()) {
                if (e.en.contains(" " + mq + " ")) r = Math.min(r, MEANING);
                else if (e.en.contains(" " + mq)) r = Math.min(r, PREFIX);
            }
            rank[e.it.at] = r;
        }
        for (Entry e : index) if (rank[e.it.at] == SYLLABLE) out.add(e.it);
        for (Item it : word) if (rank[it.at] != SYLLABLE) out.add(it);
        for (int tier : new int[]{MEANING, PREFIX})
            for (Entry e : index) if (rank[e.it.at] == tier && !word.contains(e.it)) out.add(e.it);
        return out;
    }

    /** The home screen's way in: a quiet field-shaped row, a lens drawn in ink, that opens this. */
    static View entry(Main m) {
        LinearLayout r = Ui.row(m, 10);
        r.setMinimumHeight(Ink.dp(44));
        r.setPadding(Ink.dp(16), 0, Ink.dp(18), 0);
        r.setBackground(Ui.shape(Ink.paper2, Ink.rule, 999, false));
        Ui.touch(r, Ink.paper2, Ink.rule, 999);
        r.addView(lens(m));
        r.addView(Ui.text(m, "Look it up: 水, shui, water", 15, Ink.sans, Ink.ink2), Ui.weight(1));
        r.setContentDescription("Search the path");
        r.setOnClickListener(v -> m.openSearch());
        return r;
    }

    /** A magnifying glass: a ring and a handle, in the muted ink (no emoji). */
    static View lens(Context c) {
        View v = new View(c) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas k) {
                float u = getWidth() / 24f;
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(2 * u);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setColor(Ink.ink2);
                k.drawCircle(10 * u, 10 * u, 6.5f * u, p);
                k.drawLine(15 * u, 15 * u, 20.5f * u, 20.5f * u, p);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(Ink.dp(18), Ink.dp(18)));
        return v;
    }

    static List<String> hs(List<Item> l) {
        List<String> out = new ArrayList<>();
        for (Item it : l) out.add(it.h);
        return out;
    }

    static String show(List<Item> l) { return Arrays.toString(hs(l).toArray()); }
}
