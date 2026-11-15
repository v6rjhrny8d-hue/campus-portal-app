package org.campusportal.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class UiExperienceTest {
  private static void turn(AlarmWheel picker, int value) {
    android.os.Bundle args=new android.os.Bundle();
    args.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);
    assertTrue(picker.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args));
  }

  @Test public void wheelConfirmsCrossMidnightOnlyAfterSelection() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    int[] saved = {-1, -1};
    Dialog d = TimeWheelDialog.show(activity, 420, 425, true, false,
        (start, end) -> { saved[0] = start; saved[1] = end; });
    AlarmWheel hours = d.findViewById(TimeWheelDialog.HOURS);
    AlarmWheel minutes = d.findViewById(TimeWheelDialog.MINUTES);
    assertEquals(0, hours.getMinValue()); assertEquals(23, hours.getMaxValue());
    assertEquals(0, minutes.getMinValue()); assertEquals(59, minutes.getMaxValue());
    assertEquals(7,hours.getValue());
    assertEquals(0,minutes.getValue());
    turn(hours, 23); turn(minutes, 58);
    assertEquals(23,hours.getValue());
    assertEquals(58,minutes.getValue());
    d.findViewById(TimeWheelDialog.END).performClick();
    turn(hours, 0); turn(minutes, 5);
    assertEquals(-1, saved[0]);
    d.findViewById(TimeWheelDialog.CONFIRM).performClick();
    assertEquals(1438, saved[0]); assertEquals(5, saved[1]);
    assertFalse(d.isShowing());
  }

  @Test public void invalidOrCancelledWheelNeverMutatesForm() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    int[] calls = {0};
    Dialog d = TimeWheelDialog.show(activity, 420, 425, false, false, (s, e) -> calls[0]++);
    AlarmWheel minutes = d.findViewById(TimeWheelDialog.MINUTES);
    turn(minutes, 0);
    d.findViewById(TimeWheelDialog.CONFIRM).performClick();
    assertTrue(d.isShowing()); assertEquals(0, calls[0]);
    assertEquals(View.VISIBLE, d.findViewById(TimeWheelDialog.ERROR).getVisibility());
    d.findViewById(TimeWheelDialog.CANCEL).performClick();
    assertFalse(d.isShowing()); assertEquals(0, calls[0]);
  }

  @Test public void longWindowExplainsTheProblemBeforeSaving() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    int[] calls = {0};
    Dialog d = TimeWheelDialog.show(activity, 420, 425, false, false, (s, e) -> calls[0]++);
    turn((AlarmWheel)d.findViewById(TimeWheelDialog.HOURS), 10);
    d.findViewById(TimeWheelDialog.CONFIRM).performClick();
    TextView error = d.findViewById(TimeWheelDialog.ERROR);
    assertTrue(error.getText().toString().contains("2 小时"));
    assertEquals(0, calls[0]); d.dismiss();
  }

  private static void dragDialog(Dialog d,View start,int distance){
    View decor=d.getWindow().getDecorView();int width=500,height=1200;
    decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);
    int[] at=new int[2];start.getLocationOnScreen(at);float x=at[0]+Math.max(1,start.getWidth()/2),y=at[1]+Math.max(1,start.getHeight()/2);
    long t=android.os.SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0),move=MotionEvent.obtain(t,t+150,MotionEvent.ACTION_MOVE,x,y+distance,0),up=MotionEvent.obtain(t,t+300,MotionEvent.ACTION_UP,x,y+distance,0);
    d.dispatchTouchEvent(down);d.dispatchTouchEvent(move);d.dispatchTouchEvent(up);down.recycle();move.recycle();up.recycle();
  }
  @Test public void handleDragCancelsDraftWithoutSaving(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
    Dialog d=TimeWheelDialog.show(a,420,425,true,false,(s,e)->calls[0]++);
    dragDialog(d,d.findViewById(TimeWheelDialog.DRAG_HANDLE),200);
    assertFalse(d.isShowing());assertEquals(0,calls[0]);
  }

  @Test public void closingSheetCanBeInterruptedAndDismissedAgain() {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    // Robolectric does not attach Dialog views to a real WindowManager.
    View panel=new View(activity){
      @Override public boolean isAttachedToWindow(){return true;}
      @Override public boolean isLaidOut(){return true;}
    };
    YiduDialog d=new YiduDialog(activity);d.panel(panel);d.setContentView(panel);d.show();
    d.dismiss();assertTrue("exit should be pending",d.isShowing());
    assertTrue((boolean)ReflectionHelpers.getField(d,"exiting"));
    long t=android.os.SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,20,20,0);
    d.dispatchTouchEvent(down);down.recycle();assertFalse((boolean)ReflectionHelpers.getField(d,"exiting"));
    activity.finish();d.dismiss();assertFalse(d.isShowing());
  }

  private static Button findButton(View root, String text) {
    if (root instanceof Button && ((Button)root).getText().toString().equals(text)) return (Button)root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup)root;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test public void guideLinkClosesGuideAndOpensCorrectStep() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    int[] selected = {0};
    Dialog d = AppGuide.show(activity, () -> selected[0] = 1, () -> selected[0] = 2,
        () -> selected[0] = 3, () -> selected[0] = 4, () -> selected[0] = 5);
    Button b = findButton(d.getWindow().getDecorView(), "去绑定网络");
    assertNotNull(b); b.performClick();
    assertEquals(1, selected[0]); assertFalse(d.isShowing());
  }

  @Test public void condensedResultsKeepOutcomeAndDetailedEvidenceRemainsAvailable() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    activity.getSharedPreferences("background_test", 0).edit().clear().commit();
    activity.getSharedPreferences("background_test", 0).edit().putString("延迟登录自测",
        "10-25 20:58:39\nWi-Fi 网页访问验证成功；请求=7；响应=5；提交=1；耗时=501ms；结束=FINISHED"
        + "\n结果：认证成功\nAndroid=16；亮屏=false；主界面前台=false；VPN=true").commit();
    String simple = BackgroundTest.summary(activity), full = BackgroundTest.fullSummary(activity);
    assertTrue(simple.contains("提交=1")); assertTrue(simple.contains("熄屏"));
    assertTrue(simple.contains("界面：后台")); assertFalse(simple.contains("Android=16"));
    assertTrue(full.contains("Android=16")); assertTrue(full.contains("VPN=true"));
  }

  @Test public void sixThemesHaveReadableLightAndDarkColors() {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    android.content.SharedPreferences prefs=activity.getSharedPreferences(MaterialStyle.PREFS,0);
    prefs.edit().clear().commit();
    for(String palette:MaterialStyle.PALETTE_IDS)for(String mode:new String[]{"light","dark"}){
      prefs.edit().putString(MaterialStyle.PALETTE,palette).putString(MaterialStyle.APPEARANCE,mode).commit();
      MaterialStyle m=new MaterialStyle(activity);
      assertEquals(mode.equals("dark"),m.dark);
      assertTrue("accent on tint "+palette+mode,MaterialStyle.contrast(m.primary,m.primaryContainer)>=4.5);
      assertTrue("primary button "+palette+mode,MaterialStyle.contrast(m.onPrimary,m.primary)>=4.5);
      assertTrue("text "+palette+mode,MaterialStyle.contrast(m.ink,m.surface)>=4.5);
    }
    prefs.edit().clear().commit();
  }

  @Test public void appearanceChoicesAreOnlyAppliedOnConfirm() {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    activity.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    String[] result={"","",""};
    Dialog d=AppearanceSheet.show(activity,(p,a,h)->{result[0]=p;result[1]=a;result[2]=h;});
    d.findViewById(AppearanceSheet.PALETTE_BASE+1).performClick();
    d.findViewById(AppearanceSheet.MODE_BASE+2).performClick();
    d.findViewById(AppearanceSheet.HAPTIC_BASE).performClick();
    assertEquals("",result[0]);
    assertFalse(activity.getSharedPreferences(MaterialStyle.PREFS,0).contains(MaterialStyle.PALETTE));
    d.findViewById(AppearanceSheet.APPLY).performClick();
    assertEquals("pink",result[0]);assertEquals("dark",result[1]);assertEquals("off",result[2]);
  }

  @Test public void repeatedConfirmationDoesNotRepeatTheAction() {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    int[] calls={0};
    Dialog d=new YiduSheets.Builder(activity).setTitle("测试")
        .setPositiveButton("执行",(dialog,which)->calls[0]++).show();
    Button confirm=findButton(d.getWindow().getDecorView(),"执行");assertNotNull(confirm);
    confirm.performClick();confirm.performClick();assertEquals(1,calls[0]);
  }

  @Test public void dockSupportsTapAndHorizontalDragWithoutStartingWork() {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    int[] selected={-1};View content=new View(activity);
    GlassDock dock=new GlassDock(activity,new MaterialStyle(activity),content,index->selected[0]=index);
    int width=900,height=180;
    dock.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
    dock.layout(0,0,dock.getMeasuredWidth(),height);
    android.widget.LinearLayout[] tabs=ReflectionHelpers.getField(dock,"tabs");
    tabs[1].performClick();assertEquals(1,selected[0]);dock.select(1,false);
    long t=android.os.SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,100,50,0);
    MotionEvent move=MotionEvent.obtain(t,t+40,MotionEvent.ACTION_MOVE,dock.getWidth()-10,50,0);
    MotionEvent up=MotionEvent.obtain(t,t+80,MotionEvent.ACTION_UP,dock.getWidth()-10,50,0);
    dock.onInterceptTouchEvent(down);assertTrue(dock.onInterceptTouchEvent(move));
    dock.onTouchEvent(move);dock.onTouchEvent(up);
    down.recycle();move.recycle();up.recycle();assertEquals(2,selected[0]);
  }

  private static org.json.JSONObject snapshot() throws Exception {
    org.campusportal.app.Config c=new org.campusportal.app.Config();
    c.bound=true;c.gateway="192.168.31.1";c.subnet="192.168.31.0/24";
    org.json.JSONObject status=new org.json.JSONObject().put("ready",true).put("running",false)
        .put("phase","connected").put("detail","已验证").put("enabled",true)
        .put("lastCheck",1000).put("lastVerified",1000).put("next",2000);
    return new org.json.JSONObject().put("config",c.json()).put("status",status)
        .put("username","old-user").put("passwordSaved",true)
        .put("permissions",new org.json.JSONObject().put("alarm",true).put("notifications",true).put("battery",true))
        .put("logs",new org.json.JSONArray());
  }

  @Test public void replacingVisualThemePreservesUnsavedFormAndCurrentPage() throws Exception {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    android.content.SharedPreferences prefs=activity.getSharedPreferences(MaterialStyle.PREFS,0);
    prefs.edit().clear().commit();
    AppUi before=new AppUi(activity,(action,values)->{});before.receive("snapshot",snapshot());before.route(1);
    ((EditText)before.view().findViewById(AppUi.USERNAME)).setText("unsaved-user");
    ((EditText)before.view().findViewById(AppUi.PASSWORD)).setText("unsaved-password");
    AppUi.Draft draft=before.captureDraft();
    prefs.edit().putString(MaterialStyle.PALETTE,"pink").putString(MaterialStyle.APPEARANCE,"dark").commit();
    AppUi after=new AppUi(activity,(action,values)->{});after.receive("snapshot",snapshot());after.restoreDraft(draft);
    assertEquals("unsaved-user",((EditText)after.view().findViewById(AppUi.USERNAME)).getText().toString());
    assertEquals("unsaved-password",((EditText)after.view().findViewById(AppUi.PASSWORD)).getText().toString());
    assertEquals(1,after.captureDraft().page);assertEquals(420,after.captureDraft().start);
    prefs.edit().clear().commit();
  }

  @Test public void activityAppliesThemeWithoutOverwritingUnsavedCredentials() throws Exception {
    android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
    context.getSharedPreferences("campus",0).edit().clear().commit();
    context.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    org.robolectric.android.controller.ActivityController<MainActivity> controller=
        Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity=controller.get();
    AppUi before=ReflectionHelpers.getField(activity,"ui");before.route(1);
    ((EditText)activity.findViewById(AppUi.USERNAME)).setText("not-yet-saved");
    ((EditText)activity.findViewById(AppUi.PASSWORD)).setText("draft-secret");
    org.json.JSONObject request=new org.json.JSONObject().put("action","appearance")
        .put("palette","blue").put("appearance","dark").put("haptics","off");
    ReflectionHelpers.callInstanceMethod(activity,"handle",
        ReflectionHelpers.ClassParameter.from(org.json.JSONObject.class,request));
    assertEquals("not-yet-saved",((EditText)activity.findViewById(AppUi.USERNAME)).getText().toString());
    assertEquals("draft-secret",((EditText)activity.findViewById(AppUi.PASSWORD)).getText().toString());
    assertEquals("blue",context.getSharedPreferences(MaterialStyle.PREFS,0).getString(MaterialStyle.PALETTE,""));
    assertEquals(1,((AppUi)ReflectionHelpers.getField(activity,"ui")).captureDraft().page);
    controller.pause().stop().destroy();context.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
  }

  private static ScrollView findScroll(View root){
    if(root instanceof ScrollView)return (ScrollView)root;
    if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){
      ScrollView found=findScroll(g.getChildAt(i));if(found!=null)return found;}}
    return null;
  }

  @Test public void guideCanPullDownAtTopWithoutInvokingAnAction(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
    Dialog d=AppGuide.show(a,()->calls[0]++,()->{},()->{},()->{},()->{});
    Button link=findButton(d.getWindow().getDecorView(),"去绑定网络");
    dragDialog(d,link,200);assertFalse(d.isShowing());assertEquals(0,calls[0]);
  }

  @Test public void alarmWheelDragWrapsAndAccessibilityCanAdjustIt(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    AlarmWheel w=new AlarmWheel(a,new MaterialStyle(a),59,"分钟");
    w.measure(View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(250,View.MeasureSpec.EXACTLY));w.layout(0,0,160,250);
    w.setValue(59);int[] calls={0};w.setOnValueChangedListener((v,old,next)->calls[0]++);
    long t=android.os.SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,80,150,0);
    MotionEvent move=MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,80,100,0);
    MotionEvent up=MotionEvent.obtain(t,t+300,MotionEvent.ACTION_UP,80,100,0);
    w.onTouchEvent(down);w.onTouchEvent(move);w.onTouchEvent(up);
    down.recycle();move.recycle();up.recycle();assertEquals(0,w.getValue());assertEquals(1,calls[0]);
    assertTrue(w.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));
    assertEquals(59,w.getValue());assertTrue(w.getContentDescription().toString().contains("59"));
    turn(w,27);assertEquals(27,w.getValue());
  }

  @Test public void timeConfirmationIsOnlyDispatchedOnce(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
    Dialog d=TimeWheelDialog.show(a,420,425,true,false,(s,e)->calls[0]++);
    View confirm=d.findViewById(TimeWheelDialog.CONFIRM);confirm.performClick();confirm.performClick();assertEquals(1,calls[0]);
  }

  @Test public void defaultIsBlueAndCardsAreOpaqueInBothModes(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    android.content.SharedPreferences p=a.getSharedPreferences(MaterialStyle.PREFS,0);p.edit().clear().commit();
    assertEquals(2,new MaterialStyle(a).paletteIndex);
    p.edit().putString(MaterialStyle.PALETTE,"invalid").commit();
    assertEquals(2,new MaterialStyle(a).paletteIndex);
    Dialog theme=AppearanceSheet.show(a,(color,mode,feel)->{});
    assertTrue(theme.findViewById(AppearanceSheet.PALETTE_BASE+2).isSelected());theme.dismiss();p.edit().remove(MaterialStyle.PALETTE).commit();
    for(String mode:new String[]{"light","dark"}){
      p.edit().putString(MaterialStyle.APPEARANCE,mode).commit();MaterialStyle m=new MaterialStyle(a);
      assertEquals(255,android.graphics.Color.alpha(m.cardSurface(24).getColor().getDefaultColor()));
      assertEquals(255,android.graphics.Color.alpha(m.hero().getColor().getDefaultColor()));
    }
    p.edit().putString(MaterialStyle.PALETTE,"pink").commit();assertEquals(1,new MaterialStyle(a).paletteIndex);p.edit().clear().commit();
  }

  @Test public void guideScrollHasOneClippedViewportAndNoScrollBar(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    Dialog d=AppGuide.show(a,()->{},()->{},()->{},()->{},()->{});
    ScrollView scroll=findScroll(d.getWindow().getDecorView());assertNotNull(scroll);
    assertFalse(scroll.isVerticalScrollBarEnabled());assertFalse(scroll.isVerticalFadingEdgeEnabled());
    assertTrue(scroll.getClipToPadding());assertTrue(((ViewGroup)scroll.getParent()).getClipToOutline());
    d.dismiss();
  }

  @Test public void simplifiedScreenHasNoSaveBarAndContentContinuesBehindDock()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});
    a.setContentView(ui.view());ui.receive("snapshot",snapshot());ui.route(1);View shell=ui.view();
    shell.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(740,View.MeasureSpec.EXACTLY));shell.layout(0,0,393,740);
    assertNull(shell.findViewById(AppUi.SAVE));assertNull(shell.findViewById(AppUi.SAVE_TEST));
    assertNull(findButton(shell,"保存设置"));assertNull(findButton(shell,"保存并测试"));
    ScrollView[] pages=ReflectionHelpers.getField(ui,"pages");assertFalse(pages[1].getClipToPadding());assertEquals(740,pages[1].getBottom()+((View)pages[1].getParent()).getTop());
    ui.setBottomSafeInset(24);android.widget.FrameLayout.LayoutParams p=(android.widget.FrameLayout.LayoutParams)shell.findViewById(GlassDock.DOCK_ID).getLayoutParams();
    assertTrue(p.bottomMargin>=24);ui.setKeyboardVisible(true);assertEquals(View.GONE,shell.findViewById(GlassDock.DOCK_ID).getVisibility());ui.dispose();
  }

  @Test @Config(qualifiers="w320dp-h568dp") public void largeFontTimePickerCanReachConfirmOnSmallScreen(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());config.fontScale=1.6f;
    a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());
    int[] saved={0};Dialog d=TimeWheelDialog.show(a,420,425,true,false,(s,e)->saved[0]++);
    View decor=d.getWindow().getDecorView();android.view.WindowManager.LayoutParams p=d.getWindow().getAttributes();
    decor.measure(View.MeasureSpec.makeMeasureSpec(p.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(p.height,View.MeasureSpec.EXACTLY));decor.layout(0,0,p.width,p.height);
    ScrollView scroll=findScroll(decor);scroll.scrollTo(0,Math.max(0,scroll.getChildAt(0).getHeight()-scroll.getHeight()));
    View confirm=d.findViewById(TimeWheelDialog.CONFIRM);
    android.graphics.Rect rect=new android.graphics.Rect(0,0,confirm.getWidth(),confirm.getHeight());
    scroll.offsetDescendantRectToMyCoords(confirm,rect);
    rect.offset(-scroll.getScrollX(),-scroll.getScrollY());
    assertTrue("confirm should have height",rect.height()>0);
    assertTrue("confirm top "+rect+" scroll height "+scroll.getHeight(),rect.top>=0);
    assertTrue("confirm bottom "+rect+" scroll height "+scroll.getHeight(),rect.bottom<=scroll.getHeight());
    confirm.performClick();assertEquals(1,saved[0]);
  }

  @Test public void interruptedWheelKeepsSelectedValueAndDoesNotApplyCancelledDraft(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
    Dialog d=TimeWheelDialog.show(a,420,425,true,false,(s,e)->calls[0]++);
    AlarmWheel hours=d.findViewById(TimeWheelDialog.HOURS);turn(hours,8);
    d.findViewById(TimeWheelDialog.END).performClick();assertEquals(7,hours.getValue());
    d.findViewById(TimeWheelDialog.START).performClick();assertEquals(8,hours.getValue());
    d.findViewById(TimeWheelDialog.CANCEL).performClick();assertEquals(0,calls[0]);
  }

  @Test public void repeatedAppearanceApplyIsDispatchedOnce(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
    Dialog d=AppearanceSheet.show(a,(p,m,h)->calls[0]++);View apply=d.findViewById(AppearanceSheet.APPLY);
    apply.performClick();apply.performClick();assertEquals(1,calls[0]);
  }

  @Test public void autosaveDebouncesAndIncompleteAccountNeverReplacesSavedAccount()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    java.util.List<org.json.JSONObject> writes=new java.util.ArrayList<>();
    AppUi ui=new AppUi(a,(action,values)->{if(action.equals("save"))writes.add(values);});ui.receive("snapshot",snapshot());
    EditText user=ui.view().findViewById(AppUi.USERNAME),pass=ui.view().findViewById(AppUi.PASSWORD);
    user.setText("new-user");
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(950,java.util.concurrent.TimeUnit.MILLISECONDS);
    assertEquals("half an account must not trigger replacement",0,writes.size());
    pass.setText("new-");pass.setText("new-secret");
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(500,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(0,writes.size());
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(500,java.util.concurrent.TimeUnit.MILLISECONDS);
    assertEquals(1,writes.size());assertTrue(writes.get(0).getBoolean("automatic"));
    assertEquals("new-user",writes.get(0).getString("username"));assertEquals("new-secret",writes.get(0).getString("password"));ui.dispose();
  }

  @Test public void leavingSettingsFlushesConfigAndBackgroundTaskDefersWrites()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();java.util.List<org.json.JSONObject> writes=new java.util.ArrayList<>();
    AppUi ui=new AppUi(a,(action,values)->{if(action.equals("save"))writes.add(values);});org.json.JSONObject snap=snapshot();ui.receive("snapshot",snap);ui.route(1);
    EditText label=ReflectionHelpers.getField(ui,"label");label.setText("我的宿舍");ui.route(0);
    assertEquals(1,writes.size());assertEquals("我的宿舍",writes.get(0).getJSONObject("config").getString("label"));
    ui.receive("autoSaved",true);writes.clear();snap.getJSONObject("status").put("running",true);ui.receive("snapshot",snap);
    label.setText("任务后保存");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1000,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(0,writes.size());
    snap.getJSONObject("status").put("running",false);ui.receive("snapshot",snap);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1000,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(1,writes.size());ui.dispose();
  }

  @Test public void removedFeaturesAndPageIntroductionsAreAbsent()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});ui.receive("snapshot",snapshot());
    String labels=allText(ui.view());assertFalse(labels.contains("CAMPUS WI-FI"));assertFalse(labels.contains("YOUR CONNECTION"));assertFalse(labels.contains("MAKE IT YOURS"));
    assertFalse(labels.contains("高级配置"));assertFalse(labels.contains("登录网址"));assertFalse(labels.contains("保存并测试"));ui.dispose();
  }
  private static String allText(View v){
    String result=v instanceof TextView?((TextView)v).getText().toString()+"\n":"";
    if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)result+=allText(g.getChildAt(i));}return result;
  }

  private static String dayKey(long stamp) {
    return org.campusportal.app.core.LogHistory.today(stamp).toString();
  }
  private static org.json.JSONObject record(long stamp,String text)throws Exception {
    return new org.json.JSONObject().put("timestamp",stamp).put("time",AppStore.format(stamp,true)).put("text",text);
  }
  private static View logView(AppUi ui,String type,String key) {
    return ui.view().findViewWithTag("log-"+type+":"+key);
  }

  @Test public void recordsGroupByDayAndOnlyDateHeadersToggleFullSelectableText()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});
    long now=System.currentTimeMillis(),old=now-86400000L;
    org.json.JSONObject snap=snapshot();snap.getJSONArray("logs").put(record(old,"完整记录\n第二行")).put(record(now,"今天的记录"));
    ui.receive("snapshot",snap);LinearLayout logs=ReflectionHelpers.getField(ui,"logs");
    assertEquals(2,logs.getChildCount());assertEquals("log-day:"+dayKey(now),logs.getChildAt(0).getTag());
    LinearLayout today=(LinearLayout)logView(ui,"content",dayKey(now));
    LinearLayout history=(LinearLayout)logView(ui,"content",dayKey(old));
    assertEquals(View.VISIBLE,today.getVisibility());assertEquals(View.GONE,history.getVisibility());
    assertEquals(0,history.getChildCount());
    logView(ui,"header",dayKey(old)).performClick();
    assertEquals(View.VISIBLE,history.getVisibility());
    TextView detail=(TextView)((LinearLayout)history.getChildAt(0)).getChildAt(1);
    assertEquals("完整记录\n第二行",detail.getText().toString());assertTrue(detail.isTextSelectable());
    detail.performClick();assertEquals(View.VISIBLE,history.getVisibility());
    assertNull(ui.view().findViewById(2020));assertNull(ui.view().findViewById(2021));ui.dispose();
  }

  @Test public void collapsedDaySurvivesNewLogsPageSwitchAndThemeRebuild()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});
    long now=System.currentTimeMillis();String key=dayKey(now);
    org.json.JSONObject snap=snapshot();snap.getJSONArray("logs").put(record(now,"第一条"));ui.receive("snapshot",snap);
    logView(ui,"header",key).performClick();ui.route(1);ui.route(2);
    snap.getJSONArray("logs").put(record(now+1,"第二条"));ui.receive("snapshot",snap);
    assertEquals(View.GONE,logView(ui,"content",key).getVisibility());
    assertTrue(allText(logView(ui,"header",key)).contains("2条"));
    AppUi.Draft draft=ui.captureDraft();ui.dispose();
    AppUi next=new AppUi(a,(action,extra)->{});next.receive("snapshot",snap);next.restoreDraft(draft);
    assertEquals(View.GONE,logView(next,"content",key).getVisibility());
    logView(next,"header",key).performClick();
    assertTrue(allText(logView(next,"content",key)).contains("第二条"));next.dispose();
  }

  @Test public void appendingLogsKeepsExistingSelectableViewsAndShowsMoreThanTwoHundredRows()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});
    long now=System.currentTimeMillis();org.json.JSONObject snap=snapshot();
    for(int i=0;i<205;i++)snap.getJSONArray("logs").put(record(now+i,"记录"+i));
    ui.receive("snapshot",snap);LinearLayout body=(LinearLayout)logView(ui,"content",dayKey(now));
    View first=body.getChildAt(0);assertTrue(allText(body).contains("记录204"));
    snap.getJSONArray("logs").put(record(now+206,"新记录"));ui.receive("snapshot",snap);
    assertSame(first,body.getChildAt(0));assertTrue(allText(body).contains("新记录"));ui.dispose();
  }

  @Test public void authorContactCopiesTheExactRequestedEmail()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();AppUi ui=new AppUi(a,(action,extra)->{});ui.route(1);
    View contact=ui.view().findViewById(AppUi.AUTHOR_CONTACT);assertNotNull(contact);contact.performClick();
    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)a.getSystemService(Activity.CLIPBOARD_SERVICE);
    assertEquals("2042322026@qq.com",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    assertEquals("邮箱已复制",org.robolectric.shadows.ShadowToast.getTextOfLatestToast());ui.dispose();
  }

  @Test public void readingAndExportingLogsPermanentlyPruneExpiredDays()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    long now=System.currentTimeMillis();long cutoff=org.campusportal.app.core.LogHistory.today(now).minusDays(29)
        .atStartOfDay().toInstant(org.campusportal.app.core.LogHistory.ZONE).toEpochMilli();
    org.json.JSONArray fixture=new org.json.JSONArray().put(record(cutoff-1,"expired-fixture"))
        .put(record(cutoff,"oldest-retained-fixture")).put(record(now,"today-fixture"));
    android.content.SharedPreferences prefs=a.getSharedPreferences("campus",0);prefs.edit().putString("logs",fixture.toString()).commit();
    AppStore store=new AppStore(a);assertEquals(2,store.logs().length());
    assertEquals(2,new org.json.JSONArray(prefs.getString("logs","[]")).length());
    store.log("new-fixture");assertEquals(3,store.logs().length());
    String exported=store.exportLogs();assertFalse(exported.contains("expired-fixture"));
    assertTrue(exported.contains("oldest-retained-fixture"));assertTrue(exported.contains("today-fixture"));
    assertTrue(store.logs().getJSONObject(2).getLong("timestamp")>0);
  }

  @Test public void dayToggleRespectsDisabledAnimationsAndRapidRepeatedTaps()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().putBoolean("animations",false).commit();
    AppUi ui=new AppUi(a,(action,extra)->{});long now=System.currentTimeMillis();
    org.json.JSONObject snap=snapshot();snap.getJSONArray("logs").put(record(now,"fixture"));ui.receive("snapshot",snap);
    ui.route(2);a.setContentView(ui.view());ui.view().measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));ui.view().layout(0,0,1080,1920);
    for(int i=0;i<21;i++)logView(ui,"header",dayKey(now)).performClick();
    View content=logView(ui,"content",dayKey(now));assertEquals(View.GONE,content.getVisibility());
    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,content.getLayoutParams().height);
    logView(ui,"header",dayKey(now)).performClick();assertEquals(View.VISIBLE,content.getVisibility());
    ui.dispose();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
  }

  @Test public void animatedDayToggleSettlesAfterInterruption()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().putBoolean("animations",true).commit();
    AppUi ui=new AppUi(a,(action,extra)->{});long now=System.currentTimeMillis();
    org.json.JSONObject snap=snapshot();snap.getJSONArray("logs").put(record(now,"fixture"));ui.receive("snapshot",snap);
    ui.route(2);a.setContentView(ui.view());ui.view().measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));ui.view().layout(0,0,786,1704);
    View header=logView(ui,"header",dayKey(now)),content=logView(ui,"content",dayKey(now));
    header.performClick();header.performClick();header.performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,java.util.concurrent.TimeUnit.MILLISECONDS);
    assertEquals(View.GONE,content.getVisibility());assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,content.getLayoutParams().height);
    header.performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,java.util.concurrent.TimeUnit.MILLISECONDS);
    assertEquals(View.VISIBLE,content.getVisibility());assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,content.getLayoutParams().height);
    ui.dispose();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
  }

  @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Config(sdk=35,manifest=Config.NONE,qualifiers="w393dp-h852dp-xhdpi")
  public void lightAndDarkHistoryAndContactLayoutsFitAboveDock()throws Exception{
    java.io.File output=new java.io.File("build/history-qa");output.mkdirs();
    for(String mode:new String[]{"light","dark"}){
      Activity a=Robolectric.buildActivity(Activity.class).setup().get();
      a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().putString(MaterialStyle.APPEARANCE,mode).putBoolean("animations",false).commit();
      AppUi ui=new AppUi(a,(action,extra)->{});long now=System.currentTimeMillis();org.json.JSONObject snap=snapshot();
      for(int i=5;i>=1;i--)snap.getJSONArray("logs").put(record(now-i*86400000L,"校园网检测已完成"));
      snap.getJSONArray("logs").put(record(now-3000,"定时检测开始"))
          .put(record(now-2000,"发现校园网认证页，正在确认网络状态"))
          .put(record(now-1000,"认证成功，Wi-Fi 的 HTTPS 访问已验证"));
      ui.receive("snapshot",snap);ui.route(2);a.setContentView(ui.view());layoutPhone(ui.view());
      savePhone(ui.view(),new java.io.File(output,"history-"+mode+".png"));
      assertTrue(allText(ui.view()).contains("今天"));
      ui.route(1);layoutPhone(ui.view());ScrollView[] pages=ReflectionHelpers.getField(ui,"pages");
      pages[1].scrollTo(0,pages[1].getChildAt(0).getHeight());
      android.graphics.Rect contact=new android.graphics.Rect(),dock=new android.graphics.Rect();
      assertTrue(ui.view().findViewById(AppUi.AUTHOR_CONTACT).getGlobalVisibleRect(contact));
      assertTrue(ui.view().findViewById(GlassDock.DOCK_ID).getGlobalVisibleRect(dock));
      assertTrue("email target is above navigation",contact.bottom<=dock.top);
      savePhone(ui.view(),new java.io.File(output,"contact-"+mode+".png"));
      ui.dispose();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    }
  }

  private static void layoutPhone(View root){
    root.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));
    root.layout(0,0,786,1704);
  }
  private static void savePhone(View root,java.io.File file)throws Exception{
    android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(786,1704,android.graphics.Bitmap.Config.ARGB_8888);
    root.draw(new android.graphics.Canvas(image));
    try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    image.recycle();
  }

  @Test public void animationSwitchOnlyAppliesOnConfirmation(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    Dialog d=AppearanceSheet.show(a,(p,m,h)->{});((android.widget.Switch)d.findViewById(AppearanceSheet.ANIMATIONS)).setChecked(false);d.dismiss();
    assertTrue(YiduMotion.animations());d=AppearanceSheet.show(a,(p,m,h)->{});
    ((android.widget.Switch)d.findViewById(AppearanceSheet.ANIMATIONS)).setChecked(false);d.findViewById(AppearanceSheet.APPLY).performClick();assertFalse(YiduMotion.animations());
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();assertTrue(YiduMotion.animations());
  }

  @Test public void draggingWheelOrScrollableContentDoesNotDismissSheet(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();Dialog d=TimeWheelDialog.show(a,420,425,true,false,(s,e)->{});
    AlarmWheel wheel=d.findViewById(TimeWheelDialog.HOURS);assertFalse(YiduDialog.dragAllowed(wheel,d.getWindow().getDecorView()));
    dragDialog(d,wheel,200);assertTrue(d.isShowing());d.dismiss();
    ScrollView scroll=new ScrollView(a){@Override public boolean canScrollVertically(int direction){return direction<0;}};
    TextView text=new TextView(a);scroll.addView(text);assertFalse(YiduDialog.dragAllowed(text,scroll));
    HorizontalScrollView horizontal=new HorizontalScrollView(a);View item=new View(a);horizontal.addView(item);assertFalse(YiduDialog.dragAllowed(item,horizontal));
  }

  @Test public void timeSheetMovesItsOnlyOpaqueBackground(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();YiduDialog d=(YiduDialog)TimeWheelDialog.show(a,420,425,true,false,(s,e)->{});
    View moving=ReflectionHelpers.getField(d,"panel");assertTrue(moving instanceof ScrollView);assertNotNull(moving.getBackground());
    assertNull(((ScrollView)moving).getChildAt(0).getBackground());d.dismiss();
  }

  @Test public void activityAutosavesOnLeavingAndDoesNotRewriteUnchangedConfig()throws Exception{
    android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();context.getSharedPreferences("campus",0).edit().clear().commit();
    org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity a=c.get();AppUi ui=ReflectionHelpers.getField(a,"ui");
    EditText label=ReflectionHelpers.getField(ui,"label");label.setText("自动保存测试");
    ((EditText)ui.view().findViewById(AppUi.USERNAME)).setText("unfinished");ui.flushAutoSave();
    AppStore store=new AppStore(a);assertEquals("自动保存测试",store.config().label);assertFalse(store.credentialsReady());
    long revision=store.revision();ui.flushAutoSave();assertEquals(revision,store.revision());
    assertEquals("unfinished",((EditText)ui.view().findViewById(AppUi.USERNAME)).getText().toString());
    label.setText("离开时保存");c.pause();assertEquals("离开时保存",store.config().label);c.stop().destroy();
  }

  @Test public void failedAutosaveWaitsForNewInputInsteadOfRetryingEverySnapshot()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};AppUi[] holder={null};org.json.JSONObject snap=snapshot();
    holder[0]=new AppUi(a,(action,values)->{if(action.equals("save")){calls[0]++;holder[0].receive("error","保存失败");}});
    AppUi ui=holder[0];ui.receive("snapshot",snap);EditText label=ReflectionHelpers.getField(ui,"label");label.setText("A");
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1000,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(1,calls[0]);
    ui.receive("snapshot",snap);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(2000,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(1,calls[0]);
    label.setText("B");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1000,java.util.concurrent.TimeUnit.MILLISECONDS);assertEquals(2,calls[0]);ui.dispose();
  }

  @Test public void dimAndSurfaceReverseTogetherAndReplacementNeverStacksWindows(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    View panel=new View(a){@Override public boolean isAttachedToWindow(){return true;}@Override public boolean isLaidOut(){return true;}};
    panel.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));panel.layout(0,0,300,400);
    YiduDialog d=new YiduDialog(a);d.panel(panel);d.setContentView(panel);d.show();d.enter();panel.getViewTreeObserver().dispatchOnPreDraw();
    android.animation.ValueAnimator entrance=ReflectionHelpers.getField(d,"motion");assertNotNull(entrance);entrance.setCurrentFraction(.4f);
    float before=d.getWindow().getAttributes().dimAmount;assertTrue("dim="+before,before>0&&before<.24f);assertTrue(panel.getTranslationY()>0);
    d.dismiss();assertEquals(before,d.getWindow().getAttributes().dimAmount,.001f);
    android.animation.ValueAnimator exit=ReflectionHelpers.getField(d,"motion");assertNotNull(exit);exit.end();assertFalse(d.isShowing());
    Dialog first=new YiduSheets.Builder(a).setTitle("前一张").show();
    Dialog second=new YiduSheets.Builder(a).setTitle("后一张").show();assertFalse(first.isShowing());assertTrue(second.isShowing());second.dismiss();
  }

  @Test public void intervalUsesThemedSheetAndPreservesImportedValue()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();java.util.List<org.json.JSONObject> writes=new java.util.ArrayList<>();
    AppUi ui=new AppUi(a,(action,value)->{if(action.equals("save"))writes.add(value);});org.json.JSONObject snap=snapshot();snap.getJSONObject("config").put("interval",45);ui.receive("snapshot",snap);
    android.widget.Spinner interval=ui.view().findViewById(AppUi.INTERVAL);assertEquals(5,interval.getCount());interval.performClick();
    Dialog d=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(d instanceof YiduDialog);
    findButton(d.getWindow().getDecorView(),"每 120 秒").performClick();ui.flushAutoSave();
    assertEquals(120,writes.get(writes.size()-1).getJSONObject("config").getInt("interval"));
    interval.performClick();d=org.robolectric.shadows.ShadowDialog.getLatestDialog();findButton(d.getWindow().getDecorView(),"取消").performClick();assertTrue(interval.getSelectedItem().toString().contains("120"));ui.dispose();
  }

  @Test public void pageSwitchSlidesHorizontallyAndDisabledAnimationsSwitchDirectly()throws Exception{
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    AppUi ui=new AppUi(a,(action,value)->{});a.setContentView(ui.view());ui.receive("snapshot",snapshot());View shell=ui.view();
    shell.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(740,View.MeasureSpec.EXACTLY));shell.layout(0,0,393,740);
    ScrollView[] pages=ReflectionHelpers.getField(ui,"pages");ui.route(1);assertTrue(pages[1].getTranslationX()>0);assertEquals(1,pages[1].getAlpha(),0);assertEquals(0,pages[1].getTranslationY(),0);
    ui.route(2);ui.route(0);
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().putBoolean("animations",false).commit();ui.route(2);
    for(int n=0;n<3;n++){assertEquals(n==2?View.VISIBLE:View.GONE,pages[n].getVisibility());assertEquals(0,pages[n].getTranslationX(),0);}
    ui.dispose();a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
  }
}
