package org.campusportal.app.core;

import java.util.concurrent.CancellationException;

/** One run owns an immutable deadline and a first-wins terminal reason. */
public final class RunControl {
  public interface Clock {
    long wall();
    long elapsed();
  }

  public enum Reason {
    NONE, FINISHED, USER, PLAN_DISABLED, DEADLINE, SYSTEM_TIMEOUT,
    START_REJECTED, SERVICE_DESTROYED, JOB_STOPPED, NETWORK_UNAVAILABLE, ERROR
  }

  private final Clock clock;
  public final long startedWall, startedElapsed, deadlineWall, deadlineElapsed;
  private Reason reason = Reason.NONE;

  public RunControl(Clock clock, long deadlineWall, long maximumMillis) {
    this.clock = clock;
    this.startedWall = clock.wall();
    this.startedElapsed = clock.elapsed();
    this.deadlineWall = deadlineWall;
    this.deadlineElapsed = startedElapsed
        + Math.max(0, Math.min(maximumMillis, deadlineWall - startedWall));
  }

  public synchronized Reason reason() { return reason; }

  public synchronized boolean stop(Reason value) {
    if (value == Reason.NONE) throw new IllegalArgumentException("Terminal reason required");
    if (reason != Reason.NONE) return false;
    reason = value;
    return true;
  }

  public long remaining() {
    return Math.max(0, Math.min(deadlineWall - clock.wall(), deadlineElapsed - clock.elapsed()));
  }

  public long duration() { return Math.max(0, clock.elapsed() - startedElapsed); }
  public long elapsed() { return clock.elapsed(); }

  public synchronized void check() {
    if (reason == Reason.NONE && remaining() <= 0) reason = Reason.DEADLINE;
    if (reason != Reason.NONE || Thread.currentThread().isInterrupted())
      throw new CancellationException(reason.name());
  }

  public synchronized int timeout(int requested) {
    check();
    return (int) Math.max(1, Math.min(requested, remaining()));
  }

  public static boolean eligible(long now, long expectedEnd, long currentEnd,
      boolean enabled, long completedEnd, long stoppedEnd) {
    return enabled && expectedEnd > now && expectedEnd == currentEnd
        && expectedEnd != completedEnd && expectedEnd != stoppedEnd;
  }

  public static boolean needsNetworkRecovery(boolean scheduled, boolean job, int requests, int responses, int posts) {
    return scheduled && !job && requests >= 2 && responses == 0 && posts == 0;
  }

  public static boolean needsRecovery(Reason reason) {
    return reason == Reason.SYSTEM_TIMEOUT || reason == Reason.START_REJECTED
        || reason == Reason.NETWORK_UNAVAILABLE;
  }
}
