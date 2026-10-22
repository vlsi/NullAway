package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

/**
 * Each relation rewrites a seed into a program Java gives the same meaning, keeps every line on its
 * line number, and yields no variant where the rewrite would change the meaning.
 */
public class RelationsTest {

  private static ImmutableList<ImmutableList<Source>> variants(Relation relation, Source... sources) {
    return relation.variants(new Seed("seed", ImmutableList.copyOf(sources)));
  }

  /** Returns the text of the single unit in the first variant of {@code relation} over {@code text}. */
  private static String rewritten(Relation relation, String text) {
    return variants(relation, new Source("A.java", text)).get(0).get(0).text();
  }

  @Test
  public void parenthesesWrapAReturnedExpressionAndACallReceiver() {
    assertThat(
            rewritten(
                SyntacticRelations.PARENTHESES,
                "class A { String f(String s) { return s.trim().trim(); } }"))
        .isEqualTo("class A { String f(String s) { return ((s.trim()).trim()); } }");
  }

  @Test
  public void nullCheckSwapExchangesTheOperandsAroundTheOperator() {
    assertThat(
            rewritten(
                SyntacticRelations.NULL_CHECK_SWAP,
                "class A { boolean f(Object o) { return o != null; } }"))
        .isEqualTo("class A { boolean f(Object o) { return null != o; } }");
  }

  @Test
  public void nullCheckNegationRewritesNotEqualsAsANegatedEquals() {
    assertThat(
            rewritten(
                SyntacticRelations.NULL_CHECK_NEGATION,
                "class A { boolean f(Object o) { return o != null; } }"))
        .isEqualTo("class A { boolean f(Object o) { return !(o == null); } }");
  }

  @Test
  public void splitDeclarationKeepsTheLineBreakAfterTheEqualsSign() {
    assertThat(
            rewritten(
                SyntacticRelations.SPLIT_DECLARATION,
                "class A {\n  void f() {\n    Object o =\n        new Object();\n  }\n}\n"))
        .isEqualTo("class A {\n  void f() {\n    Object o ; o =\n        new Object();\n  }\n}\n");
  }

  @Test
  public void splitDeclarationLeavesAnAnnotatedDeclaration() {
    assertThat(
            variants(
                SyntacticRelations.SPLIT_DECLARATION,
                new Source(
                    "A.java", "class A { void f() { @SuppressWarnings(\"x\") Object o = null; } }")))
        .isEmpty();
  }

  @Test
  public void packageNullMarkedMovesTheAnnotationIntoANewPackageInfo() {
    assertThat(
            variants(
                SyntacticRelations.PACKAGE_NULL_MARKED,
                new Source("p/A.java", "package p;\n@NullMarked class A {}\n"),
                new Source("p/B.java", "package p;\n@NullMarked class B {}\n")))
        .containsExactly(
            ImmutableList.of(
                new Source("p/A.java", "package p;\n class A {}\n"),
                new Source("p/B.java", "package p;\n class B {}\n"),
                new Source(
                    "p/package-info.java",
                    "@org.jspecify.annotations.NullMarked\npackage p;\n")));
  }

  @Test
  public void packageNullMarkedLeavesAPackageWithAnUnmarkedClass() {
    assertThat(
            variants(
                SyntacticRelations.PACKAGE_NULL_MARKED,
                new Source("p/A.java", "package p;\n@NullMarked class A {}\n"),
                new Source("p/B.java", "package p;\nclass B {}\n")))
        .isEmpty();
  }

  @Test
  public void castWrapsAnArgumentInACastToItsOwnType() {
    assertThat(
            rewritten(
                AttributedRelations.CAST,
                "class A { void g(String s) {} void f(String s) { g(s); } }"))
        .isEqualTo(
            "class A { void g(String s) {} void f(String s) { g((java.lang.String) (s)); } }");
  }

  @Test
  public void castLeavesTheResultOfAGenericMethodCall() {
    assertThat(
            rewritten(
                AttributedRelations.CAST,
                "class A { String f(String s) { return java.util.Objects.requireNonNull(s); } }"))
        .isEqualTo(
            "class A { String f(String s) { return java.util.Objects.requireNonNull((java.lang.String) (s)); } }");
  }

  @Test
  public void lambdaBecomesAnAnonymousClassOfAnInterfaceFromTheSeed() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "class A { interface F { String apply(String s); } F f = s -> s.trim(); }"))
        .isEqualTo(
            "class A { interface F { String apply(String s); } F f = new A.F() { @Override public"
                + " java.lang.String apply(java.lang.String s) { return s.trim(); } }; }");
  }

  @Test
  public void lambdaOfAnInterfaceFromAClassFileStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { java.util.function.Function<String, String> f = s -> s.trim(); }")))
        .isEmpty();
  }

  @Test
  public void lambdaPassedToAGenericMethodStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { interface F { String apply(String s); }"
                        + " static <T> void take(T t, F f) {} void g() { take(\"x\", s -> s); } }")))
        .isEmpty();
  }
}
