package org.campusportal.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.campusportal.app.core.RunControl;

/** A task-scoped Wi-Fi request. Only ordered callback snapshots authorize I/O. */
final class WifiSession implements AutoCloseable {
  static final class Unavailable extends IOException {
    Unavailable(String message) { super(message); }
  }
  private static final class State {
    NetworkCapabilities caps;
    LinkProperties links;
    boolean gatewayReported;
    boolean blocked, blockKnown = Build.VERSION.SDK_INT < 29;
  }
  private final ConnectivityManager cm;
  private final Config config;
  private final Network assigned;
  private final boolean job;
  private final Consumer<String> events;
  private final Map<Network, State> states = new LinkedHashMap<>();
  private boolean closed, registered;

  WifiSession(Context context, Config config, boolean job, Network assigned, Consumer<String> events) {
    cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.config = config;
    this.job = job;
    this.assigned = assigned;
    this.events = events;
  }

  static NetworkRequest request() {
    return new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build();
  }

  synchronized void open() throws IOException {
    if (closed) throw new Unavailable("网络会话已关闭");
    if (!config.bound) throw new IOException("请先在设置中绑定宿舍网络");
    if (job && Build.VERSION.SDK_INT >= 28 && assigned == null)
      throw new Unavailable("系统尚未给本任务分配网络");
    if (registered) return;
    // JobScheduler owns the job network request. A service must hold its own request.
    if (job) cm.registerNetworkCallback(request(), callback, new Handler(Looper.getMainLooper()));
    else cm.requestNetwork(request(), callback, new Handler(Looper.getMainLooper()));
    registered = true;
  }

  private boolean accepts(Network network) { return assigned == null || assigned.equals(network); }

  final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
    @Override public void onAvailable(Network network) {
      synchronized (WifiSession.this) {
        if (closed || !accepts(network)) return;
        if (!job) states.clear(); // requestNetwork follows its latest best network.
        states.put(network, new State());
        WifiSession.this.notifyAll();
      }
      events.accept("Wi-Fi 回调可用；网络标识=" + network.getNetworkHandle());
    }
    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
      synchronized (WifiSession.this) {
        State s = states.get(network);
        if (closed || s == null) return;
        s.caps = new NetworkCapabilities(caps);
        WifiSession.this.notifyAll();
      }
    }
    @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
      synchronized (WifiSession.this) {
        State s = states.get(network);
        if (closed || s == null) return;
        if (!s.gatewayReported && links != null) {
          for (android.net.RouteInfo route : links.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof java.net.Inet4Address) {
              s.gatewayReported = true;
              events.accept("已取得 Wi-Fi 的 IPv4 默认网关，等待核对绑定网段");
              break;
            }
          }
        }
        s.links = links; // The framework delivers a snapshot; never mutate it.
        WifiSession.this.notifyAll();
      }
    }
    @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
      synchronized (WifiSession.this) {
        State s = states.get(network);
        if (closed || s == null) return;
        s.blocked = blocked;
        s.blockKnown = true;
        WifiSession.this.notifyAll();
      }
      events.accept("本应用网络访问被系统阻止=" + blocked + "；网络标识=" + network.getNetworkHandle());
    }
    @Override public void onLost(Network network) {
      synchronized (WifiSession.this) {
        if (closed || states.remove(network) == null) return;
        WifiSession.this.notifyAll();
      }
      events.accept("Wi-Fi 回调丢失；网络标识=" + network.getNetworkHandle());
    }
    @Override public void onUnavailable() {
      synchronized (WifiSession.this) { if (closed) return; WifiSession.this.notifyAll(); }
      events.accept("系统未能提供请求的 Wi-Fi");
    }
  };

  private NetworkController.Wifi matching(Network n, State s) {
    for (NetworkController.Wifi wifi : NetworkController.describe(n, s.caps, s.links))
      if (NetworkController.matches(wifi, config)) return wifi;
    return null;
  }

  synchronized NetworkController.Wifi ready() throws Unavailable {
    if (closed) throw new Unavailable("网络会话已关闭");
    for (Map.Entry<Network, State> item : states.entrySet()) {
      State s = item.getValue();
      NetworkController.Wifi wifi = matching(item.getKey(), s);
      if (wifi == null || !s.blockKnown) continue;
      if (s.blocked) throw new Unavailable("系统已阻止本应用访问宿舍 Wi-Fi");
      return wifi;
    }
    return null;
  }

  NetworkController.Wifi await(RunControl control, Runnable check) throws Exception {
    return await(control, check, 10000L);
  }

  NetworkController.Wifi await(RunControl control, Runnable check, long waitMillis) throws Exception {
    long end = SystemClock.elapsedRealtime() + Math.min(waitMillis, control.remaining());
    String last = "等待宿舍 Wi-Fi 就绪超时，未发送认证信息";
    while (true) {
      check.run();
      synchronized (this) {
        if (closed) throw new Unavailable("网络会话已关闭");
        try {
          NetworkController.Wifi wifi = ready();
          if (wifi != null) return wifi;
        } catch (Unavailable e) {
          // A newly started job may receive its unblock callback just after its link callback.
          last = e.getMessage();
        }
        long left = end - SystemClock.elapsedRealtime();
        if (left <= 0) throw new Unavailable(last);
        wait(Math.min(250L, left));
      }
    }
  }

  synchronized void check(NetworkController.Wifi wifi) throws Unavailable {
    if (closed) throw new Unavailable("网络会话已关闭");
    State s = states.get(wifi.network);
    NetworkController.Wifi current = s == null ? null : matching(wifi.network, s);
    if (s == null || current == null || !current.address.equals(wifi.address))
      throw new Unavailable("宿舍 Wi-Fi 已断开或地址已变化，停止使用旧连接");
    if (!s.blockKnown || s.blocked) throw new Unavailable("系统尚未允许本应用访问宿舍 Wi-Fi");
  }

  synchronized boolean captivePortal(NetworkController.Wifi wifi) {
    State s = states.get(wifi.network);
    return !closed && s != null && s.caps != null
        && s.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        && !s.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
  }

  @Override public void close() {
    boolean unregister;
    synchronized (this) {
      if (closed) return;
      closed = true;
      states.clear();
      notifyAll();
      unregister = registered;
      registered = false;
    }
    if (unregister) try { cm.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) {}
  }
}
