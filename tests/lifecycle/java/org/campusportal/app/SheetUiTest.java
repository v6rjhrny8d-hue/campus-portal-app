package org.campusportal.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SheetUiTest {
  Activity activity() {
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().putBoolean("animations",false).commit();
    return a;
  }
  static Button button(View v,String label) {
    if(v instanceof Button&&((Button)v).getText().toString().equals(label))return (Button)v;
    if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++){
      Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;
    }
    return null;
  }
  static String text(View v) {
    String s=v instanceof TextView?((TextView)v).getText().toString()+"\n":"";
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s+=text(((ViewGroup)v).getChildAt(i));
    return s;
  }
  static ViewGroup layout(Dialog d) {
    ViewGroup panel=ReflectionHelpers.getField(d,"panel");
    int width=d.getWindow().getAttributes().width,height=d.getWindow().getAttributes().height;
    panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
    panel.layout(0,0,width,height);return panel;
  }
  static void fittedButtons(LinearLayout bar) {
    for(int i=0;i<bar.getChildCount();i++){
      Button b=(Button)bar.getChildAt(i);
      assertTrue("touch target >=48dp",b.getHeight()>=Math.round(48*b.getResources().getDisplayMetrics().density));
      assertTrue("label fits width",android.text.Layout.getDesiredWidth(b.getText(),b.getPaint())
          <=b.getWidth()-b.getCompoundPaddingLeft()-b.getCompoundPaddingRight()+1);
      assertEquals("label remains one line at tested scale",1,b.getLineCount());
      if(i>0&&bar.getOrientation()==LinearLayout.VERTICAL)assertTrue(b.getTop()>=bar.getChildAt(i-1).getBottom());
      if(i>0&&bar.getOrientation()==LinearLayout.HORIZONTAL)assertEquals(bar.getChildAt(0).getHeight(),b.getHeight());
    }
  }
  @Test public void threeActionsUseFullWidthVerticalButtons() {
    Activity a=activity();int[] calls={0};
    Dialog d=new YiduSheets.Builder(a).setTitle("后台联网自测").setMessage("说明")
        .setPositiveButton("立即检测",(x,w)->calls[0]++)
        .setNeutralButton("延迟检测与结果",null).setNegativeButton("关闭",null).show();
    ViewGroup panel=layout(d);LinearLayout bar=panel.findViewById(YiduSheets.ACTIONS);
    assertEquals(LinearLayout.VERTICAL,bar.getOrientation());fittedButtons(bar);
    Button primary=button(panel,"立即检测");primary.performClick();primary.performClick();assertEquals(1,calls[0]);
  }
  @Test public void twoShortActionsRemainEqualHeightAndHorizontal() {
    Activity a=activity();Dialog d=new YiduSheets.Builder(a).setTitle("确认").setMessage("测试")
        .setNegativeButton("取消",null).setPositiveButton("确定",null).show();
    ViewGroup panel=layout(d);LinearLayout bar=panel.findViewById(YiduSheets.ACTIONS);
    assertEquals(LinearLayout.HORIZONTAL,bar.getOrientation());fittedButtons(bar);d.dismiss();
  }
  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk=35,manifest=Config.NONE,qualifiers="w320dp-h640dp-xhdpi")
  public void longActionsAtLargeFontStackInsteadOfWrappingUnevenly() {
    Activity a=activity();android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());
    config.fontScale=1.3f;a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());
    Dialog d=new YiduSheets.Builder(a).setTitle("操作").setMessage("测试")
        .setNegativeButton("返回当前页面",null).setPositiveButton("打开系统设置检查省电",null).show();
    ViewGroup panel=layout(d);LinearLayout bar=panel.findViewById(YiduSheets.ACTIONS);
    assertEquals(LinearLayout.VERTICAL,bar.getOrientation());fittedButtons(bar);
    assertEquals("打开系统设置检查省电",((Button)bar.getChildAt(0)).getText().toString());d.dismiss();
  }
  @Test public void summaryCanScrollToFlatDetailsButtonWithoutFooterOverlap() {
    Activity a=activity();StringBuilder summary=new StringBuilder();for(int i=0;i<70;i++)summary.append("检测记录：HTTPS已验证，熄屏后台运行。\n");
    Button details=YiduSheets.button(a,new MaterialStyle(a),"查看详细自测记录",false,()->{});
    Dialog d=new YiduSheets.Builder(a).setTitle("延迟后台自测").setMessage(summary.toString()).setView(details)
        .setPositiveButton("两分钟后自测",null).setNeutralButton("取消待测",null).setNegativeButton("关闭",null).show();
    ViewGroup panel=layout(d);ScrollView scroll=panel.findViewById(YiduSheets.BODY_SCROLL);
    LinearLayout bar=panel.findViewById(YiduSheets.ACTIONS);fittedButtons(bar);
    assertTrue(scroll.getHeight()>0);assertTrue(scroll.getBottom()<=bar.getTop());assertTrue(bar.getBottom()<=panel.getHeight()-panel.getPaddingBottom());
    scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
    Rect visible=new Rect();details.getDrawingRect(visible);panel.offsetDescendantRectToMyCoords(details,visible);
    assertTrue("details button fully inside scroll viewport",visible.top>=scroll.getTop()&&visible.bottom<=scroll.getBottom());
    assertNull(details.getStateListAnimator());assertEquals(0f,details.getElevation(),0f);d.dismiss();
  }
  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk=35,manifest=Config.NONE,qualifiers="w320dp-h640dp-xhdpi")
  public void narrowLargeFontSummaryKeepsEveryActionAndDetailsFullyVisible()throws Exception {
    Activity a=activity();android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());
    config.fontScale=1.3f;a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());
    StringBuilder message=new StringBuilder();for(int i=0;i<24;i++)message.append("后台自测已完成，结果需结合隔夜记录确认。\n");
    Button details=YiduSheets.button(a,new MaterialStyle(a),"查看详细自测记录",false,()->{});
    Dialog d=new YiduSheets.Builder(a).setTitle("延迟后台自测").setMessage(message.toString()).setView(details)
        .setPositiveButton("安排两分钟后自测",null).setNeutralButton("取消待测",null).setNegativeButton("关闭",null).show();
    ViewGroup panel=layout(d);LinearLayout bar=panel.findViewById(YiduSheets.ACTIONS);fittedButtons(bar);
    ScrollView scroll=panel.findViewById(YiduSheets.BODY_SCROLL);scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
    Rect box=new Rect();details.getDrawingRect(box);panel.offsetDescendantRectToMyCoords(details,box);
    assertTrue(box.top>=scroll.getTop()&&box.bottom<=scroll.getBottom());
    assertTrue(scroll.getBottom()<=bar.getTop());assertTrue(bar.getBottom()<=panel.getHeight()-panel.getPaddingBottom());
    File output=new File("build/sheet-qa");output.mkdirs();save(panel,new File(output,"delayed-320-font130.png"));d.dismiss();
  }
  @Test public void actualBackgroundDialogsUseShortLabelsAndSharedFlatButton()throws Exception {
    MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().putBoolean("animations",false).commit();
    ReflectionHelpers.callInstanceMethod(a,"handle",ClassParameter.from(JSONObject.class,new JSONObject().put("action","backgroundTest")));
    Dialog first=ShadowDialog.getLatestDialog();ViewGroup panel=layout(first);fittedButtons(panel.findViewById(YiduSheets.ACTIONS));
    assertTrue(text(panel).contains("夜间休眠省电"));button(panel,"延迟自测").performClick();
    Dialog second=ShadowDialog.getLatestDialog();ViewGroup next=layout(second);
    Button details=button(next,"查看详细自测记录");assertNotNull(details);assertNull(details.getStateListAnimator());assertEquals(0f,details.getElevation(),0f);
    assertNotNull(button(next,"两分钟后自测"));assertFalse(first.isShowing());second.dismiss();a.finish();
  }
  @Test public void nightReminderDistinguishesExemptionAndOpensManualSettingsGuidance() {
    Activity a=activity();AppUi ui=new AppUi(a,(action,value)->{});ui.route(1);
    assertTrue(text(ui.view()).contains("重要：可能需要关闭夜间休眠省电"));
    assertTrue(text(ui.view()).contains("不代表这个开关已关闭"));
    ui.view().findViewById(AppUi.NIGHT_POWER_GUIDE).performClick();Dialog d=ShadowDialog.getLatestDialog();ViewGroup panel=layout(d);
    assertTrue(text(panel).contains("更多电池功能"));assertTrue(text(panel).contains("应用无法可靠读取"));
    button(panel,"打开系统设置").performClick();Intent launched=org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
    assertEquals(android.provider.Settings.ACTION_SETTINGS,launched.getAction());ui.dispose();
  }

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void renderActualDialogsAndNightReminderForLightAndDark()throws Exception {
    File output=new File("build/sheet-qa");output.mkdirs();
    for(String mode:new String[]{"light","dark"}){
      MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
      a.getSharedPreferences(MaterialStyle.PREFS,0).edit().putString(MaterialStyle.APPEARANCE,mode).putBoolean("animations",false).commit();
      a.getSharedPreferences("background_test",0).edit().putString("延迟登录自测",
          "11-02 07:00:00\nWi-Fi网页访问验证成功；请求=5；响应=5；提交=1；耗时=500ms；结束=FINISHED\n结果：认证成功\n亮屏=false；主界面前台=false").commit();
      ReflectionHelpers.callInstanceMethod(a,"handle",ClassParameter.from(JSONObject.class,new JSONObject().put("action","backgroundTest")));
      Dialog first=ShadowDialog.getLatestDialog();ViewGroup panel=layout(first);fittedButtons(panel.findViewById(YiduSheets.ACTIONS));
      save(panel,new File(output,"background-"+mode+".png"));button(panel,"延迟自测").performClick();
      Dialog second=ShadowDialog.getLatestDialog();ViewGroup delayed=layout(second);fittedButtons(delayed.findViewById(YiduSheets.ACTIONS));
      ScrollView scroll=delayed.findViewById(YiduSheets.BODY_SCROLL);scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
      save(delayed,new File(output,"delayed-"+mode+".png"));second.dismiss();
      AppUi ui=new AppUi(a,(action,value)->{});ui.route(1);a.setContentView(ui.view());
      ui.view().measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));ui.view().layout(0,0,786,1704);
      LinearLayout permissions=ReflectionHelpers.getField(ui,"permissionCard");ScrollView[] pages=ReflectionHelpers.getField(ui,"pages");pages[1].scrollTo(0,permissions.getTop());
      save(ui.view(),new File(output,"night-"+mode+".png"));ui.dispose();a.finish();
    }
  }
  static void save(View view,File file)throws Exception {
    Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
    try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
  }
}
