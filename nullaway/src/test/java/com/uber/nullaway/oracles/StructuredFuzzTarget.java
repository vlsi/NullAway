package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A Jazzer fuzz target whose input bytes choose a seed, a configuration, and a sequence of
 * Java-aware edits, rather than spelling out source text.
 *
 * <p>An input decodes as follows, every number big-endian and reduced modulo its range:
 *
 * <ul>
 *   <li>bytes 0 and 1: the index of the seed in {@link FuzzSupport#loadSeeds()};
 *   <li>byte 2: the index of the configuration in {@link FuzzSupport#CONFIGS};
 *   <li>the rest, three bytes per operation: a kind byte and a two-byte index. A kind below
 *       {@value #ANNOTATION_KINDS} applies the {@link AnnotationMutator#candidates} mutation at that
 *       index; any other kind picks a relation of {@link #RELATIONS} and applies its variant at that
 *       index.
 * </ul>
 *
 * <p>Each operation applies to the text the previous ones produced, and the candidates and the
 * variants are computed afresh on that text, so an edit can build on an earlier one. An operation
 * with nothing to apply, such as a relation that finds no site, leaves the text as it is. Because
 * every three bytes are one operation, libFuzzer's insertion, erasure, and crossover of bytes
 * insert, erase, and splice operations. A missing byte reads as zero, and an input of three bytes or
 * fewer compiles its seed unchanged.
 */
public final class StructuredFuzzTarget {

  /** The kind bytes, out of 256, that select an annotation mutation rather than a relation. */
  static final int ANNOTATION_KINDS = 192;

  /** The rewrites an operation may apply, in the order the kind byte indexes them. */
  static final ImmutableList<Relation> RELATIONS =
      ImmutableList.<Relation>builder()
          .addAll(SyntacticRelations.all())
          .addAll(AttributedRelations.all())
          .build();

  private static final int HEADER = 3;
  private static final int OPERATION = 3;

  /**
   * Text produced by a seed and a prefix of its operations, the 20000 most recently used. Inputs
   * libFuzzer derives from one corpus entry share most of its operations, so most decodes resume
   * from a cached step instead of attributing the units again for every relation.
   */
  private static final Map<PrefixKey, Step> PREFIXES =
      new LinkedHashMap<>(1 << 14, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<PrefixKey, Step> eldest) {
          return size() > 20_000;
        }
      };

  /**
   * A seed and the bytes of a prefix of its operations; the configuration byte is not part of it,
   * since the configuration does not change the text.
   */
  private record PrefixKey(Seed seed, ByteBuffer operations) {
    static PrefixKey of(Seed seed, byte[] data, int end) {
      return new PrefixKey(seed, ByteBuffer.wrap(Arrays.copyOfRange(data, HEADER, end)));
    }
  }

  /** The exception classes a mutator has thrown so far, each printed once. */
  private static final Set<String> HARNESS_FAILURES = new HashSet<>();

  private static @Nullable FuzzSupport support;
  private static ImmutableList<Seed> seeds = ImmutableList.of();
  private static final ImmutableList<String> CONFIG_NAMES =
      FuzzSupport.CONFIGS.keySet().asList();

  private StructuredFuzzTarget() {}

  /** Loads the seeds once per process; Jazzer calls it before the first input. */
  public static void fuzzerInitialize() {
    seeds = FuzzSupport.loadSeeds();
    support = new FuzzSupport();
    System.out.println("StructuredFuzzTarget: " + seeds.size() + " seeds");
  }

  /** Runs the crash oracle over the variant {@code data} describes. */
  public static void fuzzerTestOneInput(byte[] data) {
    FuzzSupport oracle = support;
    if (oracle == null) {
      throw new IllegalStateException("fuzzerInitialize has not run");
    }
    Decoded input = decode(data, seeds);
    oracle.check(
        input.sources(), FuzzSupport.CONFIGS.get(input.config()), input.description());
  }

  /**
   * One decoded input.
   *
   * @param config a key of {@link FuzzSupport#CONFIGS}
   * @param sources the units to compile
   * @param description the seed name, the configuration, and the operations that changed the text
   */
  record Decoded(String config, ImmutableList<Source> sources, String description) {}

  /**
   * The text after a prefix of operations.
   *
   * @param sources the units
   * @param applied what each operation that changed the text did, in order
   */
  private record Step(ImmutableList<Source> sources, ImmutableList<String> applied) {}

  /** Decodes {@code data} against {@code seeds} as the class comment describes. */
  static Decoded decode(byte[] data, List<Seed> seeds) {
    int seedIndex = (byteAt(data, 0) << 8 | byteAt(data, 1)) % seeds.size();
    Seed seed = seeds.get(seedIndex);
    String config = CONFIG_NAMES.get(byteAt(data, 2) % CONFIG_NAMES.size());
    Step step = new Step(seed.sources(), ImmutableList.of());
    int end = HEADER;
    for (int e = data.length - (data.length - HEADER) % OPERATION; e > HEADER; e -= OPERATION) {
      Step cached = PREFIXES.get(PrefixKey.of(seed, data, e));
      if (cached != null) {
        step = cached;
        end = e;
        break;
      }
    }
    for (; end + OPERATION <= data.length; end += OPERATION) {
      int index = (data[end + 1] & 0xff) << 8 | (data[end + 2] & 0xff);
      step = applySafely(seed.name(), step, data[end] & 0xff, index);
      PREFIXES.put(PrefixKey.of(seed, data, end + OPERATION), step);
    }
    String description = seed.name() + " [" + config + "]";
    if (!step.applied().isEmpty()) {
      description += " with " + String.join("; ", step.applied());
    }
    return new Decoded(config, step.sources(), description);
  }

  /**
   * Applies one operation as {@link #apply} does, but leaves {@code step} unchanged when a mutator
   * throws on text an earlier operation garbled. Such a throw is a defect of the harness, not of
   * NullAway, so the first one of each exception class is printed and the input still runs.
   */
  private static Step applySafely(String name, Step step, int kind, int index) {
    try {
      return apply(name, step, kind, index);
    } catch (RuntimeException e) {
      if (HARNESS_FAILURES.add(e.getClass().getName())) {
        System.err.println("StructuredFuzzTarget: operation " + kind + "/" + index + " on " + name + " threw");
        e.printStackTrace();
      }
      return step;
    }
  }

  /** Applies one operation to {@code step}, or returns it unchanged if the operation has no site. */
  private static Step apply(String name, Step step, int kind, int index) {
    Seed current = new Seed(name, step.sources());
    if (kind < ANNOTATION_KINDS) {
      ImmutableList<Mutation> candidates = AnnotationMutator.candidates(current);
      if (candidates.isEmpty()) {
        return step;
      }
      Mutation mutation = candidates.get(index % candidates.size());
      List<Source> sources = new ArrayList<>(step.sources());
      Source unit = sources.get(mutation.unitIndex());
      sources.set(
          mutation.unitIndex(),
          unit.withText(TextEdit.apply(unit.text(), List.of(mutation.edit()))));
      return new Step(ImmutableList.copyOf(sources), append(step.applied(), mutation.description()));
    }
    Relation relation = RELATIONS.get((kind - ANNOTATION_KINDS) % RELATIONS.size());
    ImmutableList<ImmutableList<Source>> variants = relation.variants(current);
    if (variants.isEmpty()) {
      return step;
    }
    int chosen = index % variants.size();
    return new Step(variants.get(chosen), append(step.applied(), relation.name() + " #" + chosen));
  }

  private static ImmutableList<String> append(ImmutableList<String> list, String element) {
    return ImmutableList.<String>builder().addAll(list).add(element).build();
  }

  private static int byteAt(byte[] data, int index) {
    return index < data.length ? data[index] & 0xff : 0;
  }
}
