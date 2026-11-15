package org.campusportal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/** Native palette and opaque surface roles derived from TransRead 0.5.48. */
final class MaterialStyle {
  static final int CARD_RADIUS = 34;
  static final String PREFS = "campus_ui", PALETTE = "palette", APPEARANCE = "appearance", HAPTICS = "haptics";
  static final String[] PALETTE_IDS = {"sage", "pink", "blue", "brown", "purple", "gray"};
  static final String[] PALETTE_NAMES = {"鼠尾草绿", "雾樱粉", "雾霾蓝", "烟熏茶棕", "石墨紫", "月岩灰"};
  static final int[] ACCENTS = {0xff526b50, 0xff9b5269, 0xff4a6985, 0xff82614d, 0xff706080, 0xff62666c};
  static final int[] PAPERS = {0xfff4f2e9, 0xfff8f0f1, 0xfff0f3f6, 0xfff5f0e9, 0xfff2f0f5, 0xfff2f2f1};
  static final int[] DARK_PAPERS = {0xff1b211c, 0xff261d22, 0xff1b222a, 0xff26211d, 0xff221f28, 0xff202124};
  final Context context;
  final boolean dark;
  final int paletteIndex;
  final int surface,
      containerLow,
      container,
      containerHigh,
      primary,
      onPrimary,
      primaryContainer,
      onPrimaryContainer,
      ink,
      muted,
      outline,
      outlineVariant,
      success,
      successContainer,
      error,
      errorContainer;

  MaterialStyle(Context context) {
    this.context = context;
    YiduMotion.configure(context);
    android.content.SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String chosen = p.getString(PALETTE, "blue"), appearance = p.getString(APPEARANCE, "system");
    int index = java.util.Arrays.asList(PALETTE_IDS).indexOf(chosen);
    paletteIndex = index < 0 ? 2 : index;
    boolean systemDark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    dark = "dark".equals(appearance) || (!"light".equals(appearance) && systemDark);
    surface = dark ? DARK_PAPERS[paletteIndex] : PAPERS[paletteIndex];
    containerLow = mix(surface, 0xffffffff, dark ? .035f : .25f);
    container = mix(surface, 0xffffffff, dark ? .055f : .42f);
    containerHigh = mix(surface, 0xffffffff, dark ? .055f : .58f);
    ink = dark ? 0xfff3f3f4 : 0xff242426;
    muted = mix(ink, surface, .28f);
    outline = mix(ink, surface, .58f);
    outlineVariant = mix(ink, surface, .86f);
    int accent = dark ? mix(ACCENTS[paletteIndex], 0xffffffff, .48f) : ACCENTS[paletteIndex];
    for (int i = 0; i < 20 && contrast(accent, mix(surface,accent,dark ? .24f : .11f)) < 4.5; i++)
      accent = mix(accent, ink, .13f);
    primary = accent;
    onPrimary = contrast(0xffffffff, primary) >= contrast(0xff152018, primary) ? 0xffffffff : 0xff152018;
    primaryContainer = mix(surface, primary, dark ? .24f : .11f);
    onPrimaryContainer = primary;
    success = dark ? 0xff8fd7a6 : 0xff146c2e;
    successContainer = dark ? 0xff123824 : 0xffc4eed0;
    error = dark ? 0xfff2b8b5 : 0xffb3261e;
    errorContainer = dark ? 0xff601410 : 0xfff9dedc;
  }

  static int mix(int a, int b, float t) {
    return Color.rgb(Math.round(Color.red(a) * (1-t) + Color.red(b) * t),
        Math.round(Color.green(a) * (1-t) + Color.green(b) * t),
        Math.round(Color.blue(a) * (1-t) + Color.blue(b) * t));
  }

  static double contrast(int a, int b) {
    double x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
  }

  private static double luminance(int c) {
    double[] v = {Color.red(c)/255d, Color.green(c)/255d, Color.blue(c)/255d};
    for (int i=0; i<3; i++) v[i] = v[i] <= .04045 ? v[i]/12.92 : Math.pow((v[i]+.055)/1.055, 2.4);
    return v[0]*.2126 + v[1]*.7152 + v[2]*.0722;
  }

  GradientDrawable cardSurface(float radius) {
    return shape(containerHigh, outlineVariant, CARD_RADIUS);
  }

  GradientDrawable hero() {
    return shape(mix(surface, primary, dark ? .16f : .09f), outlineVariant, CARD_RADIUS);
  }

  int dp(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int fill, int stroke, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  Drawable ripple(int fill, int stroke, float radius, int tint) {
    return new RippleDrawable(
        ColorStateList.valueOf((tint & 0x00ffffff) | 0x20000000),
        shape(fill, stroke, radius),
        shape(Color.WHITE, 0, radius));
  }

  ColorStateList textStates(int normal) {
    return new ColorStateList(
        new int[][] {{-android.R.attr.state_enabled}, {}},
        new int[] {(ink & 0x00ffffff) | 0x61000000, normal});
  }

  Drawable input() {
    GradientDrawable focus = shape(containerHigh, primary, 4);
    focus.setStroke(dp(2), primary);
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {android.R.attr.state_focused}, focus);
    states.addState(new int[] {}, shape(containerHigh, outline, 4));
    return states;
  }

