package dev.stefanofrigerio.hanzipath;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A batch being learnt, or one item looked up: the strokes animating on the square (tap it
 * to see them again, or Step through them one at a time), said aloud, its reading, parts,
 * why, the words it opens. Write it, beside Next: trace it over its outline (a hint after two
 * misses on a stroke; Again to write it once more); not graded.
 * After the batch comes the check (Quiz), its first grade.
 */
class Learn {
    final Main m;
    final List<String> items;
    final boolean lookup;
    final String from;
    int i;
    HanziView hv;
    TextView write;        // Write it, beside Next (none on a word's card)

    Learn(Main m, List<String> items, boolean lookup, String from) {
        this.m = m;
        this.items = items;
        this.lookup = lookup;
        this.from = from;
    }

    void show() { show(true); }

    void show(boolean speak) {
        String h = items.get(i);
        if (Words.is(h)) { word(h, speak); return; }
        Item it = PathData.get(h);
        LinearLayout col = m.page("learn", java.util.Arrays.asList(this, i), () -> show(false));
        String count = lookup ? known(h) : "new " + (i + 1) + " / " + items.size();
        col.addView(Cards.bar(m, count, Ui.close(m, v -> m.back())));

        LinearLayout card = Ui.card(m);
        card.addView(Cards.tag(m, it));
        hv = Cards.box(m, it);
        card.addView(hv);
        final android.view.View stepper = Cards.stepper(m, hv);
        Cards.add(card, stepper);
        card.addView(Cards.reading(m, it));
        Cards.parts(m, card, it);
        Cards.add(card, Cards.why(m, it));
        Cards.add(card, Cards.words(m, it));
        Cards.add(card, Sentences.view(m, h));
        col.addView(card);

        TextView go = Ui.pill(m, lookup ? "Done" : i + 1 < items.size() ? "Next" : "Check yourself", Ui.SOLID, v -> next());
        if (hv.glyph != null && hv.fill.length > 0) {
            // Write it, beside Next: trace it over its outline; the hint after 2 misses on a
            // stroke; not graded. While it's being written the Step pill steps aside.
            // (The Step pill keeps its place, unseen, so nothing moves under the finger.)
            final HanziView sq = hv;
            final TextView wr = write = Ui.pill(m, "Write it", Ui.OUTLINE, null);
            wr.setOnClickListener(v -> {
                if (sq.quizzing) sq.show();                 // Stop: the character whole again
                else sq.quiz(2, true, mistakes -> wrote(wr, mistakes));
            });
            sq.onTrace = () -> {
                if (stepper != null) stepper.setVisibility(sq.quizzing ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
                if (sq.quizzing) name(wr, "Stop", "Stop writing, show the character");
                else name(wr, "Write it", "Write it: trace the strokes over the outline");
            };
            sq.onTrace.run();
            col.addView(Ui.even(m, 10, write, go), new LinearLayout.LayoutParams(-1, -2));
        } else {
            write = null;
            col.addView(go, new LinearLayout.LayoutParams(-1, -2));
        }

        hv.setOnClickListener(v -> hv.animateStrokes());
        hv.post(hv::animateStrokes);
        if (speak && !lookup && m.prog.say && it.py.length > 0) m.say(h);
    }

    /** A word being learnt: the word large, its reading (tap the speaker to hear it), what it
     *  means, its level, and each character in it with its own meaning. */
    void word(String h, boolean speak) {
        String[] w = Words.get(h);
        LinearLayout col = m.page("learn", java.util.Arrays.asList(this, i), () -> show(false));
        hv = null;
        write = null;
        col.addView(Cards.bar(m, lookup ? known(h) : "new word " + (i + 1) + " / " + items.size(), Ui.close(m, v -> m.back())));
        LinearLayout card = Ui.card(m);
        card.addView(Cards.wordTag(m, w));
        card.addView(Cards.word(m, h));
        card.addView(Cards.wordReading(m, w));
        card.addView(Cards.chips(m, "Its characters", Words.chars(h)));
        Cards.add(card, Sentences.view(m, h));
        col.addView(card);
        col.addView(Ui.pill(m, lookup ? "Done" : i + 1 < items.size() ? "Next" : "Check yourself", Ui.SOLID, v -> next()),
                new LinearLayout.LayoutParams(-1, -2));
        if (speak && !lookup && m.prog.say) m.say(h);
    }

    /** Written to the end: how it went, and Again to write it once more. */
    static void wrote(TextView write, int mistakes) {
        name(write, mistakes == 0 ? "Clean. Again?" : mistakes == 1 ? "1 miss. Again" : mistakes + " misses. Again",
                (mistakes == 0 ? "Written without a miss" : "Written with " + mistakes + (mistakes == 1 ? " miss" : " misses")) + ". Write it again");
    }

    static void name(TextView t, String text, String spoken) {
        t.setText(text);
        t.setContentDescription(spoken);
    }

    /** A card looked up: how well it's known, or that it's new. */
    String known(String h) {
        return m.prog.cards.containsKey(h) ? "known " + Math.round(m.prog.strength(h, System.currentTimeMillis()) * 100) + "%" : "not met yet";
    }

    void next() {
        if (lookup) { m.back(); return; }
        if (i + 1 < items.size()) { i++; show(); return; }
        m.prog.learnt(items.size());
        Words.learnt(m.prog, Words.is(items.get(0)));
        m.startCheck(new java.util.ArrayList<>(items));
    }
}
