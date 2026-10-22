package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import com.uber.nullaway.oracles.CombinationGenerator.Row;
import com.uber.nullaway.oracles.FuzzSupport.NullAwayCrash;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Runs the crash oracle of the fuzz targets without coverage guidance, as {@link OraclePilot}
 * does with {@code --relations none}, over the configurations of {@link FuzzSupport#CONFIGS}.
 *
 * <p>Every seed is compiled once and then as {@code --mutants} variants (default 3) of up to three
 * mutations each, drawn from a generator seeded with 1 out of the candidates of the chosen
 * mutators. Arguments, all optional:
 *
 * <ul>
 *   <li>{@code --seeds <comma-separated sources>} (default {@code tests}): {@code tests} for the
 *       seeds of {@link FuzzSupport#loadSeeds()}, {@code samples:<dir>} for {@link
 *       DirectorySeeds#samples}, {@code files:<dir>} for {@link DirectorySeeds#perFile} and {@code
 *       files-marked:<dir>} for the same with null-marked variants, each under every configuration
 *       of {@code --configs}; {@code generated} for the programs of {@link
 *       CombinationGenerator#generate(long)}, each under its own configuration;
 *   <li>{@code --generator-seeds <comma-separated longs>}: one covering array per seed, for {@code
 *       --seeds generated} (default {@code 1});
 *   <li>{@code --configs <comma-separated names>} (default all), for every source but {@code
 *       generated};
 *   <li>{@code --mutants <count>};
 *   <li>{@code --mutators <comma-separated names>}: {@code annotation} for {@link
 *       AnnotationMutator}, {@code type} for {@link TypeMutator} (default {@code annotation});
 *   <li>{@code --skip <comma-separated substrings>}: seeds whose name contains one are left out,
 *       such as javac stress tests that keep javac busy for hours.
 * </ul>
 *
 * <p>A compilation that runs longer than {@value #SLOW_SECONDS} seconds is printed as {@code SLOW}
 * with its origin, so that the seed can be skipped on the next run.
 *
 * <p>Statistics and signatures go where {@link FuzzSupport} puts them.
 */
public final class RandomCrashRun {

  private static final long SLOW_SECONDS = 60;

  /** The origin of the compilation in progress, and when it started, for the slow-run watchdog. */
  private static volatile String current = "";

  private static volatile long currentStarted = System.nanoTime();

  private RandomCrashRun() {}

  /** Runs the seeds and their variants as the class comment describes. */
  public static void main(String[] argv) {
    String seedSource = "tests";
    String configNames = String.join(",", FuzzSupport.CONFIGS.keySet());
    int mutants = 3;
    List<String> mutators = List.of("annotation");
    List<String> generatorSeeds = List.of("1");
    List<String> skip = List.of();
    for (int i = 0; i + 1 < argv.length; i += 2) {
      switch (argv[i]) {
        case "--seeds" -> seedSource = argv[i + 1];
        case "--configs" -> configNames = argv[i + 1];
        case "--mutants" -> mutants = Integer.parseInt(argv[i + 1]);
        case "--mutators" -> mutators = List.of(argv[i + 1].split(","));
        case "--generator-seeds" -> generatorSeeds = List.of(argv[i + 1].split(","));
        case "--skip" -> skip = List.of(argv[i + 1].split(","));
        default -> throw new IllegalArgumentException("Unknown argument: " + argv[i]);
      }
    }
    List<Run> runs = new ArrayList<>();
    for (String source : seedSource.split(",")) {
      if (source.equals("generated")) {
        for (String generatorSeed : generatorSeeds) {
          for (Row row : CombinationGenerator.generate(Long.parseLong(generatorSeed))) {
            runs.add(
                new Run(new Seed(row.toString(), ImmutableList.of(row.source())), row.config()));
          }
        }
        continue;
      }
      ImmutableList<Seed> seeds;
      if (source.equals("tests")) {
        seeds = FuzzSupport.loadSeeds();
      } else if (source.startsWith("samples:")) {
        seeds = DirectorySeeds.samples(Path.of(source.substring("samples:".length())));
      } else if (source.startsWith("files:")) {
        seeds = DirectorySeeds.perFile(Path.of(source.substring("files:".length())), false);
      } else if (source.startsWith("files-marked:")) {
        seeds = DirectorySeeds.perFile(Path.of(source.substring("files-marked:".length())), true);
      } else {
        throw new IllegalArgumentException("Unknown seed source: " + source);
      }
      for (Seed seed : seeds) {
        if (skip.stream().anyMatch(seed.name()::contains)) {
          continue;
        }
        for (String config : configNames.split(",")) {
          runs.add(new Run(seed, config));
        }
      }
    }
    System.out.println("RandomCrashRun: " + runs.size() + " seeds and configurations, mutators " + mutators);
    FuzzSupport oracle = new FuzzSupport();
    startWatchdog();
    Random random = new Random(1);
    long started = System.nanoTime();
    for (Run run : runs) {
      ImmutableList<String> options = FuzzSupport.CONFIGS.get(run.config());
      if (options == null) {
        throw new IllegalArgumentException("Unknown configuration: " + run.config());
      }
      String origin = run.seed().name() + " [" + run.config() + "]";
      check(oracle, run.seed().sources(), options, origin);
      if (mutants == 0) {
        continue;
      }
      ImmutableList<Mutation> candidates = candidates(run.seed(), mutators);
      for (int m = 0; m < mutants; m++) {
        Variant variant = AnnotationMutator.mutate(run.seed(), candidates, random, 3);
        if (variant == null) {
          break;
        }
        String mutations =
            String.join("; ", variant.mutations().stream().map(Mutation::description).toList());
        check(oracle, variant.sources(), options, origin + " with " + mutations);
      }
    }
    System.out.printf(
        "RandomCrashRun: %d seeds and configurations, %d s%n",
        runs.size(), (System.nanoTime() - started) / 1_000_000_000L);
  }

  /**
   * One seed and the configuration it runs under.
   *
   * @param seed the seed
   * @param config a key of {@link FuzzSupport#CONFIGS}
   */
  private record Run(Seed seed, String config) {}

  /** Returns the candidates of every mutator named in {@code mutators}. */
  private static ImmutableList<Mutation> candidates(Seed seed, List<String> mutators) {
    ImmutableList.Builder<Mutation> result = ImmutableList.builder();
    for (String mutator : mutators) {
      switch (mutator) {
        case "annotation" -> result.addAll(AnnotationMutator.candidates(seed));
        case "type" -> result.addAll(TypeMutator.candidates(seed));
        default -> throw new IllegalArgumentException("Unknown mutator: " + mutator);
      }
    }
    return result.build();
  }

  /** Starts a daemon thread that prints each compilation running longer than {@value #SLOW_SECONDS} s. */
  private static void startWatchdog() {
    Thread watchdog =
        new Thread(
            () -> {
              String reported = null;
              while (true) {
                try {
                  Thread.sleep(10_000);
                } catch (InterruptedException e) {
                  return;
                }
                String origin = current;
                long seconds = (System.nanoTime() - currentStarted) / 1_000_000_000L;
                if (seconds > SLOW_SECONDS && !origin.equals(reported)) {
                  System.out.println("SLOW " + seconds + " s: " + origin);
                  reported = origin;
                }
              }
            },
            "slow-compilation-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /** Runs the oracle once; a crash is already recorded, so it only goes to the console. */
  private static void check(
      FuzzSupport oracle, ImmutableList<Source> sources, List<String> options, String origin) {
    currentStarted = System.nanoTime();
    current = origin;
    try {
      oracle.check(sources, options, origin);
    } catch (NullAwayCrash e) {
      System.out.println("CRASH " + e.getMessage().lines().findFirst().orElse("") + " in " + origin);
    }
  }
}
