package org.campusportal.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.util.ReflectionHelpers;
import org.campusportal.app.core.RunControl.Reason;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35, manifest = org.robolectric.annotation.Config.NONE,
    shadows = NetworkFlowTest.FixtureNetwork.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NetworkFlowTest {
  static volatile boolean online;
  static volatile boolean portalMode, authenticated, blockPostChecks;
  static volatile int portalPosts, httpsBodies;
  static final String PORTAL = "http://10.100.1.5/eportal/index.jsp?wlanuserip=fixture&mac=fixture";
  static final List<String> urls = java.util.Collections.synchronizedList(new ArrayList<>());
  static final List<Integer> networks = java.util.Collections.synchronizedList(new ArrayList<>());

  // Test-only JVM provider; device Keystore security is covered by the device harness.
  public static class FixtureKeyStore extends java.security.KeyStoreSpi {
    public java.security.Key engineGetKey(String alias, char[] password) { return new javax.crypto.spec.SecretKeySpec(new byte[16], "AES"); }
    public boolean engineContainsAlias(String alias) { return true; }
    public void engineLoad(InputStream in, char[] password) {}
    public void engineStore(java.io.OutputStream out, char[] password) {}
    public java.security.cert.Certificate[] engineGetCertificateChain(String alias) { return null; }
    public java.security.cert.Certificate engineGetCertificate(String alias) { return null; }
    public java.util.Date engineGetCreationDate(String alias) { return null; }
    public void engineSetKeyEntry(String a, java.security.Key k, char[] p, java.security.cert.Certificate[] c) {}
    public void engineSetKeyEntry(String a, byte[] k, java.security.cert.Certificate[] c) {}
    public void engineSetCertificateEntry(String a, java.security.cert.Certificate c) {}
    public void engineDeleteEntry(String alias) {}
    public java.util.Enumeration<String> engineAliases() { return java.util.Collections.enumeration(java.util.Collections.singleton("fixture")); }
    public int engineSize() { return 1; }
    public boolean engineIsKeyEntry(String alias) { return true; }
    public boolean engineIsCertificateEntry(String alias) { return false; }
    public String engineGetCertificateAlias(java.security.cert.Certificate cert) { return null; }
  }

  void fixtureCredentials() throws Exception {
    java.security.Provider provider = new java.security.Provider("CampusFixture", 1.0, "JVM tests only") {};
    provider.put("KeyStore.AndroidKeyStore", FixtureKeyStore.class.getName());
    java.security.Security.insertProviderAt(provider, 1);
    store.saveCredentials("fixture-user", "fixture-pass");
  }

  @Implements(Network.class)
  public static class FixtureNetwork extends ShadowNetwork {
    @Implementation protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
      assertEquals(Proxy.NO_PROXY, proxy);
      urls.add(url.toString()); networks.add(getNetId());
      if (portalMode) return new HttpURLConnection(url) {
        public void connect() throws IOException {
          if (url.getProtocol().equals("https") && (!authenticated || blockPostChecks))
            throw new IOException("fixture HTTPS unavailable");
          if ("POST".equals(getRequestMethod())) { portalPosts++; authenticated = true; }
        }
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        public int getResponseCode() { return url.toString().equals(PORTAL) || "POST".equals(getRequestMethod())
            || url.getProtocol().equals("https") ? 200 : 302; }
        public String getHeaderField(String name) { return "Location".equals(name) && getResponseCode() == 302 ? PORTAL : null; }
        public InputStream getInputStream() {
          if (url.getProtocol().equals("https")) { httpsBodies++; throw new AssertionError("HTTPS body should not be read"); }
          String body = "POST".equals(getRequestMethod()) ? "{\"result\":\"success\"}" : "<input name=passwordEncrypt value=false>";
          return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      };
      if (!online) throw new UnknownHostException("fixture network blocked");
      return new HttpURLConnection(url) {
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{1}); }
      };
    }
  }

  Context context;
  AppStore store;
  JobScheduler jobs;
  long end;
  final List<ServiceController<?>> services = new ArrayList<>();
  @Before public void setup() throws Exception {
    urls.clear(); networks.clear(); online = false;
    portalMode = authenticated = blockPostChecks = false; portalPosts = httpsBodies = 0;
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("campus", 0).edit().clear().commit();
    store = new AppStore(context);
    Config c = new Config(); c.bound = true; c.gateway = "192.168.31.1"; c.subnet = "192.168.31.0/24";
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
    c.start = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    c.end = (c.start + 5) % 1440; c.days = 127; c.loginUrl = "http://10.100.1.5/";
    store.saveConfig(c); store.enabled(true);
    end = c.schedule().windowEnd(System.currentTimeMillis());
    jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE); jobs.cancelAll();
  }
  @After public void cleanup() throws Exception {
    Reconnect.clock = java.time.Clock.systemUTC();
    Reconnect.clear(context);
    TaskRunner.cancel(Reason.USER);
    for (ServiceController<?> c : services) c.destroy();
    for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().startsWith("campus-task-")) t.join(1000);
    shadowOf(Looper.getMainLooper()).idle(); RecoveryScheduler.cancel(context);
    java.security.Security.removeProvider("CampusFixture");
  }
  void until(BooleanSupplier condition) throws Exception {
    long limit = System.nanoTime() + 3000000000L;
    while (!condition.getAsBoolean() && System.nanoTime() < limit) {
      shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5);
    }
    shadowOf(Looper.getMainLooper()).idle(); assertTrue("async task completed within test bound", condition.getAsBoolean());
  }
  TaskRunner foreground() {
    ServiceController<WorkService> c = Robolectric.buildService(WorkService.class).create(); services.add(c);
    c.get().onStartCommand(new Intent(context, WorkService.class).setAction("scheduled"), 0, 1);
    return ReflectionHelpers.getField(c.get(), "task");
  }
  WifiSession ready(TaskRunner task, int id) throws Exception {
    return ready(task, id, false);
  }
  WifiSession ready(TaskRunner task, int id, boolean captive) throws Exception {
    until(() -> ReflectionHelpers.getField(task, "networkSession") != null);
    WifiSession s = ReflectionHelpers.getField(task, "networkSession");
    WifiSessionTest fixture = new WifiSessionTest();
    android.net.NetworkCapabilities caps = fixture.caps();
    if (captive) caps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    fixture.announce(s, new Network(id), caps);
    return s;
  }

  private void morningTime(String time) {
    Reconnect.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-04T" + time + "+08:00"),
        java.time.ZoneOffset.UTC);
  }

  private void waitingForWifi(TaskRunner runner) throws Exception {
    until(() -> {
      Thread worker = ReflectionHelpers.getField(runner, "worker");
      if (worker == null) return false;
      for (StackTraceElement frame : worker.getStackTrace())
        if (frame.getClassName().equals(WifiSession.class.getName()) && frame.getMethodName().equals("await"))
          return worker.getState() == Thread.State.TIMED_WAITING;
      return false;
    });
  }

  private JobParameters morningFailure() throws Exception {
    morningTime("07:02:47");
    Config c = store.config();
    c.start = 420; c.end = 425;
    store.saveConfig(c);
    end = c.schedule().windowEnd(Reconnect.now());
    Reconnect.arm(context, end);
    JobInfo job = jobs.getPendingJob(Reconnect.JOB);
    assertNotNull(job);
    assertEquals(133000L, job.getMinLatencyMillis());
    JobParameters params = Shadow.newInstanceOf(JobParameters.class);
    ReflectionHelpers.setField(params, "jobId", Reconnect.JOB);
    ReflectionHelpers.setField(params, "extras", job.getExtras());
    ReflectionHelpers.setField(params, "mNetwork", new Network(811));
    return params;
  }

  private ReconnectJob reconnectService() {
    ServiceController<ReconnectJob> service = Robolectric.buildService(ReconnectJob.class).create();
    services.add(service);
    return service.get();
  }

  @Test public void scheduledTaskAcceptsWifiArrivingAfterFirstTenSeconds() throws Exception {
    fixtureCredentials(); online = true;
    TaskRunner runner = foreground();
    waitingForWifi(runner);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20));
    ready(runner, 810);
    until(() -> !TaskRunner.isRunning());
    assertTrue(runner.verified);
    assertEquals(0, portalPosts);
    assertNull(jobs.getPendingJob(Reconnect.JOB));
  }

  @Test public void afterWindowWifiJobLogsInOnceOnAssignedCampusNetwork() throws Exception {
    fixtureCredentials(); portalMode = true;
    JobParameters params = morningFailure();
    ReconnectJob service = reconnectService();
    assertFalse(service.onStartJob(params));
    morningTime("09:22:53");
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "running");
    ready(runner, 811, true);
    until(() -> !TaskRunner.isRunning());
    assertTrue(runner.verified);
    assertEquals(1, portalPosts);
    assertEquals(end, store.completedWindowEnd());
    assertFalse(Reconnect.waiting(context));
    assertFalse(service.onStartJob(params));
    assertTrue(networks.stream().allMatch(id -> id == 811));
  }

  @Test public void reconnectAlreadyOnlineSkipsCredentialsAndClearsTicket() throws Exception {
    fixtureCredentials(); online = true;
    JobParameters params = morningFailure();
    morningTime("07:05:00");
    ReconnectJob service = reconnectService();
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "running");
    ready(runner, 811);
    until(() -> !TaskRunner.isRunning());
    assertTrue(runner.verified);
    assertEquals(java.util.Collections.singletonList("https://www.baidu.com"), urls);
    assertFalse(Reconnect.waiting(context));
  }

  @Test public void differentGatewayCannotConsumeReconnectOrSendRequests() throws Exception {
    JobParameters params = morningFailure();
    morningTime("08:15:00");
    ReconnectJob service = reconnectService();
    assertTrue(service.onStartJob(params));
    TaskRunner runner = ReflectionHelpers.getField(service, "running");
    waitingForWifi(runner);
    WifiSession session = ReflectionHelpers.getField(runner, "networkSession");
    Network network = new Network(811);
    WifiSessionTest fixture = new WifiSessionTest();
    session.callback.onAvailable(network);
    session.callback.onCapabilitiesChanged(network, fixture.caps());
    session.callback.onLinkPropertiesChanged(network, fixture.links("192.168.3.2", "192.168.3.1"));
    session.callback.onBlockedStatusChanged(network, false);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11));
    until(() -> !TaskRunner.isRunning());
    assertTrue(Reconnect.waiting(context));
    assertTrue(urls.isEmpty());
  }

  @Test public void noonAndExplicitStopSuppressDelayedDelivery() throws Exception {
    JobParameters params = morningFailure();
    morningTime("12:00:00");
    ReconnectJob service = reconnectService();
    assertFalse(service.onStartJob(params));
    assertFalse(Reconnect.claim(context, end));
    Reconnect.restore(context);
    assertNull(jobs.getPendingJob(Reconnect.JOB));
    params = morningFailure();
    WorkService.cancel(context);
    morningTime("10:00:00");
    assertFalse(service.onStartJob(params));
    assertTrue(urls.isEmpty());
  }

  @Test public void disablingPlanOrEditingBindingInvalidatesReconnect() throws Exception {
    JobParameters params = morningFailure();
    store.enabled(false); store.enabled(true);
    morningTime("09:30:00");
    ReconnectJob service = reconnectService();
    assertFalse(service.onStartJob(params));
    params = morningFailure();
    Config c = store.config();
    c.gateway = "192.168.31.99";
    store.saveConfig(c);
    morningTime("09:32:00");
    assertFalse(service.onStartJob(params));
  }

  @Test public void captiveWifiAuthenticatesBeforeAnyExternalHttpsAndNeverDownloadsHomepage() throws Exception {
    portalMode = true; fixtureCredentials();
    TaskRunner task = foreground(); ready(task, 701, true);
    until(() -> !TaskRunner.isRunning());
    assertTrue(task.verified); assertEquals(1, portalPosts); assertEquals(0, httpsBodies);
    assertTrue(urls.get(0).startsWith("http://10.100.1.5/"));
    assertTrue(urls.get(urls.size()-1).startsWith("https://"));
    assertEquals(end, store.completedWindowEnd());
    assertTrue(networks.stream().allMatch(id -> id == 701));
  }

  @Test public void acceptedLoginWithSlowPropagationOnlyRechecksHttpsOnNextRound() throws Exception {
    portalMode = true; blockPostChecks = true; fixtureCredentials();
    TaskRunner task = foreground(); WifiSession first = ready(task, 702, true);
    until(() -> "等待重试".equals(ReflectionHelpers.getField(task, "stage")));
    assertEquals(1, portalPosts); assertFalse(task.verified);
    int before = urls.size(); blockPostChecks = false;
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61));
    until(() -> { WifiSession next = ReflectionHelpers.getField(task, "networkSession"); return next != null && next != first; });
    ready(task, 703, true);
    until(() -> !TaskRunner.isRunning());
    assertTrue(task.verified); assertEquals(1, portalPosts);
    assertTrue(urls.subList(before, urls.size()).stream().allMatch(url -> url.startsWith("https://")));
  }

  @Test public void alreadyOnlineEndsScheduledWindowWithoutTouchingSavedPortal() throws Exception {
    online = true;
    TaskRunner runner = foreground(); ready(runner, 101);
    until(() -> !TaskRunner.isRunning());
    assertTrue(runner.verified);
    assertEquals(Reason.FINISHED, runner.control.reason());
    assertEquals(end, store.completedWindowEnd());
    assertEquals(java.util.Arrays.asList("https://www.baidu.com"), urls);
    assertFalse(RecoveryScheduler.pending(context));
  }

  @Test public void allRequestsFailThenSystemNetworkRecoversAndFinishesWindow() throws Exception {
    TaskRunner first = foreground();
    WifiSession original = ready(first, 101);
    until(() -> { WifiSession current = ReflectionHelpers.getField(first, "networkSession"); return current != null && current != original; });
    ready(first, 102);
    until(() -> !TaskRunner.isRunning());
    assertEquals(Reason.NETWORK_UNAVAILABLE, first.control.reason());
    assertFalse(first.verified);
    JobInfo job = jobs.getPendingJob(RecoveryScheduler.JOB_ID); assertNotNull(job);
    assertTrue(job.isExpedited());
    assertTrue(urls.size() >= 2);
    assertTrue(urls.stream().noneMatch(u -> u.contains("method=login")));
    urls.clear(); networks.clear(); online = true;
    JobParameters params = Shadow.newInstanceOf(JobParameters.class);
    ReflectionHelpers.setField(params, "jobId", RecoveryScheduler.JOB_ID);
    ReflectionHelpers.setField(params, "extras", job.getExtras());
    ReflectionHelpers.setField(params, "mNetwork", new Network(202));
    ServiceController<RecoveryJobService> c = Robolectric.buildService(RecoveryJobService.class).create(); services.add(c);
    assertTrue(c.get().onStartJob(params));
    TaskRunner second = ReflectionHelpers.getField(c.get(), "task"); ready(second, 202);
    until(() -> !TaskRunner.isRunning());
    assertTrue(second.verified);
    assertEquals(end, store.completedWindowEnd());
    assertEquals(java.util.Arrays.asList(202), networks);
    assertEquals(java.util.Arrays.asList("https://www.baidu.com"), urls);
    assertFalse(RecoveryScheduler.pending(context));
  }
  @Test public void freshWifiSessionCanRecoverWithoutRestartingService() throws Exception {
    TaskRunner first = foreground();
    WifiSession original = ready(first, 301);
    until(() -> { WifiSession current = ReflectionHelpers.getField(first, "networkSession"); return current != null && current != original; });
    online = true; ready(first, 302);
    until(() -> !TaskRunner.isRunning());
    assertTrue(first.verified);
    assertEquals(end, store.completedWindowEnd());
    assertFalse(RecoveryScheduler.pending(context));
    assertEquals(Integer.valueOf(302), networks.get(networks.size() - 1));
  }

  @Test public void diagnosticDoesNotCompleteOrDisableDailyPlanOrSubmitCredentials() throws Exception {
    online = true;
    ServiceController<WorkService> c = Robolectric.buildService(WorkService.class).create(); services.add(c);
    c.get().onStartCommand(new Intent(context, WorkService.class).setAction("selftest"), 0, 1);
    TaskRunner task = ReflectionHelpers.getField(c.get(), "task"); ready(task, 401);
    until(() -> !TaskRunner.isRunning());
    assertTrue(task.verified);
    assertNotEquals(end, store.completedWindowEnd());
    assertTrue(store.enabled());
    assertTrue(BackgroundTest.summary(context).contains("提交=0"));
    assertEquals(java.util.Arrays.asList("https://www.baidu.com"), urls);
  }
  @Test public void loginDiagnosticAlreadyOnlineDoesNotPostOrFinishDailyWindow() throws Exception {
    online = true;
    context.getSharedPreferences("background_test", 0).edit().putString("kind", "延迟登录自测").commit();
    ServiceController<WorkService> c = Robolectric.buildService(WorkService.class).create(); services.add(c);
    c.get().onStartCommand(new Intent(context, WorkService.class).setAction("selftestLogin"), 0, 1);
    TaskRunner task = ReflectionHelpers.getField(c.get(), "task"); ready(task, 501);
    until(() -> !TaskRunner.isRunning());
    assertTrue(task.verified);
    assertNotEquals(end, store.completedWindowEnd());
    assertTrue(store.enabled());
    String result = BackgroundTest.summary(context);
    assertTrue(result.contains("延迟登录自测"));
    assertTrue(result.contains("提交=0"));
    assertFalse(RecoveryScheduler.pending(context));
    assertEquals(java.util.Arrays.asList("https://www.baidu.com"), urls);
  }
  @Test public void idleScheduledEntryReachesActualNetworkAndCompletesOnlineWindow() throws Exception {
    online = true;
    shadowOf((android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsDeviceIdleMode(true);
    WorkService.start(context, "scheduled");
    Intent request = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
    assertNotNull(request);
    assertFalse(RecoveryScheduler.pending(context));
    ServiceController<WorkService> c = Robolectric.buildService(WorkService.class).create(); services.add(c);
    c.get().onStartCommand(request, 0, 1);
    TaskRunner task = ReflectionHelpers.getField(c.get(), "task"); ready(task, 601);
    until(() -> !TaskRunner.isRunning());
    assertTrue(task.verified);
    assertEquals(end, store.completedWindowEnd());
    assertEquals(java.util.Arrays.asList("https://www.baidu.com"), urls);
    assertFalse(RecoveryScheduler.pending(context));
  }
}
