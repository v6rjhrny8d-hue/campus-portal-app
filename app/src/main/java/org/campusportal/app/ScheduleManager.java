package org.campusportal.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ScheduleManager {
  public static final String RUN = "org.campusportal.app.SCHEDULED_RUN";

  private static PendingIntent pending(Context context) {
    return PendingIntent.getBroadcast(
        context,
        7,
        new Intent(context, ScheduleReceiver.class).setAction(RUN),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static boolean allowed(Context context) {
    AlarmManager a = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    return Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms();
  }

  static int bootCount(Context context) {
    try {
      return android.provider.Settings.Global.getInt(context.getContentResolver(),
          android.provider.Settings.Global.BOOT_COUNT, -1);
    } catch (RuntimeException ignored) { return -1; } // Diagnostics must never prevent scheduling.
  }

  public static long plan(Context context, boolean catchUp) {
    RecoveryScheduler.reconcile(context);
    AppStore store = new AppStore(context);
    AlarmManager a = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    a.cancel(pending(context));
    store.planned(-1);
    if (!store.enabled() || !store.config().bound || !store.credentialsReady() || !allowed(context)) {
      RecoveryScheduler.cancel(context);
      return -1;
    }
    long now = System.currentTimeMillis();
    long window = store.config().schedule().windowEnd(now);
    if (catchUp && !TaskRunner.isRunning() && store.eligible(window)
        && window == store.blockedForegroundWindowEnd() && !RecoveryScheduler.pending(context))
      RecoveryScheduler.enqueue(context, window, 0);
    boolean mayCatchUp = catchUp && !TaskRunner.isRunning() && !RecoveryScheduler.pending(context)
        && window != store.stoppedWindowEnd() && window != store.blockedForegroundWindowEnd();
    long time = store.config().schedule().nextStart(now, mayCatchUp, store.completedWindowEnd());
    try {
      long wall = System.currentTimeMillis(), elapsed = android.os.SystemClock.elapsedRealtime();
      a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context));
      store.planned(time, wall, elapsed, bootCount(context));
      return time;
    } catch (SecurityException e) {
      store.log("定时权限不足，自动计划尚未安排；请在设置中允许准时启动");
      return -1;
    }
  }
}
