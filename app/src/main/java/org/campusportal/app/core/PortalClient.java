package org.campusportal.app.core;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** All I/O is injected; Android supplies sockets bound to the selected Wi-Fi. */
public final class PortalClient {
  public interface Transport {
    Response request(String url, byte[] body, int timeout) throws Exception;

    default Response headers(String url, int timeout) throws Exception {
      return request(url, null, timeout);
    }
  }

  public interface Control {
    void check() throws Exception;

    void progress(String phase, String message);

    default void diagnostic(String message) {}

    // A routing hint only: credentials still require a trusted, freshly read portal.
    default boolean captivePortal() { return false; }

    default long elapsedMillis() { return System.nanoTime() / 1000000L; }
  }

  public static final class Response {
    public final int status;
    public final String location, body;

    public Response(int status, String location, String body) {
      this.status = status;
      this.location = location == null ? "" : location;
      this.body = body == null ? "" : body;
    }
  }

  public static final class Result {
    public final String state, message;
    public final boolean submitted, verified;

    public Result(String state, String message, boolean submitted, boolean verified) {
      this.state = state;
      this.message = message;
      this.submitted = submitted;
      this.verified = verified;
    }
  }

  private static final String[] PROBES = {
    "http://connect.rom.miui.com/generate_204",
    "http://captive.apple.com/hotspot-detect.html",
    "http://1.1.1.1/",
    "http://www.baidu.com/"
  };
  private static final String[] WEBSITES = {"https://www.baidu.com", "https://www.qq.com"};
  private final PortalProtocol protocol;
  private final Transport io;
  private final Control control;

  public PortalClient(String host, Transport io, Control control) {
    protocol = new PortalProtocol(host);
    this.io = io;
    this.control = control;
  }

  private Response request(String url, byte[] body, int timeout) throws Exception {
    control.check();
    return io.request(url, body, timeout);
  }

  private String portal;
  private boolean probePassed;
  private final Set<String> discoverySeen = new HashSet<>();

  private String requestHost(String url) {
    try { return URI.create(url).getHost(); }
    catch (IllegalArgumentException e) { return "未知主机"; }
  }

  private void probePage(String first) throws Exception {
    String url = first;
    for (int hop = 0; hop < 3 && url != null && discoverySeen.add(url); hop++) {
      Response r;
      try {
        r = request(url, null, 6000);
      } catch (IOException e) {
        control.check();
        control.diagnostic("认证页探测 " + requestHost(url) + "：" + e.getClass().getSimpleName());
        return;
      }
      control.diagnostic("认证页探测 " + requestHost(url) + "：HTTP " + r.status);
      if (r.status < 200 || r.status >= 400) return;
      String next = protocol.nextDiscoveryPage(url,
          r.status >= 300 && r.status < 400 ? r.location : "", r.body);
      String found = next != null && protocol.trusted(next) ? next
          : next == null ? protocol.discover(url, r.location, r.body) : null;
      if (found != null) {
        portal = found;
        control.diagnostic("已发现当前认证页，服务器 " + requestHost(found));
        return;
      }
      if (next != null) {
        control.diagnostic("继续读取认证服务器跳转（最多三步）");
        url = next;
        continue;
      }
      if (!r.location.isEmpty())
        control.diagnostic("跳转未匹配当前认证页，目标主机 " + requestHost(r.location));
      if ((r.location + r.body).toLowerCase(Locale.ROOT).contains("eportal")) return;
      if (url.equals(PROBES[0]) && r.status == 204) probePassed = true;
      if (url.equals(PROBES[1]) && r.status == 200
          && java.util.regex.Pattern.compile("<body[^>]*>\\s*Success\\s*</body>", 2)
              .matcher(r.body).find()) probePassed = true;
      return;
    }
  }

  private void probe() throws Exception {
    portal = null;
    probePassed = false;
    discoverySeen.clear();
    for (String url : PROBES) {
      probePage(url);
      if (portal != null) return;
    }
  }

  private boolean verify(int budget, int perRequest) throws Exception {
    long end = control.elapsedMillis() + budget;
    for (String site : WEBSITES) {
      control.check();
      long remaining = end - control.elapsedMillis();
      if (remaining <= 0) break;
      try {
        Response r = io.headers(site, (int) Math.min(perRequest, remaining));
        control.check();
        if (control.elapsedMillis() >= end) break;
        if (r.status >= 200 && r.status < 400) return true;
      } catch (IOException e) {
        control.check();
        control.diagnostic("网页验证 " + requestHost(site) + "：" + e.getClass().getSimpleName());
      }
    }
    return false;
  }

  public Result run(
      boolean checkOnly, String username, String password, String service, boolean cooldown)
      throws Exception {
    return run(checkOnly, username, password, service, cooldown, "", false);
  }

