package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import com.uber.nullaway.oracles.FuzzSupport.NullAwayCrash;
import java.util.List;
import java.util.Random;

/**
 * Runs the crash oracle of the fuzz targets without coverage guidance, as {@link OraclePilot}
 * does with {@code --relations none}, over the configurations of {@link FuzzSupport#CONFIGS}.
 *
 * <p>Every seed is compiled once and then as {@code --mutants} variants (default 3) of up to three
 * {@link AnnotationMutator} mutations each, drawn from a generator seeded with 1. Arguments, all
 * optional: {@code --configs <comma-separated names>} (default all), {@code --mutants <count>}.
 * Statistics and signatures go where {@link FuzzSupport} puts them.
 */
public final class RandomCrashRun {

  private RandomCrashRun() {}

  /** Runs the seeds and their variants as the class comment describes. */
  public static void main(String[] argv) {
    String configNames = String.join(",", FuzzSupport.CONFIGS.keySet());
    int mutants = 3;
    for (int i = 0; i + 1 < argv.length; i += 2) {
      switch (argv[i]) {
        case "--configs" -> configNames = argv[i + 1];
        case "--mutants" -> mutants = Integer.parseInt(argv[i + 1]);
        default -> throw new IllegalArgumentException("Unknown argument: " + argv[i]);
      }
    }
    ImmutableList<Seed> seeds = FuzzSupport.loadSeeds();
    FuzzSupport oracle = new FuzzSupport();
    Random random = new Random(1);
    long started = System.nanoTime();
    for (Seed seed : seeds) {
      ImmutableList<Mutation> candidates = AnnotationMutator.candidates(seed);
      for (String config : configNames.split(",")) {
        ImmutableList<String> options = FuzzSupport.CONFIGS.get(config);
        if (options == null) {
          throw new IllegalArgumentException("Unknown configuration: " + config);
        }
        run(oracle, seed.sources(), options, seed.name() + " [" + config + "]");
        for (int m = 0; m < mutants; m++) {
          Variant variant = AnnotationMutator.mutate(seed, candidates, random, 3);
          if (variant == null) {
            break;
          }
          run(oracle, variant.sources(), options, seed.name() + " [" + config + "] mutant");
        }
      }
    }
    System.out.printf(
        "RandomCrashRun: %d seeds, %d s%n",
        seeds.size(), (System.nanoTime() - started) / 1_000_000_000L);
  }

  /** Runs the oracle once; a crash is already recorded, so it only goes to the console. */
  private static void run(
      FuzzSupport oracle, ImmutableList<Source> sources, List<String> options, String origin) {
    try {
      oracle.check(sources, options, origin);
    } catch (NullAwayCrash e) {
      System.out.println("CRASH " + e.getMessage().lines().findFirst().orElse("") + " in " + origin);
    }
  }
}
