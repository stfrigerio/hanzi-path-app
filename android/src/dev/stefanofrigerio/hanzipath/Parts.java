package dev.stefanofrigerio.hanzipath;

import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The parts, from home: every part on the path (氵 亻 扌, the items of kind part) and every
 * character that is one (木 in 相, 口 in 和), most used first or fewest strokes first. A row:
 * the part large in its garden state (drawn from its strokes where the font has no such
 * form), its reading and meaning, "in 42 characters, 12 known", and the first of those
 * characters as a strip of the garden; "All 42" unfolds the strip to the whole family (every
 * character with the part anywhere in its tree, Tree.family). A tap on the row opens the part's
 * card, a cell its character's; back returns here, at the same place, in the same order.
 * The list comes PAGE rows at a time (Show more); the counts are worked out once (index).
 */
final class Parts {
    static final int PAGE = 80;    // rows built at a time

    /** A part and the characters (not parts) with it anywhere in their tree, in path order. */
    static final class Part {
        final Item it;
        final int[] chars;

        Part(Item it, int[] chars) {
            this.it = it;
            this.chars = chars;
        }
    }

    static List<Part> byUse, byStrokes;
    static int indexed = -1;

    /** Every part, counted once, in both orders: most used first (then fewer strokes, then path
     *  order), and fewest strokes first (then most used, then path order). */
    static synchronized void index() {
        if (byUse != null && indexed == PathData.items.size()) return;
        Set<String> used = new HashSet<>();
        for (Item it : PathData.items) for (String p : it.parts) if (!p.equals(it.h)) used.add(p);
        List<Part> all = new ArrayList<>();
        for (Item it : PathData.items) {
            if (!it.isPart() && !used.contains(it.h)) continue;
            List<Integer> fam = Tree.family(it.h);
            int n = 0;
            for (int i : fam) if (!PathData.items.get(i).isPart()) n++;
            int[] c = new int[n];
            n = 0;
            for (int i : fam) if (!PathData.items.get(i).isPart()) c[n++] = i;
            all.add(new Part(it, c));
        }
        List<Part> u = new ArrayList<>(all), s = new ArrayList<>(all);
        Collections.sort(u, (a, b) -> a.chars.length != b.chars.length ? b.chars.length - a.chars.length
                : a.it.strokes != b.it.strokes ? a.it.strokes - b.it.strokes : a.it.at - b.it.at);
        Collections.sort(s, (a, b) -> a.it.strokes != b.it.strokes ? a.it.strokes - b.it.strokes
                : a.chars.length != b.chars.length ? b.chars.length - a.chars.length : a.it.at - b.it.at);
        byUse = u;
        byStrokes = s;
        indexed = PathData.items.size();
    }

    static List<Part> list(boolean strokes) {
        index();
        return strokes ? byStrokes : byUse;
    }

    final Main m;
    boolean strokes;              // fewest strokes first; else most used
    int shown = PAGE;             // rows built
    int scrollY;                  // where the list was left (a card opened from it)
    final Set<String> open = new HashSet<>();   // parts whose family is unfolded
    int[] st;                     // the garden's states, as of the last show
    LinearLayout rows;
    TextView more;

    Parts(Main m) { this.m = m; }

    /** Where the list is scrolled, kept to come back to. */
    void keep() {
        if ("parts".equals(m.screen) && m.root.getChildCount() > 0 && m.root.getChildAt(0) instanceof ScrollView)
            scrollY = m.root.getChildAt(0).getScrollY();
    }

    /** A card from the list: the place kept, then the card (back returns here). */
    void lookup(String h) {
        keep();
        m.lookup(h);
    }

