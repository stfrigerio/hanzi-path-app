package dev.stefanofrigerio.hanzipath;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * Settings and progress, in sections as Stats has them (a mono label under a hairline):
 * Learning (how much is new at a time and per day, how many reviews a round, how often a
 * review asks you to write), Voice (said
 * aloud or not, its speed, the line when the phone has no Chinese voice), Reminder (the time,
 * the line when it went back to off), Progress (copied out to the clipboard and back in),
 * Updates (the version, the token, a check); the credits last, in small text.
 */
class Settings {
    final Main m;
    TextView status, version, io, note, timing;
    View voice;
    boolean armed;

    static final String[] ROUNDS = {"20", "40", "60", "all"};
    static final String[] WRITING = {"off", "sometimes", "often"};   // Quiz.WRITE: how often a review asks you to write

    Settings(Main m) { this.m = m; }

    void show() {
        LinearLayout col = m.page("settings", this::show);
        col.addView(Cards.bar(m, "SETTINGS AND PROGRESS", Ui.close(m, v -> m.home())));
        Progress p = m.prog;

        // learning: how much comes at once
        LinearLayout learning = section("Learning");
        learning.addView(choice("New at a time", new String[]{"3", "5", "8", "10"}, String.valueOf(p.batch),
                v -> { p.batch = Integer.parseInt(v); p.saveOpts(); }));
        learning.addView(choice("New per day", new String[]{"10", "15", "20", "30", "50"}, String.valueOf(p.perDay),
                v -> { p.perDay = Integer.parseInt(v); p.saveOpts(); }));
        learning.addView(choice("Reviews per round", ROUNDS, p.round <= 0 ? "all" : String.valueOf(p.round),
                v -> { p.round = v.equals("all") ? 0 : Integer.parseInt(v); p.saveOpts(); }));
        learning.addView(choice("Writing in reviews", WRITING, p.write, v -> { p.write = v; p.saveOpts(); }));
        // review timing (FSRS): its line goes here, last in Learning
        col.addView(learning);

        // voice
        LinearLayout vs = section("Voice");
        vs.addView(choice("Say it aloud", new String[]{"on", "off"}, p.say ? "on" : "off",
                v -> {
                    p.say = v.equals("on");
                    p.saveOpts();
                    if (p.say) Speech.check(m);
                    voice.setVisibility(p.say && !Speech.voice() ? View.VISIBLE : View.GONE);
                }));
        vs.addView(small("Off also drops the listening quizzes."));
        vs.addView(choice("Voice speed", new String[]{"normal", "slower"}, p.speed, v -> { p.speed = v; p.saveOpts(); }));
        voice = noVoice();
        voice.setVisibility(p.say && !Speech.voice() ? View.VISIBLE : View.GONE);
        vs.addView(voice);
        col.addView(vs);

        // reminder
        LinearLayout rs = section("Reminder");
        rs.addView(choice("Reminder", Reminder.TIMES, Reminder.label(p.remind), v -> {
            p.remind = Reminder.minutes(v);
            p.saveOpts();
            Reminder.arm(m);
            note.setVisibility(View.GONE);
            if (p.remind >= 0) m.askToNotify();
        }));
        note = small("");
        note.setVisibility(View.GONE);
        rs.addView(note);
        col.addView(rs);

        // progress
        LinearLayout pr = section("Progress");
        pr.addView(small("Kept in the app's database, and in Android's backup. Copy it out to keep a copy or move it to another phone."));
        Flow acts = new Flow(m, 8);   // wraps once the text is large
        acts.addView(Ui.pill(m, "Copy progress", Ui.OUTLINE, v -> copyOut()));
        final TextView load = Ui.pill(m, "Load from clipboard", Ui.OUTLINE, null);
        load.setOnClickListener(v -> loadIn(load));
        acts.addView(load);
        pr.addView(acts);
        io = small("");
        io.setVisibility(View.GONE);
        pr.addView(io);
        pr.addView(timing());
        col.addView(pr);

        // updates
        LinearLayout up = section("Updates");
        up.addView(small("The app updates itself from the repo's latest release. It needs a GitHub token that can read hanzi-path (fine-grained, Contents: read-only)."));
        version = small("");
        up.addView(version);
        final EditText token = new EditText(m);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTypeface(Ink.mono);
        token.setTextSize(14);
        token.setTextColor(Ink.ink);
        token.setHintTextColor(Ink.ink2);
        token.setSingleLine(true);
        token.setBackground(Ui.shape(Ink.paper2, Ink.rule, 999, false));
        token.setPadding(Ink.dp(14), Ink.dp(10), Ink.dp(14), Ink.dp(10));
        token.setHint(prefs().getString("token", "").isEmpty() ? "GitHub token" : "Token saved. Paste a new one to replace it.");
        token.setMinHeight(Ink.dp(44));   // as tall as large text needs, 44 dp at least
        token.setContentDescription("GitHub token");
        up.addView(token, new LinearLayout.LayoutParams(-1, -2));
        Flow ua = new Flow(m, 8);
        ua.addView(Ui.pill(m, "Save token", Ui.OUTLINE, v -> {
            String t = token.getText().toString().trim();
            if (t.isEmpty()) { status.setText("Paste the token first."); return; }
            prefs().edit().putString("token", t).putLong("checked", 0).apply();
            token.setText("");
            token.setHint("Token saved. Paste a new one to replace it.");
            Updater.maybe(m, true);
        }));
        ua.addView(Ui.pill(m, "Check now", Ui.OUTLINE, v -> { status.setText("Checking…"); Updater.maybe(m, true); }));
        up.addView(ua);
        status = small("");
        up.addView(status);
        col.addView(up);

        // the credits, last, in smaller text under a hairline
        TextView credits = Ui.text(m, "Characters, strokes and etymology: Make Me a Hanzi. Order: Jun Da's character frequencies. "
                + "Meanings, and words beyond HSK: CC-CEDICT (CC BY-SA 4.0). Words: HSK 2.0. Meanings of parts: written for this app. Sentences: Tatoeba (CC BY 2.0 FR). Scheduling: FSRS-4.5.", 12, Ink.sans, Ink.ink2);
        credits.setPadding(0, Ink.dp(14), 0, 0);
        credits.setBackground(Home.topRule());
        col.addView(credits);
        status();
        if (p.say) Speech.check(m);   // whether there's a voice, for the line under Say it aloud
    }

