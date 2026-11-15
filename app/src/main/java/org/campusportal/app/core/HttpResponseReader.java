package org.campusportal.app.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/** Keep redirect headers even when a captive gateway leaves its response body open. */
public final class HttpResponseReader {
  private HttpResponseReader() {}
  public interface Check { void run() throws IOException; }

  /** HTTPS reachability needs trusted TLS and response headers, not a whole homepage. */
  public static PortalClient.Response headers(HttpURLConnection connection, Check check)
      throws IOException {
    check.run();
    int status = connection.getResponseCode();
    check.run();
    return new PortalClient.Response(status, connection.getHeaderField("Location"), "");
  }

  public static PortalClient.Response read(HttpURLConnection connection, Check check)
      throws IOException {
    return read(connection, check, stage -> {});
  }

  public static PortalClient.Response read(HttpURLConnection connection, Check check,
      java.util.function.Consumer<String> stage) throws IOException {
    stage.accept("等待HTTP响应头");
    check.run();
    int status = connection.getResponseCode();
    check.run();
    String location = connection.getHeaderField("Location");
    if ((status >= 300 && status < 400 && location != null && !location.isEmpty())
        || status == 204 || status == 205 || status == 304)
      return new PortalClient.Response(status, location, "");
    stage.accept("读取HTTP响应体");
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    if (stream != null)
      try (InputStream input = stream) {
        byte[] chunk = new byte[4096];
        int count;
        while (buffer.size() < 65536
            && (count = input.read(chunk, 0, Math.min(chunk.length, 65536 - buffer.size()))) != -1) {
          check.run();
          buffer.write(chunk, 0, count);
        }
      }
    check.run();
    return new PortalClient.Response(status, location, buffer.toString("UTF-8"));
  }
}
