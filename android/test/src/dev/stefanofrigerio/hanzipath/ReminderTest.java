package dev.stefanofrigerio.hanzipath;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The daily reminder: the setting, the alarm, the notification, the boot, the permission. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi")
public class ReminderTest {
    final WalkTest walk = new WalkTest();

    static ShadowAlarmManager alarms(Context c) { return shadowOf((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)); }

    static ShadowNotificationManager notes(Context c) { return shadowOf((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)); }

    static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }

    static void fire(Context c, String action) {
        new Reminder().onReceive(c, new Intent(action).setClass(c, Reminder.class));
        ShadowLooper.idleMainLooper();
    }

    static void due(Main m, String h) {
        m.prog.grade(h, 3, "test", 0);
        Progress.Card c = m.prog.cards.get(h);
        c.due = System.currentTimeMillis() - 60_000;
        m.prog.putCard(h, c);
    }

    @Test
    public void reminder() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("off by default", -1, m.prog.remind);
        assertNull("no alarm while it's off", alarms(app).peekNextScheduledAlarm());

        // the setting steps through the times; each one arms the alarm for it
        m.openSettings();
        walk.shot(m, "r0-settings-off");
        WalkTest.tap(m, "off");
        assertEquals(8 * 60, m.prog.remind);
        assertNotNull(WalkTest.find(m.root, "8:00"));
        assertNull("granted: nothing asked", shadowOf(m).getLastRequestedPermission());
        WalkTest.tap(m, "8:00");
        WalkTest.tap(m, "12:00");
        WalkTest.tap(m, "18:00");
        assertEquals(20 * 60, m.prog.remind);
        ShadowAlarmManager.ScheduledAlarm a = alarms(app).peekNextScheduledAlarm();
        assertNotNull("armed", a);
        long now = System.currentTimeMillis();
        assertEquals(AlarmManager.RTC_WAKEUP, a.getType());
        assertEquals(Reminder.next(20 * 60, now), a.getTriggerAtMs());
        assertTrue("the next 20:00, within a day", a.getTriggerAtMs() > now && a.getTriggerAtMs() <= now + Progress.DAY);
        assertEquals("one alarm, not one per step", 1, alarms(app).getScheduledAlarms().size());
        walk.shot(m, "r1-settings");
        assertEquals("kept with the other opts", 20 * 60, new Progress(m).remind);

        // nothing due: nothing said, and armed for tomorrow
        fire(app, Reminder.FIRE);
        assertEquals(0, notes(app).getAllNotifications().size());
        assertNotNull(alarms(app).peekNextScheduledAlarm());

        // two due: one notification, on the Reminders channel, opening the app
        due(m, "人");
        due(m, "口");
        m.prog.grade("日", 3, "test", 0);   // met, not due
        fire(app, Reminder.FIRE);
        List<Notification> ns = notes(app).getAllNotifications();
        assertEquals(1, ns.size());
        Notification n = ns.get(0);
        assertEquals("2 to review", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        String text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
        assertTrue(text, text.contains("人") && text.contains("口") && !text.contains("日"));
        assertEquals(Reminder.CHANNEL, n.getChannelId());
        assertEquals("Reminders", String.valueOf(nm(app).getNotificationChannel(Reminder.CHANNEL).getName()));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm(app).getNotificationChannel(Reminder.CHANNEL).getImportance());
        assertNotEquals(0, n.getSmallIcon().getResId());
        Intent open = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals(Main.class.getName(), open.getComponent().getClassName());
        assertEquals(1, alarms(app).getScheduledAlarms().size());

        // a reboot forgets alarms: the boot receiver (in the manifest) sets it again
        ((AlarmManager) app.getSystemService(Context.ALARM_SERVICE)).cancel(Reminder.alarm(app));
        assertNull(alarms(app).peekNextScheduledAlarm());
        List<ResolveInfo> boot = app.getPackageManager().queryBroadcastReceivers(
                new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.getPackageName()), 0);
        assertTrue("registered for the boot", boot.stream().anyMatch(r -> r.activityInfo.name.endsWith(".Reminder")));
        fire(app, Intent.ACTION_BOOT_COMPLETED);
        a = alarms(app).peekNextScheduledAlarm();
        assertNotNull("armed again after the boot", a);
        assertEquals(Reminder.next(20 * 60, System.currentTimeMillis()), a.getTriggerAtMs());

        // off: the alarm goes, and a fire (a late one) says nothing
        WalkTest.tap(m, "20:00");
        WalkTest.tap(m, "21:30");
        assertEquals(-1, m.prog.remind);
        assertNull("off: no alarm", alarms(app).peekNextScheduledAlarm());
        nm(app).cancelAll();
        Reminder.remind(app, System.currentTimeMillis());
        assertEquals(0, notes(app).getAllNotifications().size());
    }

    @Test
    public void refused() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        m.openSettings();
        WalkTest.tap(m, "off");
        ShadowActivity.PermissionsRequest ask = shadowOf(m).getLastRequestedPermission();
        assertNotNull("asked when turned on", ask);
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, ask.requestedPermissions[0]);
        m.onRequestPermissionsResult(ask.requestCode, ask.requestedPermissions, new int[]{PackageManager.PERMISSION_DENIED});
        ShadowLooper.idleMainLooper();
        assertEquals("back to off", -1, m.prog.remind);
        assertEquals(-1, new Progress(m).remind);
        assertNull(alarms(app).peekNextScheduledAlarm());
        assertNotNull("the row says off", WalkTest.find(m.root, "off"));
        TextView note = WalkTest.find(m.root, "Notifications aren't allowed");
        assertNotNull("and why", note);
        assertEquals(View.VISIBLE, note.getVisibility());
        walk.shot(m, "r2-refused");
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    public void night() throws Exception {
        Main m = Robolectric.buildActivity(Main.class).setup().visible().get();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        m.openSettings();
        WalkTest.tap(m, "off");
        WalkTest.tap(m, "8:00");
        WalkTest.tap(m, "12:00");
        WalkTest.tap(m, "18:00");
        WalkTest.tap(m, "20:00");
        assertEquals(21 * 60 + 30, m.prog.remind);
        walk.shot(m, "rn1-settings");
    }
}
