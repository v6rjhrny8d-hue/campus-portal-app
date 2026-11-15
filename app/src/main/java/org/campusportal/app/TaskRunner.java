package org.campusportal.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.campusportal.app.core.BoundHttpTransport;
import org.campusportal.app.core.PortalClient;
import org.campusportal.app.core.RunControl;
import org.campusportal.app.core.RunControl.Reason;
import org.json.JSONObject;

/** A single owner for foreground and JobScheduler work; lifecycle mutations run on main. */
final class TaskRunner {
  interface Listener {
    void progress(TaskRunner task, String text);
    void finished(TaskRunner task, Reason reason);
  }

  private static final AtomicReference<TaskRunner> CURRENT = new AtomicReference<>();
  private final Context context;
  private final AppStore store;
  private final Config config;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  final String mode, id, driver;
  final boolean scheduled, singleAttempt;
  final long windowEnd;
  final RunControl control;
  private final String currentUrl;
  private volatile BoundHttpTransport transport;
  private volatile WifiSession networkSession;
  private android.net.Network assignedNetwork;
  private Thread worker;
  private PowerManager.WakeLock wake;
  private boolean finished, started;
  volatile boolean verified;
  private volatile int attempts, requests, responses, posts;
  private volatile String stage = "启动";
  private long heartbeat;
  private String initialEnvironment = "";
  private final String testKind;
  private boolean refreshedNetwork;
  private final boolean reconnect;
  private volatile boolean awaitingWifi;

  private TaskRunner(Context context, String mode, String url, boolean singleAttempt,
      long expectedEnd, Listener listener) {
    this.context = context.getApplicationContext();
    this.store = new AppStore(context);
    this.config = store.config();
    this.mode = mode;
    this.testKind = BackgroundTest.kind(context);
    this.currentUrl = url;
    this.singleAttempt = singleAttempt;
    this.reconnect = "reconnect".equals(mode);
    this.scheduled = "scheduled".equals(mode);
    this.driver = singleAttempt ? "系统备用任务" : "前台服务";
    this.id = Long.toString(System.currentTimeMillis(), 36) + "-" + Long.toString(System.nanoTime() & 65535, 36);
    this.windowEnd = (scheduled || reconnect) ? expectedEnd : config.schedule().windowEnd(System.currentTimeMillis());
    long deadline = reconnect ? Reconnect.noon(expectedEnd) : scheduled ? expectedEnd : System.currentTimeMillis() + 90000L;
    this.control = new RunControl(new RunControl.Clock() {
      public long wall() { return reconnect ? Reconnect.now() : System.currentTimeMillis(); }
      public long elapsed() { return SystemClock.elapsedRealtime(); }
    }, deadline, !scheduled || singleAttempt ? 90000L : 7200000L);
    this.listener = listener;
  }

  static TaskRunner acquire(Context context, String mode, String url, boolean single,
      long expectedEnd, Listener listener) {
    TaskRunner candidate = new TaskRunner(context, mode, url, single, expectedEnd, listener);
    return CURRENT.compareAndSet(null, candidate) ? candidate : null;
  }

  static boolean isRunning() { return CURRENT.get() != null; }
  static boolean isTestMode(String mode) { return "selftest".equals(mode) || "selftestLogin".equals(mode); }

  static boolean isSelfTest() { TaskRunner task = CURRENT.get(); return task != null && isTestMode(task.mode); }

  static void cancel(Reason reason) {
    TaskRunner current = CURRENT.get();
    if (current != null) current.stop(reason);
  }

  void assignedNetwork(android.net.Network network) {
    if (started) throw new IllegalStateException("Network must be assigned before start");
    assignedNetwork = network;
  }

  void start() {
    if (finished || control.reason() != Reason.NONE) return;
    started = true;
    store.beginTask(id, mode, driver, control.deadlineWall);
    store.log("任务 " + id + " 开始；方式=" + driver + "；模式=" + mode
        + "；最迟结束=" + AppStore.format(control.deadlineWall, true));
    initialEnvironment = NetworkController.environment(context, null);
    store.log("任务环境：" + initialEnvironment);
    if (isTestMode(mode)) store.log("本次自测=" + testKind + "；允许认证=" + mode.equals("selftestLogin"));
    store.state("checking", "正在检查宿舍 Wi-Fi");
    if (!singleAttempt) {
      PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
      wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CampusPortal:network");
      wake.setReferenceCounted(false);
      wake.acquire(Math.max(1, control.remaining()) + 2000L);
    }
    heartbeat = SystemClock.elapsedRealtime();
    main.post(watchdog);
    worker = new Thread(this::execute, "campus-task-" + id);
    worker.setDaemon(true);
    worker.start();
  }

