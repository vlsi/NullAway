package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the base corpora and the dictionary for the Jazzer fuzz targets.
 *
 * <p>Under the directory given as the only argument (default {@code build/fuzz}), it replaces:
 *
 * <ul>
 *   <li>{@code corpus-structured/}: for {@link StructuredFuzzTarget}, one three-byte input per
 *       seed and configuration, which compiles that seed unchanged;
 *   <li>{@code corpus-raw/}: for {@link RawSourceFuzzTarget}, the source text of every seed with a
 *       single unit;
 *   <li>{@code java.dict}: a libFuzzer dictionary of Java keywords and nullness annotation names.
 * </ul>
 */
public final class FuzzCorpus {

  private static final ImmutableList<String> DICTIONARY =
      ImmutableList.of(
          "abstract", "boolean", "break", "case", "catch", "class", "default", "do", "else",
          "enum", "extends", "final", "for", "if", "implements", "import", "instanceof", "int",
          "interface", "new", "null", "package", "private", "protected", "public", "record",
          "return", "static", "super", "switch", "this", "throw", "throws", "try", "var", "void",
          "while", "yield", "?", "? extends ", "? super ", "<", ">", "[]", "->", "::", "@Nullable ",
          "@NonNull ", "@NullMarked ", "@NullUnmarked ", "org.jspecify.annotations.",
          "import org.jspecify.annotations.Nullable;\n",
          "import org.jspecify.annotations.NullMarked;\n", "javax.annotation.Nullable",
          "@SuppressWarnings(\"NullAway\") ", "Object", "String", "java.util.List",
          "java.util.Map", "java.util.Optional");

  private FuzzCorpus() {}

  /** Writes the corpora and the dictionary as the class comment describes. */
  public static void main(String[] args) throws IOException {
    Path out = Path.of(args.length > 0 ? args[0] : "build/fuzz");
    ImmutableList<Seed> seeds = FuzzSupport.loadSeeds();
    Path structured = clean(out.resolve("corpus-structured"));
    int configs = FuzzSupport.CONFIGS.size();
    for (int i = 0; i < seeds.size(); i++) {
      for (int c = 0; c < configs; c++) {
        Files.write(
            structured.resolve(String.format("seed-%04d-%d", i, c)),
            new byte[] {(byte) (i >> 8), (byte) i, (byte) c});
      }
    }
    Path raw = clean(out.resolve("corpus-raw"));
    int rawCount = 0;
    for (Seed seed : seeds) {
      if (seed.sources().size() == 1) {
        Files.writeString(
            raw.resolve(String.format("seed-%04d.java", rawCount++)),
            seed.sources().get(0).text());
      }
    }
    List<String> dict = new ArrayList<>();
    for (String token : DICTIONARY) {
      dict.add("\"" + token.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\x0A") + "\"");
    }
    Files.write(out.resolve("java.dict"), dict, UTF_8);
    System.out.println(
        "Seeds: "
            + seeds.size()
            + ", structured inputs: "
            + seeds.size() * configs
            + ", raw inputs: "
            + rawCount
            + ", dictionary entries: "
            + dict.size());
  }

  /** Deletes {@code dir} if it exists, creates it empty, and returns it. */
  private static Path clean(Path dir) throws IOException {
    if (Files.exists(dir)) {
      Compiler.deleteRecursively(dir);
    }
    return Files.createDirectories(dir);
  }
}
