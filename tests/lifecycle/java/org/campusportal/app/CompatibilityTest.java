package org.campusportal.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=26,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CompatibilityTest {
  @Test public void androidEightCanConstructNativeInterfaceAndBottomCards(){
    Activity a=Robolectric.buildActivity(Activity.class).setup().get();
    a.getSharedPreferences(MaterialStyle.PREFS,0).edit().clear().commit();
    AppUi ui=new AppUi(a,(action,values)->{});a.setContentView(ui.view());
    ui.route(1);assertNotNull(ui.view().findViewById(AppUi.START));
    Dialog wheel=TimeWheelDialog.show(a,420,425,true,false,(s,e)->{});
    assertNotNull(wheel.findViewById(TimeWheelDialog.HOURS));wheel.findViewById(TimeWheelDialog.CANCEL).performClick();
    Dialog theme=AppearanceSheet.show(a,(p,mode,h)->{});
    assertNotNull(theme.findViewById(AppearanceSheet.PALETTE_BASE));theme.dismiss();
    assertEquals(View.VISIBLE,ui.view().findViewById(GlassDock.DOCK_ID).getVisibility());
  }
}
