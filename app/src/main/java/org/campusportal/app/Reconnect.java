package org.campusportal.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PersistableBundle;
import java.time.Clock;
import java.util.Calendar;
import java.util.TimeZone;

/** One deferred check when a morning task never obtained a usable dorm Wi-Fi. */
public final class Reconnect extends BroadcastReceiver {
  static final int JOB = 73;
  static final String WINDOW = "sourceWindow";
  private static final String NETWORK = "org.campusportal.app.RECONNECT_WIFI";
  private static final String EXPIRE = "org.campusportal.app.EXPIRE_RECONNECT";
  static Clock clock = Clock.systemUTC();

  static long now() { return clock.millis(); }

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("morningReconnect", Context.MODE_PRIVATE);
  }

  static long noon(long end) {
    Calendar day = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
    day.setTimeInMillis(end);
    day.set(Calendar.HOUR_OF_DAY, 12);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MILLISECOND, 0);
    return day.getTimeInMillis();
  }

  static boolean valid(Context c, long end) {
    SharedPreferences p = prefs(c);
    if (end <= 0 || end != p.getLong(WINDOW, -1)) return false;
    AppStore s = new AppStore(c);
    long time = now();
    return time >= p.getLong("armedAt", Long.MAX_VALUE) && time < p.getLong("until", 0)
        && s.enabled() && s.revision() == p.getLong("revision", -1)
        && s.completedWindowEnd() != end && s.stoppedWindowEnd() != end;
  }

  static boolean waiting(Context c) {
    return valid(c, prefs(c).getLong(WINDOW, -1)) && !prefs(c).getBoolean("claimed", false);
  }

  private static PendingIntent signal(Context c, String action) {
    // The receiver needs no network from the intent. The job validates its assigned network.
    return PendingIntent.getBroadcast(c, JOB, new Intent(c, Reconnect.class).setAction(action),
        PendingIntent.FLAG_IMMUTABLE);
  }

  static synchronized void arm(Context c, long end) {
    AppStore s = new AppStore(c);
    Config config = s.config();
    long time = now();
    if (config.start >= config.end || config.end >= 720 || end <= 0 || time >= noon(end)
        || !s.enabled() || end == s.completedWindowEnd() || end == s.stoppedWindowEnd()
        || end != config.schedule().windowEnd(end - 1) || prefs(c).getLong(WINDOW, -1) == end) return;
    clear(c);
    if (!prefs(c).edit().putLong(WINDOW, end).putLong("until", noon(end))
        .putLong("armedAt", time).putLong("revision", s.revision()).commit()) return;
    register(c, end);
    s.log("定时等待宿舍 Wi-Fi 未就绪；保留一次连网补登至北京时间 12:00");
  }

  private static void register(Context c, long end) {
    try {
      c.getSystemService(ConnectivityManager.class).registerNetworkCallback(WifiSession.request(), signal(c, NETWORK));
      c.getSystemService(AlarmManager.class).set(AlarmManager.RTC, noon(end), signal(c, EXPIRE));
    } catch (RuntimeException e) {
      new AppStore(c).log("系统未登记补登提醒：" + e.getClass().getSimpleName());
    }
    enqueue(c, end, false);
  }

  private static void enqueue(Context c, long end, boolean newNetwork) {
    if (!waiting(c)) return;
    JobScheduler jobs = c.getSystemService(JobScheduler.class);
    if (jobs.getPendingJob(JOB) != null && (!newNetwork || TaskRunner.isRunning() || now() < end)) return;
    PersistableBundle extras = new PersistableBundle();
    extras.putLong(WINDOW, end);
    JobInfo.Builder job = new JobInfo.Builder(JOB, new ComponentName(c, ReconnectJob.class))
        .setExtras(extras).setMinimumLatency(Math.max(0, end - now()))
        .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    if (Build.VERSION.SDK_INT >= 28) job.setRequiredNetwork(WifiSession.request());
    else job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    try {
      if (jobs.schedule(job.build()) != JobScheduler.RESULT_SUCCESS)
        new AppStore(c).log("系统暂未接受补登任务，等待下次 Wi-Fi 通知");
    } catch (RuntimeException e) { new AppStore(c).log("补登排队失败：" + e.getClass().getSimpleName()); }
  }

  static synchronized boolean claim(Context c, long end) {
    return now() >= end && valid(c, end) && !prefs(c).getBoolean("claimed", false)
        && prefs(c).edit().putBoolean("claimed", true).commit();
  }

  static synchronized void restore(Context c) {
    long end = prefs(c).getLong(WINDOW, -1);
    if (end <= 0) return;
    if (waiting(c)) register(c, end);
    else if (!valid(c, end) || !TaskRunner.isRunning()) clear(c);
  }

  static synchronized void clear(Context c) {
    if (!prefs(c).contains(WINDOW)) return;
    prefs(c).edit().clear().commit();
    try { c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(signal(c, NETWORK)); }
    catch (RuntimeException ignored) { /* The registration can already be gone after reboot. */ }
    c.getSystemService(AlarmManager.class).cancel(signal(c, EXPIRE));
    c.getSystemService(JobScheduler.class).cancel(JOB);
  }

  @Override public void onReceive(Context c, Intent event) {
    long end = prefs(c).getLong(WINDOW, -1);
    if (!valid(c, end)) { clear(c); return; }
    if (NETWORK.equals(event.getAction())) enqueue(c, end, true);
  }
}
