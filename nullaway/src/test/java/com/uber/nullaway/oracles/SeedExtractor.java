package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads seeds out of the NullAway test sources, or out of a directory of generated programs.
 *
 * <p>Each expression statement that calls {@code addSourceLines} one or more times becomes one
 * seed, holding one {@link Source} per call. A call qualifies when every argument is a string
 * literal or a text block: the first names the path, and the rest are joined with newlines into the
 * text, as {@code CompilationTestHelper} joins them. A statement with any other call, such as one
 * that passes a constant, yields no seed.
 */
final class SeedExtractor {

  private SeedExtractor() {}

  /** Returns the {@code addSourceLines} seeds found in every {@code .java} file under {@code testRoot}, in path order. */
  static ImmutableList<Seed> extract(Path testRoot) throws IOException {
    ImmutableList.Builder<Seed> seeds = ImmutableList.builder();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(testRoot)) {
      files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    for (Path file : files) {
      String text = Files.readString(file);
      if (!text.contains("addSourceLines")) {
        continue;
      }
      ParsedUnit parsed = ParsedUnit.parse(new Source(file.getFileName().toString(), text));
      new ChainScanner(parsed, file.getFileName().toString(), seeds).scan(parsed.unit(), null);
    }
    return seeds.build();
  }

  /**
   * Returns one seed per subdirectory of {@code root}, in name order, holding every {@code .java}
   * file under that subdirectory. A unit's path is relative to its subdirectory, so a file at
   * {@code root/p1/src/a/Main.java} becomes {@code src/a/Main.java} in seed {@code p1}. A
   * subdirectory with no {@code .java} file yields no seed.
   */
  static ImmutableList<Seed> fromDirectory(Path root) throws IOException {
    ImmutableList.Builder<Seed> seeds = ImmutableList.builder();
    List<Path> programs;
    try (Stream<Path> list = Files.list(root)) {
      programs = list.filter(Files::isDirectory).sorted().toList();
    }
    for (Path program : programs) {
      List<Path> files;
      try (Stream<Path> walk = Files.walk(program)) {
        files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      }
      ImmutableList.Builder<Source> sources = ImmutableList.builder();
      for (Path file : files) {
        String path = program.relativize(file).toString().replace(File.separatorChar, '/');
        sources.add(new Source(path, Files.readString(file)));
      }
      ImmutableList<Source> built = sources.build();
      if (!built.isEmpty()) {
        seeds.add(new Seed(program.getFileName().toString(), built));
      }
    }
    return seeds.build();
  }

  /** Collects one seed per qualifying {@code addSourceLines} chain. */
  private static final class ChainScanner extends TreeScanner<Void, Void> {
    private final ParsedUnit parsed;
    private final String fileName;
    private final ImmutableList.Builder<Seed> seeds;
    private String methodName = "<init>";

    ChainScanner(ParsedUnit parsed, String fileName, ImmutableList.Builder<Seed> seeds) {
      this.parsed = parsed;
      this.fileName = fileName;
      this.seeds = seeds;
    }

    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
      String enclosing = methodName;
      methodName = tree.getName().toString();
      try {
        return super.visitMethod(tree, null);
      } finally {
        methodName = enclosing;
      }
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementTree tree, Void unused) {
      ImmutableList<Source> sources = sourcesOf(tree.getExpression());
      if (sources != null && !sources.isEmpty()) {
        long line = parsed.unit().getLineMap().getLineNumber(parsed.start(tree));
        seeds.add(new Seed(fileName + "#" + methodName + ":" + line, sources));
      }
      return super.visitExpressionStatement(tree, null);
    }

    /**
     * Returns the units the chain ending in {@code expression} adds, outermost call last, or null
     * if any {@code addSourceLines} call in it has an argument that is not a string literal.
     */
    private static @Nullable ImmutableList<Source> sourcesOf(ExpressionTree expression) {
      Deque<Source> sources = new ArrayDeque<>();
      ExpressionTree current = expression;
      while (current instanceof MethodInvocationTree invocation
          && invocation.getMethodSelect() instanceof MemberSelectTree select) {
        if (select.getIdentifier().contentEquals("addSourceLines")) {
          List<String> args = new ArrayList<>();
          for (ExpressionTree arg : invocation.getArguments()) {
            if (!(arg instanceof LiteralTree literal && literal.getValue() instanceof String s)) {
              return null;
            }
            args.add(s);
          }
          if (args.size() < 2) {
            return null;
          }
          sources.addFirst(
              new Source(args.get(0), String.join("\n", args.subList(1, args.size()))));
        }
        current = select.getExpression();
      }
      return ImmutableList.copyOf(sources);
    }
  }
}
