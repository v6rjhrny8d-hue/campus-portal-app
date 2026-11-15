package org.campusportal.app.core;

import org.json.JSONObject;

/** UI projection: task activity, historical network results and schedule stay distinct. */
public final class HomeState {
  public final String title, detail, badge, task, primaryLabel, repairLabel, repairAction;
  public final boolean showSetup, historicalSuccess, needsAttention;

  private HomeState(String title, String detail, String badge, String task,
      String primaryLabel, String repairLabel, String repairAction, boolean showSetup,
      boolean historicalSuccess, boolean needsAttention) {
    this.title = title; this.detail = detail; this.badge = badge; this.task = task;
    this.primaryLabel = primaryLabel; this.repairLabel = repairLabel;
    this.repairAction = repairAction; this.showSetup = showSetup;
    this.historicalSuccess = historicalSuccess; this.needsAttention = needsAttention;
  }

  public static HomeState from(JSONObject config, JSONObject status, boolean passwordSaved) {
    boolean running = status.optBoolean("running"), ready = status.optBoolean("ready");
    String phase = status.optString("phase"), detail = status.optString("detail");
    boolean success = ready && !status.optBoolean("credentialUnreadable") && !running && phase.equals("connected");
    boolean failed = !running && (phase.equals("failed") || phase.equals("rejected")
        || phase.equals("unsupported") || phase.equals("limited") || phase.equals("verifying")
        || phase.equals("queue_expired") || phase.equals("network_unavailable") || phase.equals("interrupted") || phase.equals("waiting"));
    boolean showSetup = !ready || status.optLong("lastVerified") <= 0;
    String title, repair = "", action = "";
    if (status.optBoolean("credentialUnreadable")) {
      title = "请重新保存账号";
      detail = "本机加密凭据暂不可读取，请在设置中重新填写账号和密码。";
      repair = "填写账号"; action = "account";
    } else if (!ready) {
      title = "完成设置，测试连接";
      detail = !config.optBoolean("bound") ? "先连接宿舍 Wi-Fi，再绑定当前网络。"
          : !passwordSaved ? "宿舍网络已绑定，请保存自己的校园网账号。" : "请补全校园网账号设置。";
    } else if (status.optBoolean("reconnectPending")) {
      title = "等待宿舍 Wi-Fi 补登";
      detail = "定时时段结束后，若在北京时间中午 12 点前连回宿舍 Wi-Fi，将检查一次是否需要登录。";
    } else if (running) {
      title = status.optBoolean("recoveryPending") ? "等待系统继续检测"
          : phase.equals("authenticating") ? "正在提交认证"
          : phase.equals("verifying") ? "正在验证网页访问" : "正在检查宿舍 Wi-Fi";
    } else if (success) {
      title = "上次联网验证成功";
      detail = "最近一次检查已通过宿舍 Wi-Fi 访问网页。当前未持续监测，结果以检查时间为准。";
    } else if (phase.equals("queue_expired")) {
      title = "备用任务已过期"; repair = "查看连接记录"; action = "logs";
    } else if (phase.equals("rejected")) {
      title = "认证未通过"; repair = "检查账号与服务"; action = "account";
    } else if (phase.equals("needs_login")) {
      title = "检测到校园网认证页"; repair = "立即登录"; action = "login";
    } else if (phase.equals("network_unavailable")) {
      title = "联网检测未收到响应"; repair = "查看连接记录"; action = "logs";
    } else if (phase.equals("verifying") || phase.equals("limited")) {
      title = "网页访问尚未验证"; repair = "重新检测"; action = "diagnose";
    } else if (phase.equals("unsupported")) {
      title = "当前认证方式需适配"; repair = "查看连接记录"; action = "logs";
    } else if (phase.equals("queue_expired") || phase.equals("network_unavailable") || phase.equals("interrupted") || phase.equals("waiting")) {
      title = "后台任务未完成"; repair = "查看中断原因"; action = "logs";
      if (phase.equals("waiting")) detail = "备用任务已超出本次检测时段，请查看记录；可手动登录。";
    } else if (phase.equals("stopped")) {
      title = "本次任务已停止";
    } else if (phase.equals("failed")) {
      title = "本次连接未完成";
      if (detail.contains("等待已绑定") || detail.contains("未找到已绑定")) {
        repair = "连接宿舍 Wi-Fi"; action = "wifi";
      } else { repair = "查看原因与记录"; action = "logs"; }
    } else {
      title = "准备好，等待连接";
      detail = "可立即登录测试，或开启自动计划，等待系统在设定时间唤醒。";
    }
    String task = running ? "当前任务：正在运行，可随时停止"
        : status.optBoolean("enabled") ? "当前任务：待机 · 自动计划已开启" : "当前任务：待机 · 自动计划已关闭";
    long end = status.optLong("windowEnd", -1);
    if (!running && status.optBoolean("enabled") && end > 0
        && status.optLong("completedWindowEnd", -2) == end)
      task = "本时段已完成 · 等待下次计划唤醒";
    if (status.optBoolean("reconnectPending")) task = "等待连网 · 可停止本次补登";
    return new HomeState(title, detail, running ? "任务进行中" : failed ? "需要处理" : ready ? "任务待机" : "尚未设置",
        task, running ? "停止本次任务" : !ready ? "开始设置" : "立即登录",
        repair, action, showSetup, success, failed);
  }
}
