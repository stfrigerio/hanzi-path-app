package dev.stefanofrigerio.hanzipath;

import android.widget.LinearLayout;

import java.util.List;

/**
 * A batch being learnt, or one item looked up: the strokes animating on the square (tap it
 * to see them again), said aloud, its reading, parts, why, the words it opens.
 * After the batch comes the check (Quiz), its first grade.
 */
class Learn {
    final Main m;
    final List<String> items;
    final boolean lookup;
    final String from;
    int i;
    HanziView hv;

    Learn(Main m, List<String> items, boolean lookup, String from) {
        this.m = m;
        this.items = items;
        this.lookup = lookup;
        this.from = from;
    }

    void show() { show(true); }

    void show(boolean speak) {
        String h = items.get(i);
        Item it = PathData.get(h);
        LinearLayout col = m.page("learn", () -> show(false));
        String count = lookup ? (m.prog.cards.containsKey(h) ? "known " + Math.round(m.prog.strength(h, System.currentTimeMillis()) * 100) + "%" : "not met yet")
                : "new " + (i + 1) + " / " + items.size();
        col.addView(Cards.bar(m, count, Ui.close(m, v -> m.back())));

        LinearLayout card = Ui.card(m);
        card.addView(Cards.tag(m, it));
        hv = Cards.box(m, it);
        card.addView(hv);
        card.addView(Cards.reading(m, it));
        Cards.parts(m, card, it);
        Cards.add(card, Cards.why(m, it));
        Cards.add(card, Cards.words(m, it));
        col.addView(card);

        col.addView(Ui.pill(m, lookup ? "Done" : i + 1 < items.size() ? "Next" : "Check yourself", Ui.SOLID, v -> next()),
                new LinearLayout.LayoutParams(-1, -2));

        hv.setOnClickListener(v -> hv.animateStrokes());
        hv.post(hv::animateStrokes);
        if (speak && !lookup && m.prog.say && it.py.length > 0) m.say(h);
    }

    void next() {
        if (lookup) { m.back(); return; }
        if (i + 1 < items.size()) { i++; show(); return; }
        m.prog.learnt(items.size());
        m.startCheck(new java.util.ArrayList<>(items));
    }
}
