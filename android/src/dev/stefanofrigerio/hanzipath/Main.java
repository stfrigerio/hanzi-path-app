package dev.stefanofrigerio.hanzipath;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/**
 * The app: one activity, its screens built in code (Home, Search, Learn, Quiz, Apart, Done, Read, Settings, Stats, Levels), each
 * a column on the paper, at most 520 dp wide, 16 dp from the sides. Back goes to the path;
 * from the path, out. The system bars are the paper's colour.
 */
public class Main extends Activity {
    Progress prog;
    FrameLayout root;
    String screen = "home";
    Runnable redraw;          // the screen as it is, built again (a theme change)
    Learn learn;
    Quiz quiz;
    Settings settings;
    Search search;
    Stats stats;
    Levels levels;            // HSK levels, from Stats
    Read read;
    Translate translate;
    Apart apart;              // "Tell them apart": a pair side by side (a review's first card, or from Stats)
    Tree tree;                // the family tree open, or the one a looked-up part came from
    Parts parts;              // the parts, from home: back from a card returns there
    boolean gardenOpen;       // the whole path shown on home, not just the rows around you
    Object shownKey;          // what the screen shows (page's key): a new one comes in
    android.animation.ValueAnimator motion;   // the screen's motion while it runs (Motion)
    final java.util.ArrayDeque<Taps.Back> taps = new java.util.ArrayDeque<>();   // cards a tapped character opened (Taps)
    boolean created;          // onCreate has just built home: the onResume after it needn't again

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        preload(getApplicationContext());
        Ink.init(this);
        prog = new Progress(this);
        try {
            PathData.load(this);   // read on its thread meanwhile: this waits for it (or reads it, if it's not begun)
        } catch (Exception e) {
            throw new RuntimeException("the path didn't load", e);
        }
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        paint();
        home();
        created = true;
        Reminder.arm(this);
        Speech.changed = () -> runOnUiThread(this::voiceChanged);
        if (prog.say) Speech.check(this);   // known before the first review whether listen can be asked
    }

    /** The start, off the main thread: the path (and its words) read while the faces and the
     *  progress load; the sentences beside it (home's Read line wants them); then, at a low
     *  priority, what only other screens use (Search's index, the HSK lists). Each is built
     *  under its own lock, so a screen that wants one before it's ready waits for it, and a
     *  failure here is met again, and said, where it's wanted. */
    static void preload(android.content.Context app) {
        new Thread(() -> {
            try {
                PathData.load(app);
                Words.build();
            } catch (Exception e) { /* onCreate reads it again and says why */ }
        }, "path").start();
        new Thread(() -> {
            Sentences.load(app);
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                PathData.load(app);
                Search.build();
                Levels.hsk(app);
            } catch (Exception e) { /* built when first used instead */ }
        }, "sentences").start();
    }

    static final int ASK_NOTIFY = 1;

    /** Android 13+ asks before an app may notify: asked when the reminder is turned on, not before. */
    void askToNotify() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFY);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != ASK_NOTIFY || (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)) return;
        prog.remind = -1;
        prog.saveOpts();
        Reminder.arm(this);
        if (settings != null && screen.equals("settings")) {
            settings.show();
            settings.remindNote("Notifications aren't allowed, so the reminder is off. Allow them for 汉字之路 in Android's settings, then pick a time again.");
        }
    }

    void paint() {
        root.setBackgroundColor(Ink.paper);
        getWindow().getDecorView().setBackgroundColor(Ink.paper);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(Ink.night ? 0 : light, light);
    }

    /** A screen: its column in a scroll, centred, 16 dp from the sides. */
    LinearLayout page(String name, Runnable redraw) { return page(name, null, redraw); }

    /** A screen, and what it shows (key: Learn's item, Quiz's question). A new screen, or a new
     *  key on the same one, comes in (Motion.enter: rising, or only fading when it's home); the
     *  same one built again (a tap's answer, a theme change, a setting) just is. */
    LinearLayout page(String name, Object key, Runnable redraw) {
        boolean moved = !name.equals(screen) || !java.util.Objects.equals(key, shownKey);
        screen = name;
        shownKey = key;
        this.redraw = redraw;
        if (motion != null) motion.cancel();       // ends at once: the old screen is going anyway
        motion = null;
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = Ui.column(this, 16);
        int side = side();
        col.setPadding(side, Ink.dp(20), side, Ink.dp(48));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        if (moved) motion = Motion.enter(sv, !name.equals("home"));
        return col;
    }

    /** A page's margin: 16 dp, or more to keep the column 520 dp at most. */
    int side() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(Ink.dp(16), (w - Ink.dp(520)) / 2);
    }

    /** How wide a page's column is. */
    int contentWidth() { return getResources().getDisplayMetrics().widthPixels - 2 * side(); }

    void home() {
        if (search != null) search.hideKeyboard();
        search = null;
        learn = null;
        quiz = null;
        settings = null;
        stats = null;
        levels = null;
        read = null;
        translate = null;
        apart = null;
        tree = null;
        parts = null;
        Home.build(this, page("home", this::home));
        Widget.refresh(this);   // back from a round: the widget counts what's due now
    }

    void startLearn() { startLearn(false); }

    /** A batch to learn, as Home.learning decides; perfect: from a round with no misses,
     *  which opens New per day as a good day does. Past it, it still counts toward today's. */
    void startLearn(boolean perfect) {
        Home.Learning l = Home.learning(prog, System.currentTimeMillis(), perfect);
        if (!l.open) return;
        learn = new Learn(this, l.items, false, null);
        learn.show();
    }

    void lookup(String h) {
        learn = new Learn(this, java.util.Collections.singletonList(h), true, screen);
        learn.show();
    }

    /** Look it up: the path searched by character, pinyin or meaning. */
    void openSearch() {
        search = new Search(this);
        search.show();
    }

    /** A round of reviews: the most overdue first, as many as Settings' "Reviews per round";
     *  after days away, the learned items slipping first (Progress.order). */
    void startReview() {
        List<String> due = prog.due(System.currentTimeMillis());
        if (due.isEmpty()) return;
        quiz = new Quiz(this, due.subList(0, prog.roundOf(due.size())), "review");
        quiz.show();
    }

    /** Practice, anytime: up to 20 items met, drawn from the weakest 40, asked and graded as a
     *  review is (Quiz "practice", logged as practice-<kind>). */
    void startPractice() {
        List<String> items = prog.practiceSet(System.currentTimeMillis(), new java.util.Random());
        if (items.isEmpty()) return;
        quiz = new Quiz(this, items, "practice");
        quiz.show();
    }

    void startCheck(List<String> items) {
        quiz = new Quiz(this, items, "check");
        quiz.show();
    }

    /** A Read round: sentences made of what you know, read one at a time. */
    void startRead() {
        read = new Read(this);
        if (read.queue.isEmpty()) return;
        read.show();
    }

    /** A Translate round: sentences you can read, into English and into Chinese in turn. */
    void startTranslate() {
        translate = new Translate(this);
        if (translate.queue.isEmpty()) return;
        translate.ask();
    }

    void openSettings() {
        settings = new Settings(this);
        settings.show();
    }

    /** Stats: the review log and the path, read back. */
    void openStats() {
        stats = new Stats(this);
        stats.show();
    }

    /** Every part, and how many characters each builds, from home. */
    void openParts() {
        parts = new Parts(this);
        parts.show();
    }

    /** HSK levels, from Stats: back returns there. */
    void openLevels() {
        levels = new Levels(this);
        levels.show();
    }

    /** A pair the learner mixes up, side by side, from Stats' "Mixed up": Back to Stats. */
    void openApart(Progress.Pair p) {
        Stats from = stats;
        Apart.show(this, p, "stats", () -> { if (from != null) from.show(); else home(); });
    }

    void say(String h) { Speech.say(this, h, Speech.rateOf(prog.speed)); }

    /** Back: a looked-up item to the question it was opened from, anything else to the path. */
    boolean back() {
        if (screen.equals("home")) return false;
        if (screen.equals("tree") && tree != null) { tree.leave(); return true; }
        if (Taps.back(this)) return true;   // a card a tapped character opened: where it was tapped
        if (screen.equals("learn") && learn != null && learn.lookup && "tree".equals(learn.from) && tree != null) { tree.show(); return true; }
        if (screen.equals("learn") && learn != null && learn.lookup && "quiz".equals(learn.from) && quiz != null) {
            quiz.show();
            return true;
        }
        if (screen.equals("learn") && learn != null && learn.lookup && "search".equals(learn.from) && search != null) {
            search.show();
            return true;
        }
        if (screen.equals("apart") && apart != null && "stats".equals(apart.from) && stats != null) {
            stats.show();
            return true;
        }
        if (screen.equals("learn") && learn != null && learn.lookup && "levels".equals(learn.from) && levels != null) {
            levels.show();
            return true;
        }
        if (screen.equals("learn") && learn != null && learn.lookup && "parts".equals(learn.from) && parts != null) {
            parts.show();
            return true;
        }
        if (screen.equals("levels") && stats != null) {
            stats.show();
            return true;
        }
        if (screen.equals("learn") && learn != null && learn.lookup && "stats".equals(learn.from) && stats != null) {
            stats.show();
            return true;
        }
        home();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!back()) super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Speech.recheck(this);   // back from installing a voice, maybe
        if (created) {          // home was built a moment ago, in onCreate: as it is, at rest
            created = false;
            if (motion != null) motion.cancel();
            motion = null;
        } else if (screen.equals("home")) home();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Widget.refresh(this);
    }


    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Ink.init(this);
        paint();
        if (redraw != null) redraw.run();
    }

    /** It turned out whether the phone has a Chinese voice: Settings' line under Say it aloud. */
    void voiceChanged() {
        if (settings != null && screen.equals("settings")) settings.show();
    }

    /** Android's text-to-speech settings, where a Chinese voice is installed (or its settings
     *  at all, on a phone that names the page otherwise). */
    void openVoiceSettings() {
        try {
            startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS"));
        } catch (android.content.ActivityNotFoundException e) {
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); }
            catch (android.content.ActivityNotFoundException x) { /* no settings app: nothing to open */ }
        }
    }


    @Override
    protected void onDestroy() {
        Speech.changed = null;
        Speech.close();
        prog.close();
        super.onDestroy();
    }
}
