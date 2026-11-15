package org.campusportal.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import java.util.List;
import org.campusportal.app.core.RunControl.Reason;

public final class ReconnectJob extends JobService {
  private TaskRunner running;
  private JobParameters active;

  private boolean differentWifi(Network network) {
    if (network == null) return false;
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    List<NetworkController.Wifi> candidates = NetworkController.describe(network,
        cm.getNetworkCapabilities(network), cm.getLinkProperties(network));
    Config config = new AppStore(this).config();
    return !candidates.isEmpty() && candidates.stream().noneMatch(wifi -> NetworkController.matches(wifi, config));
  }

  @Override public boolean onStartJob(JobParameters params) {
    long end = params.getExtras().getLong(Reconnect.WINDOW, -1);
    if (!Reconnect.valid(this, end) || !Reconnect.waiting(this) || Reconnect.now() < end) return false;
    if (Build.VERSION.SDK_INT >= 28 && differentWifi(params.getNetwork())) {
      new AppStore(this).log("当前 Wi-Fi 与绑定网关或网段不符，保留补登机会，等待下次连网");
      return false;
    }
    TaskRunner task = TaskRunner.acquire(this, "reconnect", "", true, end, new TaskRunner.Listener() {
      public void progress(TaskRunner owner, String detail) {}
      public void finished(TaskRunner owner, Reason reason) {
        if (running != owner) return;
        running = null;
        active = null;
        boolean retry = reason == Reason.NETWORK_UNAVAILABLE && Reconnect.waiting(ReconnectJob.this);
        jobFinished(params, retry);
        if (!retry) Reconnect.clear(ReconnectJob.this);
      }
    });
    if (task == null) {
      new android.os.Handler(getMainLooper()).post(() -> jobFinished(params, Reconnect.waiting(this)));
      return true;
    }
    running = task;
    active = params;
    if (Build.VERSION.SDK_INT >= 28) task.assignedNetwork(params.getNetwork());
    try { task.start(); } catch (RuntimeException e) { task.stop(Reason.ERROR); }
    return true;
  }

  @Override public void onNetworkChanged(JobParameters params) {
    if (running != null && active != null && params.getExtras().getLong(Reconnect.WINDOW)
        == active.getExtras().getLong(Reconnect.WINDOW)) running.stop(Reason.NETWORK_UNAVAILABLE);
  }

  @Override public boolean onStopJob(JobParameters params) {
    TaskRunner task = running;
    running = null;
    active = null;
    boolean user = Build.VERSION.SDK_INT >= 31 && params.getStopReason() == JobParameters.STOP_REASON_USER;
    if (task != null) task.stop(user ? Reason.USER : Reason.JOB_STOPPED);
    if (user || !Reconnect.waiting(this)) Reconnect.clear(this);
    return !user && Reconnect.waiting(this);
  }

  @Override public void onDestroy() {
    TaskRunner task = running;
    running = null;
    active = null;
    if (task != null) task.stop(Reason.SERVICE_DESTROYED);
    super.onDestroy();
  }
}
