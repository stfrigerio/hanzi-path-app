package dev.stefanofrigerio.hanzipath;

import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A gentle return: 300 due after 6 days away. Home says so in one calm line under Due now,
 * the Review button reads "Review 40 of 300", and Learn waits ("Reviews first",
 * disabled) until a round of reviews is done today. A round takes the learned items slipping
 * first (lowest recall first), the new-ish last; the reminder and the widget read the same
 * list. With 30 due nothing of it shows and the order is the one before. Renders: back*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class ReturnTest {
    static final long DAY = Progress.DAY;
    final HomeTest shots = new HomeTest();

    /** n items off the path's start, last seen about 6 days ago, all due: a third learned and
     *  slipping (stability 3 to 6 days), a third in between (1 to 3), a third new-ish (under 1);
     *  and a day of activity 6 days ago, nothing since. */
    static void seed(Main m, int n) throws Exception {
        long now = System.currentTimeMillis();
        m.prog.cards.clear();
        for (int i = 0; i < n; i++) {
            Progress.Card k = new Progress.Card();
            k.s = i % 3 == 0 ? 3 + (i % 30) * 0.1 : i % 3 == 1 ? 1 + (i % 20) * 0.1 : 0.2 + (i % 8) * 0.1;
            k.d = 5; k.reps = k.s < 1 ? 1 : 3; k.grade = 3;
            k.first = now - 30 * DAY; k.last = now - 6 * DAY - i * 60_000L; k.due = k.last + (long) (k.s * DAY);
            String h = PathData.items.get(i).h;
            m.prog.cards.put(h, k);
            m.prog.putCard(h, k);
        }
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, -6);
        m.prog.days = new JSONObject().put(Progress.date(c), new JSONObject().put("reviews", 40).put("learnt", 5));
        m.prog.putMeta("days", m.prog.days.toString());
        m.prog.introDone = true;
        m.prog.saveOpts();
        m.home();
        ShadowLooper.idleMainLooper();
    }

    /** The order before a backlog: the most overdue for what it holds first. */
    static List<String> before(Progress p, long now) {
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, Progress.Card> e : p.cards.entrySet()) if (e.getValue().due <= now) out.add(e.getKey());
        Collections.sort(out, (a, b) -> {
            Progress.Card x = p.cards.get(a), y = p.cards.get(b);
            return Double.compare((now - y.last) / y.s, (now - x.last) / x.s);
        });
        return out;
    }

    @Test
    public void backlog() throws Exception { backlog(RoundTest.open(), "back"); }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void backlogNight() throws Exception { backlog(RoundTest.open(), "backn"); }

    void backlog(Main m, String prefix) throws Exception {
        seed(m, 300);
        long now = System.currentTimeMillis();
        List<String> due = m.prog.due(now);
        assertEquals(300, due.size());
        assertEquals("6 days away", 6, m.prog.daysAway());
        assertTrue(m.prog.backlog(due.size()));

        // home: the line, the round's share of it, Learn waiting
        TextView line = WalkTest.find(m.root, "300 due after 6 days away");
        assertNotNull("the line under Due now", line);
        assertEquals("300 due after 6 days away. Rounds of 40 bring them back, what's slipping first.", line.getText().toString());
        assertNotNull(WalkTest.find(m.root, "Reviews due"));
        TextView rv = WalkTest.find(m.root, "Review ");
        assertEquals("Review 40 of 300", rv.getText().toString());
        assertTrue(rv.isEnabled());
        TextView lb = WalkTest.find(m.root, "Reviews first");
        assertNotNull("new items wait", lb);
        assertEquals("Reviews first", lb.getText().toString());
        assertFalse(lb.isEnabled());
        assertNull(WalkTest.find(m.root, "Learn next"));
        shots.shot(m, prefix + "1-home");

        // the order: learned and slipping first, lowest recall first; the new-ish last
        for (int i = 0; i < 100; i++) assertTrue("learned first: " + i, m.prog.cards.get(due.get(i)).s >= Progress.SLIPPING);
        for (int i = 1; i < 100; i++)
            assertTrue("lowest recall first", Progress.recall(m.prog.cards.get(due.get(i - 1)), now) <= Progress.recall(m.prog.cards.get(due.get(i)), now));
        for (int i = 100; i < 200; i++) {
            double s = m.prog.cards.get(due.get(i)).s;
            assertTrue("then the rest: " + i, s >= Progress.FRESH && s < Progress.SLIPPING);
        }
        for (int i = 200; i < 300; i++) assertTrue("new-ish last: " + i, m.prog.cards.get(due.get(i)).s < Progress.FRESH);
        assertNotEquals("not the order without a backlog", before(m.prog, now), due);

        // the reminder and the widget read the same list, in the same order
        Progress.Peek pk = Progress.peek(m, now);
        assertEquals(due, pk.due);
        assertEquals(300, Widget.show(m, now).due);
        assertEquals("the widget's item is the round's first", due.get(0), Widget.show(m, now).h);

        // the round: the 40 most slipping of the learned
        WalkTest.tap(m, "Review 40 of 300");
        assertEquals("quiz", m.screen);
        assertEquals("review", m.quiz.kind);
        assertEquals("the round is the 40 most due, shuffled", new java.util.HashSet<>(due.subList(0, 40)), new java.util.HashSet<>(m.quiz.queue));
        for (String h : m.quiz.queue) assertTrue(m.prog.cards.get(h).s >= Progress.SLIPPING);
        RoundTest.answer(m);
        assertEquals(40, m.prog.todayCount("reviews"));

        // a round done today: Learn as usual; the line stays (still a backlog), the days gone
        m.home();
        ShadowLooper.idleMainLooper();
        assertEquals(0, m.prog.daysAway());
        assertNotNull(WalkTest.find(m.root, "260 due. Rounds of 40"));
        assertNotNull(WalkTest.find(m.root, "Review 40 of 260"));
        assertNull(WalkTest.find(m.root, "Reviews first"));
        TextView ln = WalkTest.find(m.root, "Learn ");
        assertNotNull(ln);
        assertTrue(ln.getText().toString(), ln.isEnabled());
        shots.shot(m, prefix + "2-after-round");
    }

    /** 30 due after 6 days away: home and the order as they were. */
    @Test
    public void noBacklog() throws Exception {
        Main m = RoundTest.open();
        seed(m, 30);
        long now = System.currentTimeMillis();
        List<String> due = m.prog.due(now);
        assertEquals(30, due.size());
        assertFalse(m.prog.backlog(30));
        assertFalse(m.prog.pauseNew(30));
        assertEquals("the order as before", before(m.prog, now), due);
        assertEquals(due, Progress.peek(m, now).due);
        assertNull(WalkTest.find(m.root, "30 due after"));
        assertNull(WalkTest.find(m.root, "Rounds of"));
        assertEquals("Review 30", WalkTest.find(m.root, "Review ").getText().toString());
        assertNull(WalkTest.find(m.root, "Reviews first"));
        TextView ln = WalkTest.find(m.root, "Learn next");
        assertNotNull(ln);
        assertTrue(ln.isEnabled());
        WalkTest.tap(m, "Review 30");
        assertEquals("the round is all of them, shuffled", new java.util.HashSet<>(due), new java.util.HashSet<>(m.quiz.queue));
    }

    /** Between two and three rounds due: no line yet, but new items wait for a round. */
    @Test
    public void pauseOnly() throws Exception {
        Main m = RoundTest.open();
        seed(m, 100);
        assertFalse(m.prog.backlog(100));
        assertTrue(m.prog.pauseNew(100));
        assertNull(WalkTest.find(m.root, "Rounds of"));
        assertEquals("Review 100", WalkTest.find(m.root, "Review ").getText().toString());
        assertFalse(WalkTest.find(m.root, "Reviews first").isEnabled());
    }
}
