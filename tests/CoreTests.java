package org.campusportal.app.tests;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.campusportal.app.Config;
import org.campusportal.app.core.*;
import org.json.JSONObject;

/** Offline protocol and clock tests. All accounts below are invented test fixtures. */
public final class CoreTests {
  static int assertions;
  static final String QUERY = "wlanuserip=026c&url=http%3A%2F%2Fa.test%2F%3Fx%3D1%26y%3D2";
  static final String PORTAL = "http://10.100.1.5/eportal/index.jsp?" + QUERY;

  static void ok(boolean condition, String message) {
    assertions++;
    if (!condition) throw new AssertionError(message);
  }

  static void eq(Object expected, Object actual, String message) {
    ok(Objects.equals(expected, actual), message + " expected=" + expected + " actual=" + actual);
  }

  interface Task {
    void run() throws Exception;
  }

  static void rejects(Task task, String message) throws Exception {
    try {
      task.run();
      throw new AssertionError(message);
    } catch (IllegalArgumentException expected) {
      assertions++;
    }
  }

  static long at(int day, int hour, int minute, int second) {
    return ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, ZoneOffset.ofHours(8))
        .toInstant()
        .toEpochMilli();
  }

  static void schedules() throws Exception {
    Schedule s = new Schedule(420, 430, 127);
    eq(-1L, s.windowEnd(at(16, 6, 59, 59)), "before start");
    eq(at(16, 7, 10, 0), s.windowEnd(at(16, 7, 0, 0)), "start inclusive");
    eq(at(16, 7, 10, 0), s.windowEnd(at(16, 7, 9, 59)), "last second");
    eq(-1L, s.windowEnd(at(16, 7, 10, 0)), "end exclusive");
    eq(at(16, 7, 0, 0), s.nextStart(at(16, 6, 0, 0), true), "before window");
    eq(at(16, 7, 4, 3), s.nextStart(at(16, 7, 4, 0), true), "catch up current window");
    eq(at(17, 7, 0, 0), s.nextStart(at(16, 7, 9, 58), true), "no catch up too close to end");
    eq(at(17, 7, 0, 0), s.nextStart(at(16, 7, 0, 0), false), "exact start plans tomorrow");
    Schedule weekdays = new Schedule(420, 430, 31);
    eq(-1L, weekdays.windowEnd(at(17, 7, 5, 0)), "Saturday excluded");
    eq(at(19, 7, 0, 0), weekdays.nextStart(at(16, 8, 0, 0), false), "skip weekend");
    Schedule fridayNight = new Schedule(23 * 60 + 50, 10, 16);
    eq(at(17, 0, 10, 0), fridayNight.windowEnd(at(16, 23, 50, 0)), "overnight begins Friday");
    eq(at(17, 0, 10, 0), fridayNight.windowEnd(at(17, 0, 9, 59)), "overnight belongs to Friday");
    eq(-1L, fridayNight.windowEnd(at(16, 0, 1, 0)), "Thursday night not selected");
    eq(-1L, fridayNight.windowEnd(at(17, 0, 10, 0)), "overnight end exclusive");
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      eq(
          at(17, 7, 0, 0),
          s.nextStart(at(16, 8, 0, 0), false),
          "Beijing independent of device time zone");
    } finally {
      TimeZone.setDefault(original);
    }
    rejects(() -> new Schedule(420, 420, 127), "empty window rejected");
    rejects(() -> new Schedule(420, 430, 0), "no days rejected");
    rejects(() -> new Schedule(420, 541, 127), "more than two hours rejected");
    new Schedule(23 * 60, 60, 127);
    assertions++;
  }

  static void protocol() throws Exception {
    PortalProtocol p = new PortalProtocol("10.100.1.5");
    ok(p.trusted(PORTAL), "configured host trusted");
    ok(p.trusted(PORTAL.replace("1.5/", "1.5:8080/")), "8080 supported");
    for (String bad :
        new String[] {
          PORTAL.replace("10.100.1.5", "10.100.1.6"),
          PORTAL.replace("http:", "https:"),
          PORTAL.replace("10.100.1.5", "10.100.1.5.evil.test"),
          PORTAL.replace("//10", "//user:secret@10"),
          PORTAL.replace("1.5/", "1.5:8000/"),
          PORTAL + "#x",
          "http://10.100.1.5/eportal/index.jsp",
          "file:///eportal/index.jsp?a=1"
        }) ok(!p.trusted(bad), "untrusted URL must be rejected");
    eq(PORTAL, p.discover("http://1.2.0.1/", PORTAL, ""), "Location redirect");
    eq(
        PORTAL,
        p.discover(
            "http://1.2.0.1/",
            "",
            "<script>location='" + PORTAL.replace("&", "&amp;") + "';</script>"),
        "HTML entities preserve query");
    eq(
        PORTAL,
        p.discover("http://10.100.1.5/", "/eportal/index.jsp?" + QUERY, ""),
        "relative redirect");
    eq(
        null,
        p.discover("http://1.2.0.1/", "/eportal/index.jsp?bad=1", ""),
        "relative wrong host rejected");
    eq(
        "%20%2B%25%26%3D~",
        PortalProtocol.encode(" +%&=~"), "RFC3986 encoding uses percent20 not plus");
    String user = "student +&测试", password = "p+% &=", service = "中国移动";
    String body =
        new String(p.loginBody(PORTAL, user, password, service), StandardCharsets.US_ASCII);
    Map<String, String> decoded = new LinkedHashMap<>();
    for (String field : body.split("&", -1)) {
      String[] pair = field.split("=", 2);
      decoded.put(pair[0], URLDecoder.decode(URLDecoder.decode(pair[1], "UTF-8"), "UTF-8"));
    }
    eq(user, decoded.get("userId"), "username survives double encoding");
    eq(password, decoded.get("password"), "password symbols survive");
    eq(service, decoded.get("service"), "Chinese service survives");
    eq(QUERY, decoded.get("queryString"), "raw redirect query is not decoded before encoding");
    eq("false", decoded.get("passwordEncrypt"), "protocol flag");
    ok(
        body.contains("service=%25E4%25B8%25AD%25E5%259B%25BD%25E7%25A7%25BB%25E5%258A%25A8"),
        "working Python client Chinese test vector");
    eq(
        "http://10.100.1.5/eportal/InterFace.do?method=login",
        p.endpoint(PORTAL),
        "expected endpoint");
    rejects(
        () -> p.loginBody(PORTAL.replace("1.5", "1.6"), user, password, service),
        "never submit to another host");
    for (String html :
        new String[] {
          "<input id='passwordEncrypt' value='true'>",
          "<INPUT VALUE=TRUE name=passwordEncrypt>",
          "var passwordEncrypt = 'true';",
          "{\"passwordEncrypt\":true}"
        })
      ok(
          PortalProtocol.encryptionRequired(html),
          "encryption requirement must block unsupported protocol");
    ok(
        !PortalProtocol.encryptionRequired("<input id='passwordEncrypt' value='false'>"),
        "plain protocol supported");
    ok(PortalProtocol.isPrivateIPv4("100.64.0.1"), "CGNAT private range");
    ok(!PortalProtocol.isPrivateIPv4("100.128.0.1"), "outside CGNAT range");
    ok(!PortalProtocol.isPrivateIPv4("127.0.0.1"), "loopback not campus host");
    ok(!PortalProtocol.isPrivateIPv4("192.168.031.1"), "ambiguous IP rejected");
    eq("192.168.31.0/24", PortalProtocol.subnet("192.168.31.131", 24), "network prefix");
    ok(PortalProtocol.inSubnet("192.168.31.140", "192.168.31.0/24"), "DHCP address can change");
    ok(
        !PortalProtocol.inSubnet("192.168.30.140", "192.168.31.0/24"),
        "different dorm subnet rejected");
    eq("http://10.100.1.5/eportal/", p.nextDiscoveryPage(p.entry(), "/eportal/", ""), "auth-origin intermediate redirect");
    eq("http://10.100.1.5/eportal/", p.nextDiscoveryPage(p.entry(), "", "window.location.replace('/eportal/');"), "JavaScript navigation without execution");
    eq("http://10.100.1.5/eportal/", p.nextDiscoveryPage(p.entry(), "", "<meta content='0; url=/eportal/' http-equiv='refresh'>"), "meta refresh attribute order");
    eq("http://10.100.1.5/eportal/", p.nextDiscoveryPage(p.entry(), "", "<meta http-equiv='refresh' content='0; url=&quot;/eportal/&quot;'>"), "encoded meta refresh quotes");
    for (String bad : new String[]{"http://10.100.1.6/", "http://10.100.1.5.evil.test/", "http://user:pass@10.100.1.5/", "https://10.100.1.5/", "http://10.100.1.5:8000/", "http://10.100.1.5/#fragment", "file:///eportal/"})
      eq(null, p.nextDiscoveryPage(p.entry(), bad, ""), "untrusted discovery navigation blocked");
    String wireless = "http://10.100.1.5/eportal/index.jsp?wlanuserip=1111&wlanacname=2222&ssid=&nasip=3333&snmpagentip=&mac=4444&t=wireless-v2&url=5555&apmac=&nasid=6666&vid=7777&port=8888&nasportid=9999";
    eq(wireless, p.discover("http://www.baidu.com/",wireless,""), "wireless-v2 URL shape accepted unchanged; identifiers are synthetic");
  }

  static final class Fake implements PortalClient.Transport, PortalClient.Control {
    String redirect = PORTAL,
        page = "<input id='passwordEncrypt' value='false'>",
        reply = "{\"result\":\"success\"}";
    boolean https = false, failProbes = false, cancelled = false, cancelAfterPage = false;
    boolean httpsAfterLogin = true;
    boolean portalHint;
    long elapsed, httpsDelay, elapsedAtPost = -1;
    final List<Integer> timeouts = new ArrayList<>();
    int posts, gets, httpsCount;
    String postBody = "", cancelAfterUrl = "";
    final List<String> phases = new ArrayList<>();
    final List<String> diagnostics = new ArrayList<>(), requests = new ArrayList<>();
    final Map<String, PortalClient.Response> routes = new HashMap<>();

    public PortalClient.Response request(String url, byte[] body, int timeout) throws Exception {
      requests.add(url);
      timeouts.add(timeout);
      if (body != null) {
        elapsedAtPost = elapsed;
        posts++;
        postBody = new String(body, StandardCharsets.US_ASCII);
        return new PortalClient.Response(200, "", reply);
      }
      gets++;
      if (url.startsWith("https:")) {
        httpsCount++;
        elapsed += Math.min(httpsDelay, timeout);
        if (httpsDelay >= timeout) throw new java.net.SocketTimeoutException("fixture slow connection");
        if (!https && !(httpsAfterLogin && posts > 0)) throw new IOException("fixture offline");
        return new PortalClient.Response(200, "", "fixture verified");
      }
      if (url.equals(cancelAfterUrl)) cancelled = true;
      if (routes.containsKey(url)) return routes.get(url);
      if (url.equals(PORTAL)) {
        if (cancelAfterPage) cancelled = true;
        return new PortalClient.Response(200, "", page);
      }
      if (failProbes) throw new IOException("fixture endpoint blocked");
      return new PortalClient.Response(302, redirect, "");
    }

    public void check() {
      if (cancelled) throw new CancellationException();
    }

    public void progress(String phase, String message) {
      phases.add(phase);
    }

    public void diagnostic(String message) { diagnostics.add(message); }
    public boolean captivePortal() { return portalHint; }
    public long elapsedMillis() { return elapsed; }

    PortalClient client() {
      return new PortalClient("10.100.1.5", this, this);
    }

    PortalClient.Result run(boolean readOnly, boolean cooldown) throws Exception {
      return client().run(readOnly, "fixture-user", "fixture-pass", "中国移动", cooldown);
    }
  }

  static void discoveryRegressions() throws Exception {
    String root = "http://10.100.1.5/", middle = root + "eportal/";
    Fake f = new Fake(); f.redirect = root;
    f.routes.put(root, new PortalClient.Response(302, PORTAL, ""));
    eq("connected", f.run(false,false).state, "follow auth root before final portal");
    eq(1,f.posts,"chain submits only once");
    f = new Fake(); f.redirect = root;
    f.routes.put(root,new PortalClient.Response(200,"","window.location.replace('/eportal/');"));
    f.routes.put(middle,new PortalClient.Response(302,PORTAL,""));
    eq("needs_login",f.run(true,false).state,"three-step JavaScript navigation discovers portal");
    eq(0,f.posts,"read-only chained discovery never posts");
    f = new Fake(); f.redirect = root;
    f.routes.put(root,new PortalClient.Response(200,"","<meta content='0;url=/eportal/' http-equiv='refresh'>"));
    f.routes.put(middle,new PortalClient.Response(302,PORTAL,""));
    eq("connected",f.run(false,false).state,"meta refresh reaches portal");
    f = new Fake(); f.failProbes=true;f.https=false;f.httpsAfterLogin=false;f.httpsAfterLogin=true;
    f.routes.put(root,new PortalClient.Response(302,PORTAL,""));
    eq("connected",f.run(false,false).state,"configured entry works when public probes fail");
    eq(1,f.posts,"fallback authenticates once using fresh redirect");
    f = new Fake(); f.failProbes=true;
    f.routes.put("http://www.baidu.com/",new PortalClient.Response(302,PORTAL,""));
    eq("needs_login",f.run(true,false).state,"ordinary HTTP probe complements connectivity endpoints");
    f = new Fake();f.redirect="";f.https=true;
    eq("connected",f.run(false,false).state,"already-online path still succeeds");
    ok(!f.requests.contains(root),"already-online skips direct auth entry");
    f = new Fake();f.redirect=root;f.https=false;f.httpsAfterLogin=false;
    f.routes.put(root,new PortalClient.Response(302,root,""));
    eq("limited",f.run(false,false).state,"redirect loop returns bounded failure");
    ok(f.gets<=10,"visited URLs and hop limit bound probe requests");
    eq(0,f.posts,"loop cannot cause credential submission");
    f = new Fake();f.redirect=root;f.https=false;f.httpsAfterLogin=false;
    f.routes.put(root,new PortalClient.Response(302,"http://10.100.1.6/eportal/index.jsp?secret=fixture-token",""));
    eq("limited",f.run(false,false).state,"foreign auth origin rejected after intermediate hop");
    ok(f.requests.stream().noneMatch(u -> u.startsWith("http://10.100.1.6")),"never follows unknown origin");
    ok(f.diagnostics.stream().noneMatch(m -> m.contains("fixture-token") || m.contains(QUERY) || m.contains("fixture-pass")),"diagnostics omit query and credentials");
    f = new Fake();f.redirect=root;f.cancelAfterUrl=root;
    f.routes.put(root,new PortalClient.Response(302,PORTAL,""));
    try { f.run(false,false);throw new AssertionError("chain should cancel"); }
    catch (CancellationException expected) { assertions++; }
    eq(0,f.posts,"cancelling discovery prevents authentication");
    f = new Fake();
    String fresh=PORTAL+"&t=wireless-v2";
    f.routes.put(PORTAL,new PortalClient.Response(302,fresh,""));
    f.routes.put(fresh,new PortalClient.Response(200,"","<input name=passwordEncrypt value=false>"));
    eq("connected",f.run(false,false).state,"trusted page refresh before POST is supported");
    ok(f.postBody.contains("wireless-v2"),"POST uses latest raw query from trusted page redirect");
  }

  static final class Connection extends HttpURLConnection {
    final int status; final String location; final byte[] bytes;
    boolean forbidBody, bodyOpened, closed; int responseCalls;
    Connection(int status,String location,String body) throws Exception {
      super(new URL("http://fixture.test/"));this.status=status;this.location=location;
      this.bytes=body.getBytes(StandardCharsets.UTF_8);
    }
    public void connect() {} public void disconnect() {} public boolean usingProxy(){return false;}
    public int getResponseCode(){responseCalls++;return status;}
    public String getHeaderField(String key){return key.equalsIgnoreCase("Location")?location:null;}
    public InputStream getInputStream() throws IOException {
      bodyOpened=true;if(forbidBody)throw new IOException("body must not be read");
      return new ByteArrayInputStream(bytes){ public void close() throws IOException {closed=true;super.close();} };
    }
    public InputStream getErrorStream(){try{return getInputStream();}catch(IOException e){return null;}}
  }

  static void responseHeaders() throws Exception {
    for (int status:new int[]{301,302,303,307,308}) {
      Connection c=new Connection(status,PORTAL,"");c.forbidBody=true;
      PortalClient.Response r=HttpResponseReader.read(c,()->{});
      eq(PORTAL,r.location,"redirect Location survives unreadable body");
      ok(!c.bodyOpened,"redirect body is never opened");
    }
    Connection c=new Connection(204,null,"");c.forbidBody=true;
    eq(204,HttpResponseReader.read(c,()->{}).status,"204 requires no body");
    ok(!c.bodyOpened,"204 skips body");
    c=new Connection(200,null,"普通网页 fixture");
    eq("普通网页 fixture",HttpResponseReader.read(c,()->{}).body,"normal UTF-8 body remains intact");
    ok(c.closed,"successful body stream closes");
    c=new Connection(200,null,new String(new char[70000]).replace('\0','a'));
    eq(65536,HttpResponseReader.read(c,()->{}).body.length(),"body stays bounded at 64 KiB");
    c=new Connection(200,null,"");c.forbidBody=true;
    try {HttpResponseReader.read(c,()->{});throw new AssertionError("body error must propagate");}
    catch(IOException expected){assertions++;}
    c=new Connection(200,null,"fixture");
    try {HttpResponseReader.read(c,()->{throw new CancellationException();});throw new AssertionError("must cancel");}
    catch(CancellationException expected){assertions++;}
    eq(0,c.responseCalls,"cancel before headers prevents connect");
    c=new Connection(200,null,"fixture");final int[] calls={0};
    try {HttpResponseReader.read(c,()->{if(++calls[0]>2)throw new CancellationException();});throw new AssertionError("read must cancel");}
    catch(CancellationException expected){assertions++;}
    ok(c.closed,"cancel during body closes stream");
  }

  static void client() throws Exception {
    Fake fast = new Fake(); fast.portalHint = true;
    PortalClient.Result fastResult = fast.run(false, false);
    ok(fastResult.verified && fastResult.submitted, "portal hint takes fast path and still verifies HTTPS");
    eq("http://connect.rom.miui.com/generate_204", fast.requests.get(0), "known captive portal skips external HTTPS preflight");
    eq(1, fast.httpsCount, "only post-auth HTTPS verification on fast path");
    eq(1, fast.posts, "fast path submits exactly once");
    fast = new Fake(); fast.portalHint = true;
    eq("needs_login", fast.run(true, false).state, "read-only fast path identifies portal");
    eq(0, fast.posts, "fast diagnostic never posts");
    eq(0, fast.httpsCount, "fast diagnostic avoids blocked public endpoints");
    fast = new Fake(); fast.httpsDelay = 16000;
    fastResult = fast.run(false, false);
    eq(4000L, fast.elapsedAtPost, "two slow public checks share four-second preflight budget");
    eq(14000L, fast.elapsed, "post-auth checks also have a shared budget");
    eq("verifying", fastResult.state, "slow HTTPS does not fake verified access");
    eq(1, fast.posts, "slow HTTPS never loops a credential submission in client");
    fast = new Fake(); fast.portalHint = true; fast.failProbes = true; fast.https = true;
    fastResult = fast.run(false, false);
    ok(fastResult.verified && !fastResult.submitted, "stale captive flag falls back to real HTTPS without POST");
    fast = new Fake(); fast.portalHint = true; fast.httpsAfterLogin = false;
    fast.redirect = "http://10.100.1.6/eportal/index.jsp?fixture=1";
    fastResult = fast.run(false, false);
    ok(!fastResult.verified && !fastResult.submitted, "portal hint never authorizes an untrusted host");
    Fake f = new Fake();
    PortalClient.Result r = f.run(true, false);
    eq("needs_login", r.state, "diagnosis sees portal");
    eq(0, f.posts, "read-only never authenticates");
    f = new Fake();
    r = f.run(false, false);
    eq("connected", r.state, "login verifies HTTPS");
    ok(r.submitted && r.verified, "both submission and verification recorded");
    eq(1, f.posts, "one submission");
    ok(f.phases.contains("verifying"), "separate verification phase");
    f = new Fake();
    f.https = false; f.httpsAfterLogin = false;
    r = f.run(false, false);
    eq("verifying", r.state, "interface success alone is not online");
    ok(!r.verified, "unverified must stay unverified");
    eq(4, f.httpsCount, "both websites tried before and after login");
    f = new Fake();
    f.reply = "{\"result\":\"fail\",\"message\":\"fixture secret must not be logged\"}";
    r = f.run(false, false);
    eq("rejected", r.state, "account rejection distinct");
    ok(!r.message.contains("fixture secret"), "server response not echoed");
    f = new Fake();
    f.reply = "not JSON";
    r = f.run(false, false);
    eq("failed", r.state, "bad JSON not treated as account error");
    f = new Fake();
    f.page = "<input name=passwordEncrypt value=true>";
    r = f.run(false, false);
    eq("unsupported", r.state, "unsupported encryption blocked");
    eq(0, f.posts, "no unencrypted password sent to unsupported protocol");
    f = new Fake();
    r = f.run(false, true);
    eq("rejected", r.state, "cooldown state");
    eq(0, f.posts, "cooldown prevents submission");
    f = new Fake();
    f.redirect = "http://10.100.1.6/eportal/index.jsp?a=1";
    f.https = false; f.httpsAfterLogin = false;
    r = f.run(false, false);
    eq("limited", r.state, "unknown portal is limited");
    eq(0, f.posts, "credentials not sent to unknown host");
    f = new Fake();
    f.redirect = ""; f.https = true;
    r = f.run(false, false);
    eq("connected", r.state, "already online skips login");
    eq(0, f.posts, "no needless auth");
    eq(1, f.requests.size(), "already-online normal path needs one request");
    f = new Fake(); f.https = true; f.failProbes = true;
    r = f.client().run(false, "fixture-user", "fixture-pass", "中国移动", false, "http://10.100.1.5/", false);
    ok(r.verified && !r.submitted, "logged-in weekend succeeds despite unavailable saved portal");
    eq(1, f.requests.size(), "no portal request at all when already online");
    f = new Fake();
    f.failProbes = true; f.https = true;
    r = f.run(true, false);
    eq("connected", r.state, "HTTPS can verify even when HTTP endpoints blocked");
    eq(0, f.posts, "blocked probes never cause credential submission");
    f = new Fake();
    f.cancelAfterPage = true;
    try {
      f.run(false, false);
      throw new AssertionError("cancelled login should stop");
    } catch (CancellationException expected) {
      assertions++;
    }
    eq(0, f.posts, "cancellation checked immediately before POST");
  }

  static void config() throws Exception {
    Config c = Config.from(new JSONObject());
    eq(420, c.start, "default 07:00");
    eq(425, c.end, "default 07:05");
    eq(60, c.interval, "default once a minute");
    eq(430, Config.from(new JSONObject().put("start", 420).put("end", 430)).end,
        "existing saved end time remains unchanged");
    JSONObject j =
        c.json().put("bound", true).put("gateway", "192.168.31.1").put("subnet", "192.168.31.0/24");
    c = Config.from(j);
    ok(c.bound, "bound profile accepted");
    ok(
        !c.json().has("username") && !c.json().has("password"),
        "export does not contain credentials");
    rejects(
        () -> Config.from(new JSONObject(j.toString()).put("portalHost", "8.8.8.8")),
        "public auth host rejected");
    rejects(
        () -> Config.from(new JSONObject(j.toString()).put("gateway", "192.168.32.1")),
        "gateway outside saved subnet rejected");
    rejects(
        () -> Config.from(new JSONObject(j.toString()).put("interval", 1)),
        "tight retry loop rejected");
    rejects(
        () -> Config.from(new JSONObject(j.toString()).put("service", "")),
        "missing service rejected");
    rejects(
        () -> Config.from(new JSONObject(j.toString()).put("schema", 2)),
        "future configuration version rejected");
  }

  static PortalClient.Result direct(Fake f, String url, boolean readOnly, boolean cooldown) throws Exception {
    return f.client().run(readOnly, "fixture-user", "fixture-pass", "中国移动", cooldown, url, true);
  }

  static void savedLinks() throws Exception {
    PortalProtocol p = new PortalProtocol("10.100.1.5");
    ok(p.browserAllowed(p.entry() + "eportal/index.jsp"), "queryless entry can open in browser");
    ok(!p.browserAllowed("http://10.100.1.5.evil.test/"), "lookalike browser host blocked");
    ok(!p.browserAllowed("http://user:pass@10.100.1.5/"), "userinfo blocked");
    ok(!p.browserAllowed("intent://10.100.1.5/"), "external app schemes blocked");
    ok(!p.browserAllowed("file:///data/data/org.campusportal.app/"), "local files blocked");
    eq(PORTAL, p.discover(PORTAL, "", "<html>login form</html>"), "saved raw URL retained without redirect");
    String fresh = PORTAL + "&fresh=1";
    eq(fresh, p.discover(PORTAL, "", "<script>location='" + fresh + "'</script>"), "fresh page query preferred over saved query");
    Config c = Config.from(new JSONObject().put("loginUrl", PORTAL));
    eq(PORTAL, Config.from(c.json()).loginUrl, "URL including encoded query round trips exactly");
    eq("", Config.from(new JSONObject()).loginUrl, "old profiles keep automatic discovery");
    rejects(() -> Config.from(c.json().put("loginUrl", "http://10.100.1.6/")), "URL host must match configured host");
    Fake f = new Fake(); f.failProbes = true; f.https = false; f.httpsAfterLogin = false; f.httpsAfterLogin = true;
    PortalClient.Result r = direct(f, PORTAL, false, false);
    eq("https://www.baidu.com", f.requests.get(0), "check current Internet before visiting saved URL");
    eq(PORTAL, f.requests.get(2), "saved URL is first portal request after failed HTTPS");
    ok(r.submitted && r.verified, "direct URL authenticates even when public discovery fails");
    eq(1, f.posts, "direct login submits only once");
    ok(!f.requests.contains("http://1.1.1.1/"), "direct mode does not depend on public probes");
    String raw = URLDecoder.decode(URLDecoder.decode(f.postBody.split("queryString=")[1].split("&")[0], "UTF-8"), "UTF-8");
    eq(QUERY, raw, "direct POST preserves exact raw query after double encoding");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false;
    r = direct(f, PORTAL, true, false);
    eq("needs_login", r.state, "saved-link diagnosis is read-only");
    eq(0, f.posts, "read-only direct path never submits");
    f = new Fake(); f.https = true;
    r = direct(f, PORTAL, false, false);
    eq(1, f.requests.size(), "already online skips all saved-page discovery");
    ok(r.verified && !r.submitted, "already-online direct path does not reauthenticate");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false;
    r = direct(f, PORTAL, false, true);
    eq("rejected", r.state, "direct link respects account cooldown");
    eq(0, f.posts, "cooldown prevents direct POST");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false;
    f.routes.put(PORTAL, new PortalClient.Response(404, "", "not found"));
    r = direct(f, PORTAL, false, false);
    eq("limited", r.state, "HTTP error cannot be accepted as usable saved page");
    eq(0, f.posts, "broken saved link never submits credentials");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false; f.failProbes = true;
    f.routes.put(p.entry(), new PortalClient.Response(302, PORTAL, ""));
    r = f.client().run(false, "fixture-user", "fixture-pass", "中国移动", false, p.entry()+"unavailable", false);
    eq("verifying", r.state, "normal login falls back from unavailable saved entry");
    eq(1, f.posts, "fallback still authenticates only once");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false;
    f.routes.put(PORTAL, new PortalClient.Response(302, fresh, ""));
    f.routes.put(fresh, new PortalClient.Response(200, "", "<input name=passwordEncrypt value=false>"));
    direct(f, PORTAL, false, false);
    ok(f.postBody.contains("fresh"), "direct login follows refreshed query");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false;
    f.routes.put(PORTAL, new PortalClient.Response(200, "", "location='index.jsp?fresh=relative%26token';"));
    f.routes.put("http://10.100.1.5/eportal/index.jsp?fresh=relative%26token", new PortalClient.Response(200, "", "login"));
    direct(f, PORTAL, false, false);
    ok(f.postBody.contains("relative%252526token"), "relative JavaScript navigation replaces old saved query without decoding");
    ok(f.diagnostics.stream().noneMatch(m -> m.contains(QUERY) || m.contains("fixture-pass")), "direct diagnostics redact query and credentials");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false; f.page = "passwordEncrypt=true";
    eq("unsupported", direct(f, PORTAL, false, false).state, "unsupported encryption still blocks direct login");
    eq(0, f.posts, "no password sent for unsupported encryption");
    f = new Fake(); f.https = false; f.httpsAfterLogin = false; f.cancelAfterPage = true;
    try { direct(f, PORTAL, false, false); throw new AssertionError("direct path must cancel"); }
    catch (CancellationException expected) { assertions++; }
    eq(0, f.posts, "direct cancellation prevents POST");
  }


  static void homeStates() throws Exception {
    JSONObject c = new Config().json(), s = new JSONObject().put("ready", false).put("running", false);
    HomeState h = HomeState.from(c, s, false);
    ok(h.showSetup && h.primaryLabel.equals("开始设置"), "first launch offers setup");
    ok(!h.historicalSuccess, "unconfigured app cannot report historical success");
    c.put("bound", true); s.put("ready", true).put("phase", "connected").put("lastVerified", 100);
    h = HomeState.from(c,s,true);
    ok(h.title.equals("上次联网验证成功"), "success is explicitly historical");
    ok(h.detail.contains("未持续监测"), "historical result explains no continuous monitoring");
    ok(h.badge.equals("任务待机"), "network success is independent from active task");
    ok(!h.showSetup, "verified configured user exits first-use guide");
    s.put("enabled", true).put("windowEnd", 900).put("completedWindowEnd",900);
    h = HomeState.from(c,s,true);
    ok(h.task.contains("等待下次计划"), "completed window displays sleep until next plan");
    s.put("running", true).put("phase", "verifying"); h=HomeState.from(c,s,true);
    ok(h.title.equals("正在验证网页访问"), "verification has distinct progress");
    ok(h.primaryLabel.equals("停止本次任务"), "active task provides scoped stop");
    ok(!h.historicalSuccess, "running verification does not claim online");
    s.put("running",false);h=HomeState.from(c,s,true);
    ok(h.needsAttention && h.title.equals("网页访问尚未验证"), "unverified portal success remains unverified");
    s.put("phase","rejected"); h=HomeState.from(c,s,true);
    ok(h.repairAction.equals("account"), "rejection links to account settings");
    s.put("phase","failed").put("detail","等待已绑定的宿舍 Wi-Fi 自动连接");h=HomeState.from(c,s,true);
    ok(h.repairAction.equals("wifi"), "missing Wi-Fi links to system Wi-Fi");
    s.put("detail","Wi-Fi 请求超时");h=HomeState.from(c,s,true);
    ok(h.repairAction.equals("logs"), "unexplained request failure offers records");
    s.put("phase","needs_login");h=HomeState.from(c,s,true);
    ok(h.repairAction.equals("login"), "read-only detection offers login action");
    s.put("phase","connected").put("credentialUnreadable",true).put("ready",false);h=HomeState.from(c,s,true);
    ok(h.repairAction.equals("account") && !h.historicalSuccess, "unreadable credentials override stale success");
  }

  public static void main(String[] args) throws Exception {
    logHistory();
    ReliabilityTests.run();
    savedLinks();
    responseHeaders();
    discoveryRegressions();
    homeStates();
    Schedule early = new Schedule(420, 425, 127);
    long now = java.time.ZonedDateTime.parse("2026-10-17T07:01:00+08:00").toInstant().toEpochMilli();
    long end = early.windowEnd(now);
    long tomorrow = now + 24 * 60 * 60 * 1000L - 60000;
    ok(early.nextStart(now, true, -1) == now + 3000, "unfinished window catches up");
    ok(early.nextStart(now, true, end) == tomorrow, "completed window skips catch up until tomorrow");
    ok(early.nextStart(now, false, end) == tomorrow, "success retains next daily alarm");
    ok(early.nextStart(now + 86400000L, true, end) == now + 86400000L + 3000, "next day is eligible again");
    Schedule night = new Schedule(1438, 3, 127);
    long midnight = java.time.ZonedDateTime.parse("2026-10-18T00:01:00+08:00").toInstant().toEpochMilli();
    ok(night.nextStart(midnight, true, night.windowEnd(midnight)) > night.windowEnd(midnight), "completed cross-midnight window stays asleep");
    schedules();
    protocol();
    client();
    config();
    System.out.println(
        "PASS: "
            + assertions
            + " assertions; schedule, trusted redirects, encoding, login outcomes, cancellation and"
            + " profiles.");
  }

  static void logHistory() throws Exception {
    long now = ZonedDateTime.parse("2026-11-02T09:00:00+08:00").toInstant().toEpochMilli();
    long cutoff = ZonedDateTime.parse("2026-10-04T00:00:00+08:00").toInstant().toEpochMilli();
    org.json.JSONArray input = new org.json.JSONArray()
        .put(new JSONObject().put("timestamp", cutoff - 1).put("text", "expired"))
        .put(new JSONObject().put("timestamp", cutoff).put("text", "boundary"))
        .put(new JSONObject().put("time", "11-02 00:01:00").put("text", "legacy"));
    org.json.JSONArray kept = LogHistory.retain(input, now);
    eq(2, kept.length(), "retain today and preceding 29 Beijing days with exclusive older boundary");
    eq("boundary", kept.getJSONObject(0).getString("text"), "midnight at oldest retained date is included");
    ok(kept.getJSONObject(1).optLong("timestamp") > 0, "legacy entries are normalized with a full timestamp");
    List<LogHistory.Day> days = LogHistory.group(kept, now);
    eq("2026-11-02", days.get(0).key, "newest date is first");
    eq("今天 · 11月2日", days.get(0).title(now), "today label uses Beijing calendar date");
    eq("00:01:00", LogHistory.timeLabel(kept.getJSONObject(1), now), "day body shows original clock time");
    long jan = ZonedDateTime.parse("2027-01-02T00:10:00+08:00").toInstant().toEpochMilli();
    org.json.JSONArray winter = LogHistory.retain(new org.json.JSONArray()
        .put(new JSONObject().put("time", "12-31 07:00:00").put("text", "last year"))
        .put(new JSONObject().put("time", "01-01 07:00:00").put("text", "yesterday")), jan);
    List<LogHistory.Day> winterDays = LogHistory.group(winter, jan);
    eq("2027-01-01", winterDays.get(0).key, "January and prior December sort correctly");
    eq("2026-12-31", winterDays.get(1).key, "yearless December is inferred as previous year");
    eq("2026年12月31日", winterDays.get(1).title(jan), "cross-year heading is unambiguous");
    eq(0, LogHistory.retain(winter, jan + 366L * 86400000L).length(), "normalized old records cannot revive the next year");
    JSONObject leap = new JSONObject().put("time", "02-29 07:00:00").put("text", "leap");
    long march = ZonedDateTime.parse("2024-03-01T09:00:00+08:00").toInstant().toEpochMilli();
    eq("2024-02-29", LogHistory.group(new org.json.JSONArray().put(leap), march).get(0).key, "leap date parses correctly");
    org.json.JSONArray busy = new org.json.JSONArray();
    for (int i = 0; i < 1205; i++) busy.put(new JSONObject().put("timestamp", now).put("text", "fixture " + i));
    eq(1205, LogHistory.retain(busy, now).length(), "month retention is not truncated by the old thousand-row cap");
    eq(1205, LogHistory.group(busy, now).get(0).records.size(), "all retained day records are available to the UI");
    TimeZone saved = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      eq("2027-01-02", LogHistory.today(jan).toString(), "device zone cannot shift the log day");
    } finally { TimeZone.setDefault(saved); }
    org.json.JSONArray unknown = LogHistory.retain(new org.json.JSONArray()
        .put(new JSONObject().put("time", "invalid").put("text", "preserve diagnostic")), now);
    eq(1, unknown.length(), "undatable legacy data is not silently destroyed");
    eq(LogHistory.UNKNOWN, LogHistory.group(unknown, now).get(0).key, "undatable legacy record has its own heading");
  }
}
