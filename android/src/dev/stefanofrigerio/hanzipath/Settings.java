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
 * Settings and progress: how much is new at a time and per day, how many reviews a round, the voice, the daily reminder;
 * the progress copied out to the clipboard and back in; the updates.
 */
class Settings {
    final Main m;
    TextView status, version, io, note;
    boolean armed;

    static final String[] ROUNDS = {"20", "40", "60", "all"};

    Settings(Main m) { this.m = m; }

    void show() {
        LinearLayout col = m.page("settings", this::show);
        col.addView(Cards.bar(m, "SETTINGS AND PROGRESS", Ui.close(m, v -> m.home())));
        Progress p = m.prog;

        LinearLayout rows = Ui.column(m, 10);
        rows.addView(choice("New at a time", new String[]{"3", "5", "8", "10"}, String.valueOf(p.batch),
                v -> { p.batch = Integer.parseInt(v); p.saveOpts(); }));
        rows.addView(choice("New per day", new String[]{"10", "15", "20", "30", "50"}, String.valueOf(p.perDay),
                v -> { p.perDay = Integer.parseInt(v); p.saveOpts(); }));
        rows.addView(choice("Reviews per round", ROUNDS, p.round <= 0 ? "all" : String.valueOf(p.round),
                v -> { p.round = v.equals("all") ? 0 : Integer.parseInt(v); p.saveOpts(); }));
        rows.addView(choice("Say it aloud", new String[]{"on", "off"}, p.say ? "on" : "off",
                v -> { p.say = v.equals("on"); p.saveOpts(); }));
        rows.addView(choice("Reminder", Reminder.TIMES, Reminder.label(p.remind), v -> {
            p.remind = Reminder.minutes(v);
            p.saveOpts();
            Reminder.arm(m);
            note.setVisibility(View.GONE);
            if (p.remind >= 0) m.askToNotify();
        }));
        note = small("");
        note.setVisibility(View.GONE);
        rows.addView(note);
        col.addView(rows);

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
        col.addView(pr);

        // updates
        LinearLayout up = section("Updates");
        version = small("");
        up.addView(version);
        up.addView(small("The app updates itself from the repo's latest release. It needs a GitHub token that can read hanzi-path (fine-grained, Contents: read-only)."));
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

        TextView credits = small("Characters, strokes and etymology: Make Me a Hanzi. Order: Jun Da's character frequencies. "
                + "Meanings, and words beyond HSK: CC-CEDICT (CC BY-SA 4.0). Words: HSK 2.0. Meanings of parts: written for this app. Scheduling: FSRS-4.5.");
        credits.setPadding(0, Ink.dp(8), 0, 0);
        col.addView(credits);
        status();
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
        final TextView b = Ui.pill(m, values[at[0]] + "  ›", Ui.OUTLINE, null);
        b.setTypeface(Ink.monoMedium);
        b.setTextSize(14);
        b.setMinHeight(Ink.dp(38));
        b.setPadding(Ink.dp(14), 0, Ink.dp(14), 0);
        b.setOnClickListener(v -> {
            at[0] = (at[0] + 1) % values.length;
            b.setText(values[at[0]] + "  ›");
            b.setContentDescription(name + ": " + values[at[0]] + ". Tap to change.");
            pick.set(values[at[0]]);
        });
        b.setContentDescription(name + ": " + values[at[0]] + ". Tap to change.");
        return Ui.pair(m, 12, Ui.text(m, name, 15, Ink.sans, Ink.ink), b);
    }

    LinearLayout section(String title) {
        LinearLayout c = Ui.column(m, 10);
        c.setPadding(0, Ink.dp(14), 0, 0);
        c.setBackground(Home.topRule());
        c.addView(Ui.label(m, title));
        return c;
    }

    TextView small(String s) { return Ui.text(m, s, 13, Ink.sans, Ink.ink2); }

    /** The line under the reminder: why it went back to off. */
    void remindNote(String s) {
        note.setText(s);
        note.setVisibility(View.VISIBLE);
    }

    void say(String s) {
        io.setText(s);
        io.setVisibility(View.VISIBLE);
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
