package org.campusportal.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  static volatile boolean resumed;
  private AppUi ui;
  private android.widget.FrameLayout host;
  private final android.os.Handler refresh =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable polling =
      new Runnable() {
        public void run() {
          snapshot();
          // The activity removes this callback onPause; use less UI polling while idle.
          refresh.postDelayed(this, WorkService.isRunning() ? 3000 : 15000);
        }
      };
  private AppStore store;
  private String pendingExport;
  private boolean loaded;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new AppStore(this);
    applySystemColors();
    host = new android.widget.FrameLayout(this);
    host.setBackgroundColor(new MaterialStyle(this).surface);
    host.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout()
                        | android.view.WindowInsets.Type.ime());
            boolean ime=insets.isVisible(android.view.WindowInsets.Type.ime());
            view.setPadding(i.left,i.top,i.right,ime?i.bottom:0);
            if(ui!=null){
              ui.setBottomSafeInset(insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom);
              ui.setKeyboardVisible(ime);
            }
          } else {
            int bottom=insets.getSystemWindowInsetBottom();
            boolean ime=bottom>new MaterialStyle(this).dp(160);
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),ime?bottom:0);
            if(ui!=null){ui.setBottomSafeInset(ime?0:bottom);ui.setKeyboardVisible(ime);}
          }
          return insets;
        });
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    installUi();
    setContentView(host);
    loaded = true;
  }

  private void applySystemColors() {
    MaterialStyle palette = new MaterialStyle(this);
    getWindow().setStatusBarColor(palette.surface);
    getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    if(Build.VERSION.SDK_INT>=28)getWindow().setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
    if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
    int flags=android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE|android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    if(!palette.dark)flags|=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    getWindow().getDecorView().setSystemUiVisibility(flags);
  }

  private void installUi() {
    ui = new AppUi(
            this,
            (action, extra) -> {
              try {
                extra.put("action", action);
                handle(extra);
              } catch (Exception e) {
                error(
                    e instanceof IllegalArgumentException
                        ? e.getMessage()
                        : "操作未完成：" + e.getClass().getSimpleName());
                snapshot();
              }
            });
    host.addView(ui.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
  }

  private JSONObject permissions() throws Exception {
    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
    boolean battery = power.isIgnoringBatteryOptimizations(getPackageName());
    boolean notifications =
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled()
            && (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    return new JSONObject()
        .put("alarm", ScheduleManager.allowed(this))
        .put("battery", battery)
        .put("notifications", notifications)
        .put("vpn", NetworkController.vpnActive(this))
        .put("package", getPackageName());
  }

  private void snapshot() {
    if (!loaded || isFinishing() || isDestroyed()) return;
    try {
      Config c = store.config();
      JSONObject account = new JSONObject();
      boolean unreadable = false;
      try {
        account = store.credentials();
      } catch (Exception e) {
        unreadable = true;
      }
      JSONObject state = store.status();
      state
          .put("ready", c.bound && store.credentialsReady())
          .put("credentialUnreadable", unreadable)
          .put(
              "next",
              store.enabled()
                      && ScheduleManager.allowed(this)
                      && store.credentialsReady()
                      && c.bound
                  ? store.planned()
                  : -1)
          .put("windowEnd", c.schedule().windowEnd(System.currentTimeMillis()))
          .put("completedWindowEnd", store.completedWindowEnd())
          .put("now", System.currentTimeMillis());
      emit(
          "snapshot",
          new JSONObject()
              .put("config", c.json())
              .put("username", account.optString("username", ""))
              .put("passwordSaved", !account.optString("password", "").isEmpty())
              .put("status", state)
              .put("permissions", permissions())
              .put("logs", store.logs()));
    } catch (Exception e) {
      error("读取本机状态未完成，请重新打开应用");
    }
  }

  private void emit(String type, Object data) {
    if (ui != null && !isFinishing() && !isDestroyed()) ui.receive(type, data);
  }

  private void message(String text) {
    emit("message", text);
  }

  private void error(String text) {
    emit("error", text == null ? "操作未完成" : text);
  }

  private void open(Intent intent) {
    try {
      startActivity(intent);
    } catch (RuntimeException e) {
      error("当前系统没有对应入口，请在系统设置中打开本应用详情");
    }
  }

  private void handle(JSONObject request) throws Exception {
    String action = request.optString("action");
    switch (action) {
      case "appearance":
        {
          String palette=request.optString("palette","blue"), appearance=request.optString("appearance","system"),
              haptics=request.optString("haptics","gentle");
          if(!java.util.Arrays.asList(MaterialStyle.PALETTE_IDS).contains(palette)
              ||!java.util.Arrays.asList("system","light","dark").contains(appearance)
              ||!java.util.Arrays.asList("off","gentle","standard").contains(haptics))
            throw new IllegalArgumentException("主题设置无效，请重试");
          ui.flushAutoSave();
          AppUi.Draft draft=ui.captureDraft();
          ui.dispose();
          getSharedPreferences(MaterialStyle.PREFS,0).edit().putString(MaterialStyle.PALETTE,palette)
              .putString(MaterialStyle.APPEARANCE,appearance).putString(MaterialStyle.HAPTICS,haptics).apply();
          applySystemColors();host.setBackgroundColor(new MaterialStyle(this).surface);
          host.removeAllViews();installUi();snapshot();ui.restoreDraft(draft);
          host.requestApplyInsets();
          break;
        }
      case "background":
        finish();
        break;
      case "snapshot":
        snapshot();
        break;
      case "save":
        {
          if (WorkService.isRunning()) throw new IllegalArgumentException("请先停止本次任务，再修改设置");
          boolean automatic=request.optBoolean("automatic");
          Config c = Config.from(request.getJSONObject("config"));
          if(automatic)c.loginUrl="";
          if (request.optBoolean("testAfterSave") && !c.bound)
            throw new IllegalArgumentException("请先绑定当前宿舍 Wi-Fi，再保存并测试");
          String username = request.optString("username", "").trim(),
              password = request.optString("password", "");
          JSONObject old = new JSONObject();
          try {
            old = store.credentials();
          } catch (Exception ignored) {
          }
          if (username.isEmpty() && password.isEmpty() && !old.optString("username").isEmpty())
            throw new IllegalArgumentException("如需移除账号，请使用下方的“清除本机账号密码”");
          if (password.isEmpty()
              && !old.optString("username").equals(username)
              && !username.isEmpty()) throw new IllegalArgumentException("更换账号时请同时填写密码");
          boolean credentialsChanged=!password.isEmpty()&&(!username.equals(old.optString("username"))||!password.equals(old.optString("password")));
          boolean configChanged=!c.json().toString().equals(store.config().json().toString());
          if(credentialsChanged)store.saveCredentials(username,password);
          if(configChanged||credentialsChanged){
            RecoveryScheduler.cancel(this);
            if(configChanged)store.saveConfig(c);
            if(credentialsChanged)store.resetCooldown();
            ScheduleManager.plan(this,false);
            store.log(automatic?"已自动保存设置":"已保存设置");
          }
          emit(automatic?"autoSaved":"saved",true);
          snapshot();
          if (request.optBoolean("testAfterSave")) {
            emit("startTesting", true);
            handle(new JSONObject().put("action", "login"));
          } else if(!automatic)message("设置已保存");
          break;
        }
      case "bindWifi":
        {
          if (WorkService.isRunning()) throw new IllegalArgumentException("请先停止本次任务，再绑定网络");
          java.util.List<NetworkController.Wifi> list = NetworkController.available(this);
          if (list.isEmpty()) throw new IllegalArgumentException("尚未找到带内网网关的 Wi-Fi，请先连接宿舍 Wi-Fi");
          if (list.size() > 1 && !request.has("index")) {
            JSONArray options = new JSONArray();
            for (NetworkController.Wifi wifi : list) options.put(wifi.json());
            emit("wifiChoices", options);
            break;
          }
          int index = request.optInt("index", 0);
          if (index < 0 || index >= list.size())
            throw new IllegalArgumentException("Wi-Fi 已变化，请重新选择");
          NetworkController.Wifi wifi = list.get(index);
          Config c = store.config();
          c.gateway = wifi.gateway;
          c.subnet = wifi.subnet;
          c.bound = true;
          RecoveryScheduler.cancel(this);
          store.saveConfig(c);
          ScheduleManager.plan(this, false);
          store.log("已绑定宿舍网络：网关 " + c.gateway + "，网段 " + c.subnet);
          message("已绑定当前 Wi-Fi 的网关和网段");
          snapshot();
          break;
        }
      case "enabled":
        {
          boolean value = request.optBoolean("value");
          if (value && (!store.config().bound || !store.credentialsReady()))
            throw new IllegalArgumentException("请先绑定宿舍网络，并保存账号密码");
          store.enabled(value);
          if (value) {
            store.stoppedWindowEnd(-1);
            store.blockedForegroundWindowEnd(-1);
          }
          if (!value) WorkService.cancel(this);
          long planned = ScheduleManager.plan(this, value);
          store.log(
              value
                  ? planned > 0
                      ? "已开启自动计划；下次 " + AppStore.format(planned, true)
                      : "已保存自动计划，等待准时启动权限"
                  : "已关闭自动计划");
          message(value ? planned > 0 ? "自动计划已安排" : "请允许准时启动，才能安排自动检测" : "自动计划已关闭");
          snapshot();
          break;
        }
      case "backgroundTest":
        new YiduSheets.Builder(this).setTitle("后台联网自测")
            .setMessage("先做前台检测，再安排延迟检测，随后退到后台或锁屏。延迟测试可选仅检测或使用已保存账号检测并登录。短时通过后仍需隔夜验证。\n\n" + NightPowerGuide.SUMMARY)
            .setPositiveButton("立即检测", (d, w) -> {
              if (WorkService.isRunning()) { error("请先等待当前任务结束"); return; }
              BackgroundTest.startNow(this);
            })
            .setNeutralButton("延迟自测", (d, w) -> showBackgroundTest())
            .setNegativeButton("关闭", null).show();
        break;
      case "login":
      case "linkLogin":
      case "diagnose":
      case "web":
        {
          if (WorkService.isRunning()) throw new IllegalArgumentException("本次任务正在进行，可在首页停止");
          if (!store.config().bound) throw new IllegalArgumentException("请先在设置中绑定宿舍网络");
          if ((action.equals("login") || action.equals("linkLogin")) && !store.credentialsReady())
            throw new IllegalArgumentException("请先保存账号和密码");
          if (action.equals("linkLogin") && store.config().loginUrl.isEmpty())
            throw new IllegalArgumentException("请先在设置中保存登录网址");
          store.state("checking", "正在启动宿舍 Wi-Fi 任务");
          String currentUrl = request.optString("loginUrl", "");
          if (!currentUrl.isEmpty() && (!action.equals("linkLogin")
              || !new org.campusportal.app.core.PortalProtocol(store.config().host).trusted(currentUrl)))
            throw new IllegalArgumentException("网页返回的认证地址不在允许范围内");
          WorkService.start(this, action, currentUrl);
          snapshot();
          break;
        }
      case "portalBrowser":
        if (WorkService.isRunning()) throw new IllegalArgumentException("请先停止本次任务，再打开登录网页");
        NetworkController.select(this, store.config());
        if (store.config().loginUrl.isEmpty()) throw new IllegalArgumentException("请先在设置中保存登录网址");
        store.log("用户打开登录网页；页面打开不代表已联网");
        startActivityForResult(new Intent(this, PortalBrowserActivity.class)
            .putExtra("config", store.config().json().toString()), 21);
        break;
      case "stop":
        WorkService.cancel(this);
        message("已请求停止当前任务");
        break;
      case "notifications":
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
          requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 3);
        else
          open(
              new Intent(
                  Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        break;
      case "alarm":
        if (Build.VERSION.SDK_INT >= 31)
          open(
              new Intent(
                  Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                  Uri.parse("package:" + getPackageName())));
        else message("当前系统无需单独授权准时启动");
        break;
      case "battery":
        open(
            new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
        break;
      case "appSettings":
        open(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        break;
      case "wifiSettings":
        open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        break;
      case "vpnSettings":
        open(new Intent("android.settings.VPN_SETTINGS"));
        break;
      case "exportConfig":
        pendingExport = store.config().json().put("loginUrl", "").toString(2);
        createDocument("application/json", "campus-network-profile.json");
        break;
      case "exportLogs":
        pendingExport = store.exportLogs();
        createDocument("text/plain", "campus-portal-log.txt");
        break;
      case "importConfig":
        openForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
            10);
        break;
      case "clearLogs":
        store.clearLogs();
        snapshot();
        message("日志已清空");
        break;
      case "clearCredentials":
        new YiduSheets.Builder(this)
            .setTitle("清除本机账号？")
            .setMessage("将删除本机保存的校园网账号密码，并关闭自动计划。网络配置和日志保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "清除",
                (d, w) -> {
                  WorkService.cancel(this);
                  store.clearCredentials();
                  ScheduleManager.plan(this, false);
                  store.log("用户已清除本机账号");
                  emit("saved", true);
                  snapshot();
                })
            .show();
        break;
      default:
        error("不支持的操作");
    }
  }

  private void createDocument(String type, String name) {
    openForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(type)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, name),
        11);
  }

  private void openForResult(Intent intent, int code) {
    try {
      startActivityForResult(intent, code);
    } catch (RuntimeException e) {
      error("未找到文件选择器");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 21 && result == RESULT_OK && data != null) {
      try {
        String action = data.getBooleanExtra("login", false) ? "linkLogin" : "web";
        handle(new JSONObject().put("action", action).put("loginUrl", data.getStringExtra("loginUrl")));
      } catch (Exception e) { error(e instanceof IllegalArgumentException ? e.getMessage() : "任务启动未完成"); }
      return;
    }
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    try {
      if (request == 11) {
        if (pendingExport == null) throw new IllegalArgumentException("导出内容已失效，请重新点击导出");
        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
          if (output == null) throw new java.io.IOException();
          output.write(pendingExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        pendingExport = null;
        message("已导出，不包含账号密码");
      } else if (request == 10) {
        if (WorkService.isRunning()) throw new IllegalArgumentException("请先停止本次任务，再导入配置");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
          if (input == null) throw new java.io.IOException();
          byte[] chunk = new byte[4096];
          int n;
          while ((n = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
            if (buffer.size() > 131072) throw new IllegalArgumentException("配置文件过大");
          }
        }
        JSONObject imported = new JSONObject(buffer.toString("UTF-8"));
        JSONObject config = imported.has("config") ? imported.getJSONObject("config") : imported;
        if (!config.has("portalHost") && imported.has("username")) {
          JSONObject merged = store.config().json();
          if (imported.has("service")) merged.put("service", imported.getString("service"));
          config = merged;
        }
        Config c = Config.from(config);
        final JSONObject account = imported;
        boolean hasAccount = imported.has("password") && !imported.optString("password").isEmpty();
        new YiduSheets.Builder(this)
            .setTitle("导入网络配置")
            .setMessage(
                "认证服务器："
                    + c.host
                    + "\n网关："
                    + (c.bound ? c.gateway : "尚未绑定")
                    + "\n"
                    + (hasAccount ? "文件含账号密码，将加密保存到本机。" : "不包含账号密码，其他宿舍需要填写自己的账号。"))
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "导入",
                (d, w) -> {
                  try {
                    if (WorkService.isRunning()) {
                      error("请先停止本次任务，再导入配置");
                      return;
                    }
                    if (hasAccount)
                      store.saveCredentials(
                          account.getString("username").trim(), account.getString("password"));
                    RecoveryScheduler.cancel(this);
                    store.saveConfig(c);
                    store.enabled(false);
                    ScheduleManager.plan(this, false);
                    store.log("已导入配置，自动计划保持关闭，请先测试");
                    emit("saved", true);
                    snapshot();
                  } catch (Exception e) {
                    error("导入失败，请检查配置中的账号与参数格式");
                  }
                })
            .show();
      }
    } catch (Exception e) {
      error(e instanceof IllegalArgumentException ? e.getMessage() : "文件操作未完成，请检查文件格式");
    }
  }

  private void showBackgroundTest() {
    android.widget.Button details = YiduSheets.button(this, new MaterialStyle(this), "查看详细自测记录", false,
        () -> new YiduSheets.Builder(this)
        .setTitle("详细自测记录")
        .setMessage(BackgroundTest.fullSummary(this))
        .setPositiveButton("关闭", null).show());
    new YiduSheets.Builder(this).setTitle("延迟后台自测")
        .setMessage(BackgroundTest.summary(this) + "\n\n" + NightPowerGuide.SUMMARY)
        .setView(details)
        .setPositiveButton("两分钟后自测", (d, w) -> {
          new YiduSheets.Builder(this).setTitle("选择自测方式")
              .setItems(new String[]{"仅检测（不提交账号）", "检测并登录（使用已保存账号）"}, (choice, which) -> {
                try {
                  BackgroundTest.schedule(this, which == 1);
                  message(which == 1 ? "已安排登录自测；请熄屏等待，已联网时不会重复登录" : "已安排仅检测；请熄屏等待");
                } catch (RuntimeException e) {
                  error(e instanceof IllegalArgumentException ? e.getMessage() : "安排失败，请检查准时启动权限");
                }
              }).setNegativeButton("取消", null).show();
        })
        .setNeutralButton("取消待测", (d, w) -> { BackgroundTest.cancel(this); message("已取消延迟自测"); })
        .setNegativeButton("关闭", null).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (store != null) ScheduleManager.plan(this, true);
    snapshot();
    refresh.removeCallbacks(polling);
    refresh.postDelayed(polling, 3000);
  }

  @Override
  protected void onPause() {
    if(ui!=null)ui.flushAutoSave();
    resumed = false;
    refresh.removeCallbacks(polling);
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    snapshot();
  }

  @Override
  public void onBackPressed() {
    if (ui == null || !ui.back()) finish();
  }

  @Override
  protected void onDestroy() {
    if(ui!=null)ui.dispose();
    refresh.removeCallbacks(polling);
    super.onDestroy();
  }
}
