package org.campusportal.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import org.campusportal.app.core.Schedule;
import org.campusportal.app.core.HomeState;
import org.campusportal.app.core.LogHistory;
import org.json.*;

/** Native campus flows with opaque cards and a compact floating navigation capsule. */
public final class AppUi {
  public interface Actions {
    void send(String action, JSONObject values);
  }

  public static final int USERNAME = 2001,
      PASSWORD = 2002,
      SAVE = 2003,
      START = 2004,
      END = 2005,
      INTERVAL = 2006,
      LOGIN = 2007,
      AUTO = 2008,
      DIAGNOSE = 2009,
      SAVE_TEST = 2010,
      STATUS_TITLE = 2011,
      TASK_STATUS = 2012,
      ERROR_MESSAGE = 2013,
      AUTHOR_CONTACT = 2014,
      NIGHT_POWER_GUIDE = 2015;
  public static final String AUTHOR_EMAIL = "2042322026@qq.com";
  private final Activity a;
  private final Actions actions;
  private final MaterialStyle m;
  private final int ink, muted, blue, line;
  private final LinearLayout root;
  private final FrameLayout shell;
  private final GlassDock dock;
  private final ScrollView[] pages = new ScrollView[3];
  private final TextView headerTitle,
      heroTitle,
      heroDetail,
      heroBadge,
      checked,
      planTime,
      planDetail,
      nextRun,
      network,
      bound,
      passwordHint;
  private final ImageView heroIcon;
  private final ProgressBar progress;
  private final Button login, diagnose, startButton, endButton;
  private final EditText label, username, password, customService;
  private final ImageButton passwordVisibility;
  private final Map<EditText, View> fields = new HashMap<>();
  private final Spinner service, interval;
  private final Switch automatic;
  private final Button[] days = new Button[7], permissions = new Button[3];
  private final List<Integer> intervals = new ArrayList<>(Arrays.asList(30, 60, 120, 300));
  private final LinearLayout logs;
  private final TextView logSummary;
  private JSONObject snapshot;
  private boolean formReady, rendering;
  private int current = -1, start = 420, end = 425, dayMask = 127;
  private boolean keyboardVisible, dirty, disposed, saving, autoSaveBlocked;
  private int bottomSafeInset;
  private final Map<String, Boolean> logExpansion = new HashMap<>();
  private final Map<String, LogDayView> logDayViews = new HashMap<>();
  private final android.os.Handler autosave=new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable savePending=()->attemptAutoSave(false);
  private String logSignature = "unrendered";
  private final LinearLayout settingsBody;
  private LinearLayout scheduleCard, permissionCard, networkCard, accountCard;
  private final LinearLayout setupCard, feedbackPanel;
  private final TextView taskStatus, feedbackText, setupSummary;
  private final Button setupNetwork, setupAccount, setupTest, repair;
  private String repairAction = "";

  public AppUi(Activity activity, Actions sink) {
    a = activity;
    actions = sink;
    m = new MaterialStyle(a);
    ink = m.ink;
    muted = m.muted;
    blue = m.primary;
    line = m.outlineVariant;
    root = column();
    shell = new FrameLayout(a);
    shell.addView(root,new FrameLayout.LayoutParams(-1,-1));
    root.setBackgroundColor(m.surface);
    LinearLayout header = row();
    header.setPadding(dp(16), 0, dp(16), 0);
    header.addView(iconButton("info", "使用指南", this::showGuide));
    headerTitle = text("", 17, ink, true);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
    titleParams.leftMargin = dp(12);
    header.addView(headerTitle, titleParams);
    header.addView(iconButton("palette", "主题与交互", this::showAppearance));
    root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
    feedbackPanel = row();
    feedbackPanel.setPadding(dp(20), dp(8), dp(8), dp(8));
    feedbackPanel.setBackgroundColor(m.errorContainer);
    feedbackText = text("", 14, m.error, false);
    feedbackText.setId(ERROR_MESSAGE);
    feedbackText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    feedbackPanel.addView(feedbackText, new LinearLayout.LayoutParams(0, -2, 1));
    feedbackPanel.addView(iconButton("check", "收起错误提示", () -> feedbackPanel.setVisibility(View.GONE)));
    feedbackPanel.setVisibility(View.GONE);
    root.addView(feedbackPanel);
    FrameLayout space = new FrameLayout(a);
    root.addView(space, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int i = 0; i < 3; i++) {
      pages[i] = new ScrollView(a);
      pages[i].setFillViewport(true);
      YiduSheets.cleanScroll(pages[i]);
      pages[i].setClipToPadding(false);
      pages[i].setVerticalScrollBarEnabled(false);
      space.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
    }

    LinearLayout home = body(pages[0]);
    LinearLayout hero = card(home, m.containerHigh);
    hero.setBackground(m.hero());
    LinearLayout statusRow = row();
    FrameLayout statusDisc = disc(m.primaryContainer, 56);
    heroIcon = image("wifi", m.onPrimaryContainer);
    statusDisc.addView(heroIcon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
    statusRow.addView(statusDisc);
    Space flexible = new Space(a);
    statusRow.addView(flexible, new LinearLayout.LayoutParams(0, 1, 1));
    heroBadge = text("准备设置", 12, muted, true);
    heroBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
    heroBadge.setBackground(m.shape(m.containerLow, 0, 100));
    statusRow.addView(heroBadge);
    hero.addView(statusRow);
    heroTitle = text("先完成宿舍设置", 26, ink, false);
    heroTitle.setId(STATUS_TITLE);
    heroTitle.setPadding(0, dp(20), 0, dp(8));
    hero.addView(heroTitle);
    heroDetail = text("绑定宿舍 Wi-Fi 并保存账号，试试第一次自动登录。", 14, muted, false);
    heroDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    hero.addView(heroDetail);
    taskStatus = text("当前任务：待机", 13, muted, true);
    taskStatus.setId(TASK_STATUS);
    taskStatus.setPadding(0, dp(14), 0, 0);
    hero.addView(taskStatus);
    repair = action("查看处理方法", 2, "chevron", this::repair);
    repair.setVisibility(View.GONE);
    hero.addView(repair);
    progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setIndeterminateTintList(ColorStateList.valueOf(blue));
    LinearLayout.LayoutParams pg = new LinearLayout.LayoutParams(-1, dp(4));
    pg.topMargin = dp(16);
    hero.addView(progress, pg);
    progress.setVisibility(View.GONE);
    checked = text("尚无检测记录", 12, muted, false);
    checked.setPadding(0, dp(20), 0, 0);
    hero.addView(checked);
    LinearLayout actionsRow = row();
    login = action("立即登录", 0, "arrow", () -> run("login"));
    login.setId(LOGIN);
    diagnose = action("仅检测", 1, "refresh", () -> run("diagnose"));
    diagnose.setId(DIAGNOSE);
    two(actionsRow, login, diagnose);
    home.addView(actionsRow);

    setupCard = card(home, m.containerHigh);
    section(setupCard, "check", "首次使用 · 三步准备");
    setupSummary = hint("绑定宿舍网络、保存账号，再测试一次连接。");
    setupCard.addView(setupSummary);
    setupNetwork = action("1  绑定宿舍 Wi-Fi", 1, null, () -> openSettings(networkCard));
    setupAccount = action("2  保存校园网账号", 1, null, () -> openSettings(accountCard));
    setupTest = action("3  测试网络连接", 3, null, () -> run("login"));
    setupCard.addView(setupNetwork);
    setupCard.addView(setupAccount);
    setupCard.addView(setupTest);

    LinearLayout plan = card(home, m.containerLow);
    LinearLayout planHeading = row();
    ImageView clockIcon = image("clock", blue);
    planHeading.addView(clockIcon, sized(24, 24));
    TextView planTitle = text("自动计划", 16, ink, true);
    LinearLayout.LayoutParams ph = new LinearLayout.LayoutParams(0, -2, 1);
    ph.leftMargin = dp(12);
    planHeading.addView(planTitle, ph);
    automatic = new Switch(a);
    automatic.setId(AUTO);
    automatic.setContentDescription("自动计划");
    automatic.setShowText(false);
    automatic.setSplitTrack(false);
    automatic.setSwitchMinWidth(dp(52));
    automatic.setThumbDrawable(m.switchThumb());
    automatic.setTrackDrawable(m.switchTrack());
    automatic.setMinimumHeight(dp(48));
    automatic.setPadding(dp(8), dp(8), 0, dp(8));
    planHeading.addView(automatic);
    plan.addView(planHeading);
    automatic.setOnCheckedChangeListener(
        (v, on) -> {
          if (!rendering && snapshot != null) {
            if(on&&!flushAutoSave()){rendering=true;automatic.setChecked(false);rendering=false;return;}
            send("enabled",object("value",on));
          }
        });
    automatic.setOnClickListener(v -> YiduMotion.tick(v));
    YiduMotion.press(automatic);
    planTime = text("07:00 — 07:05", 32, ink, false);
    planTime.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    planTime.setPadding(0, dp(16), 0, dp(6));
    plan.addView(planTime);
    planDetail = text("每天 · 北京时间 · 每 60 秒", 14, muted, false);
    plan.addView(planDetail);
    plan.addView(hint("联网验证成功后结束本次任务；失败才重试到结束时间。"));
    nextRun = text("自动计划未开启", 12, muted, false);
    nextRun.setPadding(0, dp(18), 0, dp(8));
    plan.addView(nextRun);
    Button change =
        action(
            "修改计划",
            2,
            "chevron",
            () -> {
              route(1);
              scrollTo(scheduleCard);
            });
    plan.addView(change);

    LinearLayout nc = card(home, m.containerHigh);
    section(nc, "network", "宿舍网络");
    network = text("尚未绑定", 14, muted, false);
    network.setPadding(0, dp(12), 0, dp(12));
    nc.addView(network);
    divider(nc);
    nc.addView(
        listAction(
            "shield",
            "夜间运行检查",
            "检查准时启动与手机后台设置",
            () -> {
              route(1);
              scrollTo(permissionCard);
            }));
    nc.addView(listAction("wifi", "验证网页访问", "通过宿舍 Wi-Fi 检查外网", () -> run("web")));
    LinearLayout help = card(home, m.primaryContainer);
    help.setPadding(dp(16), dp(4), dp(16), dp(4));
    help.addView(listAction("info", "使用指南", "从第一次设置到熄屏测试", this::showGuide));
    settingsBody = body(pages[1]);
    LinearLayout c1 = card(settingsBody, m.containerHigh);
    networkCard = c1;
    section(c1, "wifi", "宿舍 Wi-Fi");
    c1.addView(hint("先连接宿舍 Wi-Fi，再绑定当前网络。"));
    bound = text("尚未绑定宿舍网络", 14, ink, false);
    bound.setPadding(0, dp(8), 0, dp(12));
    c1.addView(bound);
    LinearLayout br = row();
    two(
        br,
        action("绑定当前 Wi-Fi", 1, null, () -> send("bindWifi")),
        action("连接 Wi-Fi", 2, null, () -> send("wifiSettings")));
    c1.addView(br);
    label = field(c1, "网络备注", "例如：3 栋 401 宿舍", false);
    label.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});

