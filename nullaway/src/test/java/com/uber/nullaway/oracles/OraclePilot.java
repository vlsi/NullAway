package com.uber.nullaway.oracles;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import com.uber.nullaway.oracles.Compiler.Outcome;
import com.uber.nullaway.oracles.Compiler.Status;
import com.uber.nullaway.oracles.Compiler.Warning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Runs NullAway over seeds taken from its own tests and over variants of them, and reports where
 * one of two oracles fails.
 *
 * <ul>
 *   <li><b>Crash.</b> NullAway must not throw on any source javac accepts. Inputs are the seeds
 *       and {@link AnnotationMutator} variants of them.
 *   <li><b>Metamorphic pairs.</b> Two compilations that differ only in a way Java gives no meaning
 *       must yield the same NullAway warnings. The pairs are a seed against each {@link
 *       Relation} rewrite of it, and one unit compiled with the rest of its seed as source against
 *       the same unit compiled with the rest as class files. Warnings compare by unit, line, and
 *       message, with the parts of the message a rewrite may change masked out.
 * </ul>
 *
 * <p>Findings are deduplicated and written under the output directory, with the sources that
 * reproduce each one and a summary in {@code report.md}. A crash is deduplicated by its exception
 * class and its first NullAway stack frame, and the reproduction kept is the smallest seen.
 *
 * <p>Arguments, all optional: {@code --tests <dir>} (default {@code src/test/java}), {@code
 * --seeds-dir <dir>} (one seed per subdirectory, read by {@link SeedExtractor#fromDirectory} in
 * place of the test seeds), {@code --null-marked true} (adds {@code @NullMarked} to every top-level
 * type of every seed before any oracle runs), {@code --out
 * <dir>} (default {@code build/oracle-pilot}), {@code --filter <substring of the seed name>},
 * {@code --limit <seeds>}, {@code --mutants <variants per seed and configuration>} (default 3),
 * {@code --max-mutations <edits per variant>} (default 3), {@code --random-seed <long>}, {@code
 * --configs <comma-separated names from CONFIGS>}, {@code --relations <comma-separated relation
 * names>} (default all), {@code --max-minutes <wall-clock budget>}.
 */
public final class OraclePilot {

  /** NullAway options for each configuration a seed runs under, keyed by a name for the report. */
  static final ImmutableMap<String, ImmutableList<String>> CONFIGS =
      ImmutableMap.of(
          "legacy",
          ImmutableList.of(
              "-XepOpt:NullAway:AnnotatedPackages=com.uber",
              "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.[a-zA-Z0-9.]+.unannotated"),
          "jspecify",
          ImmutableList.of(
              "-XepOpt:NullAway:AnnotatedPackages=com.uber",
              "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.[a-zA-Z0-9.]+.unannotated",
              "-XepOpt:NullAway:JSpecifyMode=true"),
          "jspecify-nullmarked",
          ImmutableList.of(
              "-XepOpt:NullAway:OnlyNullMarked=true", "-XepOpt:NullAway:JSpecifyMode=true"));

  private static final Pattern EXCEPTION_CLASS =
      Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*(?:Exception|Error))\\b");
  /** The {@code (line N)} NullAway puts after a field it names. */
  private static final Pattern LINE_REFERENCE = Pattern.compile("\\(line \\d+\\)");

  /**
   * The overridden method a parameter warning names. NullAway calls it a functional interface
   * method for a lambda and a superclass method for a class, and prints its signature differently.
   */
  private static final Pattern OVERRIDDEN =
      Pattern.compile("parameter in (?:functional interface|superclass) method .*? is @");

  /**
   * A span NullAway quotes, such as an expression or a field name. A multi-line expression is cut
   * at the end of the first line, which {@link Warning#message()} keeps, so the closing quote may
   * be missing.
   */
  private static final Pattern QUOTED = Pattern.compile("'[^']*(?:'|$)");

  /** The metamorphic relations, besides compiling one unit against the others' class files. */
  private static final ImmutableList<Relation> RELATIONS =
      ImmutableList.<Relation>builder()
          .addAll(SyntacticRelations.all())
          .addAll(AttributedRelations.all())
          .build();

  private static final Pattern NULLAWAY_FRAME = Pattern.compile("at (com\\.uber\\.nullaway\\.\\S+)");

  private final Compiler compiler = new Compiler();
  private final Path out;
  private final Map<String, Crash> crashes = new TreeMap<>();
  private final List<Divergence> divergences = new ArrayList<>();
  private final Map<String, Integer> counters = new TreeMap<>();

  /** Wall-clock milliseconds spent in NullAway runs, by the kind of input. */
  private final Map<String, Long> millis = new TreeMap<>();

  /**
   * The smallest reproduction seen for one crash signature.
   *
   * @param signature exception class and first NullAway frame
   * @param config the configuration the reproduction ran under
   * @param origin the seed name, plus the mutations or the relation that produced the input
   * @param sources the input
   * @param detail the stack trace
   * @param occurrences how many inputs crashed with this signature
   */
  private record Crash(
      String signature,
      String config,
      String origin,
      ImmutableList<Source> sources,
      String detail,
      int occurrences) {}

  /**
   * Two compilations of one metamorphic pair that NullAway reported differently.
   *
   * @param relation which pair
   * @param config the configuration both ran under
   * @param seed the seed name
   * @param left the first compilation's sources
   * @param right the second compilation's sources
   * @param onlyLeft warnings only the first compilation reported, rendered for the report
   * @param onlyRight warnings only the second compilation reported, rendered for the report
   */
  private record Divergence(
      String relation,
      String config,
      String seed,
      ImmutableList<Source> left,
      ImmutableList<Source> right,
      ImmutableList<String> onlyLeft,
      ImmutableList<String> onlyRight) {}

  private OraclePilot(Path out) {
    this.out = out;
  }

  /** Runs the pilot with the arguments the class comment lists. */
  public static void main(String[] argv) throws IOException {
    Map<String, String> args = new LinkedHashMap<>();
    for (int i = 0; i + 1 < argv.length; i += 2) {
      args.put(argv[i], argv[i + 1]);
    }
    Path tests = Path.of(args.getOrDefault("--tests", "src/test/java"));
    Path out = Path.of(args.getOrDefault("--out", "build/oracle-pilot"));
    String filter = args.getOrDefault("--filter", "");
    int limit = Integer.parseInt(args.getOrDefault("--limit", "" + Integer.MAX_VALUE));
    int mutants = Integer.parseInt(args.getOrDefault("--mutants", "3"));
    int maxMutations = Integer.parseInt(args.getOrDefault("--max-mutations", "3"));
    long randomSeed = Long.parseLong(args.getOrDefault("--random-seed", "1"));
    List<String> configs = List.of(args.getOrDefault("--configs", String.join(",", CONFIGS.keySet())).split(","));
    String relationNames = args.getOrDefault("--relations", "");
    ImmutableList<Relation> relations =
        RELATIONS.stream()
            .filter(r -> relationNames.isEmpty() || List.of(relationNames.split(",")).contains(r.name()))
            .collect(ImmutableList.toImmutableList());
    Duration budget = Duration.ofMinutes(Long.parseLong(args.getOrDefault("--max-minutes", "60")));

    String seedsDir = args.get("--seeds-dir");
    boolean nullMarked = Boolean.parseBoolean(args.getOrDefault("--null-marked", "false"));
    ImmutableList<Seed> seeds =
        (seedsDir == null ? SeedExtractor.extract(tests) : SeedExtractor.fromDirectory(Path.of(seedsDir)))
            .stream()
            .filter(s -> s.name().contains(filter))
            .limit(limit)
            .map(s -> nullMarked ? AnnotationMutator.nullMarked(s) : s)
            .collect(ImmutableList.toImmutableList());
    System.out.println(
        "Seeds: " + seeds.size() + ", configurations: " + configs + ", JDK " + Runtime.version());

    OraclePilot pilot = new OraclePilot(out);
    Random random = new Random(randomSeed);
    Instant deadline = Instant.now().plus(budget);
    int done = 0;
    for (Seed seed : seeds) {
      if (Instant.now().isAfter(deadline)) {
        System.out.println("Stopped at the time budget after " + done + " seeds");
        break;
      }
      ImmutableList<Mutation> candidates = AnnotationMutator.candidates(seed);
      Map<Relation, ImmutableList<ImmutableList<Source>>> variants = new LinkedHashMap<>();
      for (Relation relation : relations) {
        long started = System.nanoTime();
        variants.put(relation, relation.variants(seed));
        pilot.millis.merge(
            relation.name() + " rewrite", (System.nanoTime() - started) / 1_000_000, Long::sum);
      }
      for (String config : configs) {
        ImmutableList<String> options = CONFIGS.get(config);
        if (options == null) {
          throw new IllegalArgumentException("Unknown configuration: " + config);
        }
        pilot.check(seed, config, options, candidates, variants, random, mutants, maxMutations);
      }
      if (++done % 25 == 0) {
        System.out.println(done + "/" + seeds.size() + " " + pilot.counters + ", crashes: " + pilot.crashes.size() + ", divergences: " + pilot.divergences.size());
      }
    }
    pilot.writeReport();
    System.out.println("Counters: " + pilot.counters);
    System.out.println("Milliseconds: " + pilot.millis);
    System.out.println("Crash signatures: " + pilot.crashes.size() + ", divergences: " + pilot.divergences.size());
    System.out.println("Report: " + out.toAbsolutePath().resolve("report.md"));
  }

  /** Runs every oracle over {@code seed} under one configuration. */
  private void check(
      Seed seed,
      String config,
      ImmutableList<String> options,
      List<Mutation> candidates,
      Map<Relation, ImmutableList<ImmutableList<Source>>> variants,
      Random random,
      int mutants,
      int maxMutations) {
    Outcome baseline = run("seed", seed.name(), config, options, seed.sources(), null);
    if (baseline.status() == Status.OK) {
      variants.forEach(
          (relation, list) -> checkRelation(relation, list, seed, config, options, baseline));
      checkBytecode(seed, config, options, baseline);
    }
    for (int i = 0; i < mutants; i++) {
      Variant variant = AnnotationMutator.mutate(seed, candidates, random, maxMutations);
      if (variant == null) {
        break;
      }
      String origin =
          seed.name()
              + " with "
              + String.join("; ", variant.mutations().stream().map(Mutation::description).toList());
      run("mutant", origin, config, options, variant.sources(), null);
    }
  }

  /**
   * Compares {@code baseline} with a run over the first variant of {@code relation} that javac
   * accepts, trying the variants in order.
   */
  private void checkRelation(
      Relation relation,
      ImmutableList<ImmutableList<Source>> variants,
      Seed seed,
      String config,
      List<String> options,
      Outcome baseline) {
    if (variants.isEmpty()) {
      count(relation.name() + ": not applicable");
      return;
    }
    for (ImmutableList<Source> variant : variants) {
      Outcome outcome =
          run(relation.name(), seed.name() + " rewritten by " + relation.name(), config, options, variant, null);
      if (outcome.status() == Status.OK) {
        compare(
            relation.name(),
            config,
            seed.name(),
            seed.sources(),
            variant,
            keys(baseline.warnings()),
            keys(outcome.warnings()));
        return;
      }
      if (outcome.status() == Status.CRASH) {
        return;
      }
    }
  }

  /**
   * For each unit of a seed with several, compares its warnings in {@code baseline} with a run over
   * that unit alone, against the other units compiled to class files.
   */
  private void checkBytecode(Seed seed, String config, List<String> options, Outcome baseline) {
    if (seed.sources().size() < 2) {
      return;
    }
    for (int client = 0; client < seed.sources().size(); client++) {
      Source clientSource = seed.sources().get(client);
      List<Source> library = new ArrayList<>(seed.sources());
      library.remove(client);
      Path classes = Compiler.createTempDirectory();
      try {
        long started = System.nanoTime();
        boolean libraryCompiled = compiler.compileWithoutNullAway(library, classes);
        millis.merge("bytecode library", (System.nanoTime() - started) / 1_000_000, Long::sum);
        if (!libraryCompiled) {
          count("bytecode: library does not compile alone");
          continue;
        }
        ImmutableList<Source> clientOnly = ImmutableList.of(clientSource);
        Outcome outcome =
            run("bytecode", seed.name() + " compiled against class files", config, options, clientOnly, classes);
        if (outcome.status() == Status.OK) {
          compare(
              "bytecode",
              config,
              seed.name(),
              seed.sources(),
              clientOnly,
              keys(onlyIn(clientSource.path(), baseline.warnings())),
              keys(outcome.warnings()));
        }
      } finally {
        Compiler.deleteRecursively(classes);
      }
    }
  }

  /** Runs NullAway once, counts the outcome, and records it if NullAway crashed. */
  private Outcome run(
      String kind,
      String origin,
      String config,
      List<String> options,
      ImmutableList<Source> sources,
      @Nullable Path classes) {
    long started = System.nanoTime();
    Outcome outcome = compiler.runNullAway(sources, options, classes);
    millis.merge(kind, (System.nanoTime() - started) / 1_000_000, Long::sum);
    count(kind + ": " + outcome.status());
    counters.merge(kind + ": warnings", outcome.warnings().size(), Integer::sum);
    if (outcome.status() == Status.CRASH) {
      recordCrash(config, origin, sources, outcome.detail());
    }
    return outcome;
  }

  private void recordCrash(String config, String origin, ImmutableList<Source> sources, String detail) {
    Matcher exception = EXCEPTION_CLASS.matcher(detail.substring(Math.max(0, detail.indexOf("Stack Trace:"))));
    Matcher frame = NULLAWAY_FRAME.matcher(detail);
    String signature =
        (exception.find() ? exception.group(1) : "unknown exception")
            + " at "
            + (frame.find() ? frame.group(1) : "no NullAway frame");
    Crash previous = crashes.get(signature);
    int occurrences = previous == null ? 1 : previous.occurrences() + 1;
    if (previous == null || size(sources) < size(previous.sources())) {
      crashes.put(signature, new Crash(signature, config, origin, sources, detail, occurrences));
    } else {
      crashes.put(signature, new Crash(previous.signature(), previous.config(), previous.origin(), previous.sources(), previous.detail(), occurrences));
    }
  }

  private void compare(
      String relation,
      String config,
      String seed,
      ImmutableList<Source> left,
      ImmutableList<Source> right,
      Multiset<String> leftWarnings,
      Multiset<String> rightWarnings) {
    if (leftWarnings.equals(rightWarnings)) {
      count(relation + ": agree");
      return;
    }
    count(relation + ": DIVERGE");
    divergences.add(
        new Divergence(
            relation,
            config,
            seed,
            left,
            right,
            ImmutableList.copyOf(Multisets.difference(leftWarnings, rightWarnings)),
            ImmutableList.copyOf(Multisets.difference(rightWarnings, leftWarnings))));
  }

  /**
   * Keys each warning by its unit, its line, and its message with every quoted span replaced by
   * {@code '_'}, every {@code (line N)} by {@code (line _)}, and the name of an overridden method
   * by {@code _}. A rewrite may change an expression
   * NullAway quotes, such as {@code 'x'} becoming {@code '(x)'}, without changing the warning.
   */
  private static Multiset<String> keys(List<Warning> warnings) {
    Multiset<String> result = HashMultiset.create();
    for (Warning w : warnings) {
      String message = OVERRIDDEN.matcher(w.message()).replaceAll("parameter in overridden method _ is @");
      message = QUOTED.matcher(message).replaceAll("'_'");
      message = LINE_REFERENCE.matcher(message).replaceAll("(line _)");
      result.add(w.path() + ":" + w.line() + " | " + message);
    }
    return result;
  }

  private static ImmutableList<Warning> onlyIn(String path, List<Warning> warnings) {
    return warnings.stream().filter(w -> w.path().equals(path)).collect(ImmutableList.toImmutableList());
  }

  private static int size(List<Source> sources) {
    return sources.stream().mapToInt(s -> s.text().length()).sum();
  }

  private void count(String key) {
    counters.merge(key, 1, Integer::sum);
  }

  /** Writes {@code report.md} and one directory per finding under the output directory. */
  private void writeReport() throws IOException {
    if (Files.exists(out)) {
      Compiler.deleteRecursively(out);
    }
    Files.createDirectories(out);
    StringBuilder report =
        new StringBuilder("# NullAway oracle pilot\n\nJDK " + Runtime.version() + "\n\n## Counters\n\n");
    counters.forEach((k, v) -> report.append("- ").append(k).append(": ").append(v).append('\n'));

    report.append("\n## Crashes\n\n");
    int index = 0;
    for (Crash crash : crashes.values()) {
      Path dir = out.resolve("crash-" + ++index);
      writeSources(dir.resolve("src"), crash.sources());
      Files.writeString(
          dir.resolve("README.md"),
          "# " + crash.signature() + "\n\nConfiguration: `" + crash.config() + "` "
              + CONFIGS.get(crash.config()) + "\n\nInputs with this signature: " + crash.occurrences()
              + "\n\nSmallest input: " + crash.origin() + "\n\n```text\n" + crash.detail() + "\n```\n");
      report.append("- [").append(crash.signature()).append("](").append(dir.getFileName())
          .append("/README.md), ").append(crash.occurrences()).append(" inputs, config `")
          .append(crash.config()).append("`\n");
    }

    report.append("\n## Divergences\n\n");
    index = 0;
    for (Divergence d : divergences) {
      Path dir = out.resolve(d.relation() + "-" + ++index);
      writeSources(dir.resolve("left"), d.left());
      writeSources(dir.resolve("right"), d.right());
      String body =
          "# " + d.relation() + ": " + d.seed() + "\n\nConfiguration: `" + d.config() + "` "
              + CONFIGS.get(d.config()) + "\n\nOnly in `left`:\n\n" + bullets(d.onlyLeft())
              + "\nOnly in `right`:\n\n" + bullets(d.onlyRight());
      Files.writeString(dir.resolve("README.md"), body);
      report.append("- [").append(d.relation()).append(" ").append(d.seed()).append("](")
          .append(dir.getFileName()).append("/README.md), config `").append(d.config())
          .append("`: ").append(d.onlyLeft().size()).append(" left only, ")
          .append(d.onlyRight().size()).append(" right only\n");
    }
    Files.writeString(out.resolve("report.md"), report);
  }

  private static String bullets(List<String> lines) {
    StringBuilder result = new StringBuilder();
    lines.forEach(l -> result.append("- `").append(l).append("`\n"));
    return result.length() == 0 ? "- nothing\n" : result.toString();
  }

  private static void writeSources(Path dir, List<Source> sources) throws IOException {
    for (Source s : sources) {
      Path file = dir.resolve(s.path());
      Files.createDirectories(file.getParent());
      Files.writeString(file, s.text());
    }
  }
}
