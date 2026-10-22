package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.errorprone.FileObjects;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.Test;

/**
 * A description names the structure of a type, its wildcards, and its nullness annotations, and
 * of a lambda whether its parameters are typed, but no names.
 */
public class TypeShapesTest {

  /** Returns the description of the type of field {@code f} declared as {@code type f;}. */
  private static String fieldShape(String type) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    String text =
        "import org.jspecify.annotations.Nullable;\nclass A {\n  " + type + " f;\n}\n";
    JavacTask task =
        (JavacTask)
            javac.getTask(
                null,
                javac.getStandardFileManager(null, Locale.ROOT, UTF_8),
                diagnostic -> {},
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path")),
                null,
                List.of(FileObjects.forSourceLines("A.java", text)));
    task.analyze();
    TypeElement a = task.getElements().getTypeElement("A");
    VariableElement field = ElementFilter.fieldsIn(a.getEnclosedElements()).get(0);
    TypeMirror fieldType = field.asType();
    return TypeShapes.describe(fieldType);
  }

  /** Returns the descriptions of the lambdas in the single-unit source {@code text}. */
  private static List<String> lambdaShapes(String text) {
    ParsedUnit parsed = ParsedUnit.parse(new Source("A.java", text));
    List<String> result = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
        result.add(TypeShapes.describe(tree));
        return super.visitLambdaExpression(tree, null);
      }
    }.scan(parsed.unit(), null);
    return result;
  }

  @Test
  public void aSuperWildcardWithANullableBoundIsDescribedWithItsDirectionAndAnnotation()
      throws IOException {
    assertThat(fieldShape("java.util.List<? super @Nullable String>")).isEqualTo("C<W-?C>");
  }

  @Test
  public void anExtendsWildcardIsDescribedApartFromAnExactArgument() throws IOException {
    assertThat(fieldShape("java.util.List<? extends String>")).isEqualTo("C<W+C>");
    assertThat(fieldShape("java.util.List<String>")).isEqualTo("C<C>");
  }

  @Test
  public void aNullableArrayIsAnnotatedAtTheArrayLevel() throws IOException {
    assertThat(fieldShape("String @Nullable []")).isEqualTo("?AC");
  }

  @Test
  public void nestingBeyondThreeLevelsIsCut() throws IOException {
    assertThat(fieldShape("java.util.List<java.util.List<java.util.List<String>>>"))
        .isEqualTo("C<C<C<*>>>");
  }

  @Test
  public void aLambdaIsDescribedByItsParameterCountTypingAndBody() {
    assertThat(
            lambdaShapes(
                "class A { java.util.function.BiFunction<String, String, String> f = (String a,"
                    + " String b) -> a; java.util.function.Function<String, String> g = a -> {"
                    + " return a; }; }"))
        .containsExactly("LAMBDA2TEXPRESSION", "LAMBDA1STATEMENT")
        .inOrder();
  }

  @Test
  public void anArgumentThatIsNeitherATypeNorATreeIsAnUnderscore() {
    assertThat(TypeShapes.describe("not a type")).isEqualTo("_");
  }
}
