package org.campusportal.app.tests;

import static org.campusportal.app.tests.CoreTests.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.campusportal.app.core.*;
import org.campusportal.app.core.RunControl.Reason;
import org.json.JSONObject;

/** Regression scenarios derived from startup timeouts, slow sockets and delayed recovery. */
final class ReliabilityTests {
  static final class Clock implements RunControl.Clock {
    long wall = 1000, elapsed = 5000;
    public long wall() { return wall; }
    public long elapsed() { return elapsed; }
  }

  static void cancelled(Task action, String message) throws Exception {
    try { action.run(); throw new AssertionError(message); }
    catch (CancellationException expected) { assertions++; }
  }

  static class Connection extends HttpURLConnection {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    Connection() throws Exception { super(new URL("http://127.0.0.1/")); }
    public void connect() {}
    public void disconnect() {}
    public boolean usingProxy() { return false; }
    public int getResponseCode() throws IOException { return 204; }
    public OutputStream getOutputStream() throws IOException { return output; }
  }

  static void run() throws Exception {
    Clock headerClock = new Clock();
    RunControl headerRun = new RunControl(headerClock, 91000, 90000);
    AtomicInteger bodyReads = new AtomicInteger();
    Connection headerOnly = new Connection() {
      public int getResponseCode() { return 200; }
      public java.io.InputStream getInputStream() { bodyReads.incrementAndGet(); throw new AssertionError("homepage body must not be downloaded"); }
    };
    BoundHttpTransport headerIo = new BoundHttpTransport(url -> headerOnly, headerRun, () -> {});
    eq(200, headerIo.headers("https://fixture.test/", 2000).status, "HTTPS headers can verify without waiting for body");
    eq(0, bodyReads.get(), "verification makes zero body reads");

    Clock sharedClock = new Clock();
    RunControl sharedRun = new RunControl(sharedClock, 91000, 90000);
    AtomicInteger readBudget = new AtomicInteger();
    Connection shared = new Connection() {
      public void connect() { sharedClock.elapsed += 70; }
      public int getResponseCode() { readBudget.set(getReadTimeout()); sharedClock.elapsed += 31; return 204; }
    };
    BoundHttpTransport sharedIo = new BoundHttpTransport(url -> shared, sharedRun, () -> {});
    try { sharedIo.headers("https://fixture.test/", 100); throw new AssertionError("late HTTP success must expire"); }
    catch (java.net.SocketTimeoutException expected) { assertions++; }
    eq(30, readBudget.get(), "read only receives time remaining after connect, not a second full timeout");
    eq(Reason.NONE, sharedRun.reason(), "request timeout does not cancel the whole task");
    headerIo.headers("https://fixture.test/", 2000);
    eq(Reason.NONE, headerRun.reason(), "successful request cancels its watchdog without closing a later request");

    Clock watchdogClock = new Clock();
    RunControl watchdogRun = new RunControl(watchdogClock, 91000, 90000);
    CountDownLatch watchdogDisconnected = new CountDownLatch(1);
    Connection watchdogConnection = new Connection() {
      public int getResponseCode() throws IOException {
        try { if (!watchdogDisconnected.await(2, TimeUnit.SECONDS)) throw new AssertionError("watchdog failed to disconnect"); }
        catch (InterruptedException e) { throw new IOException(e); }
        return 200;
      }
      public void disconnect() { watchdogDisconnected.countDown(); }
    };
    BoundHttpTransport watchdog = new BoundHttpTransport(url -> watchdogConnection, watchdogRun, () -> {});
    try { watchdog.headers("https://fixture.test/", 50); throw new AssertionError("watchdog rejects late success"); }
    catch (java.net.SocketTimeoutException expected) { assertions++; }
    eq(Reason.NONE, watchdogRun.reason(), "watchdog timeout allows next portal discovery request");
    ok(RunControl.needsRecovery(Reason.NETWORK_UNAVAILABLE), "network failure has bounded recovery");
    ok(RunControl.needsNetworkRecovery(true, false, 9, 0, 0), "morning all-timeout round hands off");
    ok(!RunControl.needsNetworkRecovery(false, false, 9, 0, 0), "manual failure does not create automatic job");
    ok(!RunControl.needsNetworkRecovery(true, true, 9, 0, 0), "job does not recursively hand off");
    ok(!RunControl.needsNetworkRecovery(true, false, 9, 1, 0), "HTTP response rules out complete access failure");
    ok(!RunControl.needsNetworkRecovery(true, false, 9, 0, 1), "uncertain POST is not blindly repeated by recovery");
    ok(!RunControl.needsNetworkRecovery(true, false, 1, 0, 0), "single endpoint failure is insufficient evidence");

    Clock stagesClock = new Clock();
    RunControl stagesControl = new RunControl(stagesClock, 91000, 90000);
    Connection headersFail = new Connection() {
      public int getResponseCode() throws IOException { throw new IOException("redacted fixture"); }
    };
    BoundHttpTransport stages = new BoundHttpTransport(url -> headersFail, stagesControl, () -> {});
    try { stages.request("http://127.0.0.1/", null, 6000); throw new AssertionError("expected header failure"); }
    catch (IOException expected) { eq("等待HTTP响应头", stages.stage(), "header failure distinct from connect"); }
    BoundHttpTransport opening = new BoundHttpTransport(url -> { throw new IOException("private URL"); }, stagesControl, () -> {});
    try { opening.request("http://127.0.0.1/", null, 6000); throw new AssertionError("expected opening failure"); }
    catch (IOException expected) { eq("创建网络绑定连接", opening.stage(), "opening stage has no URL"); }

    Clock clock = new Clock();
    RunControl run = new RunControl(clock, 301000, 300000);
    run.stop(Reason.SYSTEM_TIMEOUT);
    ok(!run.stop(Reason.USER), "late user/cleanup cannot relabel system timeout");
    ok(!run.stop(Reason.SERVICE_DESTROYED), "destroy callback preserves original reason");
    eq(Reason.SYSTEM_TIMEOUT, run.reason(), "startup timeout remains system timeout");
    AtomicInteger opens = new AtomicInteger();
    BoundHttpTransport io = new BoundHttpTransport(url -> { opens.incrementAndGet(); return new Connection(); }, run, () -> {});
    cancelled(() -> io.request("http://127.0.0.1/", null, 6000), "cancelled startup must not connect");
    eq(0, opens.get(), "startup timeout issues zero network requests");
    ok(RunControl.needsRecovery(run.reason()), "system timeout enables bounded fallback");
    ok(RunControl.needsRecovery(Reason.START_REJECTED), "FGS start refusal enables bounded fallback");
    for (Reason r : new Reason[]{Reason.USER, Reason.PLAN_DISABLED, Reason.DEADLINE, Reason.FINISHED, Reason.SERVICE_DESTROYED, Reason.JOB_STOPPED})
      ok(!RunControl.needsRecovery(r), "no FGS-recovery restart after " + r);

    Clock backwards = new Clock();
    RunControl deadline = new RunControl(backwards, 91000, 90000);
    backwards.wall -= 3600000;
    backwards.elapsed += 90000;
    cancelled(deadline::check, "moving wall clock backward cannot extend work");
    eq(Reason.DEADLINE, deadline.reason(), "monotonic deadline enforced");
    Clock forwards = new Clock();
    RunControl forwarded = new RunControl(forwards, 91000, 90000);
    forwards.wall = 91000;
    cancelled(forwarded::check, "wall end is exclusive even before monotonic deadline");
    Clock nearEnd = new Clock();
    RunControl shortRun = new RunControl(nearEnd, 1100, 90000);
    eq(100, shortRun.timeout(12000), "request timeout shrinks to remaining window");
    nearEnd.elapsed += 100;
    cancelled(() -> shortRun.timeout(12000), "no new request at end boundary");

    ok(RunControl.eligible(1000, 5000, 5000, true, -1, -1), "valid remaining window may recover");
    ok(!RunControl.eligible(5000, 5000, 5000, true, -1, -1), "late job cannot authenticate after end");
    ok(!RunControl.eligible(6000, 5000, 5000, true, -1, -1), "expired job remains expired");
    ok(!RunControl.eligible(1000, 5000, 5000, false, -1, -1), "disabled plan rejects recovery");
    ok(!RunControl.eligible(1000, 5000, 5000, true, 5000, -1), "verified window rejects recovery");
    ok(!RunControl.eligible(1000, 5000, 5000, true, -1, 5000), "user stopped window rejects recovery");
    ok(!RunControl.eligible(1000, 5000, 6000, true, -1, -1), "changed window rejects stale recovery");
    ok(!RunControl.eligible(1000, -1, -1, true, -1, -1), "outside schedule cannot recover");
    ok(RunControl.eligible(6000, 9000, 9000, true, -1, 5000), "user stop does not suppress future windows");

    Clock late = new Clock();
    RunControl expiredPost = new RunControl(late, 2000, 1000);
    Connection post = new Connection() {
      public OutputStream getOutputStream() { late.wall = 2000; return output; }
    };
    BoundHttpTransport posting = new BoundHttpTransport(url -> post, expiredPost, () -> {});
    cancelled(() -> posting.request("http://127.0.0.1/", new byte[]{1, 2}, 12000), "check again before writing credentials");
    eq(0, post.output.size(), "no credential bytes after deadline during connection setup");

    Clock slowClock = new Clock();
    RunControl slowRun = new RunControl(slowClock, 91000, 90000);
    CountDownLatch reading = new CountDownLatch(1), disconnected = new CountDownLatch(1), done = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    Connection blocked = new Connection() {
      public int getResponseCode() throws IOException {
        reading.countDown();
        try { if (!disconnected.await(2, TimeUnit.SECONDS)) throw new IOException("test read timeout"); }
        catch (InterruptedException e) { throw new IOException(e); }
        return 204;
      }
      public void disconnect() { disconnected.countDown(); }
    };
    BoundHttpTransport blockedIo = new BoundHttpTransport(url -> blocked, slowRun, () -> {});
    Thread worker = new Thread(() -> {
      try { blockedIo.request("http://127.0.0.1/", null, 6000); completed.incrementAndGet(); }
      catch (CancellationException expected) {}
      catch (Exception e) { completed.set(9); }
      finally { done.countDown(); }
    });
    worker.setDaemon(true); worker.start();
    ok(reading.await(1, TimeUnit.SECONDS), "request reached blocked response read");
    slowRun.stop(Reason.SYSTEM_TIMEOUT);
    blockedIo.close();
    ok(done.await(1, TimeUnit.SECONDS), "closing task interrupts blocked response promptly");
    eq(0, completed.get(), "late HTTP success after cancellation is discarded");
    cancelled(() -> blockedIo.request("http://127.0.0.1/", null, 6000), "closed old transport cannot resume");

    RunControl failed = new RunControl(new Clock(), 91000, 90000);
    AtomicInteger misleading = new AtomicInteger();
    PortalClient client = new PortalClient("10.100.1.5", (url, body, timeout) -> {
      failed.stop(Reason.SYSTEM_TIMEOUT); throw new IOException("socket disconnected");
    }, new PortalClient.Control() {
      public void check() { failed.check(); }
      public void progress(String phase, String message) {}
      public void diagnostic(String message) { misleading.incrementAndGet(); }
    });
    cancelled(() -> client.run(false, "fixture", "fixture", "fixture", false), "closed socket should terminate probe chain");
    eq(0, misleading.get(), "system cancellation is not reported as a DNS or server failure");
    JSONObject config = new JSONObject().put("bound", true);
    JSONObject status = new JSONObject().put("ready", true).put("running", false).put("phase", "interrupted")
        .put("lastVerified", 1).put("detail", "系统中断了后台任务");
    HomeState home = HomeState.from(config, status, true);
    ok(home.needsAttention && !home.historicalSuccess, "interruption cannot reuse old success UI");
    eq("后台任务未完成", home.title, "home distinguishes interrupted service from network failure");
    status.put("phase", "network_unavailable");
    eq("联网检测未收到响应", HomeState.from(config, status, true).title, "no response is not credential rejection");
    ok(HomeState.from(config, status, true).needsAttention, "network failure needs attention");
    status.put("running", true).put("recoveryPending", true).put("phase", "waiting");
    eq("等待系统继续检测", HomeState.from(config, status, true).title, "queued recovery is not shown as active network I/O");
  }
}