  Drawable switchThumb() {
    StateListDrawable states = new StateListDrawable();
    GradientDrawable on = shape(onPrimary, 0, 100);
    on.setSize(dp(24), dp(24));
    GradientDrawable off = shape(outline, 0, 100);
    off.setSize(dp(24), dp(24));
    states.addState(new int[] {android.R.attr.state_checked}, on);
    states.addState(new int[] {}, off);
    // Switch gives its thumb the full track height; the inset preserves a circular thumb.
    return new InsetDrawable(states, 0, dp(4), 0, dp(4));
  }

  Drawable switchTrack() {
    StateListDrawable states = new StateListDrawable();
    GradientDrawable on = shape(primary, 0, 100);
    on.setSize(dp(52), dp(32));
    GradientDrawable off = shape(container, outline, 100);
    off.setSize(dp(52), dp(32));
    states.addState(new int[] {android.R.attr.state_checked}, on);
    states.addState(new int[] {}, off);
    return states;
  }

  Drawable icon(String name, int color) {
    return new Symbol(name, color, dp(24));
  }

  CharSequence iconLabel(String label, String symbol, int color) {
    SpannableString text = new SpannableString("\uFFFC" + label);
    Drawable drawable = icon(symbol, color);
    drawable.setBounds(0, 0, dp(24), dp(24));
    text.setSpan(new InlineIcon(drawable, dp(8)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return text;
  }

  /** An inline icon keeps the complete icon/label group centered, including full-width buttons. */
  private static final class InlineIcon extends ReplacementSpan {
    final Drawable drawable;
    final int gap;

    InlineIcon(Drawable drawable, int gap) {
      this.drawable = drawable;
      this.gap = gap;
    }

    @Override
    public int getSize(
        Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
      if (fm != null) {
        Paint.FontMetricsInt base = paint.getFontMetricsInt();
        int center = (base.ascent + base.descent) / 2, half = drawable.getBounds().height() / 2;
        fm.ascent = Math.min(base.ascent, center - half);
        fm.descent = Math.max(base.descent, center + half);
        fm.top = Math.min(base.top, fm.ascent);
        fm.bottom = Math.max(base.bottom, fm.descent);
      }
      return drawable.getBounds().width() + gap;
    }

    @Override
    public void draw(
        Canvas canvas,
        CharSequence text,
        int start,
        int end,
        float x,
        int top,
        int y,
        int bottom,
        Paint paint) {
      Paint.FontMetricsInt fm = paint.getFontMetricsInt();
      canvas.save();
      canvas.translate(x, y + (fm.ascent + fm.descent) / 2f - drawable.getBounds().height() / 2f);
      drawable.setAlpha(paint.getAlpha());
      drawable.draw(canvas);
      canvas.restore();
    }
  }

  /** Small original symbols on the Material 24-unit grid, not text/emoji glyphs. */
  private static final class Symbol extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    private final int size;

    Symbol(String name, int color, int size) {
      this.name = name;
      this.size = size;
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.8f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void lines(Canvas c, float... p) {
      Path path = new Path();
      path.moveTo(p[0], p[1]);
      for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
      c.drawPath(path, paint);
    }

    private void box(Canvas c, float l, float t, float r, float b, float radius) {
      c.drawRoundRect(l, t, r, b, radius, radius, paint);
    }

    @Override
    public void draw(Canvas c) {
      Rect b = getBounds();
      c.save();
      c.translate(b.left, b.top);
      c.scale(b.width() / 24f, b.height() / 24f);
      switch (name) {
        case "palette":
          Path palette=new Path();palette.moveTo(12,3);
          palette.cubicTo(7,3,3,7,3,12);palette.cubicTo(3,17,7,21,12,21);
          palette.cubicTo(14.5f,21,15.5f,19,14,17.5f);
          palette.cubicTo(12.5f,16,13.5f,14,15.5f,14);palette.lineTo(18,14);
          palette.cubicTo(22.5f,14,22,3,12,3);palette.close();c.drawPath(palette,paint);
          paint.setStyle(Paint.Style.FILL);
          c.drawCircle(7.5f,11,1.1f,paint);c.drawCircle(10,7.5f,1.1f,paint);c.drawCircle(15,7.5f,1.1f,paint);
          paint.setStyle(Paint.Style.STROKE);break;
        case "wifi":
          c.drawArc(1, 4, 23, 26, 225, 90, false, paint);
          c.drawArc(5, 9, 19, 23, 225, 90, false, paint);
          paint.setStyle(Paint.Style.FILL);
          c.drawCircle(12, 18, 1.7f, paint);
          paint.setStyle(Paint.Style.STROKE);
          break;
        case "home":
          lines(c, 3, 10, 12, 3, 21, 10);
          lines(c, 5, 9, 5, 21, 10, 21, 10, 15, 14, 15, 14, 21, 19, 21, 19, 9);
          break;
        case "settings":
          lines(c, 4, 6, 20, 6);
          lines(c, 4, 12, 20, 12);
          lines(c, 4, 18, 20, 18);
          box(c, 8, 3, 11, 9, 1);
          box(c, 15, 9, 18, 15, 1);
          box(c, 6, 15, 9, 21, 1);
          break;
        case "history":
          c.drawArc(4, 4, 21, 21, -90, 295, false, paint);
          lines(c, 3, 4, 3, 9, 8, 9);
          lines(c, 12, 7, 12, 13, 16, 15);
          break;
        case "clock":
        case "alarm":
          c.drawCircle(12, 12, 8.5f, paint);
          lines(c, 12, 7, 12, 12, 15.5f, 14);
          if (name.equals("alarm")) {
            lines(c, 4, 2, 1, 5);
            lines(c, 20, 2, 23, 5);
          }
          break;
        case "check":
          lines(c, 5, 12, 10, 17, 20, 7);
          break;
        case "chevron":
          lines(c, 9, 5, 16, 12, 9, 19);
          break;
        case "down":
          lines(c, 6, 9, 12, 15, 18, 9);
          break;
        case "arrow":
          lines(c, 4, 12, 20, 12);
          lines(c, 14, 6, 20, 12, 14, 18);
          break;
        case "stop":
          box(c, 6, 6, 18, 18, 2);
          break;
        case "shield":
          Path p = new Path();
          p.moveTo(12, 2);
          p.lineTo(20, 5);
          p.lineTo(20, 11);
          p.quadTo(20, 18, 12, 22);
          p.quadTo(4, 18, 4, 11);
          p.lineTo(4, 5);
          p.close();
          c.drawPath(p, paint);
          lines(c, 8, 12, 11, 15, 16, 9);
          break;
        case "lock":
          box(c, 5, 10, 19, 21, 2);
          c.drawArc(8, 2, 16, 16, 180, 180, false, paint);
          c.drawCircle(12, 15, 1, paint);
          break;
        case "bell":
          lines(c, 4, 18, 6, 15, 6, 9);
          c.drawArc(6, 3, 18, 15, 180, 180, false, paint);
          lines(c, 18, 9, 18, 15, 20, 18, 4, 18);
          c.drawArc(10, 17, 14, 23, 0, 180, false, paint);
          break;
        case "battery":
          box(c, 6, 4, 18, 22, 2);
          lines(c, 10, 2, 14, 2);
          lines(c, 13, 7, 10, 13, 14, 13, 11, 19);
          break;
        case "download":
          lines(c, 12, 3, 12, 16);
          lines(c, 7, 11, 12, 16, 17, 11);
          lines(c, 4, 17, 4, 21, 20, 21, 20, 17);
          break;
        case "upload":
          lines(c, 12, 17, 12, 4);
          lines(c, 7, 9, 12, 4, 17, 9);
          lines(c, 4, 17, 4, 21, 20, 21, 20, 17);
          break;
        case "code":
          lines(c, 8, 6, 2, 12, 8, 18);
          lines(c, 16, 6, 22, 12, 16, 18);
          lines(c, 14, 4, 10, 20);
          break;
        case "eye":
          Path eye = new Path();
          eye.moveTo(2, 12);
          eye.quadTo(12, -1, 22, 12);
          eye.quadTo(12, 25, 2, 12);
          c.drawPath(eye, paint);
          c.drawCircle(12, 12, 3, paint);
          break;
        case "eye_off":
          c.drawArc(2, 5, 22, 19, 10, 160, false, paint);
          c.drawArc(2, 5, 22, 19, 195, 130, false, paint);
          lines(c, 3, 3, 21, 21);
          break;
        case "info":
          c.drawCircle(12, 12, 9, paint);
          lines(c, 12, 11, 12, 17);
          c.drawPoint(12, 7, paint);
          break;
        case "network":
          box(c, 3, 14, 21, 21, 2);
          lines(c, 7, 14, 7, 7);
          lines(c, 17, 14, 17, 7);
          c.drawCircle(8, 18, 0.5f, paint);
          lines(c, 14, 18, 17, 18);
          break;
        case "refresh":
          c.drawArc(4, 4, 20, 20, 35, 290, false, paint);
          lines(c, 20, 4, 20, 10, 14, 10);
          break;
        case "delete":
          lines(c, 4, 6, 20, 6);
          lines(c, 9, 3, 15, 3);
          lines(c, 6, 6, 7, 21, 17, 21, 18, 6);
          lines(c, 10, 10, 10, 17);
          lines(c, 14, 10, 14, 17);
          break;
        case "error":
          c.drawCircle(12, 12, 9, paint);
          lines(c, 12, 7, 12, 13);
          c.drawPoint(12, 17, paint);
          break;
        default:
          c.drawCircle(12, 12, 8, paint);
      }
      c.restore();
    }

    @Override
    public int getIntrinsicWidth() {
      return size;
    }

    @Override
    public int getIntrinsicHeight() {
      return size;
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
      paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
