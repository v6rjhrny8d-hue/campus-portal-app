package org.campusportal.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent event) {
    AppStore store = new AppStore(context);
    if (ScheduleManager.RUN.equals(event.getAction())) {
      long planned = store.planned(), now = System.currentTimeMillis();
      long elapsed = android.os.SystemClock.elapsedRealtime();
      store.log("收到定时事件；计划=" + (planned > 0 ? AppStore.format(planned, true) : "未记录")
          + "；到达延迟=" + (planned > 0 ? Math.max(0, now - planned) : -1) + "ms");
      store.log(store.alarmTiming(now, elapsed, ScheduleManager.bootCount(context)));
      ScheduleManager.plan(context, false);
      if (store.eligible(store.config().schedule().windowEnd(System.currentTimeMillis()))) {
        try {
          WorkService.start(context, "scheduled");
        } catch (RuntimeException e) {
          store.state("failed", "系统未允许启动本次定时任务，请检查后台权限");
          store.log("定时启动失败：" + e.getClass().getSimpleName());
        }
      } else store.log("收到计划事件时，时段已过期、已停止或已完成，本次未提交认证");
    } else {
      Reconnect.restore(context);
      ScheduleManager.plan(context, true);
      if (store.enabled()) store.log("计划恢复事件=" + event.getAction() + "；已重新检查自动计划");
    }
  }
}
