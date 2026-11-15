package org.campusportal.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Three destination opaque capsule, with an interruptible sliding selection. */
final class GlassDock extends FrameLayout {
  interface Selection { void choose(int index); }
  static final int DOCK_ID = 2401;
  private final MaterialStyle m;
  private final Selection selection;
  private final View indicator;
  private final LinearLayout[] tabs = new LinearLayout[3];
  private final ImageView[] icons = new ImageView[3];
  private final TextView[] labels = new TextView[3];
  private final String[] names = {"首页", "设置", "记录"}, symbols = {"home", "settings", "history"};
  private final int pad, touchSlop;
  private int current, dragTick = -1;
  private float downX, downY;
  private boolean dragging;

  GlassDock(Activity a, MaterialStyle style, View underlying, Selection callback) {
    super(a); m = style; selection = callback; pad = m.dp(6);
    touchSlop = ViewConfiguration.get(a).getScaledTouchSlop();
    setId(DOCK_ID);
    setClipChildren(false); setClipToPadding(false);
    setBackground(m.shape(m.containerHigh,MaterialStyle.mix(m.outlineVariant,m.containerHigh,.5f),100));
    setStateListAnimator(null);
    setOutlineProvider(new android.view.ViewOutlineProvider(){
      @Override public void getOutline(View view,android.graphics.Outline outline){
        outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),view.getHeight()/2f);
      }
    });
    setElevation(m.dp(4));
    if(android.os.Build.VERSION.SDK_INT>=28){
      setOutlineAmbientShadowColor(m.dark?0x66000000:0x33000000);
      setOutlineSpotShadowColor(m.dark?0x55000000:0x28000000);
    }
    indicator = new View(a);
    indicator.setBackground(m.shape(m.primaryContainer, 0, 100));
    indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(1, -1, Gravity.START | Gravity.CENTER_VERTICAL);
    ip.leftMargin = pad; ip.topMargin = pad; ip.bottomMargin = pad;
    addView(indicator, ip);
    LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(pad, pad, pad, pad);
    addView(row, new FrameLayout.LayoutParams(-1, -1));
    for (int i=0; i<3; i++) {
      final int index = i;
      LinearLayout tab = new LinearLayout(a);
      tab.setOrientation(LinearLayout.VERTICAL); tab.setGravity(Gravity.CENTER);
      tab.setFocusable(true); tab.setClickable(true);
      icons[i] = new ImageView(a); icons[i].setImageDrawable(m.icon(symbols[i], m.muted));
      icons[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tab.addView(icons[i], new LinearLayout.LayoutParams(m.dp(24), m.dp(24)));
      labels[i] = new TextView(a); labels[i].setText(names[i]); labels[i].setTextSize(11);
      labels[i].setGravity(Gravity.CENTER); labels[i].setTextColor(m.muted);
      labels[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tab.addView(labels[i]); tabs[i] = tab;
      YiduMotion.click(tab, () -> selection.choose(index));
      row.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
    }
    select(0, false);
  }
  @Override protected void onMeasure(int widthSpec, int heightSpec) {
    int width = Math.min(MeasureSpec.getSize(widthSpec), m.dp(390));
    LayoutParams lp=(LayoutParams)indicator.getLayoutParams();
    int wanted=Math.max(1,(width-pad*2)/3);
    if(lp.width!=wanted){lp.width=wanted;indicator.setLayoutParams(lp);}
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
  }
  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    indicator.setTranslationX(current * cellWidth());
  }
  private float cellWidth() { return Math.max(1, (getWidth() - pad * 2) / 3f); }
  void select(int index, boolean animate) {
    current = Math.max(0, Math.min(2, index));
    for (int i=0; i<3; i++) {
      boolean active = i == current;
      tabs[i].setSelected(active);
      tabs[i].setContentDescription(names[i] + (active ? "，已选中" : ""));
      icons[i].setImageDrawable(m.icon(symbols[i], active ? m.primary : m.muted));
      labels[i].setTextColor(active ? m.primary : m.muted);
    }
    indicator.animate().cancel();
    if (getWidth() == 0) { requestLayout(); return; }
    float target = current * cellWidth();
    if (animate && YiduMotion.animations())
      indicator.animate().translationX(target).setDuration(320)
          .setInterpolator(new OvershootInterpolator(.8f)).start();
    else indicator.setTranslationX(target);
   
  }
  @Override public boolean onInterceptTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      downX=e.getX(); downY=e.getY(); dragging=false; dragTick=current;
    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      float dx=e.getX()-downX, dy=e.getY()-downY;
      if (Math.abs(dx)>touchSlop && Math.abs(dx)>Math.abs(dy)*1.2f) {
        dragging=true; indicator.animate().cancel();
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
    }
    return super.onInterceptTouchEvent(e);
  }
  @Override public boolean onTouchEvent(MotionEvent e) {
    if (!dragging) return super.onTouchEvent(e);
    if (e.getActionMasked()==MotionEvent.ACTION_MOVE || e.getActionMasked()==MotionEvent.ACTION_UP) {
      float x=Math.max(0, Math.min(2*cellWidth(), e.getX()-pad-cellWidth()/2));
      indicator.setTranslationX(x);
      int target=Math.max(0, Math.min(2, Math.round(x/cellWidth())));
      if (target!=dragTick) { dragTick=target; YiduMotion.tick(this); }
      if (e.getActionMasked()==MotionEvent.ACTION_UP) {
        dragging=false; if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        selection.choose(target);
      }
      return true;
    }
    if (e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
      dragging=false; select(current, true);
      if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
      return true;
    }
    return true;
  }

}
