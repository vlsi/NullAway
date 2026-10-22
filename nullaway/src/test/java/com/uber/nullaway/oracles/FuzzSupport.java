package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.uber.nullaway.oracles.Compiler.Outcome;
import com.uber.nullaway.oracles.Compiler.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the crash oracle for the Jazzer fuzz targets {@link StructuredFuzzTarget} and {@link
 * RawSourceFuzzTarget}, and keeps the statistics libFuzzer does not.
 *
 * <p>A compilation javac rejects returns normally. A NullAway crash throws {@link NullAwayCrash},
 * whose stack trace holds only the crash signature, so that Jazzer's deduplication token, which
 * hashes the exception class and the whole stack trace, separates inputs by signature alone. The
 * signature is the exception class and the first NullAway frame of the last cause, where {@link
 * OraclePilot} takes the outermost trace.
 *
 * <p>Every statistic goes under the directory the {@code nullaway.fuzz.out} system property names:
 * {@code stats.tsv} gains a line of outcome counts every {@value #STATS_INTERVAL_SECONDS} seconds,
 * and {@code signatures/} holds the smallest input seen for each crash signature. One instance
 * serves one fuzzing process, which libFuzzer drives from a single thread.
 */
final class FuzzSupport {

  private static final long STATS_INTERVAL_SECONDS = 30;

  /** The exception class in a stack trace; the same pattern {@link OraclePilot} matches. */
  private static final Pattern EXCEPTION_CLASS =
      Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*(?:Exception|Error))\\b");

  /** The first NullAway frame in a stack trace, split into class, method, file, and line. */
  private static final Pattern NULLAWAY_FRAME =
      Pattern.compile("at (com\\.uber\\.nullaway\\.[\\w$.]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)");

  /**
   * A NullAway crash, reported to Jazzer.
   *
   * <p>The stack trace is two synthetic frames, the exception class and the first NullAway frame,
   * and the message carries the original stack trace.
   */
  static final class NullAwayCrash extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NullAwayCrash(String signature, String detail, StackTraceElement[] frames) {
      super(signature + "\n" + detail, null, false, true);
      setStackTrace(frames);
    }
  }

  /**
   * NullAway options for each configuration a fuzz input may run under: those of {@link
   * OraclePilot#CONFIGS}, in its order, followed by {@code jspecify-experimental}, which adds
   * {@code JSpecifyExperimental} to JSpecify mode and so turns on wildcard handling, warnings on
   * generic inference failures, and the JSpecify JDK models.
   */
  static final ImmutableMap<String, ImmutableList<String>> CONFIGS =
      ImmutableMap.<String, ImmutableList<String>>builder()
          .putAll(OraclePilot.CONFIGS)
          .put(
              "jspecify-experimental",
              ImmutableList.of(
                  "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                  "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.[a-zA-Z0-9.]+.unannotated",
                  "-XepOpt:NullAway:JSpecifyMode=true",
                  "-XepOpt:NullAway:JSpecifyExperimental=true"))
          .buildOrThrow();

  /**
   * Returns the seeds under the directory the {@code nullaway.fuzz.tests} system property names,
   * {@code src/test/java} by default, in {@link SeedExtractor#extract} order. A structured input
   * names a seed by its index in this list, so the order has to stay the same across processes.
   */
  static ImmutableList<Seed> loadSeeds() {
    try {
      return SeedExtractor.extract(Path.of(System.getProperty("nullaway.fuzz.tests", "src/test/java")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final Compiler compiler = new Compiler();
  private final Path out;
  private final Map<Status, Long> outcomes = new EnumMap<>(Status.class);
  private final Map<String, Integer> signatureCounts = new TreeMap<>();
  private final Map<String, Integer> smallestInput = new TreeMap<>();
  private final long startNanos = System.nanoTime();
  private long lastStatsNanos = startNanos;
  private long lastStatsRuns;

  /** Creates the oracle and the output directory, keeping no statistics from an earlier run. */
  FuzzSupport() {
    out = Path.of(System.getProperty("nullaway.fuzz.out", "build/fuzz/out"));
    try {
      Files.createDirectories(out);
      Files.writeString(out.resolve("stats.tsv"), "seconds\truns\tok\tinvalid\tcrash\texec/s\n");
      if (Files.exists(out.resolve("signatures"))) {
        Compiler.deleteRecursively(out.resolve("signatures"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (Status s : Status.values()) {
      outcomes.put(s, 0L);
    }
    Runtime.getRuntime().addShutdownHook(new Thread(this::writeStats));
  }

  /**
   * Runs NullAway over {@code sources} and throws {@link NullAwayCrash} if it crashed.
   *
   * @param origin what the input was derived from, written next to the smallest reproduction of
   *     each signature
   */
  void check(ImmutableList<Source> sources, List<String> options, String origin) {
    Outcome outcome = compiler.runNullAway(sources, options, null);
    outcomes.merge(outcome.status(), 1L, Long::sum);
    if (System.nanoTime() - lastStatsNanos > STATS_INTERVAL_SECONDS * 1_000_000_000L) {
      writeStats();
    }
    if (outcome.status() != Status.CRASH) {
      return;
    }
    String detail = outcome.detail();
    String trace = detail.substring(Math.max(0, detail.indexOf("Stack Trace:")));
    // A wrapper such as dataflow's BugInCF names the frame that called the analysis; the last
    // cause names the code that failed, so two defects under one wrapper stay apart.
    String rootCause = trace.substring(Math.max(0, trace.lastIndexOf("Caused by:")));
    Matcher exception = EXCEPTION_CLASS.matcher(rootCause);
    String exceptionClass = exception.find() ? exception.group(1) : "unknown exception";
    Matcher frame = NULLAWAY_FRAME.matcher(rootCause);
    if (!frame.find(0)) {
      frame = NULLAWAY_FRAME.matcher(trace);
    }
    StackTraceElement nullAwayFrame =
        frame.find(0)
            ? new StackTraceElement(
                frame.group(1),
                frame.group(2),
                frame.group(3),
                frame.group(4) == null ? -1 : Integer.parseInt(frame.group(4)))
            : new StackTraceElement("no.NullAway", "frame", null, -1);
    String signature = exceptionClass + " at " + nullAwayFrame;
    recordSignature(signature, sources, options, origin, detail);
    throw new NullAwayCrash(
        signature,
        detail,
        new StackTraceElement[] {
          new StackTraceElement(exceptionClass, "thrown", null, -1), nullAwayFrame
        });
  }

  /** Counts {@code signature} and writes {@code sources} if they are its smallest input yet. */
  private void recordSignature(
      String signature,
      ImmutableList<Source> sources,
      List<String> options,
      String origin,
      String detail) {
    signatureCounts.merge(signature, 1, Integer::sum);
    int size = sources.stream().mapToInt(s -> s.text().length()).sum();
    Integer previous = smallestInput.get(signature);
    if (previous != null && previous <= size) {
      writeSignatureIndex();
      return;
    }
    smallestInput.put(signature, size);
    Path dir = out.resolve("signatures").resolve(Integer.toHexString(signature.hashCode()));
    try {
      if (Files.exists(dir)) {
        Compiler.deleteRecursively(dir);
      }
      for (Source s : sources) {
        Path file = dir.resolve("src").resolve(s.path());
        Files.createDirectories(file.getParent());
        Files.writeString(file, s.text());
      }
      Files.writeString(
          dir.resolve("README.md"),
          "# " + signature + "\n\nOptions: " + options + "\n\nOrigin: " + origin
              + "\n\n```text\n" + detail + "\n```\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    writeSignatureIndex();
  }

  /** Rewrites {@code signatures.tsv} with the count and smallest size of every signature. */
  private void writeSignatureIndex() {
    StringBuilder index = new StringBuilder("dir\tinputs\tsmallest chars\tsignature\n");
    signatureCounts.forEach(
        (s, n) ->
            index.append(Integer.toHexString(s.hashCode())).append('\t').append(n).append('\t')
                .append(smallestInput.get(s)).append('\t').append(s).append('\n'));
    try {
      Files.writeString(out.resolve("signatures.tsv"), index.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Appends one line of outcome counts and the execution rate since the previous line. */
  private synchronized void writeStats() {
    long now = System.nanoTime();
    long runs = outcomes.values().stream().mapToLong(Long::longValue).sum();
    double window = (now - lastStatsNanos) / 1e9;
    String line =
        String.format(
            Locale.ROOT,
            "%d\t%d\t%d\t%d\t%d\t%.1f%n",
            (now - startNanos) / 1_000_000_000L,
            runs,
            outcomes.get(Status.OK),
            outcomes.get(Status.INVALID),
            outcomes.get(Status.CRASH),
            window > 0 ? (runs - lastStatsRuns) / window : 0.0);
    lastStatsNanos = now;
    lastStatsRuns = runs;
    try {
      Files.writeString(out.resolve("stats.tsv"), line, UTF_8, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