    LinearLayout c2 = card(settingsBody, m.containerHigh);
    accountCard = c2;
    section(c2, "lock", "校园网账号");
    c2.addView(hint("填写自己的账号，并选择认证页上的服务类型。"));
    username = field(c2, "账号", "校园网账号", false);
    username.setId(USERNAME);
    username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
    password = field(c2, "密码", "校园网密码", true);
    password.setId(PASSWORD);
    password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
    FrameLayout passwordFrame = (FrameLayout) fields.get(password);
    password.setPadding(dp(16), dp(12), dp(56), dp(12));
    passwordVisibility = iconButton("eye", "显示密码", null);
    FrameLayout.LayoutParams eyeParams =
        new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
    eyeParams.rightMargin = dp(4);
    eyeParams.bottomMargin = dp(4);
    passwordFrame.addView(passwordVisibility, eyeParams);
    passwordVisibility.setOnClickListener(
        v -> {
          boolean hidden = password.getTransformationMethod() != null;
          password.setTransformationMethod(
              hidden ? null : PasswordTransformationMethod.getInstance());
          password.setSelection(password.length());
          passwordVisibility.setImageDrawable(m.icon(hidden ? "eye_off" : "eye", muted));
          passwordVisibility.setContentDescription(hidden ? "隐藏密码" : "显示密码");
        });
    c2.addView(caption("服务类型"));
    service = new Spinner(a, Spinner.MODE_DROPDOWN);
    service.setAdapter(adapter(Arrays.asList("中国移动", "中国电信", "校园网", "自定义")));
    dropdown(c2, service);
    customService = field(c2, "自定义服务名称", "与学校认证页保持一致", false);
    fields.get(customService).setVisibility(View.GONE);
    service.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> v) {}

          public void onItemSelected(AdapterView<?> v, View item, int p, long id) {
            fields.get(customService).setVisibility(p == 3 ? View.VISIBLE : View.GONE);
            queueAutoSave();
          }
        });
    passwordHint = hint("账号密码加密保存在本机");
    c2.addView(passwordHint);

    scheduleCard = card(settingsBody, m.containerHigh);
    section(scheduleCard, "clock", "检测计划");
    scheduleCard.addView(hint("在指定时段检测。早晨若未等到宿舍 Wi-Fi，时段结束后可在北京时间中午 12 点前连网补登一次。"));
    LinearLayout times = row();
    LinearLayout first = column(), last = column();
    first.addView(caption("开始时间"));
    last.addView(caption("结束时间"));
    startButton = timeButton("07:00", () -> pickTime(true));
    endButton = timeButton("07:05", () -> pickTime(false));
    startButton.setId(START);
    endButton.setId(END);
    first.addView(startButton);
    last.addView(endButton);
    LinearLayout.LayoutParams leftTime = new LinearLayout.LayoutParams(0, -2, 1);
    leftTime.rightMargin = dp(6);
    LinearLayout.LayoutParams rightTime = new LinearLayout.LayoutParams(0, -2, 1);
    rightTime.leftMargin = dp(6);
    times.addView(first, leftTime);
    times.addView(last, rightTime);
    scheduleCard.addView(times);
    scheduleCard.addView(caption("重复 · 北京时间"));
    HorizontalScrollView dayScroll = new HorizontalScrollView(a);
    dayScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout ds = row();
    String[] weekday = {"一", "二", "三", "四", "五", "六", "日"};
    for (int i = 0; i < 7; i++) {
      final int bit = i;
      days[i] =
          button(
              weekday[i],
              false,
              () -> {
                dayMask ^= 1 << bit;
                renderDays();
                queueAutoSave();
              });
      days[i].setPadding(0, 0, 0, 0);
      days[i].setTextSize(14);
      days[i].setContentDescription("星期" + weekday[i]);
      LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(dp(48), dp(48));
      d.setMargins(0, dp(4), i == 6 ? 0 : dp(6), dp(4));
      ds.addView(days[i], d);
    }
    dayScroll.addView(ds);
    scheduleCard.addView(dayScroll);
    scheduleCard.addView(caption("检测间隔"));
    interval = new Spinner(a, Spinner.MODE_DROPDOWN){
      @Override public boolean performClick(){
        String[] labels=new String[getAdapter().getCount()];
        for(int i=0;i<labels.length;i++)labels[i]=String.valueOf(getAdapter().getItem(i));
        new YiduSheets.Builder(a).setTitle("检测间隔").setCheckedItem(getSelectedItemPosition())
            .setItems(labels,(dialog,index)->{setSelection(index);queueAutoSave();}).setNegativeButton("取消",null).show();
        return true;
      }
    };
    interval.setId(INTERVAL);
    refreshIntervals();
    dropdown(scheduleCard, interval);
    scheduleCard.addView(action("快速填写 07:00—07:05", 2, "clock", () -> {
      start = 420; end = 425;
      startButton.setText("07:00"); endButton.setText("07:05");
      queueAutoSave();
    }));
    scheduleCard.addView(hint("北京时间 · 支持跨午夜，单次最长 2 小时。修改后自动保存。"));
    scheduleCard.addView(hint("成功结束后若再次掉线，可手动登录，或等待下次计划。"));

    permissionCard = card(settingsBody, m.containerHigh);
    section(permissionCard, "shield", "夜间运行检查");
    permissionCard.addView(hint("检查准时启动、后台自启动和电池策略。"));
    LinearLayout nightReminder = column();
    nightReminder.setPadding(dp(16), dp(12), dp(16), dp(12));
    nightReminder.setBackground(m.shape(m.primaryContainer, 0, 18));
    TextView nightTitle = text("重要：" + NightPowerGuide.TITLE, 15, m.onPrimaryContainer, true);
    nightReminder.addView(nightTitle);
    TextView nightDetail = text(NightPowerGuide.SUMMARY, 14, m.onPrimaryContainer, false);
    nightDetail.setPadding(0, dp(8), 0, dp(4));nightReminder.addView(nightDetail);
    Button nightHelp = action("查看关闭方法", 2, "chevron", () -> NightPowerGuide.show(a));
    nightHelp.setId(NIGHT_POWER_GUIDE);nightReminder.addView(nightHelp);
    permissionCard.addView(nightReminder);
    permissions[0] = permission(permissionCard, "准时启动", "定时检测需要系统授权", "alarm");
    permissions[1] = permission(permissionCard, "任务通知", "允许查看后台任务的运行提示", "notifications");
    permissions[2] = permission(permissionCard, "电池优化", "建议不限制，实际行为取决于系统", "battery");
    Button autostart = permission(permissionCard, "后台自启动", "请在应用详情中允许自启动（如有）", "appSettings");
    autostart.setText("去查看");
    permissionCard.addView(listAction("history", "后台联网自测", "熄屏后检测，或使用已保存账号登录", () -> run("backgroundTest")));
    permissionCard.addView(listAction("shield", "VPN 直连设置", "需要时检查应用直连权限", this::vpnHelp));

    LinearLayout share = card(settingsBody,m.containerHigh);
    section(share,"download","配置与数据");
    LinearLayout sr=row();two(sr,action("导出配置",1,"upload",()->{flushAutoSave();send("exportConfig");}),
        action("导入配置",1,"download",()->{flushAutoSave();send("importConfig");}));share.addView(sr);
    Button clear=action("清除本机账号密码",2,"delete",()->send("clearCredentials"));clear.setTextColor(m.error);share.addView(clear);
    TextView about = hint("校园网助手 1.5.8\n账号仅保存在本机 · 无广告");
    about.setGravity(Gravity.CENTER);
    about.setPadding(0, dp(16), 0, dp(12));
    settingsBody.addView(about);
    TextView contactTitle = text("联系作者", 12, muted, false);
    contactTitle.setGravity(Gravity.CENTER);
    settingsBody.addView(contactTitle);
    Button contact = action(AUTHOR_EMAIL + "  ·  复制", 2, null, this::copyAuthorEmail);
    contact.setId(AUTHOR_CONTACT);
    contact.setContentDescription("复制作者邮箱：" + AUTHOR_EMAIL);
    settingsBody.addView(contact);

    LinearLayout lb = body(pages[2]);
    LinearLayout logActions = row();
    two(
        logActions,
        action("导出日志", 1, "upload", () -> send("exportLogs")),
        action(
            "清空记录",
            2,
            "delete",
            () ->
                new YiduSheets.Builder(a)
                    .setTitle("清空连接记录？")
                    .setMessage("账号和检测计划会保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> send("clearLogs"))
                    .show()));
    lb.addView(logActions);
    LinearLayout resultCard = card(lb, m.containerHigh);
    section(resultCard, "history", "最近状态");
    logSummary = text("尚无检测记录", 14, muted, false);
    logSummary.setPadding(0, dp(10), 0, dp(4));
    resultCard.addView(logSummary);
    TextView retention = hint("按天展开或收起 · 保留最近30天，更早记录自动清理");
    lb.addView(retention);
    logs=column();lb.addView(logs);

    dock = new GlassDock(a, m, root, this::route);
    FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(-1,dp(68),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
    dockParams.setMargins(dp(20),0,dp(20),dp(12));
    shell.addView(dock,dockParams);
    route(0);
    renderDays();
    android.text.TextWatcher watcher=new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){queueAutoSave();}
      public void afterTextChanged(android.text.Editable e){}
    };
    for(EditText field:new EditText[]{label,username,password,customService}){
      field.addTextChangedListener(watcher);
      field.setOnFocusChangeListener((v,focused)->{if(!focused){autosave.removeCallbacks(savePending);attemptAutoSave(false);}});
    }
    interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(AdapterView<?> v){}
      public void onItemSelected(AdapterView<?> v,View child,int position,long id){queueAutoSave();}
    });
  }

  public View view() {
    return shell;
  }

  public void route(int tab) {
    if(current==1&&tab!=1)flushAutoSave();
    int previous=current;
    if(previous==tab)return;
    current=tab;headerTitle.setText("");
    int pageWidth=((View)pages[tab].getParent()).getWidth();
    boolean slide=previous>=0&&root.isAttachedToWindow()&&pageWidth>0&&YiduMotion.animations();
    float targetX=pages[tab].getTranslationX();boolean targetVisible=pages[tab].getVisibility()==View.VISIBLE;
    for(int i=0;i<pages.length;i++){
      pages[i].animate().cancel();pages[i].animate().withEndAction(null);
      pages[i].setAlpha(1);pages[i].setTranslationY(0);
      if(!slide||i!=previous&&i!=tab){pages[i].setVisibility(i==tab?View.VISIBLE:View.GONE);pages[i].setTranslationX(0);}
    }
    dock.select(tab,root.isAttachedToWindow());updatePageInsets();
    if(slide){
      int direction=tab>previous?1:-1;float width=pageWidth;
      View outgoing=pages[previous],incoming=pages[tab];
      outgoing.setVisibility(View.VISIBLE);incoming.setVisibility(View.VISIBLE);
      incoming.setTranslationX(targetVisible?targetX:direction*width);
      outgoing.animate().translationX(-direction*width).setDuration(280)
          .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
          .withEndAction(()->{if(current!=previous){outgoing.setVisibility(View.GONE);outgoing.setTranslationX(0);}}).start();
      incoming.animate().translationX(0).setDuration(280)
          .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f)).start();
    }
    android.view.inputmethod.InputMethodManager im =
        (android.view.inputmethod.InputMethodManager)
            a.getSystemService(Activity.INPUT_METHOD_SERVICE);
    im.hideSoftInputFromWindow(root.getWindowToken(), 0);
  }

  public boolean back() {
    if (current != 0) {
      route(0);
      return true;
    }
    return false;
  }

  public void requestSnapshot() {
    send("snapshot");
  }

  public void resetForm() {
    autosave.removeCallbacks(savePending);dirty=false;
    formReady = false;
  }

  /** In-memory only; keeps an unsaved form intact while applying a visual theme. */
  static final class Draft {
    boolean ready; int page, start, end, days, service, interval, scroll;
    String label, username, password, customService; boolean dirty; Map<String, Boolean> logExpansion;
  }
  Draft captureDraft() {
    Draft d=new Draft(); d.ready=formReady; d.page=Math.max(0,current);
    d.start=start;d.end=end;d.days=dayMask;d.service=service.getSelectedItemPosition();
    d.interval=interval.getSelectedItemPosition();d.scroll=pages[d.page].getScrollY();
    d.label=label.getText().toString();d.username=username.getText().toString();
    d.password=password.getText().toString();d.customService=customService.getText().toString();
    d.dirty=dirty;d.logExpansion=new HashMap<>(logExpansion);return d;
  }
  void restoreDraft(Draft d) {
    if(d==null)return;
    rendering=true;logExpansion.clear();if(d.logExpansion!=null)logExpansion.putAll(d.logExpansion);logSignature="unrendered";
    for (LogDayView day : logDayViews.values()) day.stopAnimation();
    logDayViews.clear();logs.removeAllViews();
    if(d.ready){
      label.setText(d.label);username.setText(d.username);password.setText(d.password);
      customService.setText(d.customService);
      if(d.service>=0&&d.service<service.getCount())service.setSelection(d.service);
      if(d.interval>=0&&d.interval<interval.getCount())interval.setSelection(d.interval);
      start=d.start;end=d.end;dayMask=d.days;startButton.setText(Schedule.time(start));endButton.setText(Schedule.time(end));
      renderDays();formReady=true;
    }
    rendering=false;dirty=d.dirty;
    if(snapshot!=null)renderLogs(snapshot.optJSONArray("logs"));
    route(Math.max(0,Math.min(2,d.page)));
    if(dirty)queueAutoSave();
    pages[current].post(()->pages[current].scrollTo(0,d.scroll));
  }

  public void setKeyboardVisible(boolean visible) {
    keyboardVisible=visible;
    dock.setVisibility(visible ? View.GONE : View.VISIBLE);
    updatePageInsets();
  }

  public void setBottomSafeInset(int inset) {
    bottomSafeInset=Math.max(0,inset);
    FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)dock.getLayoutParams();
    int margin=dp(12)+bottomSafeInset;
    if(p.bottomMargin!=margin){p.bottomMargin=margin;dock.setLayoutParams(p);}
    updatePageInsets();
  }

  private void updatePageInsets() {
    int bottom=(keyboardVisible?0:dp(88)+bottomSafeInset)+dp(8);
    for(ScrollView page:pages)if(page.getPaddingBottom()!=bottom)page.setPadding(0,0,0,bottom);
  }

  private void scrollTo(View v) {
    pages[1].post(() -> pages[1].smoothScrollTo(0, v.getTop()));
  }

  private int dp(float n) {
    return m.dp(n);
  }

  private LinearLayout.LayoutParams sized(int w, int h) {
    return new LinearLayout.LayoutParams(dp(w), dp(h));
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private LinearLayout body(ScrollView scroll) {
    FrameLayout holder = new FrameLayout(a);
    scroll.addView(holder, new ScrollView.LayoutParams(-1, -2));
    LinearLayout content =
        new LinearLayout(a) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int width = Math.min(View.MeasureSpec.getSize(widthSpec), dp(640));
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec);
          }
        };
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(12), dp(20), dp(16));
    holder.addView(
        content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    return content;
  }

  private GradientDrawable shape(int fill, int stroke, int radius) {
    return m.shape(fill, stroke, radius);
  }

  private TextView text(String value, float size, int color, boolean medium) {
    TextView t = new TextView(a);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    return t;
  }

  private TextView hint(String value) {
    TextView t = text(value, 14, muted, false);
    t.setPadding(0, dp(8), 0, dp(8));
    return t;
  }

  private TextView caption(String value) {
    TextView t = text(value, 12, muted, true);
    t.setPadding(0, dp(16), 0, dp(8));
    return t;
  }

  private LinearLayout card(LinearLayout parent, int color) {
    LinearLayout v = column();
    v.setPadding(dp(20), dp(20), dp(20), dp(20));
    v.setBackground(m.cardSurface(24));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, 0, 0, dp(16));
    parent.addView(v, p);
    return v;
  }

  private void section(LinearLayout parent, String icon, String title) {
    LinearLayout r = row();
    r.addView(image(icon, blue), sized(24, 24));
    TextView t = text(title, 16, ink, true);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(12);
    r.addView(t, p);
    parent.addView(r);
  }

  private ImageView image(String symbol, int color) {
    ImageView v = new ImageView(a);
    v.setImageDrawable(m.icon(symbol, color));
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return v;
  }

  private FrameLayout disc(int color, int size) {
    FrameLayout frame = new FrameLayout(a);
    frame.setBackground(m.shape(color, 0, 100));
    frame.setLayoutParams(sized(size, size));
    return frame;
  }

  private ImageButton iconButton(String symbol, String description, Runnable click) {
    ImageButton b = new ImageButton(a);
    b.setImageDrawable(m.icon(symbol, muted));
    b.setContentDescription(description);
    b.setPadding(dp(12), dp(12), dp(12), dp(12));
    b.setBackground(m.ripple(Color.TRANSPARENT, 0, 100, blue));
    b.setLayoutParams(sized(48, 48));
    if (click != null) YiduMotion.click(b,click);
    return b;
  }

  private Button button(String value, boolean primary, Runnable click) {
    return action(value, primary ? 0 : 1, null, click);
  }

  // 0 filled, 1 outlined, 2 text, 3 tonal. Each target is at least 48 dp high.
  private Button action(String value, int kind, String symbol, Runnable click) {
    int foreground = kind == 0 ? m.onPrimary : blue;
    Button b = new Button(a);
    b.setText(value);
    b.setTextSize(14);
    b.setTypeface(Typeface.create("sans-serif-medium", 0));
    b.setAllCaps(false);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(16), dp(12), dp(16), dp(12));
    b.setTextColor(m.textStates(foreground));
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setBackground(
        m.ripple(
            kind == 0 ? blue : kind == 1 || kind == 3 ? m.primaryContainer : Color.TRANSPARENT,
            0,
            100,
            foreground));
    setActionLabel(b, value, symbol, foreground);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(4), 0, dp(4));
    b.setLayoutParams(p);
    if (click != null) YiduMotion.click(b,click);
    return b;
  }

  private void setActionLabel(Button button, String label, String icon, int color) {
    button.setContentDescription(icon == null ? null : label);
    button.setCompoundDrawables(null, null, null, null);
    button.setText(icon == null ? label : m.iconLabel(label, icon, color));
  }

  private Button timeButton(String value, Runnable click) {
    Button b = action(value, 3, null, click);
    b.setTextSize(30);
    b.setMaxLines(1);
    b.setAutoSizeTextTypeUniformWithConfiguration(
        20, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    b.setTypeface(Typeface.create("sans-serif", 0));
    b.setTextColor(m.onPrimaryContainer);
    b.setBackground(m.ripple(m.primaryContainer, 0, 16, blue));
    b.setMinHeight(dp(76));
    b.setPadding(dp(8), dp(16), dp(8), dp(16));
    return b;
  }

  private void two(LinearLayout r, Button one, Button two) {
    if (a.getResources().getConfiguration().fontScale >= 1.3f
        || a.getResources().getConfiguration().screenWidthDp < 360) {
      r.setOrientation(LinearLayout.VERTICAL);
      for (Button button : new Button[] {one, two}) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(4), 0, dp(8));
        r.addView(button, p);
      }
      return;
    }
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(0, dp(4), dp(4), dp(12));
    r.addView(one, p);
    LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, -2, 1);
    q.setMargins(dp(4), dp(4), 0, dp(12));
    r.addView(two, q);
  }

  private EditText field(LinearLayout parent, String name, String placeholder, boolean secret) {
    FrameLayout frame = new FrameLayout(a);
    int inputHeight = dp(56 * Math.max(1f, a.getResources().getConfiguration().fontScale / 1.2f));
    LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, inputHeight + dp(12));
    frameParams.topMargin = dp(12);
    parent.addView(frame, frameParams);
    EditText e = new EditText(a);
    e.setId(View.generateViewId());
    e.setTextSize(16);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    e.setHint(placeholder);
    e.setSingleLine(true);
    e.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
    e.setPadding(dp(16), dp(12), dp(16), dp(12));
    e.setBackground(m.input());
    frame.addView(e, new FrameLayout.LayoutParams(-1, inputHeight, Gravity.BOTTOM));
    TextView caption = text(name, 12, muted, false);
    caption.setBackgroundColor(m.containerHigh);
    caption.setPadding(dp(4), 0, dp(4), 0);
    FrameLayout.LayoutParams labelParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    labelParams.leftMargin = dp(12);
    labelParams.topMargin = dp(5);
    frame.addView(caption, labelParams);
    e.setContentDescription(name);
    frame.post(() -> caption.setLabelFor(e.getId()));
    e.setOnFocusChangeListener((v, focused) -> caption.setTextColor(focused ? blue : muted));
    fields.put(e, frame);
    return e;
  }

  private void dropdown(LinearLayout parent, Spinner spinner) {
    FrameLayout frame = new FrameLayout(a);
    frame.setBackground(m.shape(m.containerHigh, m.outline, 4));
    spinner.setBackgroundColor(Color.TRANSPARENT);
    spinner.setPadding(dp(8), 0, dp(40), 0);
    spinner.setPopupBackgroundDrawable(m.shape(m.containerHigh, m.outlineVariant, 12));
    frame.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
    ImageView arrow = image("down", muted);
    FrameLayout.LayoutParams p =
        new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.CENTER_VERTICAL);
    p.rightMargin = dp(12);
    frame.addView(arrow, p);
    parent.addView(frame, new LinearLayout.LayoutParams(-1, dp(56)));
  }

  private ArrayAdapter<String> adapter(List<String> names) {
    return new ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, names) {
      public View getView(int position, View old, ViewGroup parent) {
        TextView t = text(getItem(position), 16, ink, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(12), dp(8), dp(12));
        return t;
      }

      public View getDropDownView(int position, View old, ViewGroup parent) {
        TextView t = text(getItem(position), 16, ink, false);
        t.setMinHeight(dp(48));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setBackgroundColor(m.containerHigh);
        return t;
      }
    };
  }

  private void divider(LinearLayout parent) {
    View v = new View(a);
    v.setBackgroundColor(line);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.setMargins(0, dp(4), 0, dp(4));
    parent.addView(v, p);
  }

  private LinearLayout listAction(String symbol, String title, String detail, Runnable click) {
    LinearLayout r = row();
    r.setMinimumHeight(dp(72));
    r.setPadding(0, dp(12), 0, dp(12));
    r.setBackground(m.ripple(Color.TRANSPARENT, 0, 12, blue));
    r.setFocusable(true);
    r.setClickable(true);
    r.setContentDescription(title + "，" + detail);
    YiduMotion.click(r,click);
    r.addView(image(symbol, muted), sized(24, 24));
    LinearLayout labels = column();
    labels.addView(text(title, 14, ink, true));
    TextView description = text(detail, 12, muted, false);
    description.setPadding(0, dp(4), 0, 0);
    labels.addView(description);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(dp(16), 0, dp(8), 0);
    r.addView(labels, p);
    r.addView(image("chevron", muted), sized(20, 20));
    return r;
  }

  private Button permission(LinearLayout parent, String title, String detail, String action) {
    LinearLayout r = row();
    r.setPadding(0, dp(8), 0, dp(8));
    String symbol =
        action.equals("alarm")
            ? "alarm"
            : action.equals("notifications")
                ? "bell"
                : action.equals("battery") ? "battery" : "settings";
    r.addView(image(symbol, muted), sized(24, 24));
    LinearLayout labels = column();
    labels.addView(text(title, 14, ink, true));
    TextView d = text(detail, 12, muted, false);
    d.setPadding(0, dp(4), 0, 0);
    labels.addView(d);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(16);
    p.rightMargin = dp(8);
    r.addView(labels, p);
    Button b = action("允许", 2, null, () -> send(action));
    b.setMinWidth(dp(64));
    r.addView(b, new LinearLayout.LayoutParams(-2, -2));
    parent.addView(r);
    return b;
  }

  private JSONObject object(String key, Object value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void send(String action) {
    send(action, new JSONObject());
  }

  private void send(String action, JSONObject values) {
    actions.send(action, values);
  }

  private void openSettings(View section) {
    route(1);
    if (section != null) scrollTo(section);
  }

  private void showError(String message) {
    feedbackText.setText(message);
    feedbackPanel.setVisibility(View.VISIBLE);
  }

  private void repair() {
    if (repairAction.equals("account")) openSettings(accountCard);
    else if (repairAction.equals("logs")) route(2);
    else if (repairAction.equals("wifi")) send("wifiSettings");
    else if (!repairAction.isEmpty()) run(repairAction);
  }

  private void toast(String message) {
    Toast.makeText(a, message, Toast.LENGTH_LONG).show();
  }

  private void run(String mode) {
    if (snapshot == null) return;
    boolean saved=flushAutoSave();
    if(!saved&&mode.equals("login"))return;
    JSONObject status = snapshot.optJSONObject("status");
    if (status.optBoolean("running")) {
      if (mode.equals("login")) send("stop");
      return;
    }
    if (!snapshot.optJSONObject("config").optBoolean("bound")
        || (mode.equals("login") && !snapshot.optBoolean("passwordSaved"))) {
      route(1);
      toast("请先绑定宿舍 Wi-Fi，并保存账号密码");
      return;
    }
    send(mode);
  }

  private void pickTime(boolean first) {
    TimeWheelDialog.show(a, start, end, first,
        true,
        (selectedStart, selectedEnd) -> {
          start = selectedStart; end = selectedEnd;
          startButton.setText(Schedule.time(start));
          endButton.setText(Schedule.time(end));
          queueAutoSave();
        });
  }

  private void showGuide() {
    AppGuide.show(a, () -> openSettings(networkCard), () -> openSettings(accountCard),
        () -> openSettings(scheduleCard), () -> openSettings(permissionCard),
        () -> run("backgroundTest"));
  }

  private void showAppearance() {
    AppearanceSheet.show(a,(palette,appearance,haptics)-> {
      try { send("appearance",new JSONObject().put("palette",palette).put("appearance",appearance).put("haptics",haptics)); }
      catch(Exception e){ showError("主题设置未保存，请重试"); }
    });
  }

  private void renderDays() {
    for (int i = 0; i < 7; i++) {
      boolean selected = (dayMask & (1 << i)) != 0;
      days[i].setSelected(selected);
      days[i].setTextColor(selected ? m.onPrimaryContainer : muted);
      days[i].setBackground(m.ripple(selected ? m.primaryContainer : m.containerLow, 0, 100, blue));
      days[i].setContentDescription(
          "星期"
              + new String[] {"一", "二", "三", "四", "五", "六", "日"}[i]
              + (selected ? "，已选中" : "，未选中"));
    }
  }

  private void refreshIntervals() {
    List<String> names = new ArrayList<>();
    for (int n : intervals) names.add("每 " + n + " 秒");
    interval.setAdapter(adapter(names));
  }

  private void queueAutoSave() {
    if(rendering||!formReady||disposed)return;
    autoSaveBlocked=false;dirty=true;autosave.removeCallbacks(savePending);autosave.postDelayed(savePending,900);
  }

  public boolean flushAutoSave() {
    autosave.removeCallbacks(savePending);autoSaveBlocked=false;
    return attemptAutoSave(true);
  }

  public void dispose() { disposed=true;autosave.removeCallbacksAndMessages(null);for(LogDayView day:logDayViews.values())day.stopAnimation();for(ScrollView page:pages){page.animate().cancel();page.animate().withEndAction(null);} }

  private boolean attemptAutoSave(boolean explicit) {
    if(snapshot==null||!formReady||disposed||rendering)return true;
    JSONObject state=snapshot.optJSONObject("status");
    if(state!=null&&state.optBoolean("running")){
      if(dirty&&explicit)toast("本次任务结束后自动保存修改");
      return !dirty;
    }
    try {
      JSONObject c=new JSONObject(snapshot.getJSONObject("config").toString());
      c.put("label",label.getText().toString().trim()).put("loginUrl","")
          .put("service",service.getSelectedItemPosition()==3?customService.getText().toString().trim():service.getSelectedItem().toString())
          .put("start",start).put("end",end).put("days",dayMask)
          .put("interval",intervals.get(interval.getSelectedItemPosition()));
      Config.from(c);
      String user=username.getText().toString().trim(),pass=password.getText().toString();
      boolean complete=(!user.isEmpty()&&!pass.isEmpty())
          ||(user.equals(snapshot.optString("username"))&&(snapshot.optBoolean("passwordSaved")||user.isEmpty())&&pass.isEmpty());
      if(!complete)passwordHint.setText("账号尚未填完整，暂未替换已保存的账号密码。");
      boolean changed=!c.toString().equals(snapshot.getJSONObject("config").toString())
          ||(!pass.isEmpty()&&complete)||(complete&&!user.equals(snapshot.optString("username")));
      if(changed){
        saving=true;
        send("save",new JSONObject().put("automatic",true).put("config",c)
            .put("username",complete?user:snapshot.optString("username"))
            .put("password",complete?pass:""));
      }else dirty=false;
      if(!complete&&explicit)toast("请填写完整账号和密码后再操作；原账号仍保留");
      return complete;
    }catch(Exception e){
      if(explicit)showError(e instanceof IllegalArgumentException?e.getMessage():"设置尚未保存，请检查输入");
      return false;
    }
  }

  private void copyAuthorEmail() {
    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
        a.getSystemService(Activity.CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("作者邮箱", AUTHOR_EMAIL));
      toast("邮箱已复制");
    }
  }

  private void renderLogs(JSONArray items) {
    if (items == null) items = new JSONArray();
    long now = System.currentTimeMillis();
    String signature = LogHistory.today(now) + "|" + items.toString();
    if (signature.equals(logSignature)) return;
    logSignature = signature;
    List<LogHistory.Day> groups = LogHistory.group(items, now);
    Set<String> keys = new HashSet<>();
    for (LogHistory.Day group : groups) keys.add(group.key);
    final int previousScroll = pages[2].getScrollY();
    View anchor = null;
    if (current == 2 && previousScroll > 0) {
      for (int i = 0; i < logs.getChildCount(); i++) {
        View child = logs.getChildAt(i);
        if (child.getBottom() + logs.getTop() >= previousScroll) { anchor = child; break; }
      }
    }
    final View previousAnchor = anchor;
    final int previousTop = anchor == null ? 0 : anchor.getTop();
    Iterator<Map.Entry<String, LogDayView>> old = logDayViews.entrySet().iterator();
    while (old.hasNext()) {
      Map.Entry<String, LogDayView> entry = old.next();
      if (!keys.contains(entry.getKey())) {
        entry.getValue().stopAnimation();logs.removeView(entry.getValue().frame);old.remove();
      }
    }
    logExpansion.keySet().retainAll(keys);
    if (groups.isEmpty()) {
      logs.removeAllViews();
      LinearLayout empty = card(logs, m.containerLow);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(dp(24), dp(40), dp(24), dp(40));
      FrameLayout disc = disc(m.primaryContainer, 72);
      disc.addView(image("history", m.onPrimaryContainer), new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
      empty.addView(disc);
      TextView title = text("还没有检测记录", 20, ink, false);
      title.setGravity(Gravity.CENTER);title.setPadding(0, dp(24), 0, dp(8));empty.addView(title);
      TextView detail = hint("完成一次检测后，结果会显示在这里。");
      detail.setGravity(Gravity.CENTER);empty.addView(detail);
      return;
    }
    if (logDayViews.isEmpty()) logs.removeAllViews();
    for (int i = 0; i < groups.size(); i++) {
      LogHistory.Day group = groups.get(i);
      LogDayView day = logDayViews.get(group.key);
      if (day == null) { day = new LogDayView(group);logDayViews.put(group.key, day); }
      day.update(group, now);
      if (logs.indexOfChild(day.frame) != i) { logs.removeView(day.frame);logs.addView(day.frame, i); }
    }
    if (previousAnchor != null) pages[2].post(() -> {
      if (!disposed && current == 2 && previousAnchor.getParent() == logs)
        pages[2].scrollTo(0, previousScroll + previousAnchor.getTop() - previousTop);
    });
  }

  private final class LogDayView {
    final LinearLayout frame, header, content;
    final TextView title;
    final ImageView arrow;
    final String key;
    final List<String> rendered = new ArrayList<>();
    LogHistory.Day group;
    boolean expanded;
    android.animation.ValueAnimator animation;

    LogDayView(LogHistory.Day day) {
      key = day.key;group = day;
      frame = card(logs, m.containerHigh);frame.setTag("log-day:" + key);
      frame.setPadding(dp(20), dp(8), dp(20), dp(8));
      header = row();header.setTag("log-header:" + key);header.setMinimumHeight(dp(52));
      header.setFocusable(true);header.setBackground(m.ripple(Color.TRANSPARENT, 0, 18, blue));
      title = text("", 15, ink, true);title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      title.setPadding(0, dp(10), dp(8), dp(10));
      header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      arrow = image("chevron", muted);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      header.addView(arrow, sized(20, 20));
      YiduMotion.click(header, () -> { logExpansion.put(key, !expanded);setExpanded(!expanded, true); });
      frame.addView(header);
      content = column();content.setTag("log-content:" + key);content.setVisibility(View.GONE);
      content.setClipChildren(true);frame.addView(content);
    }

    void update(LogHistory.Day day, long now) {
      group = day;title.setText(day.title(now) + "（" + day.records.size() + "条）");
      boolean next = logExpansion.containsKey(key) ? logExpansion.get(key) : key.equals(LogHistory.today(now).toString());
      if (next != expanded) setExpanded(next, false);
      else {
        if (expanded) renderRecords(now);
        describe();
      }
    }

    void describe() {
      header.setContentDescription(title.getText() + (expanded ? "，已展开，点击收起" : "，已收起，点击展开"));
      if (android.os.Build.VERSION.SDK_INT >= 30) header.setStateDescription(expanded ? "已展开" : "已收起");
    }

    void renderRecords(long now) {
      boolean prefix = rendered.size() <= group.records.size();
      for (int i = 0; prefix && i < rendered.size(); i++) prefix = rendered.get(i).equals(group.records.get(i).toString());
      if (!prefix) { content.removeAllViews();rendered.clear(); }
      for (int i = rendered.size(); i < group.records.size(); i++) {
        JSONObject record = group.records.get(i);
        if (i > 0) divider(content);
        LinearLayout entry = column();entry.setTag("log-entry");entry.setPadding(0, dp(12), 0, dp(12));
        entry.addView(text(LogHistory.timeLabel(record, now), 12, blue, true));
        TextView detail = text(record.optString("text"), 14, ink, false);
        detail.setPadding(0, dp(8), 0, 0);detail.setTextIsSelectable(true);entry.addView(detail);
        content.addView(entry);rendered.add(record.toString());
      }
    }

    void stopAnimation() {
      android.animation.ValueAnimator old = animation;animation = null;
      if (old != null) old.cancel();
      arrow.animate().cancel();arrow.setRotation(expanded ? 90 : 0);
      content.getLayoutParams().height = -2;content.setVisibility(expanded ? View.VISIBLE : View.GONE);
      content.requestLayout();
    }

    void setExpanded(boolean value, boolean animate) {
      int from = content.getVisibility() == View.GONE ? 0
          : content.getLayoutParams().height >= 0 ? content.getLayoutParams().height : content.getHeight();
      android.animation.ValueAnimator old = animation;animation = null;if (old != null) old.cancel();
      expanded = value;
      if (expanded) renderRecords(System.currentTimeMillis());
      describe();arrow.animate().cancel();
      int width = frame.getWidth() - frame.getPaddingLeft() - frame.getPaddingRight();
      if (!animate || !YiduMotion.animations() || !root.isAttachedToWindow() || width <= 0) { stopAnimation();return; }
      content.setVisibility(View.VISIBLE);
      content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      int target = expanded ? content.getMeasuredHeight() : 0;
      content.getLayoutParams().height = from;content.requestLayout();
      final android.animation.ValueAnimator next = android.animation.ValueAnimator.ofInt(from, target);
      animation = next;next.setDuration(220);next.setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f));
      next.addUpdateListener(v -> { content.getLayoutParams().height = (Integer) v.getAnimatedValue();content.requestLayout(); });
      next.addListener(new android.animation.AnimatorListenerAdapter() {
        @Override public void onAnimationEnd(android.animation.Animator animator) { if (animation == next) stopAnimation(); }
      });
      arrow.animate().rotation(expanded ? 90 : 0).setDuration(220).start();next.start();
    }
  }

  private void vpnHelp() {
    new YiduSheets.Builder(a)
        .setTitle("VPN 的设置方法")
        .setMessage(
            "在 VPN 客户端的应用访问控制中，将“校园网助手”排除在 VPN 之外。\n\n"
                + "包名：org.campusportal.app\n\n"
                + "之前只排除 Termux 或旧助手不适用于新应用。修改后重连 VPN。系统若禁止未使用 VPN 的连接，直连仍可能被阻止。")
        .setNegativeButton("关闭", null)
        .setPositiveButton("系统 VPN 设置", (d, w) -> send("vpnSettings"))
        .show();
  }

  private String phase(String name, boolean running) {
    switch (name) {
      case "connected":
        return "Wi-Fi 已验证联网";
      case "checking":
        return "正在检查网络";
      case "authenticating":
        return "正在认证";
      case "verifying":
        return running ? "正在确认联网" : "联网尚未确认";
      case "needs_login":
        return "等待校园网认证";
      case "rejected":
        return "请核对账号设置";
      case "limited":
        return "联网尚未确认";
      case "queue_expired":
        return "备用任务已过期";
      case "network_unavailable":
        return "联网检测未收到响应";
      case "failed":
        return "本次任务未完成";
      case "unsupported":
        return "需要适配认证协议";
      case "setup":
        return "还差一步设置";
      default:
        return "等待下一次连接";
    }
  }

  public void receive(String type, Object value) {
    try {
      if(type.equals("autoSaved")){dirty=false;saving=false;autoSaveBlocked=false;return;}
      if (type.equals("saved")) {
        dirty=false;autosave.removeCallbacks(savePending);
        formReady = false;
        return;
      }
      if (type.equals("startTesting")) { route(0); feedbackPanel.setVisibility(View.GONE); return; }
      if (type.equals("error")) {if(saving){saving=false;autoSaveBlocked=true;autosave.removeCallbacks(savePending);}showError(String.valueOf(value)); return; }
      if (type.equals("message")) { toast(String.valueOf(value)); return; }
      if (type.equals("wifiChoices")) {
        JSONArray choices = (JSONArray) value;
        String[] labels = new String[choices.length()];
        for (int i = 0; i < labels.length; i++) {
          JSONObject j = choices.getJSONObject(i);
          labels[i] = j.getString("gateway") + " · " + j.getString("address");
        }
        new YiduSheets.Builder(a)
            .setTitle("选择宿舍 Wi-Fi")
            .setItems(labels, (d, n) -> send("bindWifi", object("index", n)))
            .show();
        return;
      }
      if (!type.equals("snapshot")) return;
      snapshot = (JSONObject) value;
      rendering = true;
      JSONObject c = snapshot.getJSONObject("config"),
          s = snapshot.getJSONObject("status"),
          p = snapshot.getJSONObject("permissions");
      boolean running = s.optBoolean("running");
      HomeState homeState = HomeState.from(c, s, snapshot.optBoolean("passwordSaved"));
      heroTitle.setText(homeState.title);
      heroDetail.setText(homeState.detail);
      taskStatus.setText(homeState.task);
      checked.setText(s.optLong("lastCheck") > 0
          ? "最近检测：" + AppStore.format(s.getLong("lastCheck"), true) : "最近检测：尚无记录");
      logSummary.setText(homeState.title + "\n" + homeState.detail + "\n"
          + (s.optLong("lastCheck") > 0 ? "最近检测：" + AppStore.format(s.getLong("lastCheck"), true)
              : "最近检测：尚无记录"));
      int statusColor = homeState.historicalSuccess ? m.success : homeState.needsAttention ? m.error : blue;
      heroIcon.setImageDrawable(m.icon(homeState.historicalSuccess ? "check" : homeState.needsAttention ? "error" : "wifi", statusColor));
      heroBadge.setText(homeState.badge);
      heroBadge.setTextColor(muted);
      heroBadge.setBackground(m.shape(m.containerLow, 0, 100));
      progress.setVisibility(running ? View.VISIBLE : View.GONE);
      setActionLabel(login, homeState.primaryLabel, running ? "stop" : "arrow", m.onPrimary);
      repairAction = homeState.repairAction;
      repair.setVisibility(repairAction.isEmpty() || running ? View.GONE : View.VISIBLE);
      setActionLabel(repair, homeState.repairLabel, "chevron", blue);
      setupCard.setVisibility(homeState.showSetup ? View.VISIBLE : View.GONE);
      boolean hasNetwork = c.optBoolean("bound"), hasAccount = snapshot.optBoolean("passwordSaved");
      setupNetwork.setText(hasNetwork ? "1  已绑定宿舍网络 · 可修改" : "1  绑定宿舍 Wi-Fi");
      setupAccount.setText(hasAccount ? "2  已保存账号 · 可修改" : "2  保存校园网账号");
      setupTest.setEnabled(!running);
      setupSummary.setText(!hasNetwork ? "从绑定当前网络开始，移动数据可以保持开启。"
          : !hasAccount ? "网络已绑定。接着填写账号和认证页上的服务类型。"
          : "设置已保存。测试通过后，再开启自动计划。测试成功不代表隔夜验证完成。");
      diagnose.setEnabled(!running);
      automatic.setChecked(s.optBoolean("enabled"));
      planTime.setText(Schedule.time(c.getInt("start")) + " — " + Schedule.time(c.getInt("end")));
      planDetail.setText(
          (c.getInt("days") == 127 ? "每天" : "按所选星期")
              + " · 北京时间 · 失败后每 "
              + c.getInt("interval")
              + " 秒");
      nextRun.setText(
          !s.optBoolean("enabled")
              ? "自动计划未开启"
              : !p.optBoolean("alarm")
                  ? "待授权：请先允许准时启动"
                  : s.optLong("next") > 0
                      ? "下次 " + AppStore.format(s.getLong("next"), true)
                      : "请补全账号与网络设置");
      network.setText(
          c.getString("label")
              + "\n网关："
              + (c.optBoolean("bound") ? c.getString("gateway") : "待绑定")
);
      bound.setText(
          c.optBoolean("bound")
              ? "已绑定 · " + c.getString("gateway") + "\n网段 " + c.getString("subnet")
              : "尚未绑定宿舍网络");
      passwordHint.setText(
          snapshot.optBoolean("passwordSaved") ? "密码已加密保存。密码框留空会保留原密码。" : "账号密码加密保存在本机，不上传第三方服务器。");
      permissions[0].setText(p.optBoolean("alarm") ? "已允许" : "允许");
      permissions[1].setText(p.optBoolean("notifications") ? "已允许" : "允许");
      permissions[2].setText(p.optBoolean("battery") ? "已豁免" : "查看");
      if (!formReady) {
        label.setText(c.getString("label"));
        username.setText(snapshot.optString("username"));
        password.setText("");
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordVisibility.setImageDrawable(m.icon("eye", muted));
        passwordVisibility.setContentDescription("显示密码");
        password.setHint(snapshot.optBoolean("passwordSaved") ? "已保存；留空保留" : "填写校园网密码");
        String serviceValue = c.getString("service");
        int selected = Arrays.asList("中国移动", "中国电信", "校园网").indexOf(serviceValue);
        service.setSelection(selected < 0 ? 3 : selected);
        customService.setText(selected < 0 ? serviceValue : "");
        start = c.getInt("start");
        end = c.getInt("end");
        dayMask = c.getInt("days");
        startButton.setText(Schedule.time(start));
        endButton.setText(Schedule.time(end));
        renderDays();
        int n = c.getInt("interval");
        if (!intervals.contains(n)) {
          intervals.add(n);
          refreshIntervals();
        }
        interval.setSelection(intervals.indexOf(n));
        formReady = true;
      }
      renderLogs(snapshot.getJSONArray("logs"));
      if(dirty&&!running&&!autoSaveBlocked){autosave.removeCallbacks(savePending);autosave.postDelayed(savePending,900);}
      if(!c.optString("loginUrl").isEmpty()&&!autoSaveBlocked){dirty=true;autosave.removeCallbacks(savePending);autosave.postDelayed(savePending,900);}
    } catch (Exception e) {
      toast("读取界面状态未完成，请重新打开应用");
    } finally {
      rendering = false;
    }
  }

  static boolean importantLog(String text) {
    return (text.contains("任务 ") && (text.contains(" 开始；") || text.contains(" 结束；")))
        || text.contains("认证成功") || text.contains("认证未通过")
        || text.contains("网页访问验证成功") || text.contains("联网验证成功")
        || text.contains("未正常收尾") || text.contains("系统拒绝启动")
        || text.contains("全部请求无响应") || text.contains("任务异常")
        || text.startsWith("已开启自动计划") || text.startsWith("已关闭自动计划")
        || text.contains("延迟自测触发") || text.contains("延迟自测跳过");
  }
}
