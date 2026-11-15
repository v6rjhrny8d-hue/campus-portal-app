package org.campusportal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

/** Single painted alarm wheel: no native divider, editable field, or duplicate selected text. */
final class AlarmWheel extends View {
  interface Changed { void changed(AlarmWheel wheel, int oldValue, int value); }
  private final MaterialStyle m;
  private final int maximum, slop, minFling, maxFling;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final OverScroller scroller;
  private final String unit;
  private Changed listener;
  private VelocityTracker velocity;
  private ValueAnimator snap;
  private int value, previousY;
  private float offset, downY, lastY;
  private boolean moved;

  AlarmWheel(Context context, MaterialStyle style, int maximum, String unit) {
    super(context); this.m=style;this.maximum=maximum;this.unit=unit;
    ViewConfiguration cfg=ViewConfiguration.get(context);
    slop=cfg.getScaledTouchSlop();minFling=cfg.getScaledMinimumFlingVelocity();maxFling=cfg.getScaledMaximumFlingVelocity();
    scroller=new OverScroller(context);setFocusable(true);setClickable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    updateDescription();
  }
  int getValue(){return value;}
  int getMinValue(){return 0;}
  int getMaxValue(){return maximum;}
  void setOnValueChangedListener(Changed changed){listener=changed;}
  void setValue(int next){stopMotion();offset=0;value=Math.max(0,Math.min(maximum,next));updateDescription();invalidate();}
  private String digits(int n){return String.format(java.util.Locale.ROOT,"%02d",n);}
  private int wrap(int n){int count=maximum+1;return (n%count+count)%count;}
  private float row(){return Math.max(1,getHeight()/5f);}
  private void updateDescription(){setContentDescription(unit+"，"+digits(value));}
  private void change(int next){
    int old=value;value=wrap(next);if(old==value)return;
    updateDescription();if(listener!=null)listener.changed(this,old,value);
    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);invalidate();
  }
  private void shift(float delta){
    offset+=delta;float step=row();
    while(offset<=-step/2){offset+=step;change(value+1);}
    while(offset>=step/2){offset-=step;change(value-1);}
    invalidate();
  }
  @Override protected void onDraw(Canvas c){
    super.onDraw(c);float center=getHeight()/2f, step=row();
    float base=30*getResources().getDisplayMetrics().scaledDensity;
    c.save();c.clipRect(0,0,getWidth(),getHeight());
    for(int i=-3;i<=3;i++){
      float y=center+i*step+offset, distance=Math.abs(y-center)/step;
      if(distance>2.8f)continue;
      float emphasis=Math.max(0,1-distance);
      paint.setTextSize(base*(.78f+.22f*emphasis));
      paint.setColor(MaterialStyle.mix(m.muted,m.primary,emphasis));
      paint.setAlpha(Math.round(255*Math.max(.12f,1-distance*.32f)));
      Paint.FontMetrics fm=paint.getFontMetrics();
      c.drawText(digits(wrap(value+i)),getWidth()/2f,y-(fm.ascent+fm.descent)/2,paint);
    }
    c.restore();paint.setAlpha(255);
  }
  @Override public boolean onTouchEvent(MotionEvent e){
    if(!isEnabled())return false;
    switch(e.getActionMasked()){
      case MotionEvent.ACTION_DOWN:
        stopMotion();velocity=VelocityTracker.obtain();velocity.addMovement(e);
        downY=lastY=e.getY();moved=false;
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
      case MotionEvent.ACTION_MOVE:
        if(velocity!=null)velocity.addMovement(e);
        if(!moved&&Math.abs(e.getY()-downY)>slop){moved=true;lastY=downY;}
        if(moved){shift(e.getY()-lastY);lastY=e.getY();}return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        float speed=0;
        if(velocity!=null){velocity.addMovement(e);velocity.computeCurrentVelocity(1000,maxFling);speed=velocity.getYVelocity();velocity.recycle();velocity=null;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP&&!moved){
          performClick();float distance=e.getY()-getHeight()/2f;
          if(Math.abs(distance)>row()/2)change(value+(distance>0?1:-1));
          settle();
        }else if(e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(speed)>minFling&&YiduMotion.animations()){
          previousY=0;int reach=(int)(row()*12);
          scroller.fling(0,0,0,(int)speed,0,0,-reach,reach);postInvalidateOnAnimation();
        }else settle();
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
      default:return true;
    }
  }
  @Override public boolean performClick(){super.performClick();return true;}
  @Override public void computeScroll(){
    if(scroller.computeScrollOffset()){
      int next=scroller.getCurrY();shift(next-previousY);previousY=next;
      if(scroller.isFinished())settle();else postInvalidateOnAnimation();
    }
  }
  private void settle(){
    if(snap!=null)snap.cancel();
    if(!YiduMotion.animations()||Math.abs(offset)<.5f){offset=0;invalidate();return;}
    snap=ValueAnimator.ofFloat(offset,0);snap.setDuration(160);
    snap.addUpdateListener(a->{offset=(float)a.getAnimatedValue();invalidate();});snap.start();
  }
  private void stopMotion(){
    scroller.forceFinished(true);if(snap!=null){snap.cancel();snap=null;}
    if(velocity!=null){velocity.recycle();velocity=null;}
  }
  @Override protected void onDetachedFromWindow(){stopMotion();super.onDetachedFromWindow();}
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
    super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.NumberPicker");
    info.setScrollable(true);info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,maximum,value));
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
  }
  @Override public boolean performAccessibilityAction(int action,Bundle args){
    if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
      stopMotion();offset=0;change(value+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1));return true;
    }
    if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null
        &&args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)){
      float requested=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
      if(Float.isNaN(requested)||Float.isInfinite(requested))return false;
      stopMotion();offset=0;change(Math.max(0,Math.min(maximum,Math.round(requested))));return true;
    }
    return super.performAccessibilityAction(action,args);
  }
  @Override public boolean onKeyDown(int code,KeyEvent e){
    if(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN){
      stopMotion();offset=0;change(value+(code==KeyEvent.KEYCODE_DPAD_DOWN?1:-1));return true;
    }
    return super.onKeyDown(code,e);
  }
}
