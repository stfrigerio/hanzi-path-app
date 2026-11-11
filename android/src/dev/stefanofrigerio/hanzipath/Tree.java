package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The family tree, from a card's "Made of": the character at the top, its parts under it,
 * theirs under them, down to parts with nothing in them, joined by hairlines; each node in
 * the garden's state, a tap opens it. Under the tree, for each of its parts, the family: every
 * item on the path with that part anywhere in its tree, as a small garden ("The 氵 family").
 * Back (or ×) returns to the card it was opened from.
 */
class Tree {
    static final int FOLD_ROWS = 3;            // a family's rows shown before "Show all"
    static final float ROOT = 60, CELL = 34, ELBOW = 12;   // dp: the root's square, a node's, the stub to it

    final Main m;
    final String h;
    final Runnable back;         // the card it was opened from, built again
    final Learn wasLearn;
    final Tree wasTree;
    final Set<String> unfolded = new HashSet<>();

    Tree(Main m, String h) {
        this.m = m;
        this.h = h;
        this.back = m.redraw;
        this.wasLearn = m.learn;
        this.wasTree = m.tree;
    }

    static void open(Main m, String h) {
        Tree t = new Tree(m, h);
        m.tree = t;
        t.show();
    }

    /** Back to the card, as it was. */
    void leave() {
        m.learn = wasLearn;
        m.tree = wasTree;
        if (back != null) back.run();
        else m.home();
    }

    // ---- the tree ----

    /** A node: where it sits (level 0 the character), whether it's its parent's last part,
     *  whether it has parts under it, and for each level above whether that line goes on. */
    static final class Node {
        String h;
        int level;
        boolean last, kids;
        boolean[] cont;
    }

    /** The tree in reading order: the character, then each part followed by its own parts.
     *  A part already above it on its branch isn't followed again (no cycles). */
    static List<Node> nodes(String h) {
        List<Node> out = new ArrayList<>();
        walk(h, 0, true, new boolean[1], new HashSet<>(), out);
        return out;
    }

    static void walk(String h, int level, boolean last, boolean[] cont, Set<String> up, List<Node> out) {
        Node n = new Node();
        n.h = h;
        n.level = level;
        n.last = last;
        n.cont = cont;
        out.add(n);
        up.add(h);
        List<String> kids = kids(h, up);
        n.kids = !kids.isEmpty();
        boolean[] next = Arrays.copyOf(cont, level + 2);
        next[level] = level > 0 && !last;   // this node's line goes on past its parts
        for (int i = 0; i < kids.size(); i++) walk(kids.get(i), level + 1, i == kids.size() - 1, next, up, out);
        up.remove(h);
    }

    /** An item's parts on the path, less any already above it. */
    static List<String> kids(String h, Set<String> up) {
        Item it = PathData.get(h);
        List<String> out = new ArrayList<>();
        if (it == null) return out;
        for (String p : it.parts) if (PathData.get(p) != null && !up.contains(p)) out.add(p);
        return out;
    }

    // ---- the families: built once ----

    static Map<String, Set<String>> below;     // an item → every part anywhere in its tree
    static Map<String, List<Integer>> families; // a part → the path's items with it, in path order
    static int indexed = -1;

    static synchronized void index() {
        if (families != null && indexed == PathData.items.size()) return;
        below = new HashMap<>();
        Map<String, List<Integer>> f = new HashMap<>();
        for (Item it : PathData.items)
            for (String p : below(it.h, new HashSet<>())) f.computeIfAbsent(p, k -> new ArrayList<>()).add(it.at);
        families = f;
        indexed = PathData.items.size();
    }

    static Set<String> below(String h, Set<String> visiting) {
        Set<String> s = below.get(h);
        if (s != null) return s;
        s = new LinkedHashSet<>();
        Item it = PathData.get(h);
        if (it == null || !visiting.add(h)) return s;
        for (String p : it.parts) {
            if (p.equals(h)) continue;
            s.add(p);
            s.addAll(below(p, visiting));
        }
        visiting.remove(h);
        s.remove(h);
        below.put(h, s);
        return s;
    }

    /** Every item on the path with this part anywhere in its tree, as path indices, in order. */
    static List<Integer> family(String part) {
        index();
        List<Integer> l = families.get(part);
        return l == null ? Collections.<Integer>emptyList() : l;
    }

