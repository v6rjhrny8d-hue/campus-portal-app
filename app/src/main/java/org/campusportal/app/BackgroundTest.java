package org.campusportal.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Explicit user-requested, one-shot diagnostic; never changes the daily schedule. */
public final class BackgroundTest extends BroadcastReceiver {
  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("background_test", 0); }
  private static PendingIntent alarm(Context c) {
    return PendingIntent.getBroadcast(c, 133, new Intent(c, BackgroundTest.class).setAction("selftest"),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  static void startNow(Context c) {
    if (TaskRunner.isRunning() || RecoveryScheduler.pending(c)) throw new IllegalArgumentException("请先等待当前任务结束");
    if (!new AppStore(c).config().bound) throw new IllegalArgumentException("请先绑定宿舍 Wi-Fi");
    prefs(c).edit().putString("kind", "前台对照").apply();
    WorkService.start(c, "selftest");
  }
  static String kind(Context c) { return prefs(c).getString("kind", "前台对照"); }
  static void schedule(Context c) { schedule(c, false); }
  static void schedule(Context c, boolean login) {
    if (!new AppStore(c).config().bound) throw new IllegalArgumentException("请先绑定宿舍 Wi-Fi");
    if (TaskRunner.isRunning() || RecoveryScheduler.pending(c))
      throw new IllegalArgumentException("请等待当前任务及备用任务结束后自测");
    if (!ScheduleManager.allowed(c)) throw new IllegalArgumentException("请先允许准时启动权限");
    if (login && !new AppStore(c).credentialsReady()) throw new IllegalArgumentException("请先保存账号和密码");
    long at = System.currentTimeMillis() + 120000L;
    ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c));
    prefs(c).edit().putLong("pending", at).putBoolean("login", login)
        .putLong("revision", new AppStore(c).revision()).apply();
    new AppStore(c).log("用户安排延迟后台自测；计划=" + AppStore.format(at, true) + (login ? "；检测并登录，使用本机已保存账号" : "；只检测，不提交账号"));
  }
  static void cancel(Context c) {
    ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c));
    prefs(c).edit().remove("pending").apply();
  }
  @Override public void onReceive(Context c, Intent intent) {
    long at = prefs(c).getLong("pending", 0);
    if (at <= 0 || System.currentTimeMillis() < at) return;
    boolean login = prefs(c).getBoolean("login", false);
    String kind = login ? "延迟登录自测" : "延迟自测";
    long revision = prefs(c).getLong("revision", -1);
    prefs(c).edit().remove("pending").apply();
    AppStore store = new AppStore(c);
    if (System.currentTimeMillis() - at > 300000L || TaskRunner.isRunning() || RecoveryScheduler.pending(c)
        || (login && (revision != store.revision() || !store.credentialsReady()))) {
      prefs(c).edit().putString(kind, "本次未执行：已过期、有其他任务，或账号配置发生变化；请重新安排").apply();
      store.log("延迟自测跳过，避免与自动任务重叠或执行过期测试"); return;
    }
    prefs(c).edit().putString("kind", kind).apply();
    store.log("延迟自测触发；到达延迟=" + (System.currentTimeMillis() - at) + "ms");
    try { WorkService.start(c, login ? "selftestLogin" : "selftest"); }
    catch (RuntimeException e) {
      prefs(c).edit().putString(kind, "系统未允许启动：" + e.getClass().getSimpleName()).apply();
    }
  }
  static void result(Context c, String kind, String reason, boolean verified,
      int requests, int responses, int posts, long duration, String environment) {
    String detail = "";
    try { detail = "\n结果：" + new AppStore(c).status().optString("detail"); } catch (Exception ignored) {}
    String text = AppStore.format(System.currentTimeMillis(), true)
        + "\n" + (verified ? "Wi-Fi 网页访问验证成功" : "尚未验证联网")
        + "；请求=" + requests + "；响应=" + responses + "；提交=" + posts
        + "；耗时=" + duration + "ms；结束=" + reason + detail + "\n" + environment;
    prefs(c).edit().putString(kind, text).apply();
  }
  static String summary(Context c) {
    SharedPreferences p = prefs(c); long pending = p.getLong("pending", 0);
    return "先做一次前台对照，再安排延迟检测并锁屏。已在线时不会重复登录。\n"
        + (pending > 0 ? "待测时间：" + AppStore.format(pending, true) + (p.getBoolean("login", false) ? "（检测并登录）" : "（仅检测）") + "\n" : "")
        + "\n前台对照：\n" + compact(p.getString("前台对照", "尚无结果"))
        + "\n\n延迟自测：\n" + compact(p.getString("延迟自测", "尚无结果"))
        + "\n\n延迟登录自测：\n" + compact(p.getString("延迟登录自测", "尚无结果"))
        + "\n\n短时测试不能代替隔夜验证。";
  }
  private static String compact(String result) {
    String[] lines = result.split("\\n");
    if (lines.length < 2) return result;
    StringBuilder text = new StringBuilder(lines[0]).append('\n').append(lines[1]);
    if (lines.length > 2 && lines[2].startsWith("结果：")) text.append('\n').append(lines[2]);
    String environment = lines[lines.length - 1];
    java.util.regex.Matcher screen = java.util.regex.Pattern.compile("亮屏=(true|false)").matcher(environment);
    java.util.regex.Matcher foreground = java.util.regex.Pattern.compile("主界面前台=(true|false)").matcher(environment);
    if (screen.find()) text.append("\n屏幕：").append(screen.group(1).equals("true") ? "亮屏" : "熄屏");
    if (foreground.find()) text.append("；界面：").append(foreground.group(1).equals("true") ? "前台" : "后台");
    return text.toString();
  }
  static String fullSummary(Context c) {
    SharedPreferences p = prefs(c); long pending = p.getLong("pending", 0);
    return "先做一次前台对照，再安排延迟检测并锁屏。可选择仅检测，或使用已保存账号检测并登录。已在线时不重复登录。\n"
        + (pending > 0 ? "待测时间：" + AppStore.format(pending, true) + (p.getBoolean("login", false) ? "（检测并登录）" : "（仅检测）") + "\n" : "")
        + "\n前台对照：\n" + p.getString("前台对照", "尚无结果")
        + "\n\n延迟自测：\n" + p.getString("延迟自测", "尚无结果")
        + "\n\n延迟登录自测：\n" + p.getString("延迟登录自测", "尚无结果")
        + "\n\n是否处于后台、锁屏，以各次记录为准。短时测试不能代替隔夜验证。";
  }
}
