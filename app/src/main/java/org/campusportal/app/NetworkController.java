package org.campusportal.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Proxy;
import java.net.URL;
import org.campusportal.app.core.PortalClient;
import org.campusportal.app.core.PortalProtocol;
import org.json.JSONObject;

public final class NetworkController {
  public static final class Wifi {
    public final Network network;
    public final String address, gateway, subnet;

    Wifi(Network network, String address, String gateway, String subnet) {
      this.network = network;
      this.address = address;
      this.gateway = gateway;
      this.subnet = subnet;
    }

    public JSONObject json() throws Exception {
      return new JSONObject().put("address", address).put("gateway", gateway).put("subnet", subnet);
    }
  }

  public static java.util.List<Wifi> available(Context context) {
    java.util.List<Wifi> list = new java.util.ArrayList<>();
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    for (Network n : cm.getAllNetworks())
      list.addAll(describe(n, cm.getNetworkCapabilities(n), cm.getLinkProperties(n)));
    return list;
  }

  static java.util.List<Wifi> describe(Network n, NetworkCapabilities caps, LinkProperties props) {
    java.util.List<Wifi> list = new java.util.ArrayList<>();
    if (caps == null || props == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return list;
    String gateway = "";
    for (RouteInfo route : props.getRoutes())
      if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address)
        gateway = route.getGateway().getHostAddress();
    if (!PortalProtocol.isPrivateIPv4(gateway)) return list;
    for (LinkAddress address : props.getLinkAddresses()) {
      if (!(address.getAddress() instanceof Inet4Address)) continue;
      String ip = address.getAddress().getHostAddress();
      if (PortalProtocol.isPrivateIPv4(ip))
        list.add(new Wifi(n, ip, gateway, PortalProtocol.subnet(ip, address.getPrefixLength())));
    }
    return list;
  }

  static boolean matches(Wifi wifi, Config c) {
    return c.bound && wifi.gateway.equals(c.gateway) && PortalProtocol.inSubnet(wifi.address, c.subnet);
  }

  public static Wifi select(Context context, Config c) throws IOException {
    if (!c.bound) throw new IOException("请先在设置中绑定宿舍网络");
    for (Wifi wifi : available(context)) if (matches(wifi, c)) return wifi;
    throw new IOException("未找到已绑定的宿舍 Wi-Fi，请确认 Wi-Fi 已自动连接");
  }

  public static boolean vpnActive(Context context) {
    try {
      ConnectivityManager cm =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      for (Network n : cm.getAllNetworks()) {
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  public static org.campusportal.app.core.BoundHttpTransport transport(
      Wifi wifi, org.campusportal.app.core.RunControl control, Runnable check) {
    return new org.campusportal.app.core.BoundHttpTransport(
        url -> (HttpURLConnection) wifi.network.openConnection(url, Proxy.NO_PROXY), control, check);
  }

  // Retained for the existing device harness; production tasks use RunControl directly.
  public static PortalClient.Transport transport(
      Wifi wifi, long deadline, java.util.concurrent.atomic.AtomicBoolean cancelled) {
    org.campusportal.app.core.RunControl control = new org.campusportal.app.core.RunControl(
        new org.campusportal.app.core.RunControl.Clock() {
          public long wall() { return System.currentTimeMillis(); }
          public long elapsed() { return android.os.SystemClock.elapsedRealtime(); }
        }, deadline, Math.max(0, deadline - System.currentTimeMillis()));
    return transport(wifi, control, () -> {
      if (cancelled.get()) throw new java.util.concurrent.CancellationException();
    });
  }

  // Never include exception messages: they may contain URLs, query strings or server input.
  static String failureSummary(Throwable error) {
    StringBuilder out = new StringBuilder();
    for (int depth = 0; error != null && depth < 5; depth++, error = error.getCause()) {
      if (depth > 0) out.append(" > ");
      out.append(error.getClass().getSimpleName());
      if (error instanceof android.system.ErrnoException) {
        int errno = ((android.system.ErrnoException) error).errno;
        out.append("(").append(android.system.OsConstants.errnoName(errno)).append(")");
      }
    }
    return out.toString();
  }

  public static String environment(Context context, Wifi wifi) {
    android.os.PowerManager p = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    String result = "Android=" + android.os.Build.VERSION.RELEASE + "(API " + android.os.Build.VERSION.SDK_INT
        + "); 机型=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
        + "; 亮屏=" + p.isInteractive() + "; 设备休眠=" + p.isDeviceIdleMode()
        + "; 省电=" + p.isPowerSaveMode()
        + "; 电池优化豁免=" + p.isIgnoringBatteryOptimizations(context.getPackageName())
        + "; VPN=" + vpnActive(context)
        + "; 后台流量限制=" + cm.getRestrictBackgroundStatus();
    android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
    android.app.ActivityManager.getMyMemoryState(process);
    result += "; 主界面前台=" + MainActivity.resumed + "; 进程重要性=" + process.importance;
    if (android.os.Build.VERSION.SDK_INT >= 33)
      result += "; 轻度休眠=" + p.isDeviceLightIdleMode() + "; 低功耗待机启用=" + p.isLowPowerStandbyEnabled();
    if (android.os.Build.VERSION.SDK_INT >= 34)
      result += "; 低功耗待机豁免=" + p.isExemptFromLowPowerStandby();
    if (wifi != null) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(wifi.network);
      LinkProperties props = cm.getLinkProperties(wifi.network);
      result += "; 网络标识=" + wifi.network.getNetworkHandle() + "; Wi-Fi=" + wifi.address + "; 网关=" + wifi.gateway;
      if (caps != null) result += "; 系统联网验证=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          + "; 系统认证页=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
      if (props != null) {
        java.util.List<String> dns = new java.util.ArrayList<>();
        for (java.net.InetAddress address : props.getDnsServers()) dns.add(address.getHostAddress());
        result += "; DNS=" + dns;
      }
    }
    return result;
  }
}
