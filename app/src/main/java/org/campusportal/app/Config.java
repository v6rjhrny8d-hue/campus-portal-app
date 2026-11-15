package org.campusportal.app;

import org.campusportal.app.core.PortalProtocol;
import org.campusportal.app.core.Schedule;
import org.json.JSONObject;

public final class Config {
  public String label = "宿舍 Wi-Fi",
      gateway = "",
      subnet = "",
      host = "10.100.1.5",
      loginUrl = "",
      service = "中国移动";
  public int start = 420, end = 425, days = 127, interval = 60;
  public boolean bound;

  public Schedule schedule() {
    return new Schedule(start, end, days);
  }

  public static Config from(JSONObject j) throws Exception {
    if (j.optInt("schema", 1) != 1) throw new IllegalArgumentException("配置版本不支持，请先更新应用或使用版本 1 的配置");
    Config c = new Config();
    c.label = j.optString("label", c.label).trim();
    c.gateway = j.optString("gateway", "").trim();
    c.subnet = j.optString("subnet", "").trim();
    c.host = j.optString("portalHost", c.host).trim();
    c.loginUrl = j.optString("loginUrl", "").trim();
    c.service = j.optString("service", c.service).trim();
    c.bound = j.optBoolean("bound", false);
    c.start = j.optInt("start", c.start);
    c.end = j.optInt("end", c.end);
    c.days = j.optInt("days", c.days);
    c.interval = j.optInt("interval", c.interval);
    c.schedule();
    new PortalProtocol(c.host);
    if (!c.loginUrl.isEmpty() && !new PortalProtocol(c.host).browserAllowed(c.loginUrl))
      throw new IllegalArgumentException("登录网址须为已配置认证服务器上的 HTTP 80/8080 地址；请粘贴完整原始网址");
    if (c.loginUrl.length() > 16384)
      throw new IllegalArgumentException("登录网址过长，请重新复制学校认证链接");
    if (c.label.isEmpty() || c.label.length() > 40)
      throw new IllegalArgumentException("请填写 1～40 字的网络备注");
    if (c.service.isEmpty() || c.service.length() > 64)
      throw new IllegalArgumentException("请填写正确的校园网服务名称");
    if (c.interval < 30 || c.interval > 300)
      throw new IllegalArgumentException("检测间隔可设置为 30～300 秒");
    if (c.bound
        && (!PortalProtocol.isPrivateIPv4(c.gateway)
            || !PortalProtocol.inSubnet(c.gateway, c.subnet)))
      throw new IllegalArgumentException("宿舍网关和网段不匹配，请重新绑定当前 Wi-Fi");
    return c;
  }

  public JSONObject json() throws Exception {
    return new JSONObject()
        .put("schema", 1)
        .put("label", label)
        .put("gateway", gateway)
        .put("subnet", subnet)
        .put("portalHost", host)
        .put("loginUrl", loginUrl)
        .put("service", service)
        .put("bound", bound)
        .put("start", start)
        .put("end", end)
        .put("days", days)
        .put("interval", interval);
  }
}
