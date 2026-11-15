package org.campusportal.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Shared bottom-card surface and a small dialog builder for existing actions. */
final class YiduSheets {
  static final int ACTIONS = 6401, BODY_SCROLL = 6402;
  static int availableWidth(Activity a) {
    MaterialStyle m = new MaterialStyle(a);
    return Math.max(1, Math.min(m.dp(560), a.getResources().getDisplayMetrics().widthPixels - m.dp(20)));
  }
  static void show(Activity a, Dialog dialog, int height) {
    dialog.setCanceledOnTouchOutside(true);
    Window w=dialog.getWindow();
    if (w!=null) {
      MaterialStyle palette=new MaterialStyle(a);
      w.setBackgroundDrawable(palette.shape(Color.TRANSPARENT,0,MaterialStyle.CARD_RADIUS));
      w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      w.setDimAmount(dialog instanceof YiduDialog?0:.24f);
      w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setWindowAnimations(0);
    }
    dialog.show();
    if (w!=null) {
      MaterialStyle m=new MaterialStyle(a);
      int width=availableWidth(a);
      int capped=height>0?Math.min(height,(int)(availableHeight(a)*.9f)):height;
      w.setLayout(Math.max(1,width),capped);
      android.view.WindowManager.LayoutParams p=w.getAttributes(); p.y=m.dp(8); w.setAttributes(p);
    }
    if(dialog instanceof YiduDialog)((YiduDialog)dialog).enter();
  }
  static void cleanScroll(ScrollView scroll) {
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setVerticalFadingEdgeEnabled(false);
    scroll.setHorizontalFadingEdgeEnabled(false);
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scroll.setClipToPadding(true);
    scroll.setClipChildren(true);
  }
  static int availableHeight(Activity a) {
    View content=a.findViewById(android.R.id.content);
    return content!=null&&content.getHeight()>0?content.getHeight():a.getResources().getDisplayMetrics().heightPixels;
  }
  static FrameLayout handle(Activity a, MaterialStyle m, View panel, Dialog dialog) {
    FrameLayout target=new FrameLayout(a);
    target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    View line=new View(a); line.setBackground(m.shape(m.outlineVariant,0,100));
    target.addView(line,new FrameLayout.LayoutParams(m.dp(36),m.dp(4),Gravity.CENTER));
    return target;
  }
  static TextView text(Activity a,String label,int sp,int color,boolean title) {
    TextView t=new TextView(a);t.setText(label);t.setTextSize(sp);t.setTextColor(color);
    t.setIncludeFontPadding(false);t.setLineSpacing(margin(a,3),1);
    t.setTypeface(Typeface.create(title?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
    return t;
  }
  static int margin(Activity a,int d) { return Math.round(d*a.getResources().getDisplayMetrics().density); }
  static Button button(Activity a,MaterialStyle m,String text,boolean primary,Runnable action) {
    Button b=new Button(a); b.setText(text);b.setAllCaps(false);b.setTextSize(14);
    b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(m.dp(48));b.setMinimumHeight(m.dp(48));
    b.setPadding(m.dp(12),m.dp(9),m.dp(12),m.dp(9));b.setStateListAnimator(null);
    b.setElevation(0);b.setTranslationZ(0);
    b.setTextColor(primary?m.onPrimary:m.primary);
    b.setBackground(m.ripple(primary?m.primary:m.primaryContainer,0,100,m.primary));
    if(action!=null)YiduMotion.click(b,action);return b;
  }

  /** Reflow from measured label widths, never by squeezing text into equal thirds. */
  static final class ActionBar extends LinearLayout {
    final MaterialStyle style;
    final List<Button> buttons = new ArrayList<>();
    final List<Button> primary = new ArrayList<>();
    ActionBar(Activity a, MaterialStyle style) {
      super(a);this.style = style;setId(ACTIONS);setBaselineAligned(false);
      setPadding(0, style.dp(12), 0, 0);
    }
    void add(Button button, boolean important) {
      buttons.add(button);if (important) primary.add(button);addView(button);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      boolean vertical = buttons.size() >= 3;
      float share = buttons.isEmpty() ? width : (width - style.dp(8) * (buttons.size() - 1)) / (float) buttons.size();
      for (Button button : buttons) {
        float label = android.text.Layout.getDesiredWidth(button.getText(), button.getPaint());
        if (label + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight() + style.dp(4) > share)
          vertical = true;
      }
      setOrientation(vertical ? VERTICAL : HORIZONTAL);
      List<Button> order = new ArrayList<>();
      if (vertical) order.addAll(primary);
      for (Button button : buttons) if (!order.contains(button)) order.add(button);
      for (int i = 0; i < order.size(); i++) {
        Button button = order.get(i);
        if (indexOfChild(button) != i) { removeView(button);addView(button, i); }
        int maxLines=vertical ? Integer.MAX_VALUE : 1;
        if(button.getMaxLines()!=maxLines)button.setMaxLines(maxLines);
        button.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) button.getLayoutParams();
        int targetWidth=vertical ? -1 : 0, top=vertical && i>0 ? style.dp(8) : 0;
        int right=!vertical && i<order.size()-1 ? style.dp(8) : 0;float weight=vertical ? 0 : 1;
        if(p.width!=targetWidth||p.height!=-2||p.weight!=weight||p.leftMargin!=0||p.topMargin!=top||p.rightMargin!=right||p.bottomMargin!=0){
          p.width=targetWidth;p.height=-2;p.weight=weight;p.setMargins(0,top,right,0);button.setLayoutParams(p);
        }
      }
      super.onMeasure(widthSpec, heightSpec);
    }
  }

  static final class Builder {
    final Activity a; int checkedItem=-1; String title="",message=""; View extra; String[] items;
    DialogInterface.OnClickListener itemAction;
    final List<Action> actions=new ArrayList<>();
    Builder(Activity a){this.a=a;}
    Builder setCheckedItem(int item){checkedItem=item;return this;}
    Builder setTitle(String s){title=s;return this;}
    Builder setMessage(String s){message=s;return this;}
    Builder setView(View v){extra=v;return this;}
    Builder setItems(String[] values,DialogInterface.OnClickListener listener){items=values;itemAction=listener;return this;}
    Builder setPositiveButton(String s,DialogInterface.OnClickListener listener){actions.add(new Action(s,-1,listener,true));return this;}
    Builder setNegativeButton(String s,DialogInterface.OnClickListener listener){actions.add(new Action(s,-2,listener,false));return this;}
    Builder setNeutralButton(String s,DialogInterface.OnClickListener listener){actions.add(new Action(s,-3,listener,false));return this;}
    Dialog show(){
      MaterialStyle m=new MaterialStyle(a);YiduDialog dialog=new YiduDialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
      LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);
      panel.setPadding(m.dp(20),m.dp(4),m.dp(20),m.dp(16));panel.setBackground(m.cardSurface(26));panel.setClipToOutline(true);
      panel.addView(handle(a,m,panel,dialog),new LinearLayout.LayoutParams(-1,m.dp(36)));
      TextView heading=text(a,title,21,m.ink,true);heading.setPadding(0,0,0,m.dp(12));panel.addView(heading);
      ScrollView scroll=new ScrollView(a);scroll.setId(BODY_SCROLL);cleanScroll(scroll);scroll.setFillViewport(false);
      LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);
      body.setPadding(0, 0, 0, m.dp(8));
      boolean[] dispatched={false};
      if(!message.isEmpty()) {TextView copy=text(a,message,14,m.ink,false);copy.setPadding(0,0,0,m.dp(12));body.addView(copy);}
      if(items!=null) for(int i=0;i<items.length;i++){
        final int index=i; Button b=button(a,m,items[i],false,()->{
          if(dispatched[0])return;dispatched[0]=true;dialog.dismiss();if(itemAction!=null)itemAction.onClick(dialog,index);
        });
        if(i==checkedItem){
          b.setSelected(true);b.setTextColor(m.primary);b.setBackground(m.ripple(m.primaryContainer,m.primary,18,m.primary));
          b.setText(m.iconLabel(items[i],"check",m.primary));b.setContentDescription(items[i]+"，已选中");
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=m.dp(8);body.addView(b,p);
      }
      if(extra!=null){
        if(extra instanceof TextView){((TextView)extra).setTextColor(m.primary);extra.setBackground(m.ripple(m.containerLow,0,100,m.primary));}
        if(extra instanceof Button){((Button)extra).setStateListAnimator(null);extra.setElevation(0);extra.setTranslationZ(0);}
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);ep.topMargin = m.dp(8);
        body.addView(extra, ep);
      }
      panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
      ActionBar row=new ActionBar(a,m);
      for(Action action:actions){
        Button b=button(a,m,action.name,action.primary,()->{
          if(dispatched[0])return;dispatched[0]=true;dialog.dismiss();if(action.listener!=null)action.listener.onClick(dialog,action.code);
        });
        if(action.code == DialogInterface.BUTTON_NEGATIVE)
          b.setBackground(m.ripple(Color.TRANSPARENT,0,100,m.primary));
        row.add(b,action.primary);
      }
      if(!actions.isEmpty())panel.addView(row);
      // Measure actual fonts, wrapping and controls; long summaries get a bounded scroll area.
      panel.measure(View.MeasureSpec.makeMeasureSpec(availableWidth(a),View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
      int height=Math.min(panel.getMeasuredHeight(),(int)(availableHeight(a)*.84f));
      LinearLayout.LayoutParams sp=(LinearLayout.LayoutParams)scroll.getLayoutParams();sp.height=0;sp.weight=1;scroll.setLayoutParams(sp);
      dialog.panel(panel);dialog.setContentView(panel);
      YiduSheets.show(a,dialog,height);return dialog;
    }
    static final class Action {final String name;final int code;final DialogInterface.OnClickListener listener;final boolean primary;
      Action(String n,int c,DialogInterface.OnClickListener l,boolean p){name=n;code=c;listener=l;primary=p;}}
  }
}
