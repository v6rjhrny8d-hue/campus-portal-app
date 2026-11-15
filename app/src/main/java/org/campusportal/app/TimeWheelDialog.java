package org.campusportal.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.campusportal.app.core.Schedule;

/** User-controlled draft: neither the form nor the saved schedule changes until confirmation. */
final class TimeWheelDialog {
  static final int HOURS = 2301, MINUTES = 2302, START = 2303, END = 2304,
      CONFIRM = 2305, CANCEL = 2306, ERROR = 2307,
      DRAG_HANDLE = 2310;

  interface Listener { void selected(int start, int end); }

  static Dialog show(Activity activity, int initialStart, int initialEnd, boolean first,
      boolean haptics, Listener listener) {
    MaterialStyle m = new MaterialStyle(activity);
    YiduDialog dialog = new YiduDialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout sheet = new LinearLayout(activity);
    sheet.setOrientation(LinearLayout.VERTICAL);
    sheet.setPadding(m.dp(20), m.dp(4), m.dp(20), m.dp(16));

    FrameLayout handleTarget=YiduSheets.handle(activity,m,sheet,dialog);
    handleTarget.setId(DRAG_HANDLE);sheet.addView(handleTarget,new LinearLayout.LayoutParams(-1,m.dp(32)));
    TextView title = label(activity, "自动检测时段", 22, m.ink, true);
    sheet.addView(title);
    TextView subtitle = label(activity, "北京时间 · 滑动数字调整", 14, m.muted, false);
    subtitle.setPadding(0, m.dp(6), 0, m.dp(12));
    sheet.addView(subtitle);

    LinearLayout segments = horizontal(activity);
    Button start = button(activity, m, "", false), end = button(activity, m, "", false);
    start.setId(START); end.setId(END);
    LinearLayout.LayoutParams segmentOne = new LinearLayout.LayoutParams(0, -2, 1);
    segmentOne.rightMargin = m.dp(4);
    LinearLayout.LayoutParams segmentTwo = new LinearLayout.LayoutParams(0, -2, 1);
    segmentTwo.leftMargin = m.dp(4);
    segments.addView(start, segmentOne); segments.addView(end, segmentTwo);
    sheet.addView(segments);

    float scale = Math.max(1f, Math.min(1.5f, activity.getResources().getConfiguration().fontScale));
    FrameLayout wheelPanel = new FrameLayout(activity);
    View band = new View(activity);
    band.setBackground(m.shape(m.primaryContainer, 0, 16));
    FrameLayout.LayoutParams bandParams = new FrameLayout.LayoutParams(-1,m.dp(48*scale),Gravity.CENTER);
    bandParams.leftMargin=m.dp(8);bandParams.rightMargin=m.dp(8);wheelPanel.addView(band,bandParams);
    LinearLayout wheels = horizontal(activity);
    AlarmWheel hours = new AlarmWheel(activity,m,23,"小时"), minutes = new AlarmWheel(activity,m,59,"分钟");
    hours.setId(HOURS); minutes.setId(MINUTES);
    wheels.addView(hours,new LinearLayout.LayoutParams(0,-1,1));
    TextView colon=label(activity,":",28,m.primary,true);colon.setGravity(Gravity.CENTER);
    colon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    wheels.addView(colon,new LinearLayout.LayoutParams(m.dp(20),-1));
    wheels.addView(minutes,new LinearLayout.LayoutParams(0,-1,1));
    wheelPanel.addView(wheels,new FrameLayout.LayoutParams(-1,-1));
    sheet.addView(wheelPanel,new LinearLayout.LayoutParams(-1,m.dp(240*scale)));
    TextView preview = label(activity, "", 14, m.muted, false);
    preview.setGravity(Gravity.CENTER);
    preview.setPadding(0, m.dp(8), 0, m.dp(6));
    sheet.addView(preview);
    TextView error = label(activity, "", 13, m.error, false);
    error.setId(ERROR); error.setVisibility(View.GONE);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    sheet.addView(error);
    TextView saveHint = label(activity, "确认后自动保存，取消不修改。", 12, m.muted, false);
    saveHint.setPadding(0, m.dp(4), 0, m.dp(12));
    sheet.addView(saveHint);
    LinearLayout actions = horizontal(activity);
    Button cancel = button(activity, m, "取消", false), confirm = button(activity, m, "确定时间", true);
    cancel.setId(CANCEL); confirm.setId(CONFIRM);
    LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1);
    a.rightMargin = m.dp(4);
    LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1);
    b.leftMargin = m.dp(4);
    actions.addView(cancel, a); actions.addView(confirm, b); sheet.addView(actions);

    int[] draft = {initialStart, initialEnd}, active = {first ? 0 : 1};
    boolean[] changing = {false};
    Runnable refreshLabels = () -> {
      start.setText("开始\n" + Schedule.time(draft[0]));
      end.setText("结束\n" + Schedule.time(draft[1]));
      styleSegment(start, m, active[0] == 0);
      styleSegment(end, m, active[0] == 1);
      String next = draft[1] < draft[0] ? "（结束为次日）" : "";
      preview.setText(Schedule.time(draft[0]) + " — " + Schedule.time(draft[1]) + next);
      error.setVisibility(View.GONE);
    };
    Runnable loadWheel = () -> {
      changing[0] = true;
      hours.setValue(draft[active[0]] / 60);
      minutes.setValue(draft[active[0]] % 60);
      changing[0] = false;
      refreshLabels.run();
    };
    YiduMotion.click(start, () -> { active[0] = 0; loadWheel.run(); });
    YiduMotion.click(end, () -> { active[0] = 1; loadWheel.run(); });
    AlarmWheel.Changed changed = (picker, oldValue, newValue) -> {
      if (changing[0]) return;
      draft[active[0]] = hours.getValue() * 60 + minutes.getValue();
      refreshLabels.run();
      if (haptics) YiduMotion.tick(picker);
    };
    hours.setOnValueChangedListener(changed);
    minutes.setOnValueChangedListener(changed);
    YiduMotion.click(cancel, dialog::dismiss);
    boolean[] submitted={false};
    YiduMotion.click(confirm, () -> {
      if(submitted[0])return;
      try {
        new Schedule(draft[0], draft[1], 127);
        submitted[0]=true;
        hours.setValue(hours.getValue());minutes.setValue(minutes.getValue());
        listener.selected(draft[0], draft[1]);
        dialog.dismiss();
      } catch (IllegalArgumentException e) {
        error.setText(e.getMessage());
        error.setVisibility(View.VISIBLE);
      }
    });
    loadWheel.run();
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    YiduSheets.cleanScroll(scroll);
    scroll.setBackground(m.cardSurface(24));scroll.setClipToOutline(true);
    scroll.addView(sheet);
    dialog.panel(scroll);dialog.setContentView(scroll);
    sheet.measure(View.MeasureSpec.makeMeasureSpec(Math.min(m.dp(560),activity.getResources().getDisplayMetrics().widthPixels-m.dp(20)),View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
    YiduSheets.show(activity,dialog,Math.min(sheet.getMeasuredHeight(),(int)(YiduSheets.availableHeight(activity)*.88f)));
    return dialog;
  }

  private static LinearLayout horizontal(Activity a) {
    LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL);
    row.setOrientation(LinearLayout.HORIZONTAL); return row;
  }

  private static TextView label(Activity a, String s, int sp, int color, boolean medium) {
    TextView v = new TextView(a); v.setText(s); v.setTextSize(sp); v.setTextColor(color);
    v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", 0));
    return v;
  }

  private static Button button(Activity a, MaterialStyle m, String s, boolean primary) {
    Button b = new Button(a); b.setText(s); b.setAllCaps(false); b.setTextSize(14);
    b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(m.dp(48)); b.setMinimumHeight(m.dp(48));
    b.setPadding(m.dp(8), m.dp(10), m.dp(8), m.dp(10)); b.setStateListAnimator(null);
    b.setTextColor(primary ? m.onPrimary : m.primary);
    b.setBackground(m.ripple(primary ? m.primary : m.containerLow, 0, 100, m.primary));
    return b;
  }

  private static void styleSegment(Button b, MaterialStyle m, boolean selected) {
    b.setTextColor(selected ? m.onPrimaryContainer : m.muted);
    b.setBackground(m.ripple(selected ? m.primaryContainer : m.containerLow, selected ? m.primary : 0, 16, m.primary));
    b.setSelected(selected);
    b.setContentDescription(b.getText() + (selected ? "，当前选择" : "，点击选择"));
  }
}
