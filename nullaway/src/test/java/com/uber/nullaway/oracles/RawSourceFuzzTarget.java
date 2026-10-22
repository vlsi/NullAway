package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A Jazzer fuzz target whose input bytes are one Java compilation unit in UTF-8.
 *
 * <p>Every input compiles under the one configuration of {@link FuzzSupport#CONFIGS} that the
 * {@code nullaway.fuzz.config} system property names, {@code jspecify} by default. The unit's path
 * comes from its package and its first public type, so that javac accepts a public class.
 */
public final class RawSourceFuzzTarget {

  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private static final Pattern PUBLIC_TYPE =
      Pattern.compile(
          "\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed|static|strictfp)\\s+|@[\\w.]+\\s+)*"
              + "(?:class|interface|enum|record|@interface)\\s+(\\w+)");

  private static @Nullable FuzzSupport support;
  private static ImmutableList<String> options = ImmutableList.of();

  private RawSourceFuzzTarget() {}

  /** Reads the configuration once per process; Jazzer calls it before the first input. */
  public static void fuzzerInitialize() {
    String config = System.getProperty("nullaway.fuzz.config", "jspecify");
    ImmutableList<String> configOptions = FuzzSupport.CONFIGS.get(config);
    if (configOptions == null) {
      throw new IllegalArgumentException("Unknown configuration: " + config);
    }
    options = configOptions;
    support = new FuzzSupport();
    System.out.println("RawSourceFuzzTarget: configuration " + config + " " + options);
  }

  /** Runs the crash oracle over {@code data} decoded as UTF-8 source text. */
  public static void fuzzerTestOneInput(byte[] data) {
    FuzzSupport oracle = support;
    if (oracle == null) {
      throw new IllegalStateException("fuzzerInitialize has not run");
    }
    String text = new String(data, UTF_8);
    oracle.check(ImmutableList.of(new Source(pathOf(text), text)), options, "raw input");
  }

  /**
   * Returns {@code <package dirs>/<first public type>.java} for {@code text}, with {@code Test}
   * for the type name when the unit declares no public type.
   */
  static String pathOf(String text) {
    Matcher pkg = PACKAGE.matcher(text);
    Matcher type = PUBLIC_TYPE.matcher(text);
    String dir = pkg.find() ? pkg.group(1).replace('.', '/') + "/" : "";
    return dir + (type.find() ? type.group(1) : "Test") + ".java";
  }
}
