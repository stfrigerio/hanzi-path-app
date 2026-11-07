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
 * The app: one activity, its screens built in code (Home, Learn, Quiz, Done, Settings), each
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

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ink.init(this);
        try {
            PathData.load(this);
        } catch (Exception e) {
            throw new RuntimeException("the path didn't load", e);
        }
        prog = new Progress(this);
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
        Reminder.arm(this);
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
    LinearLayout page(String name, Runnable redraw) {
        screen = name;
        this.redraw = redraw;
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = Ui.column(this, 16);
        int w = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(Ink.dp(16), (w - Ink.dp(520)) / 2);
        col.setPadding(side, Ink.dp(20), side, Ink.dp(48));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        return col;
    }

    void home() {
        learn = null;
        quiz = null;
        settings = null;
        Home.build(this, page("home", this::home));
    }

    void startLearn() {
        int n = Math.min(prog.batch, prog.newLeftToday());
        List<String> items = prog.nextNew(n);
        if (items.isEmpty()) return;
        learn = new Learn(this, items, false, null);
        learn.show();
    }

    void lookup(String h) {
        learn = new Learn(this, java.util.Collections.singletonList(h), true, screen);
        learn.show();
    }

    void startReview() {
        List<String> due = prog.due(System.currentTimeMillis());
        if (due.isEmpty()) return;
        quiz = new Quiz(this, due.subList(0, Math.min(60, due.size())), "review");
        quiz.show();
    }

    void startCheck(List<String> items) {
        quiz = new Quiz(this, items, "check");
        quiz.show();
    }

    void openSettings() {
        settings = new Settings(this);
        settings.show();
    }

    void say(String h) { Speech.say(this, h); }

    /** Back: a looked-up item to the question it was opened from, anything else to the path. */
    boolean back() {
        if (screen.equals("home")) return false;
        if (screen.equals("learn") && learn != null && learn.lookup && "quiz".equals(learn.from) && quiz != null) {
            quiz.show();
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
        Updater.maybe(this, false);
        if (screen.equals("home")) home();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Ink.init(this);
        paint();
        if (redraw != null) redraw.run();
    }

    void updateStatus() {
        runOnUiThread(() -> { if (settings != null && screen.equals("settings")) settings.status(); });
    }

    @Override
    protected void onDestroy() {
        Speech.close();
        prog.close();
        super.onDestroy();
    }
}