    /** An item's own parts, once each, on the path. */
    static List<String> parts(String h) {
        List<String> out = new ArrayList<>();
        Item it = PathData.get(h);
        if (it != null) for (String p : it.parts) if (PathData.get(p) != null && !out.contains(p) && !p.equals(h)) out.add(p);
        return out;
    }

    // ---- the screen ----

    void show() {
        LinearLayout col = m.page("tree", h, this::show);
        TextView x = Ui.close(m, v -> leave());
        x.setContentDescription("Back to the card");
        col.addView(Cards.bar(m, "FAMILY TREE", x));
        long now = System.currentTimeMillis();
        Set<String> next = new HashSet<>(m.prog.nextNew(m.prog.batch));

        LinearLayout card = Ui.card(m);
        LinearLayout rows = Ui.column(m, 0);
        for (Node n : nodes(h)) rows.addView(n.level == 0 ? root(n, now, next) : row(n, now, next), new LinearLayout.LayoutParams(-1, -2));
        card.addView(rows);
        card.addView(legend());
        col.addView(card);

        for (String p : parts(h)) col.addView(familySection(p, now, next));
    }

    /** The state colours, as home's legend draws them (wrapping in the card's width). */
    View legend() {
        Flow f = new Flow(m, 12);
        f.addView(Home.legend(m, Ink.hl, Ink.hl, "next"));
        f.addView(Home.legend(m, Ink.young, Ink.young, "learning"));
        f.addView(Home.legend(m, Ink.paper2, Ink.young, "wilting"));
        f.addView(Home.legend(m, Ink.known, Ink.known, "known"));
        f.addView(Home.legend(m, 0, Ink.rule, "ahead"));
        return f;
    }

    /** Where an item is for you, as the garden draws it (ahead: outlined). */
    static int state(Progress p, String h, long now, Set<String> next) {
        return p.state(h, now, next.contains(h));
    }

    static String stateName(int st) {
        switch (st) {
            case GardenView.KNOWN: return "known";
            case GardenView.YOUNG: return "learning";
            case GardenView.WILT: return "wilting";
            case GardenView.NEXT: return "next";
            default: return "ahead";
        }
    }

