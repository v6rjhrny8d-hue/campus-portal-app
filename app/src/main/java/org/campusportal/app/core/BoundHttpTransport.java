package org.campusportal.app.core;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;

/** Android provides the network-bound opener; tests can inject real or blocked connections. */
public final class BoundHttpTransport implements PortalClient.Transport {
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
    Thread thread = new Thread(task, "campus-request-deadline");
    thread.setDaemon(true);
    return thread;
  });
  static { TIMER.setRemoveOnCancelPolicy(true); }
  public interface Opener { HttpURLConnection open(URL url) throws Exception; }
  private final Opener opener;
  private final RunControl control;
  private final Runnable check;
  private HttpURLConnection active;
  private boolean closed;
  private volatile String stage = "尚未发起";
  private String timings = "";
  private long stageStarted;
  public String stage() { return stage; }
  public String timings() { return timings; }

  private void stage(String value) {
    if (stageStarted >= 0) timings += stage + "=" + Math.max(0, control.elapsed() - stageStarted) + "ms；";
    stage = value;
    stageStarted = control.elapsed();
  }

  private static void disconnectAsync(HttpURLConnection connection) {
    Thread closer = new Thread(() -> {
      try { connection.disconnect(); } catch (RuntimeException ignored) {}
    }, "campus-close-request");
    closer.setDaemon(true);
    closer.start();
  }

  private int remaining(long deadline, AtomicBoolean expired) throws SocketTimeoutException {
    check();
    long left = deadline - control.elapsed();
    if (expired.get() || left <= 0) throw new SocketTimeoutException("Request budget exhausted");
    return control.timeout((int) Math.min(Integer.MAX_VALUE, left));
  }

  public BoundHttpTransport(Opener opener, RunControl control, Runnable check) {
    this.opener = opener;
    this.control = control;
    this.check = check;
  }

  private void check() {
    synchronized (this) {
      if (closed) throw new CancellationException();
    }
    control.check();
    check.run();
  }

  /** Never wait for network I/O on an Android lifecycle callback. */
  public void close() {
    final HttpURLConnection connection;
    synchronized (this) {
      closed = true;
      connection = active;
      active = null;
    }
    if (connection != null) disconnectAsync(connection);
  }

  @Override public PortalClient.Response request(String url, byte[] body, int timeout)
      throws Exception {
    return request(url, body, timeout, false);
  }

  @Override public PortalClient.Response headers(String url, int timeout) throws Exception {
    return request(url, null, timeout, true);
  }

  private PortalClient.Response request(String url, byte[] body, int timeout, boolean headersOnly)
      throws Exception {
    check();
    long deadline = control.elapsed() + control.timeout(timeout);
    AtomicBoolean expired = new AtomicBoolean();
    timings = "";
    stageStarted = -1;
    stage("创建网络绑定连接");
    HttpURLConnection connection = opener.open(new URL(url));
    ScheduledFuture<?> alarm = null;
    try {
      synchronized (this) {
        if (closed) throw new CancellationException();
        if (active != null) throw new IllegalStateException("Concurrent request");
        active = connection;
      }
      check();
      int left = remaining(deadline, expired);
      alarm = TIMER.schedule(() -> {
        expired.set(true);
        disconnectAsync(connection);
      }, left, TimeUnit.MILLISECONDS);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(left);
      connection.setReadTimeout(left);
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 CampusPortalApp/1.5.8");
      connection.setUseCaches(false);
      if (body != null) {
        check();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        stage("连接（含DNS/TCP/HTTPS握手）");
        connection.connect();
        connection.setReadTimeout(remaining(deadline, expired));
        stage("写入认证请求");
        try (java.io.OutputStream out = connection.getOutputStream()) {
          remaining(deadline, expired);
          out.write(body);
        }
      }
      if (body == null) {
        stage("连接（含DNS/TCP/HTTPS握手）");
        connection.connect();
      }
      connection.setReadTimeout(remaining(deadline, expired));
      HttpResponseReader.Check checkRequest = () -> {
        connection.setReadTimeout(remaining(deadline, expired));
      };
      PortalClient.Response response;
      if (headersOnly) {
        stage("等待HTTP响应头（无需正文）");
        response = HttpResponseReader.headers(connection, checkRequest);
      } else response = HttpResponseReader.read(connection, checkRequest, this::stage);
      remaining(deadline, expired);
      return response;
    } finally {
      if (alarm != null) alarm.cancel(false);
      timings += stage + "=" + Math.max(0, control.elapsed() - stageStarted) + "ms";
      synchronized (this) { if (active == connection) active = null; }
      connection.disconnect();
    }
  }
}
