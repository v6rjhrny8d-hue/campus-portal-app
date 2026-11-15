package org.campusportal.app.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.campusportal.app.*;
import org.campusportal.app.core.PortalClient;

/** Disposable emulator only. All accounts are invented fixtures. */
public final class SmokeInstrumentation extends Instrumentation {
  private int checks;
  private MainActivity activity;
  private AppUi ui;
  private String skipped = "";
  private String capturePrefix = "", upgradeMode = "";

  public void onCreate(Bundle args) {
    super.onCreate(args);
    capturePrefix = args == null ? "" : args.getString("capturePrefix", "");
    upgradeMode = args == null ? "" : args.getString("upgradeMode", "");
    start();
  }

  private void ok(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private String text(int id) {
    String[] out = new String[1];
    runOnMainSync(() -> out[0] = ((TextView) activity.findViewById(id)).getText().toString());
    return out[0];
  }

  /** Render the measured native app view; system bars and emulator dialogs are excluded. */
  private void shot(Context c, String name) {
    try {
      waitForIdleSync();
      Thread.sleep(200);
      final android.graphics.Bitmap[] rendered = new android.graphics.Bitmap[1];
      runOnMainSync(
          () -> {
            android.view.View view = ui.view();
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
            android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));
            rendered[0] = bitmap;
          });
      if (rendered[0] != null) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        rendered[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
        Bundle image = new Bundle();
        image.putString(
            "campus_image",
            name
                + "|"
                + android.util.Base64.encodeToString(
                    bytes.toByteArray(), android.util.Base64.NO_WRAP));
        sendStatus(2, image);
        rendered[0].recycle();
      }
    } catch (Exception ignored) {
    }
  }

  public void onStart() {
    Context context = getTargetContext();
    AppStore store = new AppStore(context);
    Bundle report = new Bundle();
    try {
      if (!upgradeMode.isEmpty()) {
        if (upgradeMode.equals("seed")) {
          Config sample = new Config(); sample.bound = true;
          sample.gateway = "10.0.2.2"; sample.subnet = "10.0.2.0/24";
          sample.label = "fixture-upgrade-room"; sample.start = 421; sample.end = 426;
          sample.interval = 45; sample.days = 31;
          store.saveConfig(sample); store.saveCredentials("upgrade-fixture", "fixture-password-74");
          store.completedWindowEnd(123456789L); store.enabled(true);
        } else {
          ok(store.credentials().getString("username").equals("upgrade-fixture"), "Update preserves encrypted username");
          ok(store.credentials().getString("password").equals("fixture-password-74"), "Update preserves encrypted password");
          ok(store.config().label.equals("fixture-upgrade-room") && store.config().bound, "Update preserves network binding");
          ok(store.config().start==421 && store.config().end==426 && store.config().interval==45 && store.config().days==31, "Update preserves schedule and interval");
          ok(store.enabled(), "Update preserves auto-plan switch");
          ok(store.completedWindowEnd()==123456789L, "Update preserves completed-window marker");
        }
        report.putString("stream", "PASS: upgrade " + upgradeMode + " (" + checks + " checks)\n");
        finish(Activity.RESULT_OK,report); return;
      }
      if (!capturePrefix.isEmpty()) {
        activity =
            (MainActivity)
                startActivitySync(
                    new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        Field field = MainActivity.class.getDeclaredField("ui");
        field.setAccessible(true);
        ui = (AppUi) field.get(activity);
        runOnMainSync(() -> ui.route(0));
        shot(context, capturePrefix + "-home.png");
        runOnMainSync(() -> ui.route(1));
        shot(context, capturePrefix + "-settings.png");
        runOnMainSync(() -> ui.route(2));
        shot(context, capturePrefix + "-logs.png");
        report.putString("stream", "PASS: native capture\n");
        finish(Activity.RESULT_OK, report);
        return;
      }
      store.enabled(false);
      ScheduleManager.plan(context, false);
      context.getSharedPreferences("campus", Context.MODE_PRIVATE).edit().clear().commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      Field f = MainActivity.class.getDeclaredField("ui");
      f.setAccessible(true);
      ui = (AppUi) f.get(activity);
      ok(text(AppUi.START).equals("07:00"), "Default start 07:00");
      ok(text(AppUi.END).equals("07:05"), "Default end 07:05");
      ok(
          activity.findViewById(AppUi.LOGIN).getHeight()
              >= Math.round(48 * context.getResources().getDisplayMetrics().density),
          "Primary action meets 48 dp touch target");
      ok(
          context.getDrawable(activity.getApplicationInfo().icon)
              instanceof android.graphics.drawable.AdaptiveIconDrawable,
          "Launcher icon supports adaptive masks");
      ok(text(AppUi.STATUS_TITLE).contains("完成设置"), "First launch explains setup outcome");
      ok(text(AppUi.TASK_STATUS).contains("待机"), "Task state is explicitly separate");
      shot(context, "material-home.png");
      runOnMainSync(() -> ui.route(1));
      ok(activity.findViewById(AppUi.SAVE)==null,"Explicit save button removed");
      ok(activity.findViewById(AppUi.SAVE_TEST)==null,"Save-and-test button removed");
      runOnMainSync(()->{
        ((EditText)activity.findViewById(AppUi.USERNAME)).setText("fixture-student");ui.flushAutoSave();
      });
      ok(!store.credentialsReady(),"Incomplete account does not overwrite credentials");
      runOnMainSync(()->{
        ((EditText)activity.findViewById(AppUi.PASSWORD)).setText("fixture-secret-42");ui.flushAutoSave();
      });
      shot(context,"material-settings.png");
      ok(store.credentialsReady(), "Native account form saves encrypted credentials");
      ok(
          store.credentials().getString("username").equals("fixture-student"),
          "Keystore account round trip");
      ok(
          store.credentials().getString("password").equals("fixture-secret-42"),
          "Keystore password round trip");
      String raw =
          context.getSharedPreferences("campus", Context.MODE_PRIVATE).getString("credentials", "");
      ok(
          !raw.isEmpty() && !raw.contains("fixture-student") && !raw.contains("fixture-secret-42"),
          "Credentials not plaintext in SharedPreferences");
      ok(text(AppUi.PASSWORD).equals("fixture-secret-42"), "Autosave preserves active form input");
      runOnMainSync(
          () -> {
            ui.route(1);
            ((EditText) activity.findViewById(AppUi.USERNAME)).setText("unsaved-fixture");
            ui.requestSnapshot();
          });
      ok(
          text(AppUi.USERNAME).equals("unsaved-fixture"),
          "Live status refresh preserves an unsaved form edit");
      runOnMainSync(
          () -> {
            ui.resetForm();
            ui.requestSnapshot();
          });

      ok(
          !store.config().json().has("username") && !store.config().json().has("password"),
          "Profile export excludes account");
      store.state("connected", "fixture success");
      store.checked(true);
      Config prepared = store.config();
      prepared.bound = true; prepared.gateway = "10.0.2.2"; prepared.subnet = "10.0.2.0/24";
      store.saveConfig(prepared);
      runOnMainSync(() -> { ui.requestSnapshot(); ui.route(0); });
      ok(text(AppUi.STATUS_TITLE).equals("上次联网验证成功"), "Success title does not claim realtime online");
      ok(text(AppUi.TASK_STATUS).contains("待机"), "Successful verification is not a running task");
      shot(context, "verified-home.png");
      store.state("rejected", "请核对账号与服务类型");
      runOnMainSync(() -> { ui.requestSnapshot(); ui.route(0); });
      ok(text(AppUi.STATUS_TITLE).equals("认证未通过"), "Rejected login has actionable state");
      shot(context, "rejected-home.png");
      Config c = Config.from(store.config().json().put("interval", 45));
      store.saveConfig(c);
      runOnMainSync(
          () -> {
            ui.resetForm();
            ui.requestSnapshot();
          });
      String[] selected = new String[1];
      runOnMainSync(
          () ->
              selected[0] =
                  String.valueOf(
                      ((Spinner) activity.findViewById(AppUi.INTERVAL)).getSelectedItem()));
      ok(selected[0].contains("45"), "Imported interval survives native form");
      runOnMainSync(() -> ui.route(0));
      shot(context, "native-home.png");
      runOnMainSync(() -> ui.route(1));
      shot(context, "native-settings.png");
      List<NetworkController.Wifi> networks = NetworkController.available(context);
      if (!networks.isEmpty()) {
        NetworkController.Wifi wifi = networks.get(0);
        c.gateway = wifi.gateway;
        c.subnet = wifi.subnet;
        c.bound = true;
        store.saveConfig(c);
        ok(
            NetworkController.select(context, c).network.equals(wifi.network),
            "Profile chooses actual Wi-Fi Network");
        PortalClient.Response r =
            NetworkController.transport(
                    wifi, System.currentTimeMillis() + 15000, new AtomicBoolean(false))
                .request("http://10.0.2.2:18764/fixture", null, 9000);
        ok(
            r.status == 200 && r.body.equals("CAMPUS_TEST_WIFI"),
            "Wi-Fi-bound native HTTP reaches local server");
      } else {
        skipped = "Wi-Fi transport skipped: emulator exposed no Wi-Fi. ";
        c.gateway = "10.0.2.2";
        c.subnet = "10.0.2.0/24";
        c.bound = true;
      }
      Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
      c.start = (clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE) + 120) % 1440;
      c.end = (c.start + 10) % 1440;
      store.saveConfig(c);
      store.enabled(true);
      ok(ScheduleManager.allowed(context), "Exact alarm permission is allowed");
      long next = ScheduleManager.plan(context, false);
      ok(
          next > System.currentTimeMillis() && store.planned() == next,
          "AlarmManager plan persists actual timestamp");
      new ScheduleReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
      ok(
          store.planned() > System.currentTimeMillis() && !WorkService.isRunning(),
          "Boot event restores plan without direct dataSync start");
      store.enabled(false);
      ScheduleManager.plan(context, false);
      ok(store.planned() == -1, "Disabled plan cancels alarm");
      store.clearLogs();
      runOnMainSync(
          () -> {
            ui.requestSnapshot();
            ui.route(0);
            activity.findViewById(AppUi.DIAGNOSE).performClick();
          });
      long start = System.currentTimeMillis() + 8000;
      while (!WorkService.isRunning()
          && !store.exportLogs().contains("模式=diagnose")
          && System.currentTimeMillis() < start) Thread.sleep(100);
      ok(
          WorkService.isRunning() || store.exportLogs().contains("模式=diagnose"),
          "Native user action starts foreground service");
      WorkService.cancel(context);
      long finish = System.currentTimeMillis() + 20000;
      while (WorkService.isRunning() && System.currentTimeMillis() < finish) Thread.sleep(100);
      ok(!WorkService.isRunning(), "Foreground task stops after cancellation");
      ok(
          !store.exportLogs().contains("fixture-secret-42")
              && !store.exportLogs().contains("fixture-student"),
          "Logs omit account and password");
      android.content.pm.PackageInfo info =
          context
              .getPackageManager()
              .getPackageInfo(
                  context.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
      String requested = Arrays.toString(info.requestedPermissions);
      ok(
          !requested.contains("LOCATION") && !requested.contains("EXTERNAL_STORAGE"),
          "No location or broad storage permission");
      store.clearCredentials();
      store.saveConfig(new Config());
      store.clearLogs();
      store.state("idle", "绑定宿舍网络并填写账号，即可测试登录");
      ScheduleManager.plan(context, false);
      runOnMainSync(
          () -> {
            ui.requestSnapshot();
            ui.route(0);
            activity.onBackPressed();
          });
      ok(activity.isFinishing() || activity.isDestroyed(), "Home back finishes activity");
      report.putString(
          "stream",
          "PASS: "
              + checks
              + " Android native UI, Keystore, network, alarm and service checks. "
              + skipped
              + "No real campus credentials used.\n");
      finish(Activity.RESULT_OK, report);
    } catch (Throwable e) {
      if (ui != null) shot(context, "native-smoke.png");
      store.enabled(false);
      ScheduleManager.plan(context, false);
      WorkService.cancel(context);
      report.putString(
          "stream",
          "FAIL after "
              + checks
              + " checks: "
              + e
              + "\n"
              + android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, report);
    }
  }
}