    SharedPreferences prefs() { return m.getSharedPreferences("hanzi", Context.MODE_PRIVATE); }

    void status() {
        SharedPreferences pr = prefs();
        long checked = pr.getLong("checked", 0);
        long code = 0;
        try { code = m.getPackageManager().getPackageInfo(m.getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { /* */ }
        version.setText("Version " + code + " · checked " + (checked == 0 ? "never"
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(checked))));
        status.setText(pr.getString("status", ""));
    }

    interface Pick { void set(String v); }

    /** A setting: its name, and a pill that steps through the values (under the name, at the
     *  end, when large text won't fit both on a line). */
    View choice(String name, String[] values, String current, Pick pick) {
        final int[] at = {java.util.Arrays.asList(values).indexOf(current)};
        if (at[0] < 0) at[0] = 0;
        final TextView b = Ui.small(m, values[at[0]] + "  ›", null);
        b.setTypeface(Ink.monoMedium);   // a value: the mono, as figures are
        b.setOnClickListener(v -> {
            at[0] = (at[0] + 1) % values.length;
            b.setText(values[at[0]] + "  ›");
            b.setContentDescription(name + ": " + values[at[0]] + ". Tap to change.");
            pick.set(values[at[0]]);
        });
        b.setContentDescription(name + ": " + values[at[0]] + ". Tap to change.");
        return Ui.pair(m, 12, Ui.text(m, name, 15, Ink.sans, Ink.ink), b);
    }

    /** A group of settings: a hairline above, its label, then its rows (and its explanation,
     *  where it has one, first under the label). */
    LinearLayout section(String title) {
        return Ui.section(m, title, null);
    }

    TextView small(String s) { return Ui.note(m, s); }

    /** Under Say it aloud, on a phone without a Chinese voice (Speech): why it's silent, and a
     *  pill to Android's text-to-speech settings to install one. */
    View noVoice() {
        LinearLayout c = Ui.column(m, 8);
        c.addView(small("No Chinese voice on this phone: install one in Android's text-to-speech settings."));
        TextView go = Ui.pill(m, "Text-to-speech settings", Ui.OUTLINE, v -> m.openVoiceSettings());
        go.setMinHeight(Ink.dp(36));
        go.setTextSize(14);
        go.setPadding(Ink.dp(14), Ink.dp(4), Ink.dp(14), Ink.dp(4));
        go.setContentDescription("Open Android's text-to-speech settings");
        c.addView(go, new LinearLayout.LayoutParams(-2, -2));
        return c;
    }

    /** The line under the reminder: why it went back to off. */
    void remindNote(String s) {
        note.setText(s);
        note.setVisibility(View.VISIBLE);
    }

    void say(String s) {
        io.setText(s);
        io.setVisibility(View.VISIBLE);
    }

    /** Progress's line on the review timing: fitted to the learner's reviews (Fit), or the defaults and
     *  why; a small pill to put the defaults back, or to fit again after that. */
    View timing() {
        LinearLayout r = Ui.row(m, 10);
        timing = small("");
        r.addView(timing, Ui.weight(1));
        final TextView b = Ui.pill(m, "", Ui.OUTLINE, null);
        b.setMinHeight(Ink.dp(36));
        b.setTextSize(14);
        b.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        r.addView(b);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            Progress p = m.prog;
            timing.setText(timingLine(p, p.logCounts()));
            boolean fitted = p.w != Progress.W, reset = p.fsrs != null && p.fsrs.optBoolean("reset");
            b.setVisibility(fitted || reset ? View.VISIBLE : View.GONE);
            b.setText(fitted ? "Reset to defaults" : "Fit to me");
            b.setContentDescription(fitted ? "Put the review timing back to the defaults" : "Fit the review timing to your reviews again");
            b.setOnClickListener(v -> {
                if (p.w != Progress.W) p.resetFit(System.currentTimeMillis());
                else {
                    p.clearFit();
                    Fit.start(p, System.currentTimeMillis(), true, () -> { if (timing.isAttachedToWindow()) fill[0].run(); });
                }
                fill[0].run();
            });
        };
        fill[0].run();
        return r;
    }

