package dev.stefanofrigerio.hanzipath;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

/**
 * The daily reminder, the app's only notification: at the time set in Settings (off by
 * default), if something on the path is due then, "12 to review" and the first of them;
 * a tap opens the app. Nothing due, nothing said.
 *
 * An inexact alarm (AlarmManager.setWindow, no exact-alarm permission), armed again after
 * it fires, when the setting changes, when the app opens, after a reboot and after an
 * update. The database is only read, opened read-only (Progress.peek).
 */
public class Reminder extends BroadcastReceiver {
    static final String FIRE = "dev.stefanofrigerio.hanzipath.REMIND";
    static final String CHANNEL = "reminders";
    static final int ID = 1;
    static final long WINDOW = 10 * 60_000L;
    /** Settings' values, and the minutes after midnight each stands for (-1 off). */
    static final String[] TIMES = {"off", "8:00", "12:00", "18:00", "20:00", "21:30"};

    static int minutes(String t) {
        if (t.equals("off")) return -1;
        String[] hm = t.split(":");
        return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
    }

    static String label(int min) { return min < 0 ? "off" : (min / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", min % 60); }

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (FIRE.equals(i.getAction())) remind(ctx, System.currentTimeMillis());
        arm(ctx);   // the next one (also: BOOT_COMPLETED, MY_PACKAGE_REPLACED)
    }

    /** The next reminder set from what's stored: cancelled when it's off. */
    static void arm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarm(ctx);
        am.cancel(pi);
        int min = Progress.peek(ctx, 0).remind;
        if (min < 0) return;
        am.setWindow(AlarmManager.RTC_WAKEUP, next(min, System.currentTimeMillis()), WINDOW, pi);
    }

    /** The first moment after now at that time of day. */
    static long next(int min, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, min / 60);
        c.set(Calendar.MINUTE, min % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DATE, 1);
        return c.getTimeInMillis();
    }

    static PendingIntent alarm(Context ctx) {
        Intent i = new Intent(ctx, Reminder.class).setAction(FIRE);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The notification, when it's on and something is due at that moment. */
    static void remind(Context ctx, long now) {
        try {
            PathData.load(ctx);
        } catch (Exception e) {
            return;
        }
        Progress.Peek p = Progress.peek(ctx, now);
        if (p.remind < 0 || p.due.isEmpty()) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Once a day, at the time in Settings, when reviews are due.");
        nm.createNotificationChannel(ch);
        Ink.init(ctx);
        Intent open = new Intent(ctx, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(ctx.getResources().getIdentifier("remind", "drawable", ctx.getPackageName()))
                .setColor(Ink.tone[1])
                .setContentTitle(p.due.size() + " to review")
                .setContentText(first(p.due) + " · 汉字之路")
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(ID, n);
    }

    /** The most overdue few, as they'd come in the review. */
    static String first(List<String> due) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(6, due.size()); i++) b.append(i == 0 ? "" : " ").append(due.get(i));
        if (due.size() > 6) b.append(" …");
        return b.toString();
    }
}
