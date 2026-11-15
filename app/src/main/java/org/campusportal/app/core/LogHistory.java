package org.campusportal.app.core;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Beijing calendar days; legacy MM-dd records are normalized once, without discarding text. */
public final class LogHistory {
  private LogHistory() {}
  public static final int DAYS = 30;
  public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
  public static final String UNKNOWN = "!unknown";

  public static LocalDate today(long now) { return Instant.ofEpochMilli(now).atOffset(ZONE).toLocalDate(); }

  public static long timestamp(JSONObject record, long now) {
    long stored = record.optLong("timestamp", -1);
    if (stored > 0) return stored;
    String time = record.optString("time", "");
    if (!time.matches("\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?")) return -1;
    LocalDate day = today(now);
    try {
      int month = Integer.parseInt(time.substring(0, 2)), date = Integer.parseInt(time.substring(3, 5));
      LocalTime hour = time.length() == 5 ? LocalTime.MIDNIGHT : LocalTime.parse(time.substring(6));
      for (int year = day.getYear(); year >= day.getYear() - 4; year--) {
        try {
          LocalDate candidate = LocalDate.of(year, month, date);
          if (!candidate.isAfter(day)) return candidate.atTime(hour).toInstant(ZONE).toEpochMilli();
        } catch (DateTimeException invalidDate) { /* Includes leap days in non-leap years. */ }
      }
    } catch (RuntimeException invalid) { /* Keep undatable legacy data under its own heading. */ }
    return -1;
  }

  public static JSONArray retain(JSONArray records, long now) throws org.json.JSONException {
    long cutoff = today(now).minusDays(DAYS - 1).atStartOfDay().toInstant(ZONE).toEpochMilli();
    JSONArray kept = new JSONArray();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.optJSONObject(i);
      if (record == null) continue;
      long stamp = timestamp(record, now);
      if (stamp >= 0 && stamp < cutoff) continue;
      if (stamp > 0 && record.optLong("timestamp", -1) <= 0) record.put("timestamp", stamp);
      kept.put(record);
    }
    return kept;
  }

  public static final class Day {
    public final String key;
    public final List<JSONObject> records = new ArrayList<>();
    Day(String key) { this.key = key; }
    public String title(long now) {
      if (UNKNOWN.equals(key)) return "日期不详";
      LocalDate date = LocalDate.parse(key), current = today(now);
      String label = (date.getYear() == current.getYear() ? "" : date.getYear() + "年")
          + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
      return (date.equals(current) ? "今天 · " : date.equals(current.minusDays(1)) ? "昨天 · " : "") + label;
    }
  }

  public static List<Day> group(JSONArray records, long now) {
    Map<String, Day> days = new TreeMap<>(Collections.reverseOrder());
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.optJSONObject(i);
      if (record == null) continue;
      long stamp = timestamp(record, now);
      String key = stamp < 0 ? UNKNOWN : today(stamp).toString();
      Day day = days.get(key);
      if (day == null) { day = new Day(key); days.put(key, day); }
      day.records.add(record);
    }
    return new ArrayList<>(days.values());
  }

  public static String timeLabel(JSONObject record, long now) {
    String original = record.optString("time", "");
    if (original.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) return original.substring(6);
    long stamp = timestamp(record, now);
    return stamp < 0 || original.length() == 5 ? original
        : Instant.ofEpochMilli(stamp).atOffset(ZONE).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
  }
}
