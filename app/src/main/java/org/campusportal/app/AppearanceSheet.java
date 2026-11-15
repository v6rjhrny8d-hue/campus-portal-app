package org.campusportal.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The same six TransRead palettes, appearance modes, and haptic choices. */
final class AppearanceSheet {
  static final int PALETTE_BASE=2600, MODE_BASE=2610, HAPTIC_BASE=2620, APPLY=2630, ANIMATIONS=2631;
  interface Apply { void selected(String palette,String appearance,String haptics); }
  static Dialog show(Activity a,Apply apply) {
    MaterialStyle m=new MaterialStyle(a);
    android.content.SharedPreferences prefs=a.getSharedPreferences(MaterialStyle.PREFS,Context.MODE_PRIVATE);
    int[] palette={m.paletteIndex};
    String[] modes={"system","light","dark"}, haptics={"off","gentle","standard"};
    int[] mode={Math.max(0,java.util.Arrays.asList(modes).indexOf(prefs.getString(MaterialStyle.APPEARANCE,"system")))};
    int[] feel={Math.max(0,java.util.Arrays.asList(haptics).indexOf(prefs.getString(MaterialStyle.HAPTICS,"gentle")))};
    YiduDialog d=new YiduDialog(a);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(m.dp(20),m.dp(4),m.dp(20),m.dp(14));panel.setBackground(m.cardSurface(26));panel.setClipToOutline(true);
    panel.addView(YiduSheets.handle(a,m,panel,d),new LinearLayout.LayoutParams(-1,m.dp(36)));
    TextView title=YiduSheets.text(a,"主题与交互",22,m.ink,true);panel.addView(title);
    ScrollView scroll=new ScrollView(a);YiduSheets.cleanScroll(scroll);LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    section(a,m,body,"主题颜色");
    Button[] colors=new Button[6];
    for(int row=0;row<3;row++){
      LinearLayout line=new LinearLayout(a);line.setOrientation(LinearLayout.HORIZONTAL);body.addView(line);
      for(int col=0;col<2;col++){
        final int i=row*2+col;
        Button b=YiduSheets.button(a,m,MaterialStyle.PALETTE_NAMES[i],false,null);b.setId(PALETTE_BASE+i);
        GradientDrawable dot=m.shape(MaterialStyle.ACCENTS[i],0,100);dot.setBounds(0,0,m.dp(18),m.dp(18));
        b.setCompoundDrawables(dot,null,null,null);b.setCompoundDrawablePadding(m.dp(8));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(m.dp(3),m.dp(3),m.dp(3),m.dp(3));line.addView(b,p);colors[i]=b;
      }
    }
    section(a,m,body,"显示模式");
    Button[] modeButtons=choiceRow(a,m,body,new String[]{"跟随系统","浅色","深色"},MODE_BASE);
    section(a,m,body,"交互触感");
    Button[] feelButtons=choiceRow(a,m,body,new String[]{"关闭","轻柔","标准"},HAPTIC_BASE);
    section(a,m,body,"界面动画");
    android.widget.Switch animationSwitch=new android.widget.Switch(a);animationSwitch.setId(ANIMATIONS);
    animationSwitch.setText("开启动画");animationSwitch.setTextColor(m.ink);animationSwitch.setTextSize(14);
    animationSwitch.setMinimumHeight(m.dp(48));animationSwitch.setThumbDrawable(m.switchThumb());animationSwitch.setTrackDrawable(m.switchTrack());
    animationSwitch.setChecked(prefs.getBoolean("animations",true));body.addView(animationSwitch,new LinearLayout.LayoutParams(-1,-2));
    TextView note=YiduSheets.text(a,"触感统一控制按钮与时间滚轮；动画也遵循系统设置。",12,m.muted,false);
    note.setPadding(0,m.dp(10),0,m.dp(4));body.addView(note);
    Runnable refresh=()->{
      for(int i=0;i<colors.length;i++)choice(colors[i],m,i==palette[0]);
      for(int i=0;i<modeButtons.length;i++)choice(modeButtons[i],m,i==mode[0]);
      for(int i=0;i<feelButtons.length;i++)choice(feelButtons[i],m,i==feel[0]);
    };
    for(int i=0;i<colors.length;i++){final int value=i;YiduMotion.click(colors[i],()->{palette[0]=value;refresh.run();});}
    for(int i=0;i<modeButtons.length;i++){final int value=i;YiduMotion.click(modeButtons[i],()->{mode[0]=value;refresh.run();});}
    for(int i=0;i<feelButtons.length;i++){final int value=i;YiduMotion.click(feelButtons[i],()->{feel[0]=value;refresh.run();});}
    refresh.run();
    LinearLayout footer=new LinearLayout(a);footer.setOrientation(LinearLayout.HORIZONTAL);
    Button cancel=YiduSheets.button(a,m,"取消",false,d::dismiss);
    boolean[] submitted={false};
    Button confirm=YiduSheets.button(a,m,"应用",true,()->{if(submitted[0])return;submitted[0]=true;prefs.edit().putBoolean("animations",animationSwitch.isChecked()).apply();d.dismiss();apply.selected(MaterialStyle.PALETTE_IDS[palette[0]],modes[mode[0]],haptics[feel[0]]);});
    confirm.setId(APPLY);
    LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.setMargins(0,m.dp(10),m.dp(4),0);
    LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.setMargins(m.dp(4),m.dp(10),0,0);
    footer.addView(cancel,left);footer.addView(confirm,right);panel.addView(footer);
    d.panel(panel);d.setContentView(panel);YiduSheets.show(a,d,(int)(YiduSheets.availableHeight(a)*.78f));return d;
  }
  private static void section(Activity a,MaterialStyle m,LinearLayout body,String label){
    TextView t=YiduSheets.text(a,label,15,m.ink,true);t.setPadding(0,m.dp(14),0,m.dp(7));body.addView(t);
  }
  private static Button[] choiceRow(Activity a,MaterialStyle m,LinearLayout body,String[] values,int ids){
    LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);body.addView(row);Button[] buttons=new Button[values.length];
    for(int i=0;i<values.length;i++){
      Button b=YiduSheets.button(a,m,values[i],false,null);b.setId(ids+i);b.setTextSize(13);
      LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(m.dp(2),0,m.dp(2),0);row.addView(b,p);buttons[i]=b;
    }
    return buttons;
  }
  private static void choice(Button b,MaterialStyle m,boolean selected){
    b.setSelected(selected);b.setTextColor(selected?m.primary:m.ink);
    b.setBackground(m.ripple(selected?m.primaryContainer:m.containerLow,selected?m.primary:0,100,m.primary));
    b.setContentDescription(b.getText()+(selected?"，已选择":""));
  }
}
