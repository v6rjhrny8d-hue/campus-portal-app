package org.campusportal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import org.campusportal.app.core.RunControl;
import org.campusportal.app.core.RunControl.Reason;

public final class WorkService extends Service {
  private static final String CHANNEL = "campus_run";
  private TaskRunner task;
  private boolean stopping;
  private int latestStartId;
  private final long createdElapsed = SystemClock.elapsedRealtime();

  public static boolean isRunning() { return TaskRunner.isRunning(); }

  public static void start(Context context, String action) { start(context, action, ""); }

  public static void start(Context context, String action, String loginUrl) {
    AppStore store = new AppStore(context);
    long end = store.config().schedule().windowEnd(System.currentTimeMillis());
    if ("scheduled".equals(action) && store.blockedForegroundWindowEnd() == end && end > 0) {
      RecoveryScheduler.enqueue(context, end, 0);
      return;
    }
    if ("scheduled".equals(action))
      store.log("定时检测直接申请前台服务；休眠状态不再仅交给系统排队");
    if (!"scheduled".equals(action) && !TaskRunner.isTestMode(action)) RecoveryScheduler.cancel(context);
    Intent intent = new Intent(context, WorkService.class).setAction(action);
    if (loginUrl != null && !loginUrl.isEmpty()) intent.putExtra("loginUrl", loginUrl);
    try { context.startForegroundService(intent); }
    catch (RuntimeException e) {
      store.log("系统拒绝启动前台任务；模式=" + action + "；类型=" + e.getClass().getSimpleName());
      store.state("interrupted", "系统未允许启动后台任务，可打开应用后重试");
      if ("scheduled".equals(action) && store.eligible(end)) {
        store.blockedForegroundWindowEnd(end);
        RecoveryScheduler.enqueue(context, end, 0);
      } else throw e;
    }
  }

  public static void cancel(Context context) {
    if (TaskRunner.isSelfTest()) { TaskRunner.cancel(Reason.USER); return; }
    AppStore store = new AppStore(context);
    long end = store.config().schedule().windowEnd(System.currentTimeMillis());
    if (end > 0) store.stoppedWindowEnd(end);
    Reconnect.clear(context);
    RecoveryScheduler.cancel(context);
    TaskRunner.cancel(store.enabled() ? Reason.USER : Reason.PLAN_DISABLED);
    if (!TaskRunner.isRunning()) store.state("stopped", store.enabled()
        ? "已停止本时段任务，保留下次自动计划；仍可手动登录" : "自动计划已关闭，本次任务已停止");
    ScheduleManager.plan(context, false);
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    AppStore store = new AppStore(this);
    if (stopping) { stopSelf(startId); return START_NOT_STICKY; }
    if (task != null) {
      store.log("合并重复启动；startId=" + startId + "；保留任务 " + task.id + " 的原始结束时间");
      return START_NOT_STICKY;
    }
    String mode = intent == null ? null : intent.getAction();
    if (!"scheduled".equals(mode) && !"login".equals(mode) && !"linkLogin".equals(mode)
        && !"diagnose".equals(mode) && !TaskRunner.isTestMode(mode) && !"web".equals(mode)) {
      stopSelf(startId); return START_NOT_STICKY;
    }
    long end = store.config().schedule().windowEnd(System.currentTimeMillis());
    if ("scheduled".equals(mode) && !store.eligible(end)) {
      store.log("定时任务已过期、已停止或已完成，本次跳过");
      stopSelf(startId); return START_NOT_STICKY;
    }
    task = TaskRunner.acquire(this, mode, intent.getStringExtra("loginUrl"), false, end,
        new TaskRunner.Listener() {
          public void progress(TaskRunner runner, String text) {
            if (!stopping && task == runner) {
              // Progress must never re-enter startForeground after a timeout.
              try { manager().notify(1, notification(text)); } catch (RuntimeException ignored) {}
            }
          }
          public void finished(TaskRunner runner, Reason reason) {
            if (task != runner) return;
            task = null;
            stopping = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(latestStartId);
            if (runner.scheduled && RunControl.needsRecovery(reason) && store.eligible(runner.windowEnd)) {
              store.blockedForegroundWindowEnd(runner.windowEnd);
              RecoveryScheduler.enqueue(WorkService.this, runner.windowEnd, 0);
            } else if (!runner.scheduled && reason == Reason.FINISHED && !TaskRunner.isTestMode(runner.mode) && store.enabled()) {
              ScheduleManager.plan(WorkService.this, true);
            }
          }
        });
    if (task == null) { stopSelf(startId); return START_NOT_STICKY; }
    store.log("前台服务启动；startId=" + startId + "；实例=" + Integer.toHexString(System.identityHashCode(this))
        + "；已记录24h前台运行=" + store.recordedForegroundMillis() + "ms");
    try {
      manager().createNotificationChannel(new NotificationChannel(CHANNEL, "校园网检测与认证", NotificationManager.IMPORTANCE_LOW));
      startForeground(1, notification("正在检查宿舍 Wi-Fi"));
    } catch (RuntimeException e) {
      store.log("进入前台失败：" + e.getClass().getSimpleName());
      task.stop(Reason.START_REJECTED);
      return START_NOT_STICKY;
    }
    try { task.start(); }
    catch (RuntimeException e) {
      store.log("启动网络任务失败：" + e.getClass().getSimpleName());
      task.stop(Reason.ERROR);
    }
    return START_NOT_STICKY;
  }

  private NotificationManager manager() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

  private Notification notification(String detail) {
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("校园网助手").setContentText(detail).setContentIntent(open).setOngoing(true).build();
  }

  @Override public void onTimeout(int startId, int fgsType) {
    boolean alreadyStopping = stopping;
    stopping = true;
    // Stop promptly; never wait for a socket or worker in a lifecycle callback.
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    AppStore store = new AppStore(this);
    store.log("系统前台超时回调；startId=" + startId + "；最近startId=" + latestStartId
        + "；fgsType=" + fgsType + "；服务实例=" + Integer.toHexString(System.identityHashCode(this))
        + "；实例存活=" + (SystemClock.elapsedRealtime() - createdElapsed) + "ms；任务="
        + (task == null ? "尚未开始" : task.id + "，运行=" + task.control.duration() + "ms"));
    if (task != null) task.stop(Reason.SYSTEM_TIMEOUT);
    else if (!alreadyStopping) {
      long end = store.config().schedule().windowEnd(System.currentTimeMillis());
      if (store.eligible(end)) {
        store.blockedForegroundWindowEnd(end);
        RecoveryScheduler.enqueue(this, end, 0);
      }
    }
  }

  @Override public void onDestroy() {
    stopping = true;
    if (task != null) task.stop(Reason.SERVICE_DESTROYED);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override public IBinder onBind(Intent intent) { return null; }
}
