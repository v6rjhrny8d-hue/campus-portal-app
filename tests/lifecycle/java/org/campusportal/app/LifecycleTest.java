package org.campusportal.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import java.time.Duration;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowJobService;
import org.robolectric.util.ReflectionHelpers;
import org.campusportal.app.core.RunControl.Reason;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35, manifest = org.robolectric.annotation.Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LifecycleTest {
  Context context;
  AppStore store;
  JobScheduler jobs;
  long end;
  private final java.util.List<ServiceController<?>> controllers = new java.util.ArrayList<>();

  @Before public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("campus", 0).edit().clear().commit();
    store = new AppStore(context);
    jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    jobs.cancelAll();
    shadowOf((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).clearAllNetworks();
    Config config = new Config();
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
    config.start = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    config.end = (config.start + 5) % 1440;
    config.days = 127;
    config.bound = true;
    config.gateway = "192.168.31.1";
    config.subnet = "192.168.31.0/24";
    store.saveConfig(config);
    store.enabled(true);
    end = config.schedule().windowEnd(System.currentTimeMillis());
    assertTrue(end > System.currentTimeMillis());
  }

  @After public void cleanup() throws Exception {
    TaskRunner.cancel(Reason.USER);
    RecoveryScheduler.cancel(context);
    for (ServiceController<?> controller : controllers) controller.destroy();
    for (Thread thread : Thread.getAllStackTraces().keySet())
      if (thread.getName().startsWith("campus-task-")) thread.join(2000);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse("task owner must be released", TaskRunner.isRunning());
  }

  WorkService foreground() {
    ServiceController<WorkService> controller = Robolectric.buildService(WorkService.class).create();
    controllers.add(controller);
    return controller.get();
  }

  RecoveryJobService background() {
    ServiceController<RecoveryJobService> controller = Robolectric.buildService(RecoveryJobService.class).create();
    controllers.add(controller);
    return controller.get();
  }

  Intent scheduled() { return new Intent(context, WorkService.class).setAction("scheduled"); }

  @Test public void alarmDiagnosticsSeparateDispatchDelayFromWallClockChangesAndReboot() {
    store.planned(61000, 1000, 5000, 8);
    String timing = store.alarmTiming(107345, 111345, 8);
    assertTrue(timing.contains("单调时钟延迟=46345ms"));
    assertTrue(timing.contains("墙钟偏移=0ms"));
    timing = store.alarmTiming(107345, 65000, 8);
    assertTrue(timing.contains("单调时钟延迟=0ms"));
    assertTrue(timing.contains("墙钟偏移=46345ms"));
    assertTrue(store.alarmTiming(107345, 111345, 9).contains("对照不可用"));
    store.planned(-1);
    assertTrue(store.alarmTiming(107345, 111345, 8).contains("对照不可用"));
  }

  @Test public void immediateTimeoutStopsServiceAndQueuesWifiOnlyRecovery() throws Exception {
    WorkService service = foreground();
    service.onStartCommand(scheduled(), 0, 1);
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    assertNotNull(runner);
    PowerManager.WakeLock wake = ReflectionHelpers.getField(runner, "wake");
    assertTrue(wake.isHeld());
    service.onTimeout(1, 1);
    assertTrue(shadowOf(service).isStoppedBySelf());
    assertTrue(shadowOf(service).isForegroundStopped());
    assertFalse(wake.isHeld());
    assertFalse(TaskRunner.isRunning());
    assertEquals(Reason.SYSTEM_TIMEOUT, runner.control.reason());
    JobInfo job = jobs.getPendingJob(RecoveryScheduler.JOB_ID);
    assertNotNull(job);
    assertEquals(end, job.getExtras().getLong(RecoveryScheduler.WINDOW));
    assertTrue(job.getRequiredNetwork().hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    assertFalse(job.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    service.onDestroy();
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals("waiting", store.phase());
    assertFalse(store.exportLogs().contains("原因=USER"));
  }

  @Test public void timeoutBeforeStartDoesNotStartWorkerLater() {
    WorkService service = foreground();
    service.onTimeout(12, 1);
    service.onStartCommand(scheduled(), 0, 12);
    assertFalse(TaskRunner.isRunning());
    assertNull(ReflectionHelpers.getField(service, "task"));
    assertNotNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
    assertFalse(store.exportLogs().contains("模式=scheduled"));
  }

  @Test public void duplicateStartKeepsDeadlineAndStopsLatestStartId() {
    WorkService service = foreground();
    service.onStartCommand(scheduled(), 0, 1);
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    long deadline = runner.control.deadlineElapsed;
    service.onStartCommand(scheduled(), 0, 2);
    assertSame(runner, ReflectionHelpers.getField(service, "task"));
    assertEquals(deadline, runner.control.deadlineElapsed);
    WorkService.cancel(context);
    assertEquals(2, shadowOf(service).getStopSelfId());
    assertTrue(shadowOf(service).isForegroundStopped());
    assertEquals(Reason.USER, runner.control.reason());
    assertEquals(end, store.stoppedWindowEnd());
    assertTrue(store.enabled());
    service.onTimeout(1, 1);
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
  }

  @Test public void foregroundPromotionFailureUsesJobWithoutRunningWorker() {
    WorkService service = foreground();
    shadowOf(service).setThrowInStartForeground(new IllegalStateException("fixture"));
    service.onStartCommand(scheduled(), 0, 1);
    assertFalse(TaskRunner.isRunning());
    assertTrue(shadowOf(service).isStoppedBySelf());
    assertNotNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
    assertTrue(store.exportLogs().contains("原因=START_REJECTED"));
    assertFalse(store.exportLogs().contains("任务环境："));
  }

  @Test public void oldWorkerCleanupCannotReleaseNewTaskOwner() {
    WorkService service = foreground();
    service.onStartCommand(scheduled(), 0, 1);
    TaskRunner old = ReflectionHelpers.getField(service, "task");
    service.onTimeout(1, 1);
    TaskRunner replacement = TaskRunner.acquire(context, "diagnose", "", false, end, new TaskRunner.Listener() {
      public void progress(TaskRunner task, String text) {}
      public void finished(TaskRunner task, Reason reason) {}
    });
    assertNotNull(replacement);
    old.stop(Reason.FINISHED);
    service.onDestroy();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(TaskRunner.isRunning());
    assertEquals(Reason.NONE, replacement.control.reason());
    replacement.stop(Reason.USER);
  }

  @Test public void watchdogStopsEvenWhenWorkerHasNotReturned() {
    WorkService service = foreground();
    service.onStartCommand(scheduled(), 0, 1);
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    runner.control.stop(Reason.DEADLINE);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertFalse(TaskRunner.isRunning());
    assertTrue(shadowOf(service).isForegroundStopped());
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
  }

  @Test public void cancelledRecoveryGenerationRejectsAlreadyDeliveredJob() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    PersistableBundle extras = jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras();
    RecoveryScheduler.cancel(context);
    assertFalse(RecoveryScheduler.eligible(context, extras));
    RecoveryJobService service = background();
    assertFalse(service.onStartJob(parameters(extras)));
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void changedConfigurationInvalidatesQueuedJob() throws Exception {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    PersistableBundle extras = jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras();
    Config config = store.config(); config.gateway = "192.168.31.2";
    store.saveConfig(config);
    assertFalse(RecoveryScheduler.eligible(context, extras));
    assertFalse(background().onStartJob(parameters(extras)));
  }

  @Test public void expiredRecoveryNeverStartsNetworkWork() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    PersistableBundle extras = new PersistableBundle(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    extras.putLong(RecoveryScheduler.WINDOW, System.currentTimeMillis() - 1);
    assertFalse(background().onStartJob(parameters(extras)));
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void expeditedQuotaFailureFallsBackToOrdinaryJob() {
    shadowOf(jobs).failExpeditedJob(true);
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobInfo job = jobs.getPendingJob(RecoveryScheduler.JOB_ID);
    assertNotNull(job);
    assertFalse(job.isExpedited());
    assertTrue(store.exportLogs().contains("改为普通系统调度"));
  }

  @Test public void jobStopCancelsOwnerAndPreservesSystemReason() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    RecoveryJobService service = background();
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    assertNotNull(runner);
    assertTrue(service.onStopJob(params));
    assertFalse(TaskRunner.isRunning());
    assertEquals(Reason.JOB_STOPPED, runner.control.reason());
    ShadowJobService shadow = Shadow.extract(service);
    assertFalse("onStopJob must not call jobFinished", shadow.getIsJobFinished());
  }

  @Test public void failedJobSchedulesDelayedRetryAndStaleStopCannotCancelIt() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters first = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    RecoveryJobService service = background();
    assertTrue(service.onStartJob(first));
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    runner.stop(Reason.FINISHED);
    shadowOf(Looper.getMainLooper()).idle();
    JobInfo next = jobs.getPendingJob(RecoveryScheduler.JOB_ID);
    assertNotNull(next);
    assertEquals(store.config().interval * 1000L, next.getMinLatencyMillis());
    assertNotEquals(first.getExtras().getLong(RecoveryScheduler.TOKEN), next.getExtras().getLong(RecoveryScheduler.TOKEN));
    JobParameters second = parameters(next.getExtras());
    assertTrue(service.onStartJob(second));
    TaskRunner newer = ReflectionHelpers.getField(service, "task");
    assertNotNull(newer);
    assertFalse(service.onStopJob(first));
    assertTrue(TaskRunner.isRunning());
    assertEquals(Reason.NONE, newer.control.reason());
    service.onStopJob(second);
  }

  @Test public void successfulWindowDoesNotQueueAnotherJob() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    RecoveryJobService service = background();
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    runner.verified = true;
    store.completedWindowEnd(end);
    runner.stop(Reason.FINISHED);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(RecoveryScheduler.pending(context));
    assertFalse(TaskRunner.isRunning());
    ShadowJobService shadow = Shadow.extract(service);
    assertTrue(shadow.getIsJobFinished());
    assertFalse(shadow.getIsRescheduleNeeded());
  }

  @Test public void userStoppedWindowCannotBeRevivedByQueuedJob() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    WorkService.cancel(context);
    assertEquals(end, store.stoppedWindowEnd());
    assertTrue(store.enabled());
    assertFalse(background().onStartJob(params));
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
  }

  @Test public void systemUserStopMustNotRequestReschedule() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    RecoveryJobService service = background();
    assertTrue(service.onStartJob(params));
    ReflectionHelpers.setField(params, "mStopReason", JobParameters.STOP_REASON_USER);
    assertFalse(service.onStopJob(params));
    assertEquals(end, store.stoppedWindowEnd());
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void idleAlarmDirectlyRequestsForegroundInsteadOfOnlyQueueing() {
    shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsDeviceIdleMode(true);
    WorkService.start(context, "scheduled");
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
    Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
    assertNotNull(intent);
    assertEquals("scheduled", intent.getAction());
    assertNotEquals(end, store.blockedForegroundWindowEnd());
  }

  @Test public void queuedButUnstartedJobDoesNotPreventPrimaryForegroundRequest() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsDeviceIdleMode(true);
    WorkService.start(context, "scheduled");
    assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    assertNotNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
    assertNotEquals(end, store.blockedForegroundWindowEnd());
  }

  @Test public void unstartedExpiredQueueIsReportedOnceAndCancelled() throws Exception {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    android.content.SharedPreferences p = context.getSharedPreferences("campus", 0);
    p.edit().putLong("recoveryQueuedEnd", System.currentTimeMillis() - 1).commit();
    RecoveryScheduler.reconcile(context);
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
    assertEquals("queue_expired", store.phase());
    assertTrue(store.status().optString("detail").contains("未实际启动"));
    assertEquals(0, p.getLong("recoveryQueuedEnd", 0));
    RecoveryScheduler.reconcile(context);
    assertEquals("queue_expired", store.phase());
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void expiredOldQueueCannotOverwriteNewVerifiedStatus() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    context.getSharedPreferences("campus", 0).edit()
        .putLong("recoveryQueuedEnd", System.currentTimeMillis() - 1).commit();
    store.state("connected", "新的检测已验证联网");
    RecoveryScheduler.reconcile(context);
    assertEquals("connected", store.phase());
    assertNull(jobs.getPendingJob(RecoveryScheduler.JOB_ID));
  }

  @Test public void queueReceiptOnlyChangesToStartedAfterActualJobWorkerStarts() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    android.content.SharedPreferences p = context.getSharedPreferences("campus", 0);
    assertFalse(p.getBoolean("recoveryHasStarted", false));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    assertTrue(background().onStartJob(params));
    assertTrue(p.getBoolean("recoveryHasStarted", false));
    assertTrue(TaskRunner.isRunning());
  }

  @Test public void longQueueRemainsWaitingAndDoesNotPretendWorkHasStarted() throws Exception {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    context.getSharedPreferences("campus", 0).edit()
        .putLong("recoveryQueuedAt", System.currentTimeMillis() - 35000L).commit();
    RecoveryScheduler.reconcile(context);
    assertEquals("waiting", store.phase());
    assertTrue(store.status().optString("detail").contains("尚未实际执行"));
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void totalNetworkFailureStopsForegroundAndQueuesSingleRecovery() {
    WorkService service = foreground();
    service.onStartCommand(scheduled(), 0, 1);
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    runner.stop(Reason.NETWORK_UNAVAILABLE);
    assertTrue(shadowOf(service).isForegroundStopped());
    assertFalse(TaskRunner.isRunning());
    JobInfo job = jobs.getPendingJob(RecoveryScheduler.JOB_ID);
    assertNotNull(job);
    assertEquals(end, job.getExtras().getLong(RecoveryScheduler.WINDOW));
    long token = job.getExtras().getLong(RecoveryScheduler.TOKEN);
    service.onDestroy();
    WorkService.start(context, "scheduled");
    assertEquals(token, jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras().getLong(RecoveryScheduler.TOKEN));
  }

  @Test public void jobUsesAssignedNetworkAndStopsWhenAssignmentChanges() {
    assertTrue(RecoveryScheduler.enqueue(context, end, 0));
    JobParameters params = parameters(jobs.getPendingJob(RecoveryScheduler.JOB_ID).getExtras());
    RecoveryJobService service = background();
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "task");
    assertEquals(params.getNetwork(), ReflectionHelpers.getField(runner, "assignedNetwork"));
    service.onNetworkChanged(params);
    assertEquals(Reason.NETWORK_UNAVAILABLE, runner.control.reason());
    assertFalse(TaskRunner.isRunning());
  }

  private static JobParameters parameters(PersistableBundle extras) {
    JobParameters params = Shadow.newInstanceOf(JobParameters.class);
    ReflectionHelpers.setField(params, "jobId", RecoveryScheduler.JOB_ID);
    ReflectionHelpers.setField(params, "extras", extras);
    ReflectionHelpers.setField(params, "mNetwork", new android.net.Network(101));
    return params;
  }
  @Test public void stoppingDiagnosticDoesNotStopTodaysAutomaticWindow() {
    TaskRunner task = TaskRunner.acquire(context, "selftest", null, false, end, new TaskRunner.Listener() {
      public void progress(TaskRunner t, String text) {}
      public void finished(TaskRunner t, Reason reason) {}
    });
    assertNotNull(task);
    WorkService.cancel(context);
    assertNotEquals(end, store.stoppedWindowEnd());
    assertTrue(store.enabled());
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void staleAndCancelledDiagnosticNeverStartsWork() {
    android.content.SharedPreferences p = context.getSharedPreferences("background_test", 0);
    p.edit().putLong("pending", System.currentTimeMillis() - 360000L).commit();
    new BackgroundTest().onReceive(context, new Intent("selftest"));
    assertEquals(0, p.getLong("pending", 0));
    assertTrue(BackgroundTest.summary(context).contains("本次未执行"));
    assertFalse(TaskRunner.isRunning());
    p.edit().putLong("pending", System.currentTimeMillis() + 120000L).commit();
    BackgroundTest.cancel(context);
    new BackgroundTest().onReceive(context, new Intent("selftest"));
    assertEquals(0, p.getLong("pending", 0));
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void diagnosticAlarmIsSeparateFromDailyPlanAndCanBeCancelled() {
    android.app.AlarmManager alarm = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    shadowOf(alarm).setCanScheduleExactAlarms(true);
    long planned = store.planned();
    BackgroundTest.schedule(context);
    assertTrue(context.getSharedPreferences("background_test", 0).getLong("pending", 0) > System.currentTimeMillis());
    assertEquals(planned, store.planned());
    BackgroundTest.cancel(context);
    assertEquals(planned, store.planned());
    assertEquals(0, context.getSharedPreferences("background_test", 0).getLong("pending", 0));
  }
  @Test public void stoppingLoginDiagnosticDoesNotStopDailyPlan() {
    TaskRunner task = TaskRunner.acquire(context, "selftestLogin", null, false, end, new TaskRunner.Listener() {
      public void progress(TaskRunner t, String text) {}
      public void finished(TaskRunner t, Reason reason) {}
    });
    assertNotNull(task);
    WorkService.cancel(context);
    assertNotEquals(end, store.stoppedWindowEnd());
    assertTrue(store.enabled());
    assertFalse(TaskRunner.isRunning());
  }

  @Test public void loginDiagnosticRejectsMissingCredentialsBeforeScheduling() {
    try { BackgroundTest.schedule(context, true); fail("missing credentials must not schedule login"); }
    catch (IllegalArgumentException expected) {}
    assertEquals(0, context.getSharedPreferences("background_test", 0).getLong("pending", 0));
  }

  @Test public void changedConfigurationCancelsPendingLoginDiagnostic() {
    android.content.SharedPreferences p = context.getSharedPreferences("background_test", 0);
    p.edit().putLong("pending", System.currentTimeMillis() - 1).putBoolean("login", true)
        .putLong("revision", store.revision() - 1).commit();
    new BackgroundTest().onReceive(context, new Intent("selftest"));
    assertTrue(BackgroundTest.summary(context).contains("本次未执行"));
    assertEquals(0, p.getLong("pending", 0));
    assertFalse(TaskRunner.isRunning());
    assertTrue(store.enabled());
  }
  @Test public void foregroundStartRejectedWhileIdleStillKeepsWifiRecovery() {
    shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsDeviceIdleMode(true);
    Context denied = new android.content.ContextWrapper(context) {
      @Override public android.content.ComponentName startForegroundService(Intent intent) {
        throw new IllegalStateException("fixture rejected");
      }
    };
    WorkService.start(denied, "scheduled");
    JobInfo job = jobs.getPendingJob(RecoveryScheduler.JOB_ID);
    assertNotNull(job);
    assertTrue(job.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN));
    assertFalse(job.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    assertEquals(end, store.blockedForegroundWindowEnd());
    assertFalse(TaskRunner.isRunning());
  }
}
