package org.campusportal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AppStore {
  private static final Object LOCK = new Object();
  private static final String KEY_ALIAS = "campus_portal_credentials_v1";
  private final SharedPreferences prefs;
  private final Context context;

  public AppStore(Context context) {
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("campus", Context.MODE_PRIVATE);
  }

  public Config config() {
    try {
      return Config.from(new JSONObject(prefs.getString("config", "{}")));
    } catch (Exception e) {
      return new Config();
    }
  }

  public void saveConfig(Config config) throws Exception {
    Reconnect.clear(context);
    prefs.edit().putString("config", config.json().toString()).putLong("revision", revision() + 1).commit();
  }

  public boolean enabled() {
    return prefs.getBoolean("enabled", false);
  }

  public void enabled(boolean value) {
    if (!value) Reconnect.clear(context);
    prefs.edit().putBoolean("enabled", value).commit();
  }

  public long planned() {
    return prefs.getLong("planned", -1);
  }

  public void planned(long time) {
    prefs.edit().putLong("planned", time).remove("alarmWall").remove("alarmElapsed")
        .remove("alarmBoot").commit();
  }

  public void planned(long time, long wall, long elapsed, int boot) {
    prefs.edit().putLong("planned", time).putLong("alarmWall", wall)
        .putLong("alarmElapsed", elapsed).putInt("alarmBoot", boot).commit();
  }

  public String alarmTiming(long now, long elapsed, int boot) {
    long wall = prefs.getLong("alarmWall", -1), start = prefs.getLong("alarmElapsed", -1);
    String result = "调度方式=setExactAndAllowWhileIdle(RTC_WAKEUP)；精确定时权限=" + ScheduleManager.allowed(context);
    if (planned() <= 0 || wall < 0 || start < 0 || elapsed < start || boot < 0
        || prefs.getInt("alarmBoot", -1) != boot)
      return result + "；单调时钟对照不可用（旧版本记录或设备已重启）";
    return result + "；单调时钟延迟=" + Math.max(0, elapsed - start - (planned() - wall))
        + "ms；墙钟偏移=" + (now - wall - (elapsed - start)) + "ms";
  }

  public long completedWindowEnd() {
    return prefs.getLong("completedWindowEnd", -1);
  }

  public void completedWindowEnd(long time) {
    prefs.edit().putLong("completedWindowEnd", time).commit();
  }

  public long revision() { return prefs.getLong("revision", 0); }
  public long recoveryGeneration() { return prefs.getLong("recoveryGeneration", 0); }
  public void invalidateRecovery() {
    prefs.edit().putLong("recoveryGeneration", recoveryGeneration() + 1).commit();
  }
  public long stoppedWindowEnd() { return prefs.getLong("stoppedWindowEnd", -1); }
  public void stoppedWindowEnd(long value) { prefs.edit().putLong("stoppedWindowEnd", value).commit(); }
  public long blockedForegroundWindowEnd() { return prefs.getLong("blockedForegroundWindowEnd", -1); }
  public void blockedForegroundWindowEnd(long value) {
    prefs.edit().putLong("blockedForegroundWindowEnd", value).commit();
  }

  public boolean eligible(long end) {
    return org.campusportal.app.core.RunControl.eligible(System.currentTimeMillis(), end,
        config().schedule().windowEnd(System.currentTimeMillis()), enabled(),
        completedWindowEnd(), stoppedWindowEnd());
  }

  public void beginTask(String id, String mode, String driver, long deadline) {
    synchronized (LOCK) {
      String previous = prefs.getString("activeTask", "");
      if (!previous.isEmpty()) log("发现上次任务未正常收尾：" + previous
          + "；可能是进程被终止，无法仅凭此项判断具体原因");
      prefs.edit().putString("activeTask", id + " / " + mode + " / " + driver
          + " / 最迟结束 " + format(deadline, true)).putLong("taskHeartbeat", System.currentTimeMillis()).commit();
    }
  }

  public void heartbeat() {
    prefs.edit().putLong("taskHeartbeat", System.currentTimeMillis()).apply();
  }

  public void endTask(long duration, boolean foreground) {
    synchronized (LOCK) {
      SharedPreferences.Editor edit = prefs.edit().remove("activeTask").remove("taskHeartbeat");
      if (foreground) {
        JSONArray recent = foregroundRuns(), next = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < recent.length(); i++) {
          JSONObject item = recent.optJSONObject(i);
          if (item != null && item.optLong("end") > now - 86400000L) next.put(item);
        }
        try { next.put(new JSONObject().put("end", now).put("duration", duration)); }
        catch (Exception ignored) {}
        edit.putString("foregroundRuns", next.toString());
      }
      edit.commit();
    }
  }

  private JSONArray foregroundRuns() {
    try { return new JSONArray(prefs.getString("foregroundRuns", "[]")); }
    catch (Exception e) { return new JSONArray(); }
  }

  public long recordedForegroundMillis() {
    JSONArray recent = foregroundRuns();
    long total = 0, now = System.currentTimeMillis();
    for (int i = 0; i < recent.length(); i++) {
      JSONObject item = recent.optJSONObject(i);
      if (item != null && item.optLong("end") > now - 86400000L) total += item.optLong("duration");
    }
    return total;
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  public JSONObject credentials() throws Exception {
    String encoded = prefs.getString("credentials", "");
    if (encoded.isEmpty()) return new JSONObject();
    String[] parts = encoded.split(":", -1);
    if (parts.length != 2) throw new IllegalStateException("本机凭据无法读取，请重新填写账号密码");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new JSONObject(
        new String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  public void saveCredentials(String username, String password) throws Exception {
    if (username.isEmpty()
        || username.length() > 128
        || password.isEmpty()
        || password.length() > 512) throw new IllegalArgumentException("请填写有效的账号和密码");
    JSONObject data = new JSONObject().put("username", username).put("password", password);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String encoded =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    Reconnect.clear(context);
    prefs.edit().putString("credentials", encoded).putLong("cooldown", 0).putLong("revision", revision() + 1).commit();
  }

  public boolean credentialsReady() {
    try {
      JSONObject c = credentials();
      return !c.optString("username").isEmpty() && !c.optString("password").isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  public void clearCredentials() {
    Reconnect.clear(context);
    prefs.edit().remove("credentials").putBoolean("enabled", false).putLong("cooldown", 0)
        .putLong("revision", revision() + 1).commit();
  }

  public long cooldown() {
    return prefs.getLong("cooldown", 0);
  }

  public void rejectFor15Minutes() {
    prefs.edit().putLong("cooldown", System.currentTimeMillis() + 900000).commit();
  }

  public void resetCooldown() {
    prefs.edit().putLong("cooldown", 0).commit();
  }

  public static String format(long time, boolean date) {
    SimpleDateFormat f = new SimpleDateFormat(date ? "MM-dd HH:mm:ss" : "HH:mm", Locale.CHINA);
    f.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
    return f.format(new Date(time));
  }

  public void state(String phase, String message) {
    prefs
        .edit()
        .putString("phase", phase)
        .putString("detail", message)
        .putLong("lastEvent", System.currentTimeMillis())
        .commit();
  }

  public String phase() { return prefs.getString("phase", "idle"); }

  public void checked(boolean verified) {
    SharedPreferences.Editor e = prefs.edit().putLong("lastCheck", System.currentTimeMillis());
    if (verified) e.putLong("lastVerified", System.currentTimeMillis());
    e.commit();
  }

  public void authenticated() {
    prefs.edit().putLong("lastAuth", System.currentTimeMillis()).commit();
  }

  public void log(String text) {
    synchronized (LOCK) {
      JSONArray next = logs();
      long now = System.currentTimeMillis();
      try {
        next.put(
            new JSONObject()
                .put("time", format(now, true))
                .put("timestamp", now)
                .put("text", text));
      } catch (Exception ignored) {
      }
      prefs.edit().putString("logs", next.toString()).commit();
    }
  }

  public JSONArray logs() {
    synchronized (LOCK) {
      try {
        String raw = prefs.getString("logs", "[]");
        JSONArray kept = org.campusportal.app.core.LogHistory.retain(new JSONArray(raw), System.currentTimeMillis());
        String normalized = kept.toString();
        if (!raw.equals(normalized)) prefs.edit().putString("logs", normalized).commit();
        return kept;
      } catch (Exception e) {
        return new JSONArray();
      }
    }
  }

  public void clearLogs() {
    synchronized (LOCK) {
      prefs.edit().remove("logs").commit();
    }
  }

  public String exportLogs() {
    StringBuilder out = new StringBuilder("校园网助手 1.5.8 · 诊断日志（不含账号密码）\n");
    out.append("导出时间：").append(format(System.currentTimeMillis(), true)).append("（北京时间）\n");
    out.append("记录范围：最近30个自然日（北京时间，含今天）；更早记录自动清理\n");
    out.append("导出时环境（不代表任务发生时）：").append(NetworkController.environment(context, null)).append('\n');
    out.append("本应用已记录的近 24 小时前台运行：").append(recordedForegroundMillis())
        .append("ms（不是系统配额读数；进程被强杀时可能不完整）\n");
    if (!prefs.getString("activeTask", "").isEmpty())
      out.append("未收尾标记：").append(prefs.getString("activeTask", ""))
          .append("；最近心跳 ").append(format(prefs.getLong("taskHeartbeat", 0), true)).append('\n');
    JSONArray logs = logs();
    for (int i = 0; i < logs.length(); i++) {
      JSONObject line = logs.optJSONObject(i);
      if (line != null)
        out.append(line.optString("time")).append(" ").append(line.optString("text")).append('\n');
    }
    return out.toString();
  }

  public JSONObject status() throws Exception {
    RecoveryScheduler.reconcile(context);
    boolean reconnectWaiting = Reconnect.waiting(context) && !TaskRunner.isRunning();
    return new JSONObject()
        .put("reconnectPending", reconnectWaiting)
        .put("phase", prefs.getString("phase", "idle"))
        .put("detail", prefs.getString("detail", "绑定宿舍网络并填写账号，即可测试登录"))
        .put("lastCheck", prefs.getLong("lastCheck", 0))
        .put("lastVerified", prefs.getLong("lastVerified", 0))
        .put("lastAuth", prefs.getLong("lastAuth", 0))
        .put("cooldown", cooldown())
        .put("enabled", enabled())
        .put("running", WorkService.isRunning() || RecoveryScheduler.pending(context) || reconnectWaiting)
        .put("recoveryPending", RecoveryScheduler.pending(context) && !WorkService.isRunning());
  }
}