  private final Runnable watchdog = new Runnable() {
    @Override public void run() {
      if (finished) return;
      try { check(); }
      catch (CancellationException e) { stop(control.reason() == Reason.NONE ? Reason.ERROR : control.reason()); return; }
      if (SystemClock.elapsedRealtime() - heartbeat >= 15000L) {
        store.heartbeat();
        heartbeat = SystemClock.elapsedRealtime();
      }
      main.postDelayed(this, Math.max(1, Math.min(1000, control.remaining())));
    }
  };

  private void check() {
    control.check();
    if (reconnect && !verified && (!Reconnect.valid(context, windowEnd) || Reconnect.now() < windowEnd))
      control.stop(Reason.PLAN_DISABLED);
    if (scheduled && !store.enabled()) control.stop(Reason.PLAN_DISABLED);
    if (scheduled && config.schedule().windowEnd(System.currentTimeMillis()) != windowEnd)
      control.stop(Reason.DEADLINE);
    control.check();
  }

  /** Serialize persisted results with termination, so an old worker cannot replace a newer result. */
  private void record(Runnable action) {
    synchronized (control) {
      check();
      action.run();
    }
  }

  private void log(String message) { record(() -> store.log("[" + id + "] " + message)); }

  private void progress(String phase, String text) {
    record(() -> { stage = phase; store.state(phase, text); });
    main.post(() -> {
      if (!finished && control.reason() == Reason.NONE && CURRENT.get() == this)
        listener.progress(this, text);
    });
  }