    /** "Review timing: fitted to your 1240 reviews (Nov 7)", or the defaults and why. counts:
     *  the log's grades, and the items they're of (Progress.logCounts). */
    static String timingLine(Progress p, long[] counts) {
        String on = p.fsrs == null ? "" : new java.text.SimpleDateFormat("MMM d", java.util.Locale.ENGLISH).format(new Date(p.fsrs.optLong("at")));
        int n = p.fsrs == null ? 0 : p.fsrs.optInt("n");
        String why;
        if (p.fitting != null) why = "the defaults, fitting to your " + counts[0] + " reviews now…";
        else if (p.w != Progress.W) return "Review timing: fitted to your " + n + " reviews (" + on + ")";
        else if (p.fsrs != null && p.fsrs.optBoolean("reset")) why = "the defaults (put back on " + on + ")";
        else if (p.fsrs != null) why = "the defaults (a fit to your " + n + " reviews predicted no better, " + on + ")";
        else if (counts[0] < Fit.MIN_REVIEWS) why = "the defaults (fitted once you have " + Fit.MIN_REVIEWS + " reviews; you have " + counts[0] + ")";
        else if (counts[0] - counts[1] < Fit.MIN_SCORED)
            why = "the defaults (fitted once " + Fit.MIN_SCORED + " reviews are of things seen before; you have " + (counts[0] - counts[1]) + ")";
        else why = "the defaults (fitted to your " + counts[0] + " reviews after your next round)";
        return "Review timing: " + why;
    }

    void copyOut() {
        try {
            String text = m.prog.export();
            ClipboardManager cb = (ClipboardManager) m.getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Hanzi Path progress", text));
            say("Copied: " + m.prog.cards.size() + " cards. Paste it somewhere safe.");
        } catch (Exception e) {
            say("Couldn't copy it: " + e.getMessage());
        }
    }

    void loadIn(TextView btn) {
        ClipboardManager cb = (ClipboardManager) m.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData c = cb.getPrimaryClip();
        String text = c == null || c.getItemCount() == 0 ? "" : String.valueOf(c.getItemAt(0).coerceToText(m));
        int n = Progress.cardsIn(text);
        if (n < 0) { say("The clipboard has no progress copy in it. Copy the whole text Copy progress gave you."); armed = false; return; }
        if (!armed) {
            armed = true;
            btn.setText("Replace with " + n + " cards");
            say("This replaces the progress on this phone. Tap again to load it.");
            return;
        }
        armed = false;
        btn.setText("Load from clipboard");
        try {
            m.prog.replace(text);
            Reminder.arm(m);
            say("Loaded " + m.prog.cards.size() + " cards.");
        } catch (Exception e) {
            say("Couldn't load it. Nothing was changed.");
        }
    }
}
