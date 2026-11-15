package org.campusportal.app.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protocol from the working Python client, with a configurable trusted host. */
public final class PortalProtocol {
  private static final Pattern URL =
      Pattern.compile(
          "https?://[^\\s\"'<>]+/eportal/index\\.jsp\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern RELATIVE =
      Pattern.compile("[\"'](/eportal/index\\.jsp\\?[^\"'<>\\s]+)");
  private final String host;

  public PortalProtocol(String host) {
    if (!isPrivateIPv4(host))
      throw new IllegalArgumentException("认证服务器须填写内网 IPv4 地址，例如 10.100.1.5");
    this.host = host;
  }

  public static long ipv4(String text) {
    if (text == null) throw new IllegalArgumentException("IPv4 地址为空");
    String[] parts = text.split("\\.", -1);
    if (parts.length != 4) throw new IllegalArgumentException("IPv4 地址格式不正确");
    long result = 0;
    for (String part : parts) {
      if (!part.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("IPv4 地址格式不正确");
      int n = Integer.parseInt(part);
      if (n > 255) throw new IllegalArgumentException("IPv4 地址格式不正确");
      result = (result << 8) | n;
    }
    return result;
  }

  public static boolean isPrivateIPv4(String host) {
    try {
      long n = ipv4(host);
      return (n >>> 24) == 10 || (n >>> 20) == 0xac1 || (n >>> 16) == 0xc0a8 || (n >>> 22) == 0x191;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static boolean inSubnet(String address, String cidr) {
    try {
      String[] pair = cidr.split("/", -1);
      if (pair.length != 2) return false;
      int prefix = Integer.parseInt(pair[1]);
      if (prefix < 1 || prefix > 32) return false;
      long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
      return (ipv4(address) & mask) == (ipv4(pair[0]) & mask);
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static String subnet(String address, int prefix) {
    if (prefix < 1 || prefix > 32) throw new IllegalArgumentException("网段前缀不正确");
    long n = ipv4(address) & ((0xffffffffL << (32 - prefix)) & 0xffffffffL);
    return ((n >>> 24) & 255)
        + "."
        + ((n >>> 16) & 255)
        + "."
        + ((n >>> 8) & 255)
        + "."
        + (n & 255)
        + "/"
        + prefix;
  }

  public boolean trusted(String text) {
    try {
      URI u = new URI(text);
      int p = u.getPort();
      return "http".equalsIgnoreCase(u.getScheme())
          && host.equals(u.getHost())
          && u.getRawUserInfo() == null
          && (p == -1 || p == 80 || p == 8080)
          && "/eportal/index.jsp".equals(u.getPath())
          && u.getRawQuery() != null
          && !u.getRawQuery().isEmpty()
          && u.getRawFragment() == null;
    } catch (Exception e) {
      return false;
    }
  }

  public String entry() {
    return "http://" + host + "/";
  }

  /** Browser navigation/resources stay on the configured school origin. No file/intents. */
  public boolean browserAllowed(String text) {
    try {
      URI u = new URI(text);
      int port = u.getPort();
      return "http".equalsIgnoreCase(u.getScheme()) && host.equals(u.getHost())
          && u.getRawUserInfo() == null && u.getRawFragment() == null
          && (port == -1 || port == 80 || port == 8080);
    } catch (Exception e) { return false; }
  }

  private String discoveryTarget(String base, String candidate) {
    try {
      if (candidate == null || candidate.trim().isEmpty()) return null;
      URI u = new URI(base).resolve(candidate.trim());
      int port = u.getPort();
      if (!"http".equalsIgnoreCase(u.getScheme()) || !host.equals(u.getHost())
          || u.getRawUserInfo() != null || u.getRawFragment() != null
          || (port != -1 && port != 80 && port != 8080)) return null;
      return u.toASCIIString();
    } catch (Exception e) {
      return null;
    }
  }

  private static String pageText(String body) {
    return body.replace("&amp;", "&").replace("&#38;", "&")
        .replace("&#x26;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("\\/", "/").replace("\\u0026", "&").replace("\\x26", "&");
  }

  /** Follow explicit read-only navigation only within the configured auth origin. */
  public String nextDiscoveryPage(String base, String location, String body) {
    String found = discoveryTarget(base, location);
    if (found != null) return found;
    String text = pageText(body);
    Matcher js = Pattern.compile(
        "(?:\\b(?:window|top|self)\\s*\\.\\s*)?\\blocation\\s*"
            + "(?:(?:\\.\\s*href\\s*)?=\\s*|\\.\\s*(?:replace|assign)\\s*\\(\\s*)"
            + "[\"']([^\"'<>\\r\\n]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(text);
    while (js.find()) {
      found = discoveryTarget(base, js.group(1));
      if (found != null) return found;
    }
    Matcher tags = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(body);
    Pattern attrs = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    while (tags.find()) {
      String equiv = "", content = "";
      Matcher a = attrs.matcher(tags.group());
      while (a.find()) {
        String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
        if (a.group(1).equalsIgnoreCase("http-equiv")) equiv = value;
        if (a.group(1).equalsIgnoreCase("content")) content = pageText(value);
      }
      if (!equiv.equalsIgnoreCase("refresh")) continue;
      Matcher url = Pattern.compile("(?:^|;)\\s*url\\s*=\\s*['\"]?([^'\"]+)", Pattern.CASE_INSENSITIVE).matcher(content);
      if (url.find()) {
        found = discoveryTarget(base, url.group(1));
        if (found != null) return found;
      }
    }
    return null;
  }

  private String resolve(String base, String candidate) {
    try {
      String text = new URI(base).resolve(candidate).toASCIIString();
      return trusted(text) ? text : null;
    } catch (Exception e) {
      return null;
    }
  }

  public String discover(String base, String location, String body) {
    String found = location == null || location.isEmpty() ? null : resolve(base, location);
    if (found != null) return found;
    String text = pageText(body);
    Matcher m = URL.matcher(text);
    while (m.find()) {
      if (trusted(m.group())) return m.group();
    }
    m = RELATIVE.matcher(text);
    while (m.find()) {
      found = resolve(base, m.group(1));
      if (found != null) return found;
    }
    return trusted(base) ? base : null;
  }

  public static String encode(String value) {
    StringBuilder out = new StringBuilder();
    final char[] HEX = "0123456789ABCDEF".toCharArray();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int n = b & 255;
      if ((n >= 'a' && n <= 'z')
          || (n >= 'A' && n <= 'Z')
          || (n >= '0' && n <= '9')
          || n == '-'
          || n == '_'
          || n == '.'
          || n == '~') out.append((char) n);
      else out.append('%').append(HEX[n >>> 4]).append(HEX[n & 15]);
    }
    return out.toString();
  }

  public byte[] loginBody(String portal, String username, String password, String service) {
    if (!trusted(portal)) throw new IllegalArgumentException("认证地址不在允许范围内");
    try {
      String[][] fields = {
        {"userId", username},
        {"password", password},
        {"service", service},
        {"queryString", new URI(portal).getRawQuery()},
        {"operatorPwd", ""},
        {"operatorUserId", ""},
        {"validcode", ""},
        {"passwordEncrypt", "false"}
      };
      StringBuilder out = new StringBuilder();
      for (String[] field : fields) {
        if (out.length() > 0) out.append('&');
        out.append(field[0]).append('=').append(encode(encode(field[1])));
      }
      return out.toString().getBytes(StandardCharsets.US_ASCII);
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException("认证地址格式不正确");
    }
  }

  public String endpoint(String portal) {
    if (!trusted(portal)) throw new IllegalArgumentException("认证地址不在允许范围内");
    URI u = URI.create(portal);
    return "http://" + u.getRawAuthority() + "/eportal/InterFace.do?method=login";
  }

  public static boolean encryptionRequired(String text) {
    Matcher tags = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(text);
    Pattern attrs = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    while (tags.find()) {
      boolean target = false, encrypted = false;
      Matcher a = attrs.matcher(tags.group());
      while (a.find()) {
        String key = a.group(1).toLowerCase(Locale.ROOT),
            value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
        if ((key.equals("id") || key.equals("name")) && value.equalsIgnoreCase("passwordEncrypt"))
          target = true;
        if (key.equals("value") && value.equalsIgnoreCase("true")) encrypted = true;
      }
      if (target && encrypted) return true;
    }
    return Pattern.compile(
            "\\bpasswordEncrypt[\"']?\\s*[:=]\\s*[\"']?true\\b", Pattern.CASE_INSENSITIVE)
        .matcher(text)
        .find();
  }
}