  private void execute() {
    boolean awaitingVerification = false;
    try {
      check();
      if (currentUrl != null && !currentUrl.isEmpty()) {
        if (!mode.equals("linkLogin")
            || !new org.campusportal.app.core.PortalProtocol(config.host).trusted(currentUrl))
          throw new IllegalArgumentException("当前网页地址不属于认证服务器");
        config.loginUrl = currentUrl;
        log("使用内置网页本次返回的认证参数（不保存查询串）");
      }
      do {
        check();
        attempts++;
        stage = "选择 Wi-Fi";
        try {
          final WifiSession session;
          synchronized (control) {
            check();
            session = new WifiSession(context, config, singleAttempt, assignedNetwork, event -> main.post(() -> {
              if (!finished && control.reason() == Reason.NONE && CURRENT.get() == this) {
                store.log("[" + id + "] " + event);
              }
            }));
            networkSession = session;
            awaitingWifi = true;
            session.open();
          }
          NetworkController.Wifi wifi = session.await(control, this::check,
              scheduled && !singleAttempt ? 30000L : 10000L);
          awaitingWifi = false;
          if (reconnect && !Reconnect.claim(context, windowEnd)) {
            control.stop(Reason.PLAN_DISABLED);
            break;
          }
          if (scheduled) Reconnect.clear(context);
          log("第 " + attempts + " 次检测；Wi-Fi 回调已就绪；" + NetworkController.environment(context, wifi));
          Runnable checkNetwork = () -> {
            check();
            try { session.check(wifi); }
            catch (WifiSession.Unavailable e) {
              log(e.getMessage());
              control.stop(Reason.NETWORK_UNAVAILABLE);
              control.check();
            }
          };
          BoundHttpTransport io = NetworkController.transport(wifi, control, checkNetwork);
          final int firstRequest = requests, firstResponse = responses, firstPost = posts;
          transport = io;
          check();
          PortalClient client = new PortalClient(config.host, new PortalClient.Transport() {
            public PortalClient.Response request(String url, byte[] body, int timeout) throws Exception {
              return perform(url, body, timeout, false);
            }
            public PortalClient.Response headers(String url, int timeout) throws Exception {
              return perform(url, null, timeout, true);
            }
            private PortalClient.Response perform(String url, byte[] body, int timeout, boolean headersOnly)
                throws Exception {
              check();
              requests++;
              if (body != null) {
                if (!reconnect && !isTestMode(mode)) Reconnect.clear(context);
                posts++;
              }
              long requestStart = SystemClock.elapsedRealtime();
              try {
                PortalClient.Response response = headersOnly ? io.headers(url, timeout) : io.request(url, body, timeout);
                checkNetwork.run();
                responses++;
                return response;
              } catch (IOException e) {
                check();
                log("请求失败阶段=" + io.stage() + "；原因链：" + NetworkController.failureSummary(e));
                throw e;
              } finally {
                if (control.reason() == Reason.NONE)
                  log("请求耗时 " + (SystemClock.elapsedRealtime() - requestStart) + "ms；"
                      + (body == null ? (headersOnly ? "HTTPS验证（仅读响应头）" : "读取") : "提交认证")
                      + "；总预算=" + timeout + "ms；" + io.timings());
              }
            }
          }, new PortalClient.Control() {
            public void check() { TaskRunner.this.check(); }
            public void progress(String phase, String message) { TaskRunner.this.progress(phase, message); }
            public void diagnostic(String message) { log(message); }
            public boolean captivePortal() { return session.captivePortal(wifi); }
            public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
          });
          JSONObject credentials = new JSONObject();
          if (mode.equals("login") || mode.equals("linkLogin") || mode.equals("selftestLogin") || scheduled || reconnect) {
            try { credentials = store.credentials(); }
            catch (Exception e) {
              record(() -> {
                store.state("setup", "本机加密凭据无法读取，请在设置中重新填写账号密码");
                if (!isTestMode(mode)) {
                  store.enabled(false);
                  store.log("本机加密凭据无法读取，已关闭自动计划");
                  ScheduleManager.plan(context, false);
                }
              });
              break;
            }
          }
          PortalClient.Result result = mode.equals("web") || awaitingVerification ? client.webOnly() : client.run(
              !mode.equals("login") && !mode.equals("linkLogin") && !mode.equals("selftestLogin") && !scheduled && !reconnect,
              credentials.optString("username", ""), credentials.optString("password", ""),
              config.service, store.cooldown() > System.currentTimeMillis(), config.loginUrl, mode.equals("linkLogin"));
          if (result.submitted && result.state.equals("verifying")) {
            awaitingVerification = true;
            log("认证接口已接受，本任务后续仅复查 HTTPS 联网，不重复提交账号");
          }
          record(() -> {
            verified = result.verified;
            store.checked(result.verified);
            store.state(result.state, result.message);
            store.log("[" + id + "] " + result.message);
            if (result.submitted && result.state.equals("rejected")) store.rejectFor15Minutes();
            if (result.submitted && (result.verified || result.state.equals("verifying"))) store.authenticated();
            if (result.verified && !isTestMode(mode) && !reconnect) Reconnect.clear(context);
            if (result.verified && !isTestMode(mode) && (reconnect || windowEnd > System.currentTimeMillis())) {
              store.completedWindowEnd(windowEnd);
              ScheduleManager.plan(context, false);
            }
            if (result.state.equals("unsupported") && !isTestMode(mode)) {
              store.enabled(false);
              ScheduleManager.plan(context, false);
            }
          });
          if (result.verified || result.state.equals("unsupported")) break;
          if (requests > firstRequest && responses == firstResponse && posts == firstPost) {
            record(() -> store.state("network_unavailable", "本次联网检测未收到响应，尚未尝试登录；请查看后台自测与记录"));
            if ((scheduled || isTestMode(mode)) && !singleAttempt && !refreshedNetwork
                && control.remaining() > 30000L) {
              refreshedNetwork = true;
              log("全部请求无响应，释放本轮连接并重新申请 Wi-Fi；仅额外尝试一次");
              continue;
            }
          }
          if (RunControl.needsNetworkRecovery(scheduled, singleAttempt, requests - firstRequest,
              responses - firstResponse, posts - firstPost)) {
            log("本轮所有网络请求均无响应且未提交账号，结束前台等待，交给带 Wi-Fi 条件的系统任务恢复");
            control.stop(Reason.NETWORK_UNAVAILABLE);
            break;
          }
        } catch (CancellationException e) { throw e; }
        catch (Exception e) {
          check();
          if (e instanceof WifiSession.Unavailable) {
            log(e.getMessage());
            control.stop(Reason.NETWORK_UNAVAILABLE);
            break;
          }
          String message = e instanceof IOException && !config.bound ? "请先在设置中绑定宿舍网络"
              : e instanceof IOException && e.getMessage() != null && e.getMessage().startsWith("未找到已绑定")
                  ? "等待已绑定的宿舍 Wi-Fi 自动连接"
                  : "Wi-Fi 请求未完成；错误类型：" + e.getClass().getSimpleName() + "，请查看本次任务环境与探测记录";
          record(() -> { store.state("failed", message); store.log("[" + id + "] " + message); });
        } finally {
          BoundHttpTransport io = transport;
          transport = null;
          if (io != null) io.close();
          WifiSession session = networkSession;
          networkSession = null;
          if (session != null) session.close();
        }
        if (!scheduled || singleAttempt || !store.enabled()) break;
        long delay = Math.min(control.remaining(), config.interval * 1000L);
        log("检测未成功；等待 " + (delay / 1000) + " 秒后重试，仍以原时段结束时间为限");
        long next = SystemClock.elapsedRealtime() + delay;
        stage = "等待重试";
        while (SystemClock.elapsedRealtime() < next) {
          check();
          Thread.sleep(Math.max(1, Math.min(500, next - SystemClock.elapsedRealtime())));
        }
      } while (scheduled || isTestMode(mode));
    } catch (CancellationException ignored) {
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      control.stop(Reason.SERVICE_DESTROYED);
    } catch (Exception e) {
      if (control.reason() == Reason.NONE) {
        try { log("任务异常：" + e.getClass().getSimpleName()); } catch (CancellationException ignored) {}
        control.stop(Reason.ERROR);
      }
    } finally {
      main.post(() -> stop(control.reason() == Reason.NONE ? Reason.FINISHED : control.reason()));
    }
  }

