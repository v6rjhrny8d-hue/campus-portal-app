package org.campusportal.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.campusportal.app.core.RunControl.Reason;

/** Each invocation makes one bounded attempt, then lets JobScheduler own the waiting time. */
public final class RecoveryJobService extends JobService {
  private final Handler main = new Handler(Looper.getMainLooper());
  private JobParameters parameters;
  private TaskRunner task;
  private boolean stopped;

  @Override public boolean onStartJob(JobParameters params) {
    RecoveryScheduler.reconcile(this);
    AppStore store = new AppStore(this);
    if (!RecoveryScheduler.eligible(this, params.getExtras())) {
      store.log("备用任务到达时，时段已过期、已停止、已成功或配置已变化，跳过网络请求");
      if (!TaskRunner.isRunning() && "waiting".equals(store.phase()))
        store.state("interrupted", "备用任务未在有效时段内执行，等待下次计划或手动登录");
      return false;
    }
    parameters = params;
    stopped = false;
    store.log("系统备用任务开始；加急执行=" + (Build.VERSION.SDK_INT >= 31 && params.isExpeditedJob()));
    task = TaskRunner.acquire(this, "scheduled", "", true,
        params.getExtras().getLong(RecoveryScheduler.WINDOW), new TaskRunner.Listener() {
          public void progress(TaskRunner runner, String text) {}
          public void finished(TaskRunner runner, Reason reason) {
            if (task != runner || stopped) return;
            task = null;
            parameters = null;
            boolean retry = reason != Reason.USER && reason != Reason.PLAN_DISABLED
                && reason != Reason.SERVICE_DESTROYED && reason != Reason.JOB_STOPPED
                && !runner.verified && RecoveryScheduler.eligible(RecoveryJobService.this, params.getExtras());
            jobFinished(params, false);
            if (retry) {
              long end = params.getExtras().getLong(RecoveryScheduler.WINDOW);
              long delay = new AppStore(RecoveryJobService.this).config().interval * 1000L;
              main.post(() -> {
                if (RecoveryScheduler.eligible(RecoveryJobService.this, params.getExtras())) {
                  RecoveryScheduler.cancel(RecoveryJobService.this);
                  RecoveryScheduler.enqueue(RecoveryJobService.this, end, delay);
                }
              });
            }
          }
        });
    if (task == null) {
      store.log("已有网络任务，备用任务不并发提交，交回系统稍后安排");
      main.post(() -> {
        if (parameters == params && !stopped) {
          parameters = null;
          jobFinished(params, RecoveryScheduler.eligible(this, params.getExtras()));
        }
      });
      return true;
    }
    if (Build.VERSION.SDK_INT >= 28) {
      task.assignedNetwork(params.getNetwork());
      store.log("使用系统分配网络；网络标识=" + (params.getNetwork() == null
          ? "未分配" : params.getNetwork().getNetworkHandle()));
    }
    try { task.start(); RecoveryScheduler.started(this, params.getExtras()); }
    catch (RuntimeException e) { task.stop(Reason.ERROR); }
    return true;
  }

  private boolean matches(JobParameters params) {
    return parameters != null && parameters.getJobId() == params.getJobId()
        && parameters.getExtras().getLong(RecoveryScheduler.TOKEN) == params.getExtras().getLong(RecoveryScheduler.TOKEN);
  }

  @Override public void onNetworkChanged(JobParameters params) {
    if (!matches(params) || stopped || task == null) return;
    // Never complete a verification or POST on the network whose grant just changed.
    new AppStore(this).log("系统任务分配的网络已变化，停止旧连接并重新等待网络");
    task.stop(Reason.NETWORK_UNAVAILABLE);
  }

  @Override public boolean onStopJob(JobParameters params) {
    if (!matches(params)) return false;
    stopped = true;
    int reason = Build.VERSION.SDK_INT >= 31 ? params.getStopReason() : -1;
    new AppStore(this).log("系统停止备用任务；stopReason=" + reason);
    TaskRunner running = task;
    task = null;
    parameters = null;
    if (running != null) running.stop(Reason.JOB_STOPPED);
    if (Build.VERSION.SDK_INT >= 31 && reason == JobParameters.STOP_REASON_USER) {
      Reconnect.clear(this);
      new AppStore(this).stoppedWindowEnd(params.getExtras().getLong(RecoveryScheduler.WINDOW));
      return false;
    }
    return RecoveryScheduler.eligible(this, params.getExtras());
  }

  @Override public void onDestroy() {
    stopped = true;
    TaskRunner running = task;
    task = null;
    parameters = null;
    if (running != null) running.stop(Reason.SERVICE_DESTROYED);
    super.onDestroy();
  }
}
