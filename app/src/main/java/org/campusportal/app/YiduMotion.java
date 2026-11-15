package org.campusportal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Short, interruptible press and selection feedback; system haptic settings remain authoritative. */
final class YiduMotion {
  private static long lastTick;
  private static Context appContext;
  static void configure(Context context){appContext=context.getApplicationContext();}
  static boolean animations() {
    return (appContext==null||appContext.getSharedPreferences(MaterialStyle.PREFS,Context.MODE_PRIVATE).getBoolean("animations",true))
        && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
  }
  static void tick(View view) {
    String mode = view.getContext().getSharedPreferences(MaterialStyle.PREFS, Context.MODE_PRIVATE)
        .getString(MaterialStyle.HAPTICS, "gentle");
    if ("off".equals(mode)) return;
    long now = android.os.SystemClock.uptimeMillis();
    if (now - lastTick < 60) return;
    lastTick = now;
    view.performHapticFeedback("standard".equals(mode)
        ? HapticFeedbackConstants.VIRTUAL_KEY : HapticFeedbackConstants.CLOCK_TICK);
  }
  static void press(View view) {
    view.setOnTouchListener((v, event) -> {
      if (!v.isEnabled() || !animations()) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          v.animate().cancel(); v.animate().scaleX(.965f).scaleY(.965f).setDuration(80).start(); break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          v.animate().cancel();
          v.animate().scaleX(1).scaleY(1).setDuration(260)
              .setInterpolator(new OvershootInterpolator(.65f)).start(); break;
        default: break;
      }
      return false;
    });
  }
  static void click(View view, Runnable action) {
    press(view);
    view.setOnClickListener(v -> { tick(v); action.run(); });
  }
}