  void stop(Reason requested) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      control.stop(requested);
      main.post(() -> stop(requested));
      return;
    }
    if (finished) return;
    control.stop(requested);
    finished = true;
    main.removeCallbacks(watchdog);
    BoundHttpTransport io = transport;
    if (io != null) io.close();
    WifiSession session = networkSession;
    if (session != null) session.close();
    if (worker != null) worker.interrupt();
    if (wake != null && wake.isHeld()) wake.release();
    CURRENT.compareAndSet(this, null);
    Reason reason = control.reason();
    switch (reason) {
      case USER: store.state("stopped", "已停止本时段任务，保留下次自动计划；仍可手动登录"); break;
      case PLAN_DISABLED: store.state("stopped", "自动计划已关闭，本次任务已停止"); break;
      case NETWORK_UNAVAILABLE:
        store.state("network_unavailable", posts == 0 ? "后台联网检测失败，尚未尝试登录；请查看记录" : "网络连接中断，认证结果尚未验证；请查看记录"); break;
      case SYSTEM_TIMEOUT: case START_REJECTED:
        store.state("interrupted", "系统中断了后台任务，尚未完成联网验证"); break;
      case SERVICE_DESTROYED: case JOB_STOPPED:
        store.state("interrupted", "系统停止了本次后台任务，请查看记录"); break;
      case ERROR: store.state("failed", "本次任务发生异常，请查看记录"); break;
      case DEADLINE:
        if (!verified) store.state("limited", scheduled && !singleAttempt
            ? "本次时段已结束，网页访问尚未验证；请查看探测记录"
            : "本次检查达到时限，网页访问尚未验证");
        break;
      default: break;
    }
    if (started) store.endTask(control.duration(), !singleAttempt);
    store.log("任务 " + id + " 结束；原因=" + reason + "；运行=" + control.duration()
        + "ms；阶段=" + stage + "；检测=" + attempts + "；请求=" + requests + "；收到响应=" + responses + "；尝试提交=" + posts
        + "；HTTPS验证=" + verified);
    if (isTestMode(mode)) BackgroundTest.result(context, testKind, reason.name(),
        verified, requests, responses, posts, control.duration(), initialEnvironment);
    if (scheduled && awaitingWifi && requests == 0
        && (reason == Reason.NETWORK_UNAVAILABLE || reason == Reason.DEADLINE))
      Reconnect.arm(context, windowEnd);
    listener.finished(this, reason);
  }
}
