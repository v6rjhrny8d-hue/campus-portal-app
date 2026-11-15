package org.campusportal.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline, task-oriented help. Entry actions return to the existing settings and tests. */
final class AppGuide {
  static Dialog show(Activity a, Runnable network, Runnable account, Runnable plan,
      Runnable night, Runnable test) {
    MaterialStyle m = new MaterialStyle(a);
    YiduDialog dialog = new YiduDialog(a);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackground(m.cardSurface(24));
    root.setClipToOutline(true);
    root.setPadding(m.dp(20), m.dp(4), m.dp(20), m.dp(12));
    root.addView(YiduSheets.handle(a,m,root,dialog),new LinearLayout.LayoutParams(-1,m.dp(36)));
    TextView title = text(a, "使用指南", 23, m.ink, true);
    root.addView(title);
    TextView subtitle = text(a, "跟着步骤设置，平时让它自动完成。", 14, m.muted, false);
    subtitle.setPadding(0, m.dp(6), 0, m.dp(12));
    root.addView(subtitle);
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(false);
    YiduSheets.cleanScroll(scroll);
    LinearLayout content = new LinearLayout(a);
    content.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    section(a, m, content, "1  连接并绑定宿舍 Wi-Fi",
        "先连接宿舍 Wi-Fi，在设置中点击“绑定当前 Wi-Fi”。移动数据可以保持开启。",
        "去绑定网络", () -> { dialog.dismiss(); network.run(); });
    section(a, m, content, "2  保存账号并测试",
        "填写校园网账号、密码和服务类型，停止输入后自动保存，再回首页点击“立即登录”。已保存的密码留空会保留。",
        "去填写账号", () -> { dialog.dismiss(); account.run(); });
    section(a, m, content, "3  设置自动计划",
        "设置开始和结束时间，确认后自动保存，再到首页开启自动计划。时间以北京时间为准。早晨若未等到宿舍 Wi-Fi，时段结束后可在当天中午 12 点前补登一次。已联网会直接结束。",
        "去设置时间", () -> { dialog.dismiss(); plan.run(); });
    section(a, m, content, "4  检查夜间运行",
        NightPowerGuide.SUMMARY + "\n\n在系统设置中搜索“夜间休眠省电”，或查看电池设置中的“更多电池功能”；不同机型入口可能不同。",
        "查看运行准备", () -> { dialog.dismiss(); night.run(); });
    section(a, m, content, "5  测试熄屏运行",
        "安排两分钟后自测，选择仅检测或使用已保存账号登录，然后熄屏等待。短时成功不代表隔夜一定成功。",
        "开始后台自测", () -> { dialog.dismiss(); test.run(); });
    section(a, m, content, "常见问题",
        "手动能用、早晨未执行：可能需要关闭系统“夜间休眠省电”，并允许后台自启动，再查看记录。\n\n"
        + "周末没有断网：检测发现已经联网，就不会重复提交账号。\n\n"
        + "首页显示的是上次检测结果和时间，不代表全天实时监测。",
        null, null);
    Button close = button(a, m, "关闭");
    YiduMotion.click(close,dialog::dismiss);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, m.dp(48));
    cp.topMargin = m.dp(8); root.addView(close, cp);
    dialog.panel(root);dialog.setContentView(root);
    int height = YiduSheets.availableHeight(a);
    YiduSheets.show(a,dialog,Math.max(m.dp(300), (int)(height * 0.82f)));
    return dialog;
  }

  private static void section(Activity a, MaterialStyle m, LinearLayout parent, String title,
      String body, String action, Runnable click) {
    LinearLayout card = new LinearLayout(a);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(m.shape(m.containerLow, 0, 18));
    card.setPadding(m.dp(16), m.dp(16), m.dp(16), m.dp(12));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = m.dp(10); parent.addView(card, p);
    card.addView(text(a, title, 16, m.ink, true));
    TextView copy = text(a, body, 14, m.muted, false);
    copy.setLineSpacing(m.dp(4), 1f); copy.setPadding(0, m.dp(8), 0, 0); card.addView(copy);
    if (click != null) {
      Button b = button(a, m, action);
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, m.dp(48));
      bp.topMargin = m.dp(8); card.addView(b, bp); YiduMotion.click(b,click);
    }
  }

  private static TextView text(Activity a, String s, int size, int color, boolean medium) {
    TextView t = new TextView(a); t.setText(s); t.setTextSize(size); t.setTextColor(color);
    t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); return t;
  }

  private static Button button(Activity a, MaterialStyle m, String label) {
    Button b = new Button(a); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
    b.setTextColor(m.primary); b.setMinHeight(m.dp(48)); b.setStateListAnimator(null);
    b.setBackground(m.ripple(m.primaryContainer, 0, 100, m.primary)); return b;
  }

}