    void show() {
        LinearLayout col = m.page("parts", () -> { keep(); show(); });
        col.addView(Cards.bar(m, "THE PARTS", Ui.close(m, v -> m.back())));
        long now = System.currentTimeMillis();
        st = Levels.states(m.prog, now);
        for (int i = 0; i < st.length; i++) if (st[i] == GardenView.FAR) st[i] = GardenView.NEAR;   // a strip is read, not faint: ahead outlined
        List<Part> l = list(strokes);

        LinearLayout head = Ui.column(m, 12);
        TextView intro = Ui.text(m, "Characters are built from these. Learn a part once, recognise it everywhere.", 15, Ink.sans, Ink.ink);
        intro.setLineSpacing(0, 1.15f);
        head.addView(intro);
        int known = 0;
        for (Part p : l) if (st[p.it.at] == GardenView.KNOWN) known++;
        TextView sort = Ui.pill(m, sortLabel(), Ui.OUTLINE, v -> {
            strokes = !strokes;
            keep();
            show();
        });
        sort.setTypeface(Ink.serifBold);
        sort.setTextSize(14);
        sort.setMinHeight(Ink.dp(38));
        sort.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        sort.setContentDescription("Sorted " + (strokes ? "by fewest strokes" : "by how many characters use it") + ". Tap to sort "
                + (strokes ? "by how many characters use it" : "by fewest strokes"));
        head.addView(Ui.pair(m, 12, Home.small(m, l.size() + " parts, " + known + " known"), sort));
        Flow lg = new Flow(m, 12);
        lg.addView(Home.legend(m, Ink.hl, Ink.hl, "next"));
        lg.addView(Home.legend(m, Ink.young, Ink.young, "learning"));
        lg.addView(Home.legend(m, Ink.paper2, Ink.young, "wilting"));
        lg.addView(Home.legend(m, Ink.known, Ink.known, "known"));
        lg.addView(Home.legend(m, 0, Ink.rule, "ahead"));
        head.addView(lg);
        col.addView(head);

        rows = Ui.column(m, 0);
        int n = Math.min(shown, l.size());
        for (int i = 0; i < n; i++) rows.addView(row(l.get(i)), new LinearLayout.LayoutParams(-1, -2));
        col.addView(rows);

        more = Ui.pill(m, "", Ui.OUTLINE, v -> more());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(more, mp);
        moreLabel();

        // back from a card (or re-sorted, or a theme change): where it was
        final int y = scrollY;
        if (y > 0) {
            ScrollView sv = (ScrollView) m.root.getChildAt(0);
            sv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int a, int b, int c, int d, int e, int f, int g, int h) {
                    sv.removeOnLayoutChangeListener(this);
                    sv.scrollTo(0, y);
                }
            });
        }
    }

    String sortLabel() { return (strokes ? "fewest strokes" : "most used") + "  ›"; }

    /** The next page of rows, under the ones there. */
    void more() {
        List<Part> l = list(strokes);
        int from = rows.getChildCount(), to = Math.min(l.size(), from + PAGE);
        for (int i = from; i < to; i++) rows.addView(row(l.get(i)), new LinearLayout.LayoutParams(-1, -2));
        shown = to;
        moreLabel();
    }

    void moreLabel() {
        int left = list(strokes).size() - rows.getChildCount();
        more.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        more.setText("Show " + Math.min(PAGE, left) + " more (" + left + " left)");
    }

    /** "in 42 characters, 12 known" */
    String count(Part p) {
        int known = 0;
        for (int i : p.chars) if (st[i] == GardenView.KNOWN) known++;
        return "in " + p.chars.length + (p.chars.length == 1 ? " character, " : " characters, ") + known + " known";
    }

    /** How many cells the strip under a part's words holds: one row of the garden at its width. */
    int stripCells() { return GardenView.columns(m.contentWidth() - Ink.dp(SQUARE + 14)); }

    static final float SQUARE = 56;   // dp

    /** A part: its square, its reading, meaning and count, and the strip of what it builds. */
    View row(Part p) {
        Item it = p.it;
        String count = count(p);
        LinearLayout r = Ui.row(m, 14);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, Ink.dp(14), 0, Ink.dp(14));
        Ui.press(r, Home.topRule(Ink.grid));
        r.addView(square(it, st[it.at]));

        LinearLayout t = Ui.column(m, 3);
        TextView py = Ui.text(m, Tree.reading(it), 16, Ink.serifBold, Ink.ink);
        py.setMaxLines(1);
        t.addView(py);
        TextView en = Ui.text(m, it.en, 14, Ink.sans, Ink.ink);
        en.setMaxLines(2);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);

        int cells = stripCells();
        GardenView g = new GardenView(m);
        g.pick = p.chars;
        g.set(st);
        g.tap = i -> lookup(PathData.items.get(i).h);
        g.show(open.contains(it.h) ? Integer.MAX_VALUE : cells);
        g.setContentDescription("The characters with " + it.h + ": " + count);
        if (p.chars.length > cells) {
            TextView all = Ui.text(m, "", 13, Ink.sansMedium, Ink.ink);
            all.setGravity(Gravity.CENTER);
            all.setMinHeight(Ink.dp(32));
            all.setPadding(Ink.dp(12), 0, Ink.dp(12), 0);
            Ui.touch(all, Ink.surface, Ink.line, 999);
            Runnable label = () -> {
                boolean o = open.contains(it.h);
                all.setText(o ? "Fold" : "All " + p.chars.length + " ›");
                all.setContentDescription(o ? "Fold the characters with " + it.h : "All " + p.chars.length + " characters with " + it.h);
            };
            label.run();
            all.setOnClickListener(v -> {
                if (!open.remove(it.h)) open.add(it.h);
                g.show(open.contains(it.h) ? Integer.MAX_VALUE : cells);
                label.run();
            });
            t.addView(Ui.pair(m, 10, Home.small(m, count), all));
        } else {
            t.addView(Home.small(m, count));
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = Ink.dp(5);
        t.addView(g, gp);
        r.addView(t, Ui.weight(1));

        r.setOnClickListener(v -> lookup(it.h));
        r.setContentDescription(it.h + " " + it.reading() + " " + it.en + (it.isPart() ? ", a part" : "") + ", "
                + GardenView.stateName(st[it.at]) + ": " + count + ". Opens its card");
        r.setTag(it.h);   // the tests find a row by its part
        return r;
    }

    static final Paint HAS = new Paint();

    /** Whether the hanzi face draws h (some radical forms, ⺼ ⺀, it doesn't). */
    static boolean drawn(String h) {
        HAS.setTypeface(Ink.han);
        return HAS.hasGlyph(h);
    }

    /** The part large, in its garden state (ahead: outlined); from its strokes where the face
     *  has no glyph for it. */
    View square(Item it, int state) {
        int bg = 0, line = 0, fg;
        switch (state) {
            case GardenView.NEXT: bg = Ink.hl; fg = Ink.hlInk; break;
            case GardenView.YOUNG: bg = Ink.young; fg = Ink.paper; break;
            case GardenView.KNOWN: bg = Ink.known; fg = Ink.paper; break;
            case GardenView.WILT: bg = Ink.paper2; line = Ink.young; fg = Ink.young; break;
            default: line = Ink.rule; fg = Ink.ink;
        }
        int s = Ink.dp(SQUARE);
        Glyph gl = drawn(it.h) ? null : PathData.glyph(m, it.h);
        View v;
        if (gl == null) {
            TextView t = Ui.text(m, it.h, 0, Ink.han, fg);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 34);   // a square's size, not reading text
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            v = t;
        } else {
            HanziView hv = new HanziView(m);
            hv.bare = true;
            hv.pad = 0.16f;
            hv.inkP.setColor(fg);
            hv.setGlyph(gl);
            hv.show();
            v = hv;
        }
        v.setBackground(Ui.shape(bg, line, 0, false));
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** Home's way in, under the path: a row like Stats' and Settings'. */
    static View entry(Main m) {
        View e = Ui.entry(m, "The parts", null, v -> m.openParts());
        e.setContentDescription("The parts: what characters are built from, and how many each builds");
        return e;
    }
}
