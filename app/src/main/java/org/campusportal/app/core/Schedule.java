package org.campusportal.app.core;

import java.util.Calendar;
import java.util.TimeZone;

/** Beijing wall-clock schedule. The selected day belongs to the window's start. */
public final class Schedule {
  public final int start, end, days;
  private static final TimeZone ZONE = TimeZone.getTimeZone("GMT+08:00");

  public Schedule(int start, int end, int days) {
    if (start < 0 || start >= 1440 || end < 0 || end >= 1440 || start == end)
      throw new IllegalArgumentException("开始和结束时间须不同");
    if (days < 1 || days > 127) throw new IllegalArgumentException("至少选择一天");
    if ((end - start + 1440) % 1440 > 120) throw new IllegalArgumentException("首版单次时段最长为 2 小时");
    this.start = start;
    this.end = end;
    this.days = days;
  }

  private Calendar calendar(long time) {
    Calendar c = Calendar.getInstance(ZONE);
    c.setTimeInMillis(time);
    return c;
  }

  private boolean selected(Calendar c) {
    int iso = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    return (days & (1 << iso)) != 0;
  }

  private Calendar startOn(Calendar date) {
    Calendar c = (Calendar) date.clone();
    c.set(Calendar.HOUR_OF_DAY, start / 60);
    c.set(Calendar.MINUTE, start % 60);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c;
  }

  public long windowEnd(long now) {
    Calendar day = calendar(now);
    int minute = day.get(Calendar.HOUR_OF_DAY) * 60 + day.get(Calendar.MINUTE);
    if (end > start) {
      if (minute < start || minute >= end) return -1;
    } else {
      if (minute >= start) {
        /* window anchored today */
      } else if (minute < end) day.add(Calendar.DATE, -1);
      else return -1;
    }
    if (!selected(day)) return -1;
    Calendar finish = startOn(day);
    finish.add(Calendar.MINUTE, (end - start + 1440) % 1440);
    return finish.getTimeInMillis();
  }

  public long nextStart(long now, boolean catchUp, long completedWindowEnd) {
    return nextStart(now, catchUp && windowEnd(now) != completedWindowEnd);
  }

  public long nextStart(long now, boolean catchUp) {
    long finish = windowEnd(now);
    if (catchUp && finish > now + 3000) return now + 3000;
    Calendar today = calendar(now);
    for (int d = 0; d <= 7; d++) {
      Calendar candidate = startOn(today);
      candidate.add(Calendar.DATE, d);
      if (selected(candidate) && candidate.getTimeInMillis() > now)
        return candidate.getTimeInMillis();
    }
    throw new IllegalStateException("没有可用的计划日期");
  }

  public static String time(int minutes) {
    return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
  }
}
