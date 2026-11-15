package org.campusportal.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.*;

/** One moving surface, one dim animator, and a direction-locked sheet gesture. */
final class YiduDialog extends Dialog {
  private final Activity activity;
  private View panel, downTarget;
  private ValueAnimator motion;
  private ViewTreeObserver.OnPreDrawListener ready;
  private boolean exiting, dragging, locked;
  private float dim,downX,downY,baseY,openingDim;
  private static java.lang.ref.WeakReference<YiduDialog> current=new java.lang.ref.WeakReference<>(null);
  private long downTime;
  private final int slop;
  private static final float FULL_DIM=.24f;
  YiduDialog(Activity a){super(a,theme(a));activity=a;slop=ViewConfiguration.get(a).getScaledTouchSlop();}
  private static int theme(Activity a){return a.getResources().getIdentifier("YiduDialogTheme","style",a.getPackageName());}
  void panel(View view){panel=view;}
  @Override public void show(){
    YiduDialog previous=current.get();
    openingDim=previous!=null&&previous!=this&&previous.isShowing()?previous.dim:0;
    setDim(openingDim);super.show();current=new java.lang.ref.WeakReference<>(this);
    if(previous!=null&&previous!=this&&previous.isShowing())previous.finishDismiss();
  }
  private void cancelMotion(){if(motion!=null){ValueAnimator previous=motion;motion=null;previous.cancel();}}
  private void removeReady(){if(ready!=null&&panel!=null){if(panel.getViewTreeObserver().isAlive())panel.getViewTreeObserver().removeOnPreDrawListener(ready);ready=null;}}
  private void setDim(float value){
    dim=Math.max(0,Math.min(FULL_DIM,value));
    if(getWindow()!=null)getWindow().setDimAmount(dim);
  }
  void enter(){
    if(panel==null)return;
    setDim(openingDim);panel.setAlpha(1);
    if(!YiduMotion.animations()){panel.setTranslationY(0);setDim(FULL_DIM);return;}
    removeReady();
    ready=()->{
      removeReady();
      if(!isShowing()||exiting)return true;
      panel.setTranslationY(panel.getHeight()+new MaterialStyle(activity).dp(16));
      animateTo(0,FULL_DIM,280,false);return true;
    };
    panel.getViewTreeObserver().addOnPreDrawListener(ready);
    panel.invalidate();
  }
  private void animateTo(float targetY,float targetDim,long duration,boolean close){
    cancelMotion();float fromY=panel.getTranslationY(),fromDim=dim;
    if(!YiduMotion.animations()){
      panel.setTranslationY(targetY);setDim(targetDim);if(close)finishDismiss();return;
    }
    ValueAnimator animation=ValueAnimator.ofFloat(0,1);motion=animation;animation.setDuration(duration);
    animation.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
    animation.addUpdateListener(a->{float f=(float)a.getAnimatedValue();panel.setTranslationY(fromY+(targetY-fromY)*f);setDim(fromDim+(targetDim-fromDim)*f);});
    animation.addListener(new AnimatorListenerAdapter(){boolean cancelled;
      @Override public void onAnimationCancel(Animator a){cancelled=true;}
      @Override public void onAnimationEnd(Animator a){if(motion==animation)motion=null;if(!cancelled&&close)finishDismiss();}
    });animation.start();
  }
  private void finishDismiss(){removeReady();cancelMotion();exiting=false;setDim(0);if(current.get()==this)current.clear();YiduDialog.super.dismiss();}
  @Override public void dismiss(){
    removeReady();
    if(activity.isFinishing()||activity.isDestroyed()||panel==null||!panel.isLaidOut()
        ||!panel.isAttachedToWindow()||!isShowing()||!YiduMotion.animations()){finishDismiss();return;}
    if(exiting)return;exiting=true;
    animateTo(panel.getHeight()+new MaterialStyle(activity).dp(16),0,220,true);
  }
  @Override public void cancel(){dismiss();}
  @Override protected void onStop(){removeReady();cancelMotion();super.onStop();}

  /** Vertical dragging belongs to scrolling content until it reaches its top. */
  static boolean dragAllowed(View target,View panel){
    for(View v=target;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null){
      if(v instanceof AlarmWheel||v instanceof NumberPicker||v instanceof SeekBar||v instanceof HorizontalScrollView
          ||v instanceof EditText||v instanceof Spinner||v instanceof CompoundButton)return false;
      if(v instanceof TextView&&((TextView)v).isTextSelectable())return false;
      if(v instanceof ScrollView&&v.canScrollVertically(-1))return false;
      if(v==panel)break;
    }
    return true;
  }
  private static boolean inside(View v,float x,float y){
    int[] pos=new int[2];v.getLocationOnScreen(pos);
    return v.getVisibility()==View.VISIBLE&&x>=pos[0]&&x<pos[0]+v.getWidth()&&y>=pos[1]&&y<pos[1]+v.getHeight();
  }
  private static View target(View root,float x,float y){
    if(!inside(root,x,y))return null;
    if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;
      for(int i=g.getChildCount()-1;i>=0;i--){View found=target(g.getChildAt(i),x,y);if(found!=null)return found;}}
    return root;
  }
  @Override public boolean dispatchTouchEvent(MotionEvent e){
    if(panel==null)return super.dispatchTouchEvent(e);
    int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){
      removeReady();
      if(exiting){cancelMotion();exiting=false;}
      if(panel.getTranslationY()!=0||dim<FULL_DIM)animateTo(0,FULL_DIM,180,false);
      downX=e.getRawX();downY=e.getRawY();downTime=e.getEventTime();baseY=panel.getTranslationY();
      downTarget=target(panel,downX,downY);dragging=false;locked=false;
    }else if(action==MotionEvent.ACTION_POINTER_DOWN){locked=true;if(dragging){dragging=false;animateTo(0,FULL_DIM,180,false);return true;}}
    else if(action==MotionEvent.ACTION_MOVE&&!locked&&downTarget!=null){
      float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
      if(!dragging&&dy<-slop&&Math.abs(dy)>Math.abs(dx))locked=true;
      if(!dragging&&Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.2f)locked=true;
      if(!dragging&&dy>slop&&dy>Math.abs(dx)*1.3f){
        if(!dragAllowed(downTarget,panel)){locked=true;return super.dispatchTouchEvent(e);}
        cancelMotion();dragging=true;
        MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
      }
      if(dragging){
        float y=Math.max(0,baseY+dy-slop);panel.setTranslationY(y);setDim(FULL_DIM*(1-Math.min(1,y/Math.max(1,panel.getHeight()))));return true;
      }
    }
    if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)&&dragging){
      float dy=e.getRawY()-downY,speed=dy*1000/Math.max(1,e.getEventTime()-downTime);
      boolean close=action==MotionEvent.ACTION_UP&&(panel.getTranslationY()>new MaterialStyle(activity).dp(80)
          ||(panel.getTranslationY()>new MaterialStyle(activity).dp(24)&&speed>new MaterialStyle(activity).dp(1100)));
      dragging=false;downTarget=null;
      if(close)dismiss();else animateTo(0,FULL_DIM,200,false);return true;
    }
    if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)downTarget=null;
    return super.dispatchTouchEvent(e);
  }
}
