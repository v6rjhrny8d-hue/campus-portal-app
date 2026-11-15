package org.campusportal.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35, manifest = org.robolectric.annotation.Config.NONE)
public class WifiSessionTest {
  Context context;
  Config config;
  Network network = new Network(101), other = new Network(102);

  @Before public void setup() {
    context = RuntimeEnvironment.getApplication();
    config = new Config(); config.bound = true;
    config.gateway = "192.168.31.1"; config.subnet = "192.168.31.0/24";
  }

  WifiSession session(boolean job, Network assigned) {
    return new WifiSession(context, config, job, assigned, message -> {});
  }
  NetworkCapabilities caps() {
    return new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }
  LinkProperties links(String address, String gateway) throws Exception {
    LinkProperties p = new LinkProperties();
    p.setInterfaceName("wlan0"); p.addLinkAddress(new LinkAddress(address + "/24"));
    p.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0"), InetAddress.getByName(gateway), "wlan0"));
    return p;
  }
  void announce(WifiSession s, Network n, NetworkCapabilities capabilities) throws Exception {
    s.callback.onAvailable(n);
    s.callback.onCapabilitiesChanged(n, capabilities);
    s.callback.onLinkPropertiesChanged(n, links("192.168.31.131", "192.168.31.1"));
    s.callback.onBlockedStatusChanged(n, false);
  }

  @Test public void waitsForLinkAndPerAppAccessBeforeUsingNetwork() throws Exception {
    WifiSession s = session(false, null);
    s.callback.onAvailable(network); assertNull(s.ready());
    s.callback.onCapabilitiesChanged(network, caps()); assertNull(s.ready());
    s.callback.onLinkPropertiesChanged(network, links("192.168.31.131", "192.168.31.1"));
    assertNull(s.ready());
    s.callback.onBlockedStatusChanged(network, false);
    assertEquals(network, s.ready().network);
  }

  @Test public void validatedFlagCannotOverrideBlockedAccess() throws Exception {
    WifiSession s = session(false, null);
    announce(s, network, caps().addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    s.callback.onBlockedStatusChanged(network, true);
    assertThrows(WifiSession.Unavailable.class, s::ready);
    s.callback.onBlockedStatusChanged(network, false);
    assertEquals(network, s.ready().network);
  }

  @Test public void captivePortalWifiIsAllowedWithoutValidatedCapability() throws Exception {
    WifiSession s = session(false, null);
    announce(s, network, caps().addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
    assertEquals(network, s.ready().network);
    assertTrue(s.captivePortal(s.ready()));
    s.callback.onCapabilitiesChanged(network, caps()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    assertFalse(s.captivePortal(s.ready()));
  }

  @Test public void lostOrReplacedNetworkCannotKeepSending() throws Exception {
    WifiSession s = session(false, null);
    announce(s, network, caps()); NetworkController.Wifi old = s.ready();
    s.callback.onLost(network);
    assertThrows(WifiSession.Unavailable.class, () -> s.check(old));
    announce(s, other, caps());
    assertEquals(other, s.ready().network);
    assertThrows(WifiSession.Unavailable.class, () -> s.check(old));
  }

  @Test public void changedIpInvalidatesPortalQueryFromOldAddress() throws Exception {
    WifiSession s = session(false, null);
    announce(s, network, caps()); NetworkController.Wifi old = s.ready();
    s.callback.onLinkPropertiesChanged(network, links("192.168.31.132", "192.168.31.1"));
    assertThrows(WifiSession.Unavailable.class, () -> s.check(old));
  }

  @Test public void systemJobUsesOnlyItsAssignedNetwork() throws Exception {
    WifiSession s = session(true, network);
    announce(s, other, caps()); assertNull(s.ready());
    announce(s, network, caps()); assertEquals(network, s.ready().network);
  }

  @Test public void missingJobAssignmentDoesNotFallBackToAnotherNetwork() {
    WifiSession s = session(true, null);
    assertThrows(WifiSession.Unavailable.class, s::open);
  }

  @Test public void rejectsVpnCellularAndWrongGatewayEvenWhenValidated() throws Exception {
    WifiSession s = session(false, null);
    announce(s, network, caps().addTransportType(NetworkCapabilities.TRANSPORT_VPN));
    assertNull(s.ready());
    s.callback.onCapabilitiesChanged(network, new NetworkCapabilities()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    assertNull(s.ready());
    s.callback.onCapabilitiesChanged(network, caps());
    s.callback.onLinkPropertiesChanged(network, links("192.168.31.131", "192.168.31.2"));
    assertNull(s.ready());
  }

  @Test public void closingSessionUnregistersAndIgnoresLateCallbacks() throws Exception {
    WifiSession s = session(false, null); s.open();
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    assertTrue(shadowOf(cm).getNetworkCallbacks().contains(s.callback));
    s.close(); s.close();
    assertFalse(shadowOf(cm).getNetworkCallbacks().contains(s.callback));
    announce(s, network, caps());
    assertThrows(WifiSession.Unavailable.class, s::ready);
    assertThrows(WifiSession.Unavailable.class, s::open);
  }

  @Test public void errorChainIncludesErrnoButOmitsUrlsAndCredentials() {
    UnknownHostException e = new UnknownHostException("http://fixture/?password=secret");
    e.initCause(new ErrnoException("private query", OsConstants.ENETUNREACH));
    String message = NetworkController.failureSummary(e);
    assertTrue(message.contains("UnknownHostException"));
    assertTrue(message.contains("ENETUNREACH"));
    assertFalse(message.contains("secret"));
    assertFalse(message.contains("fixture"));
  }
}
