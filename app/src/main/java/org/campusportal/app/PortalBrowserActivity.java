package org.campusportal.app;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import org.campusportal.app.core.PortalProtocol;
import org.json.JSONObject;

/** A private browser process; it never receives or injects stored credentials. */
public final class PortalBrowserActivity extends Activity {
  private WebView web;
  private TextView status;
  private ConnectivityManager cm;
  private ConnectivityManager.NetworkCallback callback;
  private PortalProtocol protocol;
  private Config config;
  private Network selected;
  private boolean failed, released;
  private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable timeout = () -> {
    if (web != null && !failed) {
      failed = true;
      web.stopLoading();
      status.setText("网页加载超时。请确认宿舍 Wi-Fi 和认证服务器可达，或返回设置更新链接。");
    }
  };

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    MaterialStyle m = new MaterialStyle(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(m.surface);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars()
            | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
        v.setPadding(i.left, i.top, i.right, i.bottom);
      } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
          insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets;
    });
    if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    setContentView(root);
    LinearLayout toolbar = new LinearLayout(this);
    root.addView(toolbar);
    button(toolbar, "返回", this::finish);
    button(toolbar, "重新加载", this::load);
    button(toolbar, "自动登录", () -> returnAction(true));
    status = new TextView(this);
    status.setTextColor(m.ink);
    status.setTextSize(14);
    int padding = (int) (12 * getResources().getDisplayMetrics().density);
    status.setPadding(padding, padding, padding, padding);
    root.addView(status);
    button(root, "我已在网页登录，验证 Wi-Fi 联网", () -> returnAction(false));
    try {
      config = Config.from(new JSONObject(getIntent().getStringExtra("config")));
      protocol = new PortalProtocol(config.host);
      if (config.loginUrl.isEmpty()) throw new IllegalArgumentException("请先保存登录网址");
      selected = NetworkController.select(this, config).network;
      cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      // Isolated from MainActivity/WorkService. Bind BEFORE WebView opens any sockets.
      if (!cm.bindProcessToNetwork(selected)) throw new IllegalArgumentException("宿舍 Wi-Fi 已变化，请返回重试");
      callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network n) {
          if (n.equals(selected)) runOnUiThread(() -> {
            failed = true;
            release();
            status.setText("宿舍 Wi-Fi 已断开。请返回并重新连接，不会切到移动数据加载页面。");
          });
        }
      };
      cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
          .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
      web = new WebView(this);
      web.setSaveEnabled(false);
      WebSettings settings = web.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setAllowFileAccessFromFileURLs(false);
      settings.setAllowUniversalAccessFromFileURLs(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
      settings.setSaveFormData(false);
      settings.setSupportMultipleWindows(false);
      CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
      web.setWebChromeClient(new WebChromeClient());
      web.setWebViewClient(new WebViewClient() {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
          boolean blocked = !protocol.browserAllowed(r.getUrl().toString());
          if (blocked && r.isForMainFrame()) status.setText("页面尝试离开已配置的认证服务器，已停止跳转。");
          return blocked;
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
          if (protocol.browserAllowed(r.getUrl().toString())) return null;
          return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
              java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }
        @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
          if (!protocol.browserAllowed(url)) { v.stopLoading(); failed = true; return; }
          failed = false;
          handler.removeCallbacks(timeout);
          handler.postDelayed(timeout, 20000);
          status.setText("正在加载认证网页 · " + config.host + "\n网页可见不代表已经联网；可点击自动登录。");
        }
        @Override public void onPageFinished(WebView v, String url) {
          handler.removeCallbacks(timeout);
          if (!failed) status.setText("认证网页已加载 · " + config.host + "\n点击自动登录使用已保存账号，或在网页手动登录后验证联网。");
        }
        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
          if (r.isForMainFrame()) showFailure("网页访问失败，错误码 " + e.getErrorCode()
              + "。请检查 Wi-Fi、认证服务器或更新完整链接。");
        }
        @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse response) {
          if (r.isForMainFrame()) showFailure("认证网页返回 HTTP " + response.getStatusCode() + "，请更新链接或稍后重试。");
        }
        @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { h.cancel(); }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
          release();
          status.setText("网页组件已停止，请返回首页重新打开。");
          return true;
        }
      });
      root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
      load();
    } catch (Exception e) {
      release();
      status.setText(e instanceof IllegalArgumentException || e instanceof java.io.IOException
          ? e.getMessage() : "登录网页暂时无法打开，请检查系统 WebView 和网络设置。");
    }
  }

  private void showFailure(String message) {
    failed = true;
    handler.removeCallbacks(timeout);
    status.setText(message);
  }

  private void button(LinearLayout parent, String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setOnClickListener(v -> action.run());
    parent.addView(b, new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
        -2, parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
  }

  private void load() {
    if (web == null || released) return;
    failed = false;
    web.loadUrl(config.loginUrl);
    handler.removeCallbacks(timeout);
    handler.postDelayed(timeout, 20000);
  }

  private void returnAction(boolean login) {
    Intent result = new Intent().putExtra("login", login);
    if (login && web != null && protocol != null && protocol.trusted(web.getUrl()))
      result.putExtra("loginUrl", web.getUrl());
    setResult(RESULT_OK, result);
    finish();
  }

  private void release() {
    if (released) return;
    released = true;
    handler.removeCallbacks(timeout);
    if (web != null) {
      web.stopLoading();
      if (web.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) web.getParent()).removeView(web);
      web.destroy();
      web = null;
    }
    if (cm != null) {
      if (callback != null) {
        try { cm.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        callback = null;
      }
      cm.bindProcessToNetwork(null);
    }
  }

  @Override protected void onPause() {
    release();
    if (!isFinishing()) finish();
    super.onPause();
  }
  @Override protected void onDestroy() { release(); super.onDestroy(); }
}
