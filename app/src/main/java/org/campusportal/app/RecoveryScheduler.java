package org.campusportal.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PersistableBundle;

/** Bounded network-constrained work for idle devices and failed foreground network access. */
final class RecoveryScheduler {
  static final int JOB_ID = 71;
  static final String WINDOW = "windowEnd", REVISION = "revision", TOKEN = "token", GENERATION = "generation";

  private static JobScheduler scheduler(Context context) {
    return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
  }

  static boolean eligible(Context context, PersistableBundle extras) {
    AppStore store = new AppStore(context);
    return store.eligible(extras.getLong(WINDOW, -1))
        && extras.getLong(REVISION, -1) == store.revision()
        && extras.getLong(GENERATION, -1) == store.recoveryGeneration();
  }

  static boolean pending(Context context) {
    JobInfo job = scheduler(context).getPendingJob(JOB_ID);
    return job != null && eligible(context, job.getExtras());
  }

  static void cancel(Context context) {
    clearReceipt(context);
    new AppStore(context).invalidateRecovery();
    scheduler(context).cancel(JOB_ID);
  }

  static boolean enqueue(Context context, long windowEnd, long delay) {
    AppStore store = new AppStore(context);
    if (!store.eligible(windowEnd) || windowEnd <= System.currentTimeMillis() + delay) return false;
    if (pending(context)) return true;
    PersistableBundle extras = new PersistableBundle();
    extras.putLong(WINDOW, windowEnd);
    extras.putLong(REVISION, store.revision());
    extras.putLong(GENERATION, store.recoveryGeneration());
    extras.putLong(TOKEN, System.nanoTime());
    boolean urgent = Build.VERSION.SDK_INT >= 31 && delay == 0;
    try {
      int result = scheduler(context).schedule(build(context, extras, delay, urgent));
      if (result != JobScheduler.RESULT_SUCCESS && urgent) {
        store.log("系统备用任务加急额度不足，改为普通系统调度");
        result = scheduler(context).schedule(build(context, extras, delay, false));
      }
      if (result != JobScheduler.RESULT_SUCCESS) {
        store.state("interrupted", "系统未接受备用任务，可打开应用后手动登录");
        store.log("系统备用任务未被接受；本时段不循环重启前台服务");
        return false;
      }
      receipt(context).edit().putLong("recoveryQueuedAt", System.currentTimeMillis())
          .putLong("recoveryQueuedEnd", windowEnd).putLong("recoveryQueuedToken", extras.getLong(TOKEN))
          .putLong("recoveryQueuedRevision", store.revision()).putBoolean("recoveryHasStarted", false)
          .putBoolean("recoveryQueueWarned", false).commit();
      store.state("waiting", "已安排备用检测，等待系统调度；超过 " + AppStore.format(windowEnd, false)
          + " 将跳过，不保证立即执行");
      store.log("系统已接受备用任务排队，尚未确认启动；延迟至少 " + (delay / 1000) + " 秒；最迟允许工作至 "
          + AppStore.format(windowEnd, true) + "；仍只使用绑定 Wi-Fi");
      return true;
    } catch (RuntimeException e) {
      store.state("interrupted", "系统未允许备用检测，请打开应用后手动登录");
      store.log("系统备用任务安排失败：" + e.getClass().getSimpleName());
      return false;
    }
  }

  private static android.content.SharedPreferences receipt(Context context) {
    return context.getSharedPreferences("campus", Context.MODE_PRIVATE);
  }

  private static void clearReceipt(Context context) {
    receipt(context).edit().remove("recoveryQueuedAt").remove("recoveryQueuedEnd")
        .remove("recoveryQueuedToken").remove("recoveryQueuedRevision")
        .remove("recoveryHasStarted").remove("recoveryQueueWarned").commit();
  }

  static void started(Context context, PersistableBundle extras) {
    android.content.SharedPreferences p = receipt(context);
    if (p.getLong("recoveryQueuedToken", -1) != extras.getLong(TOKEN, -2)) return;
    p.edit().putBoolean("recoveryHasStarted", true).commit();
    new AppStore(context).log("备用任务已实际启动；排队等待="
        + Math.max(0, System.currentTimeMillis() - p.getLong("recoveryQueuedAt", System.currentTimeMillis())) + "ms");
  }

  // Reconcile when the app next runs. No claim that a dead process can run a watchdog.
  static void reconcile(Context context) {
    android.content.SharedPreferences p = receipt(context);
    long end = p.getLong("recoveryQueuedEnd", 0);
    if (end <= 0) return;
    AppStore store = new AppStore(context);
    if (p.getLong("recoveryQueuedRevision", -1) != store.revision()
        || !store.enabled() || store.completedWindowEnd() == end || store.stoppedWindowEnd() == end) {
      clearReceipt(context); return;
    }
    long now = System.currentTimeMillis();
    if (now >= end && !TaskRunner.isRunning()) {
      boolean started = p.getBoolean("recoveryHasStarted", false);
      store.log(started ? "补记：备用任务已启动过，但本次时段现已结束，取消过期排队"
          : "补记：本次备用任务排队后未记录到实际启动，时段已结束，取消过期任务");
      if ("waiting".equals(store.phase()))
        store.state("queue_expired", started ? "备用任务时段已结束，联网尚未验证，请查看记录"
            : "备用任务排队后未实际启动，本时段已结束；等待下次计划或手动登录");
      cancel(context);
    } else if (!p.getBoolean("recoveryHasStarted", false) && !TaskRunner.isRunning()
        && "waiting".equals(store.phase()) && pending(context)) {
      long seconds = Math.max(0, now - p.getLong("recoveryQueuedAt", now)) / 1000;
      store.state("waiting", "备用任务仍在排队（已等待 " + seconds + " 秒），尚未实际执行；最迟至 "
          + AppStore.format(end, false));
      if (seconds >= 30 && !p.getBoolean("recoveryQueueWarned", false)) {
        p.edit().putBoolean("recoveryQueueWarned", true).commit();
        store.log("备用任务排队超过30秒，尚未记录到实际启动；排队不代表已执行");
      }
    }
  }

  private static JobInfo build(Context context, PersistableBundle extras, long delay, boolean urgent) {
    JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, new ComponentName(context, RecoveryJobService.class))
        .setExtras(extras).setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    // Captive Wi-Fi is not VALIDATED yet. Requiring validated Internet would prevent login itself.
    if (Build.VERSION.SDK_INT >= 28)
      builder.setRequiredNetwork(WifiSession.request());
    else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    if (urgent) builder.setExpedited(true);
    else if (delay > 0) builder.setMinimumLatency(delay);
    return builder.build();
  }
}