    /** A node's square in its state: the garden's fills and inks. */
    TextView square(String h, int st, float sizeDp, float sp) {
        int bg = 0, line = 0, fg;
        switch (st) {
            case GardenView.NEXT: bg = Ink.hl; fg = Ink.hlInk; break;
            case GardenView.YOUNG: bg = Ink.young; fg = Ink.paper; break;
            case GardenView.KNOWN: bg = Ink.known; fg = Ink.paper; break;
            case GardenView.WILT: bg = Ink.paper2; line = Ink.young; fg = Ink.young; break;
            default: line = Ink.rule; fg = Ink.ink;
        }
        TextView t = Ui.text(m, h, sp, Ink.han, fg);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(Ui.shape(bg, line, 0, false));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ink.dp(sizeDp), Ink.dp(sizeDp)));
        return t;
    }

    /** Pinyin (or nothing), then "part" for a part, muted. */
    static CharSequence reading(Item it) {
        SpannableStringBuilder b = it.py.length > 0 ? Ui.pinyin(it.py[0]) : new SpannableStringBuilder();
        if (it.isPart()) {
            if (b.length() > 0) b.append("  ");
            int at = b.length();
            b.append("part");
            b.setSpan(new ForegroundColorSpan(Ink.ink2), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    /** The character: its square large on the left, the trunk running down from it. */
    View root(Node n, long now, Set<String> next) {
        Item it = PathData.get(n.h);
        int st = state(m.prog, n.h, now, next);
        Row r = new Row(m, n);
        r.setPadding(0, Ink.dp(8), 0, Ink.dp(8));
        r.addView(square(n.h, st, ROOT, 40));
        LinearLayout t = Ui.column(m, 2);
        t.addView(Ui.text(m, reading(it), 20, Ink.serifBold, Ink.ink));
        TextView en = Ui.text(m, it.en, 15, Ink.sans, Ink.ink);
        en.setMaxLines(2);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ink.dp(14);
        r.addView(t, lp);
        Ui.press(r, null);
        r.setOnClickListener(v -> leave());
        r.setContentDescription(n.h + " " + it.reading() + " " + it.en + ", " + stateName(st) + ". Back to its card");
        return r;
    }

    /** A part: its square, its reading and meaning beside it, on its branch. */
    View row(Node n, long now, Set<String> next) {
        Item it = PathData.get(n.h);
        int st = state(m.prog, n.h, now, next);
        Row r = new Row(m, n);
        r.setMinimumHeight(Ink.dp(48));
        r.setPadding(Math.round(left(n.level)), Ink.dp(4), 0, Ink.dp(4));
        r.addView(square(n.h, st, CELL, 21));
        LinearLayout t = Ui.column(m, 0);
        CharSequence py = reading(it);
        if (py.length() > 0) {
            TextView p = Ui.text(m, py, 14, Ink.serifBold, Ink.ink);
            p.setMaxLines(1);
            t.addView(p);
        }
        TextView en = Ui.text(m, it.en, 13, Ink.sans, Ink.ink2);
        en.setMaxLines(1);
        en.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(en);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ink.dp(10);
        r.addView(t, lp);
        Ui.press(r, null);
        r.setOnClickListener(v -> m.lookup(n.h));
        r.setContentDescription(n.h + " " + it.reading() + " " + it.en + ", " + stateName(st) + ", level " + n.level);
        return r;
    }

    /** The trunk's x for a level's parts: the centre of the square above them. */
    static float trunk(int level) { return Ink.dp(ROOT / 2) + level * Ink.dp(ELBOW + CELL / 2); }

    /** Where a level's square starts. */
    static float left(int level) { return trunk(level - 1) + Ink.dp(ELBOW); }

    /** A row of the tree, drawing its share of the hairlines: the lines of the levels above
     *  that go on past it, its own elbow from its parent's line, and the start of its parts'. */
    static final class Row extends LinearLayout {
        final Node n;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Row(Context c, Node n) {
            super(c);
            this.n = n;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, Ink.dp(1)));
            p.setColor(Ink.rule);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float h = getHeight(), cy = h / 2f;
            for (int k = 1; k < n.level; k++) if (n.cont[k]) c.drawLine(trunk(k - 1), 0, trunk(k - 1), h, p);
            if (n.level > 0) {
                float x = trunk(n.level - 1);
                c.drawLine(x, 0, x, n.last ? cy : h, p);
                c.drawLine(x, cy, left(n.level), cy, p);
            }
            if (n.kids) {
                float half = Ink.dp(n.level == 0 ? ROOT : CELL) / 2f;
                c.drawLine(trunk(n.level), cy + half, trunk(n.level), h, p);
            }
        }
    }

    // ---- a family ----

    View familySection(String part, long now, Set<String> next) {
        List<Integer> fam = family(part);
        int known = 0;
        int[] st = new int[fam.size()];
        for (int j = 0; j < st.length; j++) {
            st[j] = state(m.prog, PathData.items.get(fam.get(j)).h, now, next);
            if (st[j] == GardenView.KNOWN) known++;
        }
        String count = fam.size() + " on the path, " + known + " known";
        LinearLayout s = Ui.section(m, "The " + part + " family", Home.small(m, count));
        FamilyView fv = new FamilyView(m, fam, st, h);
        fv.tap = i -> m.lookup(PathData.items.get(i).h);
        fv.setContentDescription("The " + part + " family: " + count);
        int cols = GardenView.columns(m.contentWidth()), fold = FOLD_ROWS * cols;
        fv.shown = unfolded.contains(part) ? Integer.MAX_VALUE : fold;
        s.addView(fv, new LinearLayout.LayoutParams(-1, -2));
        if (fam.size() > fold) {
            TextView t = Ui.small(m, "", null);
            Runnable label = () -> t.setText(unfolded.contains(part) ? "Fold the family" : "Show all " + fam.size());
            label.run();
            t.setOnClickListener(v -> {
                if (!unfolded.remove(part)) unfolded.add(part);
                fv.shown = unfolded.contains(part) ? Integer.MAX_VALUE : fold;
                fv.requestLayout();
                fv.invalidate();
                label.run();
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.gravity = Gravity.CENTER_HORIZONTAL;   // a fold, as home's and the levels': small, centred
            s.addView(t, tp);
        }
        return s;
    }

    /** A family as the garden draws the path: a cell per member in its state, the character
     *  the tree is for ringed in ink. The first rows only until it's unfolded. */
    static final class FamilyView extends View {
        final List<Integer> members;
        final int[] state;
        final String self;
        int cols = 10, shown = Integer.MAX_VALUE, pressed = -1;
        float cell, gap = Ink.dp(2);
        GardenView.Tap tap;
        final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(Paint.ANTI_ALIAS_FLAG),
                textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final GestureDetector taps;
        final Runnable unpress = () -> press(-1);

        FamilyView(Context c, List<Integer> members, int[] state, String self) {
            super(c);
            this.members = members;
            this.state = state;
            this.self = self;
            lineP.setStyle(Paint.Style.STROKE);
            textP.setTypeface(Ink.han);
            textP.setTextAlign(Paint.Align.CENTER);
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) { return true; }

                @Override
                public void onShowPress(MotionEvent e) { press(at(e)); }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    int j = at(e);
                    press(j);
                    if (j >= 0) open(j);
                    return true;
                }
            });
        }

        int count() { return Math.min(shown, members.size()); }

        /** The j-th member opened. */
        void open(int j) {
            if (tap == null) return;
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            tap.open(members.get(j));
        }

        int at(MotionEvent e) {
            int col = (int) (e.getX() / (cell + gap)), row = (int) (e.getY() / (cell + gap));
            int j = row * cols + col;
            return e.getX() >= 0 && e.getY() >= 0 && col < cols && j < count() ? j : -1;
        }

        void press(int j) {
            removeCallbacks(unpress);
            if (j == pressed) return;
            pressed = j;
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            cols = GardenView.columns(w);
            cell = (w - gap * (cols - 1)) / cols;
            int rows = (count() + cols - 1) / cols;
            setMeasuredDimension(w, (int) Math.ceil(rows * cell + Math.max(0, rows - 1) * gap));
        }

        @Override
        protected void onDraw(Canvas c) {
            textP.setTextSize(cell * 0.56f);
            Paint.FontMetrics fm = textP.getFontMetrics();
            float hair = Math.max(1, Ink.dp(1)), ring = Math.max(2, Ink.dp(2));
            for (int j = 0, end = count(); j < end; j++) {
                Item it = PathData.items.get(members.get(j));
                float x = (j % cols) * (cell + gap), y = (j / cols) * (cell + gap);
                r.set(x, y, x + cell, y + cell);
                int bg = 0, line = 0, fg;
                switch (state[j]) {
                    case GardenView.NEXT: bg = Ink.hl; fg = Ink.hlInk; break;
                    case GardenView.YOUNG: bg = Ink.young; fg = Ink.paper; break;
                    case GardenView.KNOWN: bg = Ink.known; fg = Ink.paper; break;
                    case GardenView.WILT: bg = Ink.paper2; line = Ink.young; fg = Ink.young; break;
                    default: line = Ink.rule; fg = Ink.ink2;
                }
                boolean me = it.h.equals(self);
                if (j == pressed) { bg = Ink.pressed(bg); line = Ink.ink; }
                if (bg != 0) { fillP.setColor(bg); c.drawRect(r, fillP); }
                float w = me ? ring : hair;
                if (me) line = Ink.ink;
                if (line != 0) {
                    lineP.setColor(line);
                    lineP.setStrokeWidth(w);
                    c.drawRect(x + w / 2, y + w / 2, x + cell - w / 2, y + cell - w / 2, lineP);
                }
                textP.setColor(fg);
                c.drawText(it.h, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, textP);
                if (it.isPart()) {
                    fillP.setColor(fg);
                    float d = Math.max(2, cell * 0.09f);
                    c.drawRect(x + d, y + d, x + 2 * d, y + 2 * d, fillP);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean used = taps.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_CANCEL) press(-1);
            else if (a == MotionEvent.ACTION_UP && pressed >= 0) postDelayed(unpress, 120);
            else if (a == MotionEvent.ACTION_MOVE && pressed >= 0 && at(e) != pressed) press(-1);
            return used || super.onTouchEvent(e);
        }
    }

    // ---- the link on a card ----

    /** "Made of" with "Family tree ›" at its right: the chips' column, its label made a row. */
    static View madeOf(Main m, Item it, LinearLayout chips) {
        View label = chips.getChildAt(0);
        chips.removeViewAt(0);
        LinearLayout head = Ui.row(m, 12);
        head.addView(label, Ui.weight(1));
        TextView link = Ui.small(m, "Family tree ›", v -> open(m, it.h));
        link.setContentDescription("The family tree of " + it.h + ": its parts, theirs, and the characters that share them");
        head.addView(link);
        chips.addView(head, 0);
        return chips;
    }
}