  public Result run(boolean checkOnly, String username, String password, String service,
      boolean cooldown, String loginUrl, boolean directOnly) throws Exception {
    control.progress("checking", "正在通过宿舍 Wi-Fi 检查网络");
    portal = null;
    probePassed = false;
    discoverySeen.clear();
    if (loginUrl != null && !loginUrl.isEmpty() && !protocol.browserAllowed(loginUrl))
      throw new IllegalArgumentException("登录网址不属于已配置的认证服务器");
    if (directOnly && (loginUrl == null || loginUrl.isEmpty()))
      return new Result("setup", "请先在设置中保存完整登录网址", false, false);
    boolean portalHint = control.captivePortal();
    if (portalHint) {
      control.diagnostic("宿舍 Wi-Fi 提示存在认证页，优先探测当前认证入口，跳过登录前外网等待");
    } else {
      control.progress("checking", "先快速验证宿舍 Wi-Fi 是否已经联网");
      if (verify(4000, 2000)) return new Result("connected", "宿舍 Wi-Fi 已联网，HTTPS 验证通过；无需访问认证页或重复登录", false, true);
    }
    if (loginUrl != null && !loginUrl.isEmpty()) {
      control.progress("checking", "正在通过宿舍 Wi-Fi 直接访问保存的登录网址");
      control.diagnostic("尝试用户保存的登录网址（不记录查询参数）");
      probePage(loginUrl);
      if (portal == null && directOnly) {
        if (portalHint && verify(4000, 2000))
          return new Result("connected", "宿舍 Wi-Fi 的 HTTPS 访问已验证；未重复登录", false, true);
        return new Result("limited", "已尝试保存的网址，但未取得可用认证页；尚未提交账号，请更新完整链接或查看记录", false, false);
      }
      if (portal == null) control.diagnostic("保存的网址未取得认证页，继续自动探测当前链接");
    } else if (directOnly) {
      return new Result("setup", "请先在设置中保存完整登录网址", false, false);
    }
    if (portal == null) probe();
    if (portal == null) {
      control.diagnostic("普通探测未发现认证页，尝试已配置的认证服务器入口");
      probePage(protocol.entry());
      if (portal == null) probePage(protocol.entry() + "eportal/index.jsp");
    }
    if (portal == null) {
      // Captive-portal capability can lag behind the real network state.
      if (portalHint && verify(4000, 2000))
        return new Result("connected", "宿舍 Wi-Fi 的 HTTPS 访问已验证；系统认证状态尚未更新，未重复登录", false, true);
      return new Result(
          "limited",
          probePassed ? "探测地址可访问，普通网页尚未验证；未重复提交账号" : "未能取得当前认证页，尚未提交账号；请查看探测记录",
          false,
          false);
    }
    if (checkOnly) return new Result("needs_login", "检测到校园网认证页，可以点击立即登录", false, false);
    if (cooldown) return new Result("rejected", "账号此前被拒绝，15 分钟冷却结束后再试；修改账号设置可重新测试", false, false);
    if (username.isEmpty() || password.isEmpty() || service.isEmpty())
      return new Result("setup", "请先填写校园网账号、密码和服务类型", false, false);
    control.progress("authenticating", "正在向已配置的校园网服务器提交认证");
    Response page = null;
    for (int hop = 0; hop < 3; hop++) {
      page = request(portal, null, 6000);
      if (page.status < 300 || page.status >= 400 || page.location.isEmpty()) break;
      String next = protocol.discover(portal, page.location, "");
      if (next == null || next.equals(portal)) break;
      portal = next;
    }
    if (page.status != 200) return new Result("failed", "认证页面暂不可用，请稍后重试", false, false);
    if (PortalProtocol.encryptionRequired(page.body))
      return new Result("unsupported", "认证页要求密码加密，当前协议不适用；请使用学校页面登录", false, false);
    Response response =
        request(
            protocol.endpoint(portal),
            protocol.loginBody(portal, username, password, service),
            12000);
    if (response.status != 200) return new Result("failed", "认证接口暂未正常响应，稍后再试", true, false);
    String status;
    try {
      status = new JSONObject(response.body).optString("result", "");
    } catch (Exception e) {
      return new Result("failed", "认证接口返回的格式不符合本校协议", true, false);
    }
    if (!"success".equalsIgnoreCase(status))
      return new Result("rejected", "认证被拒绝，请核对账号、密码及服务类型；暂停提交 15 分钟", true, false);
    control.progress("verifying", "认证接口返回成功，正在验证实际联网");
    if (verify(10000, 5000)) return new Result("connected", "认证成功，宿舍 Wi-Fi 的 HTTPS 访问已验证", true, true);
    return new Result("verifying", "认证接口返回成功，但普通网页尚未通过验证", true, false);
  }

  public Result webOnly() throws Exception {
    control.progress("checking", "正在通过宿舍 Wi-Fi 验证普通 HTTPS 网页");
    return verify(10000, 5000)
        ? new Result("connected", "宿舍 Wi-Fi 的 HTTPS 访问已验证", false, true)
        : new Result("limited", "普通 HTTPS 网页暂不可访问", false, false);
  }
}
