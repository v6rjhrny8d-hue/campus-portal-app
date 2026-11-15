package org.campusportal.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.provider.Settings;

/** Manufacturer setting remains user-controlled; exemption is a different switch. */
final class NightPowerGuide {
  static final String TITLE = "可能需要关闭夜间休眠省电";
  static final String SUMMARY = "若手动登录正常、隔夜定时却失败，请检查并尝试关闭系统“夜间休眠省电”。"
      + "电池优化显示“已豁免”，不代表这个开关已关闭。";
  static final String STEPS = "在系统设置中搜索“夜间休眠省电”，或在电池设置中的“更多电池功能”查找。"
      + "不同机型的名称和位置可能不同。\n\n"
      + "请手动确认该开关是否关闭，并允许本应用后台自启动。应用无法可靠读取或代为关闭厂商的夜间省电开关。\n\n"
      + "设置后安排熄屏自测，并在第二天检查定时记录；短时成功不能代替隔夜验证。";

  static Dialog show(Activity activity) {
    return new YiduSheets.Builder(activity).setTitle(TITLE).setMessage(SUMMARY + "\n\n" + STEPS)
        .setPositiveButton("打开系统设置", (dialog, which) -> {
          try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
          catch (RuntimeException unavailable) {
            android.widget.Toast.makeText(activity, "请手动打开系统设置，搜索夜间休眠省电", android.widget.Toast.LENGTH_LONG).show();
          }
        }).setNegativeButton("关闭", null).show();
  }
}
