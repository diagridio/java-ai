package io.diagrid.dapr.springai.durable.testworker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * File-based coordination between the integration test and the separate worker JVM, sharing a
 * directory passed via the {@code DSA_TEST_DIR} environment variable.
 *
 * <p>It exposes counters (incremented append-only so they survive the worker crash) and two signals
 * used to make the crash land deterministically between the completed LLM activity and the tool's
 * side effect:
 * <ul>
 *   <li>{@code readyToKill} — the tool creates this on its first attempt, just before it would do
 *       its side effect, to tell the test "kill me now";</li>
 *   <li>{@code killDone} — the test creates this after it has SIGKILLed and restarted the worker, to
 *       tell the recovered tool execution to skip waiting and perform its side effect.</li>
 * </ul>
 */
public final class CrashCoordinator {

  private final Path dir;

  public CrashCoordinator(Path dir) {
    this.dir = dir;
  }

  public static CrashCoordinator fromEnv() {
    String dir = System.getenv("DSA_TEST_DIR");
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException("DSA_TEST_DIR not set");
    }
    return new CrashCoordinator(Path.of(dir));
  }

  /** Append-only counters survive a SIGKILL because each tick is a separate line on disk. */
  public void tick(String counter) {
    try {
      Files.writeString(
          dir.resolve(counter + ".count"),
          "x\n",
          Files.exists(dir.resolve(counter + ".count"))
              ? java.nio.file.StandardOpenOption.APPEND
              : java.nio.file.StandardOpenOption.CREATE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public int count(String counter) {
    Path file = dir.resolve(counter + ".count");
    if (!Files.exists(file)) {
      return 0;
    }
    try {
      return (int) Files.readAllLines(file).stream().filter(l -> !l.isBlank()).count();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void signal(String name) {
    try {
      Files.writeString(dir.resolve(name + ".signal"), "1");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean isSignalled(String name) {
    return Files.exists(dir.resolve(name + ".signal"));
  }

  /** Blocks until the named signal appears or the timeout elapses; returns whether it appeared. */
  public boolean awaitSignal(String name, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (isSignalled(name)) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return isSignalled(name);
  }
}
