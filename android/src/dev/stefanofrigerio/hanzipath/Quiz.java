package dev.stefanofrigerio.hanzipath;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A round of questions: the check after a batch (its first grade), or a review. Three kinds:
 * choose (the character, and four answers, each a reading and a meaning), pick (the reading
 * and meaning, and four characters) and listen (it's said aloud, and four characters). The
 * check, and an item seen fewer than twice, get choose; after that a review draws one of the
 * three (listen only while Settings' "Say it aloud" is on). The other three answers are close
 * (characters sharing a part with it, else near it on the path, ones you've met first). A
 * tap grades it: right and quick is knew it (3), right after SLOW ms unsure (2), wrong
 * again (1), which puts it at the end of the round (up to three times; never straight after
 * itself while anything else is left). Then the answer in full, and Next. Undo takes the
 * last grade back. Every round comes in a random order: the set is the round's (the most
 * due, the batch just learnt, practice's draw), the order within it shuffled.
 *
 * A word (Words) gets the same three, with the word where the character was: word-choose
 * (the word, four readings and meanings), word-pick (its reading and meaning, four words)
 * and word-listen (said aloud, four words). Its wrong answers are other words, ones sharing
 * a character with it first, ones you've met or could (their characters known) before the rest.
 *
 * Practice (from home, when nothing is due) asks met items the same way, twenty drawn from
 * the weakest forty, and grades them as a review does (FSRS: a right answer to a card not
 * yet due adds little, a miss is a lapse), logged as "practice-<kind>".
 *
 * A round with no misses ends on "Learn next N" (Home.learning: if there's anything left
 * to learn, and nothing holds learning back), beside the way back.
 * And tone (Tones): the character said aloud and shown, five tones to pick from; drawn now
 * and then in a review for a character whose tone you've missed or been slow on.
 *
 * What was tapped goes in the log with each answer: a wrong one that is another character
 * is a confusion, and two of a pair within 60 days make it confusable (Progress.pairs). Its
 * wrong answers come from its confusables first, and once a week a pair a review round starts
 * with the two side by side (Apart: "Tell them apart", not graded, Next to the questions).
 *
 * And write: the reading and meaning, and you write the character on the square from nothing,
 * stroke by stroke (HanziView.quiz: the hint after three misses on a stroke). Graded from the
 * mistakes: none knew it (3), up to three unsure (2), more again (1); "Show it" gives up
 * (again). Only for a character (not a part, not a word) seen twice or more, in a review or
 * practice, never in the check; drawn by Settings' "Writing in reviews": off, sometimes
 * (about one in four), often (about one in two). Logged as "write".
 */
class Quiz {
    static final long SLOW = 8000;
    final Main m;
    final String kind;     // check | review | practice
    List<String> queue;
    int done, total, misses;
    Map<String, Integer> tries = new HashMap<>();
    long shown;
    Last last;
    String optionsFor;     // the question the options were drawn for (a redraw keeps them)
    List<String> options;
    String mode;           // choose | pick | listen | tone | write, or word-choose | word-pick | word-listen
    int wrong;             // write: the strokes missed (-1: given up, Show it)
    String picked;         // the answer tapped; null while it's open
    int grade;
    HanziView hv;
    final Random rnd;
    boolean fresh;         // just answered: the rings come in (Motion), once
    final List<View> ringed = new ArrayList<>(), faded = new ArrayList<>();
    final List<String> items;   // the round's items as given: a check's batch (Read offers its sentences)
    Progress.Pair apart;   // a review's first card: "Tell them apart" (Apart), until Next

    static class Last {
        Progress.Undo undo;
        List<String> queue;
        int done, misses;
        Map<String, Integer> tries;
        List<String> options;
        String mode;
    }

    Quiz(Main m, List<String> items, String kind) { this(m, items, kind, new Random()); }

    /** A round of items, asked in an order drawn from rnd (the tests give it a seed). */
    Quiz(Main m, List<String> items, String kind, Random rnd) {
        this.m = m;
        this.kind = kind;
        this.rnd = rnd;
        this.queue = new ArrayList<>(items);
        this.items = new ArrayList<>(items);
        Collections.shuffle(queue, rnd);
        this.total = items.size();
        // a review round starts with a pair the learner mixes up, side by side, once a week a pair (Apart)
        if (kind.equals("review")) apart = m.prog.apartDue(System.currentTimeMillis());
    }

    void show() {
        if (apart != null) {   // the round's first card: not a question, not graded; Next goes on to them
            Apart.show(m, apart, "review", () -> { apart = null; show(); });
            return;
        }
        String h = queue.get(0);
        String[] word = Words.get(h);
        Item it = PathData.get(h);
        if (!h.equals(optionsFor)) {
            mode = kindFor(h);
            options = mode.equals("tone") ? Tones.OPTIONS : mode.equals("write") ? Collections.<String>emptyList() : options(h, mode);
            optionsFor = h;
            shown = System.currentTimeMillis();
            if (heard()) m.say(h);
        }
        LinearLayout col = m.page("quiz", Arrays.asList(this, done, h, tries(h)), this::show);
        ringed.clear();
        faded.clear();
        TextView undo = last == null ? null : smallPill("Undo", v -> undo());
        if (undo != null) undo.setContentDescription("Undo the last answer");
        col.addView(Cards.bar(m, kind + " " + Math.min(done + 1, total) + " / " + total,
                undo, Ui.close(m, v -> m.home())));

        LinearLayout card = Ui.card(m);
        boolean writing = mode.equals("write");
        TextView ask = Ui.label(m, picked == null ? (mode.endsWith("listen") ? "Which one did you hear?"
                : mode.equals("tone") ? "Which tone is it?" : writing ? "Write it" : "Which one is it?")
                : writing ? written() : right(picked, h) ? "Right" : "Not that one");
        ask.setGravity(Gravity.CENTER);
        if (picked != null) ask.setTextColor(writing ? Ink.tone[grade == 3 ? 2 : grade == 2 ? 3 : 4]
                : right(picked, h) ? Ink.tone[2] : Ink.tone[4]);
        card.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        if (writing) {
            write(card, h, it);
        } else if (mode.equals("tone")) {
            Tones.question(this, card, it);
        } else if (mode.endsWith("choose")) {
            if (word != null) {
                hv = null;
                card.addView(Cards.word(m, h));
            } else {
                hv = Cards.box(m, it);
                card.addView(hv);
                hv.show();
                hv.setOnClickListener(v -> hv.animateStrokes());
            }
            LinearLayout opts = Ui.column(m, 10);
            for (String o : options) opts.addView(option(o, h), new LinearLayout.LayoutParams(-1, -2));
            card.addView(opts);
        } else {
            hv = null;
            card.addView(mode.endsWith("pick") ? said(h) : listen(h));
            card.addView(grid(h));
        }

        if (picked != null) {
            // Next right under the answers: the rest is there to read, not to scroll past
            card.addView(Ui.pill(m, queue.size() > 1 || grade == 1 && tries(h) < 3 ? "Next" : "Finish",
                    Ui.SOLID, v -> next()), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout answer = Ui.column(m, 16);
            answer.setPadding(0, Ink.dp(16), 0, 0);
            answer.setBackground(Home.topRule());
            // pick asked by the reading and meaning: they're above already (with the speaker
            // now), so the answer is what it's made of and why
            boolean asked = mode.endsWith("pick") || writing;
            if (word != null) {
                if (!asked) answer.addView(Cards.wordReading(m, word));
                answer.addView(Cards.chips(m, "Its characters", Words.chars(h)));
            } else {
                if (!asked) answer.addView(Cards.reading(m, it));
                Cards.parts(m, answer, it);
                Cards.add(answer, Cards.why(m, it));
            }
            Cards.add(answer, Sentences.view(m, h));
            if (answer.getChildCount() > 0) card.addView(answer);
            if (fresh) reveal(answer);
        }
        col.addView(card);
    }

    /** The answer coming in after a tap (Motion.ANSWER): the right one's ring and a wrong pick's
     *  fade in as they settle from a touch smaller, the other answers fade back, the answer
     *  in full fades in under them. */
    void reveal(View answer) {
        fresh = false;
        m.motion = Motion.run(m, Motion.ANSWER, f -> {
            for (View v : ringed) {
                v.getBackground().setAlpha(Math.round(255 * f));
                v.setScaleX(0.96f + 0.04f * f);
                v.setScaleY(0.96f + 0.04f * f);
            }
            for (View v : faded) v.setAlpha(1 - 0.55f * f);
            answer.setAlpha(f);
        });
    }

    /** Write's question: the reading and meaning (said), the empty square to write it on, and
     *  Show it small under it to give up. Once written: the character whole (tap: its strokes). */
    void write(LinearLayout card, String h, Item it) {
        card.addView(said(h));
        hv = Cards.box(m, it);
        hv.setContentDescription(picked == null ? "Write the character here, stroke by stroke" : "Stroke order of " + h);
        card.addView(hv);
        if (picked != null) {
            hv.show();
            hv.setOnClickListener(v -> hv.animateStrokes());
            return;
        }
        final HanziView sq = hv;
        sq.quiz(3, false, this::wrote);
        TextView give = smallPill("Show it", v -> { if (picked == null && sq.quizzing) { sq.endTrace(); wrote(-1); } });
        give.setContentDescription("Show the character: you didn't know it");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(give, lp);
    }

    /** How the writing went, as its label says it once done. */
    String written() {
        if (wrong < 0) return "Shown";
        if (wrong == 0) return "Right";
        return (grade == 2 ? "Nearly: " : "") + wrong + (wrong == 1 ? " miss" : " misses");
    }

    /** The grade from the misses: none knew (3), up to three unsure (2), more (or given up) again (1). */
    static int writeGrade(int mistakes) { return mistakes < 0 ? 1 : mistakes == 0 ? 3 : mistakes <= 3 ? 2 : 1; }

    /** The last stroke written (or Show it, -1): graded from the misses. */
    void wrote(int mistakes) {
        if (picked != null || !mode.equals("write")) return;
        String h = queue.get(0);
        wrong = mistakes;
        answered(h, null, writeGrade(mistakes), System.currentTimeMillis() - shown);
    }

    /** Writing in reviews (Settings): the share of eligible questions that ask you to write. */
    float writeShare() { return m.prog.write.equals("often") ? 0.5f : m.prog.write.equals("off") ? 0 : 0.25f; }

    /** Whether h can be written: a character (not a part, not a word) with its strokes. */
    boolean writable(String h) {
        if (Words.is(h)) return false;
        Item it = PathData.get(h);
        return it != null && !it.isPart() && PathData.glyph(m, h) != null;
    }

    /** The kind of question for h: choose in the check and while it's new (seen fewer than
     *  twice), then write now and then (Settings: writeShare), else any of the three; listen
     *  only with Say it aloud on and a Chinese voice on the phone to say it (Speech.voice). */
    String kindFor(String h) {
        Progress.Card c = m.prog.cards.get(h);
        String w = Words.is(h) ? "word-" : "";
        if (kind.equals("check") || c == null || c.reps < 2) return w + "choose";
        float share = writeShare();
        if (share > 0 && rnd.nextFloat() < share && writable(h)) return "write";
        if (kind.equals("review") && Tones.fits(m.prog, h) && rnd.nextInt(Tones.EVERY) == 0) return "tone";
        List<String> k = new ArrayList<>(Arrays.asList("choose", "pick"));
        if (m.prog.say && Speech.voice() && !py(h).isEmpty()) k.add("listen");   // no Chinese voice: nothing to hear
        return w + k.get(rnd.nextInt(k.size()));
    }

    /** A card's reading ("" for a part without one) and meaning: a character's, or a word's. */
    static String py(String h) {
        String[] w = Words.get(h);
        if (w != null) return w[1];
        Item it = PathData.get(h);
        return it.py.length > 0 ? it.py[0] : "";
    }

    static String en(String h) {
        String[] w = Words.get(h);
        return w != null ? w[2] : PathData.get(h).en;
    }

    /** Pick's question: the reading in the tones' inks, the meaning under it. Once answered,
     *  the speaker beside the reading (and a character's other reading): the answer's
     *  reading, said where it was asked. */
    View said(String h) {
        LinearLayout c = Ui.column(m, 4);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ink.dp(12), 0, Ink.dp(12));
        if (!py(h).isEmpty()) {
            TextView py = Ui.text(m, Ui.pinyin(py(h)), 30, Ink.serifBold, Ink.ink);
            py.setGravity(Gravity.CENTER);
            if (picked == null) c.addView(py);
            else {
                LinearLayout r = Ui.row(m, 10);
                r.setGravity(Gravity.CENTER);
                r.addView(py);
                r.addView(Ui.speaker(m, v -> m.say(h)));
                Item it = PathData.get(h);
                if (it != null && it.py.length > 1) r.addView(Ui.text(m, "also " + it.py[1], 15, Ink.serif, Ink.ink2));
                c.addView(r);
            }
        }
        TextView en = Ui.text(m, en(h), 19, Ink.sans, Ink.ink);
        en.setGravity(Gravity.CENTER);
        c.addView(en);
        return c;
    }

    /** Listen's question: the speaker, large, to hear it again. */
    View listen(String h) {
        LinearLayout c = Ui.column(m, 10);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ink.dp(8), 0, Ink.dp(8));
        View sp = Ui.speaker(m, v -> m.say(h));
        sp.setContentDescription("Say it again");
        c.addView(sp, new LinearLayout.LayoutParams(Ink.dp(88), Ink.dp(88)));
        TextView t = Ui.text(m, "tap to hear it again", 13, Ink.serif, Ink.ink2);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    /** The four characters, two by two in equal squares. */
    View grid(String h) {
        LinearLayout g = Ui.column(m, 10);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(m, 10);
            for (int i = 2 * r; i < 2 * r + 2 && i < options.size(); i++) row.addView(cell(options.get(i), h, i), Ui.weight(1));
            g.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return g;
    }

    /** A character to pick, drawn from its strokes. After a tap, as option(): the right one
     *  ringed green, a wrong pick pink, the rest fade. Spoken (TalkBack) by its place while
     *  it's open, "Answer 2 of 4", not by the character, whose name would be its sound and
     *  so the answer; once answered, by what it is. */
    View cell(String o, String h, int n) {
        View v;
        if (Words.is(o)) {
            // a word, written: four characters still fit half the card, at any font size
            // (drawn as the squares are, not scaled as reading text)
            TextView t = Ui.text(m, o, 0, Ink.han, Ink.ink);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, Words.chars(o).length > 3 ? 30 : 34);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setMinHeight(Ink.dp(96));
            v = t;
        } else {
            HanziView c = new HanziView(m);
            c.bare = true;
            c.pad = 0.2f;
            c.setGlyph(PathData.glyph(m, o));
            c.show();
            v = c;
        }
        v.setTag(o);   // what it is (the tests find answers by it)
        String place = "Answer " + (n + 1) + " of " + options.size();
        v.setContentDescription(picked == null ? place
                : place + ": " + o + ", " + spoken(o) + (o.equals(h) ? ", the right one" : o.equals(picked) ? ", your pick" : ""));
        if (picked == null) {
            Ui.touch(v, Ink.surface, Ink.line, 16);
            v.setOnClickListener(x -> pick(o));
        } else mark(v, o, h, 16);
        return v;
    }

    /** After a tap: the right answer ringed green, a wrong pick pink, the rest fade. */
    void mark(View v, String o, String h, float radius) { mark(v, o.equals(h), o.equals(picked), radius); }

    void mark(View v, boolean right, boolean mine, float radius) {
        android.graphics.drawable.GradientDrawable g = Ui.shape(Ink.paper, 0, radius, false);
        if (right || mine) g.setStroke(Ink.dp(2), right ? Ink.tone[2] : Ink.tone[4]);
        else g.setStroke(Math.max(1, Ink.dp(1)), Ink.rule);
        v.setBackground(g);
        if (right || mine) ringed.add(v);
        else { v.setAlpha(0.45f); faded.add(v); }
    }

    /** An answer: its reading in the tones' inks, then its meaning. After a tap, the right one
     *  is ringed green, a wrong pick pink, the rest fade. */
    View option(String o, String h) {
        String py = py(o), en = en(o);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!py.isEmpty()) {
            b.append(Ui.pinyin(py));
            b.setSpan(new TypefaceSpan(Ink.serifBold), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append("   ");
        }
        b.append(en);
        TextView t = Ui.text(m, b, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setMinHeight(Ink.dp(52));
        t.setPadding(Ink.dp(20), Ink.dp(8), Ink.dp(20), Ink.dp(8));
        if (picked == null) {
            Ui.style(t, Ui.OUTLINE);
            t.setOnClickListener(v -> pick(o));
        } else mark(t, o, h, 999);
        t.setTag(o);
        t.setContentDescription("Answer: " + spoken(o) + (picked == null ? "" : o.equals(h) ? ", the right one" : o.equals(picked) ? ", your pick" : ""));
        return t;
    }

    /** An answer as TalkBack says it: "shuǐ, water" (its reading, if it has one, and meaning). */
    static String spoken(String o) {
        String py = py(o);
        return (!py.isEmpty() ? py + ", " : "") + en(o);
    }

    /** Three close wrong answers: its confusables, then siblings (sharing a part), then neighbours on the path; ones
     *  met first; each meaning once; to listen, each reading once too. */
    List<String> options(String h, String mode) {
        if (Words.is(h)) return pickFrom(h, mode, wordPool(h, mode));
        Item it = PathData.get(h);
        List<String> sib = new ArrayList<>(), near = new ArrayList<>();
        for (String p : it.parts) {
            Item pi = PathData.get(p);
            if (pi != null) for (String u : pi.used) sib.add(u);
        }
        for (String u : it.used) sib.add(u);
        for (int d = -40; d <= 40; d++) {
            int i = it.at + d;
            if (d != 0 && i >= 0 && i < PathData.items.size() && PathData.items.get(i).isPart() == it.isPart())
                near.add(PathData.items.get(i).h);
        }
        Collections.shuffle(sib, rnd);
        Collections.shuffle(near, rnd);
        // the ones the learner has taken it for (Progress.pairs) before anything else, the most confused first
        List<String> pool = new ArrayList<>(m.prog.confusables(h, System.currentTimeMillis()));
        for (List<String> l : Arrays.asList(sib, near)) {
            for (String s : l) if (m.prog.cards.containsKey(s)) pool.add(s);      // met first
            for (String s : l) if (!m.prog.cards.containsKey(s)) pool.add(s);
        }
        return pickFrom(h, mode, pool);
    }

    /** A word's wrong answers, in order of closeness (WordChoices): words sharing a character
     *  with it, then of its field, ones met or ready first; then its neighbours on the path.
     *  For pick, ones as long as it and sounding like it first. */
    List<String> wordPool(String h, String mode) {
        return WordChoices.pool(m.prog, h, mode, rnd, System.currentTimeMillis());
    }

    /** The four from a pool in order of preference: each meaning once, nor one meaning inside
     *  another (WordChoices.sameMeaning: 勹 "wrap" and 包 "to cover; to wrap" are both right);
     *  to listen, each reading once too. */
    List<String> pickFrom(String h, String mode, List<String> pool) {
        List<String> out = new ArrayList<>();
        Set<String> meanings = new HashSet<>(), readings = new HashSet<>();
        out.add(h);
        meanings.add(en(h));
        if (!py(h).isEmpty()) readings.add(py(h));
        boolean heard = mode.endsWith("listen");
        for (String s : pool) {
            if (out.size() >= 4) break;
            if (!Words.onPath(s) || out.contains(s) || meanings.contains(en(s))
                    || heard && (py(s).isEmpty() || readings.contains(py(s)))) continue;
            if (sameAs(out, s)) continue;
            out.add(s);
            meanings.add(en(s));
            if (!py(s).isEmpty()) readings.add(py(s));
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    static boolean sameAs(List<String> out, String s) {
        for (String o : out) if (WordChoices.sameMeaning(o, s)) return true;
        return false;
    }

    TextView smallPill(String s, View.OnClickListener on) {
        return Ui.small(m, s, on);
    }

    boolean practice() { return kind.equals("practice"); }

    int tries(String h) { return tries.containsKey(h) ? tries.get(h) : 0; }

    /** Whether the answer o to h is right: the card itself, or for tone its tone. */
    boolean right(String o, String h) { return mode.equals("tone") ? Tones.right(o, h) : o.equals(h); }

    /** The kinds said aloud as they're asked: listen, tone. */
    boolean heard() { return mode.endsWith("listen") || mode.equals("tone"); }

    /** A tap: the grade, from right or wrong and how long it took. */
    void pick(String o) {
        if (picked != null) return;
        String h = queue.get(0);
        long ms = System.currentTimeMillis() - shown;
        // what was tapped goes in the log with it: a wrong card is a confusion (Progress.pairs)
        answered(o, o, !right(o, h) ? 1 : ms <= SLOW ? 3 : 2, ms);
    }

    /** An answer given (o: what shows as picked; logged: what goes in the log as tapped, null
     *  for none): graded g, then the answer in full. */
    void answered(String o, String logged, int g, long ms) {
        String h = queue.get(0);
        grade = g;
        Last l = new Last();
        l.undo = m.prog.grade(h, grade, practice() ? "practice-" + mode : mode, ms, logged);
        l.queue = new ArrayList<>(queue);
        l.done = done;
        l.misses = misses;
        l.tries = new HashMap<>(tries);
        l.options = options;
        l.mode = mode;
        last = l;
        picked = o;
        fresh = true;
        Motion.felt(m.root, grade > 1);
        if (m.prog.say && !py(h).isEmpty()) m.say(h);
        show();
    }

    void next() {
        String h = queue.remove(0);
        int t = tries(h) + 1;
        tries.put(h, t);
        if (grade == 1) misses++;
        // a miss: once more at the end of the round, after everything still left (so never
        // straight after itself, unless it's all that's left)
        if (grade == 1 && t < 3) queue.add(h);
        else done++;
        picked = null;
        optionsFor = null;
        if (queue.isEmpty()) finish();
        else show();
    }

    /** The last grade taken back: the question open again, as it was (the same kind, the
     *  same four), timed afresh. */
    void undo() {
        if (last == null) return;
        Last l = last;
        m.prog.ungrade(l.undo);
        queue = l.queue;
        done = l.done;
        misses = l.misses;
        tries = l.tries;
        last = null;
        optionsFor = queue.get(0);
        options = l.options;
        mode = l.mode;
        shown = System.currentTimeMillis();
        picked = null;
        if (heard()) m.say(optionsFor);
        m.quiz = this;
        show();
    }

    /** The round's end: a seal (好 clean, 行 fine, 练 practise), what happened, the way back.
     *  After a review, what's left: how many are still due, with Another round, or when the
     *  next fall due. A round with no misses: "Learn next N" first (solid), beside the way
     *  back, another round and undo small under them. */
    void finish() {
        LinearLayout col = m.page("done", this::finish);
        LinearLayout card = Ui.card(m);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView seal = Ui.seal(m, misses == 0 ? "好" : misses <= total / 3.0 ? "行" : "练");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ink.dp(8);
        card.addView(seal, sp);
        String said = kind.equals("check")
                ? total + " new on the path. They come back in an hour, then tomorrow; in minutes if you missed them."
                : practice() ? total + " practised" + (misses > 0 ? ", " + misses + " missed." : ", all of them right.")
                : total + " reviewed" + (misses > 0 ? ", " + misses + " to go over again." : ", all of them held.");
        TextView t = Ui.text(m, said, 16, Ink.sans, Ink.ink);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        long now = System.currentTimeMillis();
        int more = kind.equals("review") ? m.prog.due(now).size() : 0;
        if (!practice()) Fit.start(m.prog, now, false, null);   // a graded round: FSRS refitted to the learner, at most weekly
        if (kind.equals("review")) {
            // what's left: still due (another round), or when the next fall due
            long at = more > 0 ? 0 : m.prog.nextDue(now);
            TextView rest = Ui.text(m, more > 0 ? more + " still due, for another round."
                    : at == 0 ? "Nothing more is due." : "Nothing more is due. The next fall due " + when(now, at) + ".",
                    14, Ink.sans, Ink.ink2);
            rest.setGravity(Gravity.CENTER);
            card.addView(rest, new LinearLayout.LayoutParams(-1, -2));
        }
        if (practice()) {
            TextView rest = Ui.text(m, "Each answer counts toward when they come back.", 14, Ink.sans, Ink.ink2);
            rest.setGravity(Gravity.CENTER);
            card.addView(rest, new LinearLayout.LayoutParams(-1, -2));
        }
        // no misses: on to the next batch, the main way on (if anything's left to learn and
        // nothing holds learning back: Home.learning, which a perfect round opens past New per day)
        Home.Learning on = misses == 0 ? Home.learning(m.prog, now, true) : null;
        boolean onward = on != null && on.open;
        boolean again = more > 0 || practice();
        TextView read = kind.equals("check") ? Read.offer(m, items) : null;   // sentences with the batch in them
        if (onward) {
            int n = on.items.size();
            card.addView(Ui.even(m, 10, Ui.pill(m, "Back to the path", Ui.OUTLINE, v -> m.home()),
                    Ui.pill(m, on.words ? "Learn " + n + (n == 1 ? " word" : " words") : "Learn next " + n, Ui.SOLID, v -> m.startLearn(true))),
                    new LinearLayout.LayoutParams(-1, -2));
            // another round and undo, small, under them
            Flow small = new Flow(m, 10);
            small.center = true;
            if (read != null) small.addView(read);
            if (again) small.addView(smallPill("Another round", v -> { if (practice()) m.startPractice(); else m.startReview(); }));
            if (last != null) {
                TextView u = smallPill("Undo the last", v -> undo());
                u.setContentDescription("Undo the last answer");
                small.addView(u);
            }
            if (small.getChildCount() > 0) card.addView(small, new LinearLayout.LayoutParams(-1, -2));
        } else if (again) {
            // a round with more behind it: the next round beside the way back (one under the
            // other when large text won't fit them), undo under them
            card.addView(Ui.even(m, 10, Ui.pill(m, "Back to the path", Ui.OUTLINE, v -> m.home()),
                    Ui.pill(m, "Another round", Ui.SOLID, v -> { if (practice()) m.startPractice(); else m.startReview(); })),
                    new LinearLayout.LayoutParams(-1, -2));
            if (last != null) {
                LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-2, -2);
                up.gravity = Gravity.CENTER_HORIZONTAL;
                TextView u = smallPill("Undo the last", v -> undo());
                u.setContentDescription("Undo the last answer");
                card.addView(u, up);
            }
        } else {
            // the way back alone: the whole line, the small ones (read, undo) under it, as in the others
            card.addView(Ui.pill(m, "Back to the path", Ui.SOLID, v -> m.home()), new LinearLayout.LayoutParams(-1, -2));
            Flow small = new Flow(m, 10);
            small.center = true;
            if (read != null) small.addView(read);
            if (last != null) {
                TextView u = smallPill("Undo the last", v -> undo());
                u.setContentDescription("Undo the last answer");
                small.addView(u);
            }
            if (small.getChildCount() > 0) card.addView(small, new LinearLayout.LayoutParams(-1, -2));
        }
        col.addView(card);
    }

    /** When a time falls, said plainly from now: "in 10 minutes", "in 3 hours", "tomorrow",
     *  "in 4 days". Within half a day it's hours; past that, the calendar day it falls on. */
    static String when(long now, long at) {
        long min = Math.max(1, (at - now + 59_999) / 60_000);
        if (min < 60) return min == 1 ? "in a minute" : "in " + min + " minutes";
        long hours = Math.round(min / 60.0);
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(at);
        int days = 0;
        while (a.get(java.util.Calendar.YEAR) != b.get(java.util.Calendar.YEAR)
                || a.get(java.util.Calendar.DAY_OF_YEAR) != b.get(java.util.Calendar.DAY_OF_YEAR)) {
            a.add(java.util.Calendar.DATE, 1);
            days++;
            if (days > 400) break;
        }
        if (days == 0 || min < 12 * 60) return hours == 1 ? "in an hour" : "in " + hours + " hours";
        return days == 1 ? "tomorrow" : "in " + days + " days";
    }
}
