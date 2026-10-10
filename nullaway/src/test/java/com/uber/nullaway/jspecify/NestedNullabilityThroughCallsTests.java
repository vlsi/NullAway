package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/**
 * Nested nullability carried through generic calls, generic constructors without a diamond, and
 * generic method references outside an inferred call, which NullAway infers from javac's types
 * today.
 */
public class NestedNullabilityThroughCallsTests extends NullAwayTestsBase {

  private static final String HEADER =
      """
      import java.util.List;
      import org.jspecify.annotations.NullMarked;
      import org.jspecify.annotations.Nullable;
      @NullMarked
      class Test {
        static <U> U id(U value) { return value; }
        @SafeVarargs static <U> U first(U... values) { return values[0]; }
        static <E extends @Nullable Object> List<E> list(E value) { throw new RuntimeException(); }
      """;

  private void check(String body) {
    makeHelper().addSourceLines("Test.java", HEADER + body + "\n}\n").doTest();
  }

  @Test
  public void idMissedReport() {
    check(
        """
          void a(@Nullable String s) {
            var input = list(s);
            // BUG: Diagnostic contains: cannot be converted
            List<String> output = id(input);
          }
        """);
  }

  @Test
  public void idFalsePositive() {
    check(
        """
          void b(@Nullable String s) {
            var input = list(s);
            List<@Nullable String> output = id(input);
          }
        """);
  }

  @Test
  public void varargsMissedReport() {
    check(
        """
          void a(@Nullable String s) {
            var input = list(s);
            // BUG: Diagnostic contains: cannot be converted
            List<String> output = first(input);
          }
        """);
  }

  @Test
  public void varargsFalsePositive() {
    check(
        """
          void b(@Nullable String s) {
            var input = list(s);
            List<@Nullable String> output = first(input);
          }
        """);
  }

  @Test
  public void inlineIdMissedReport() {
    check(
        """
          void a(@Nullable String s) {
            // BUG: Diagnostic contains: cannot be converted
            List<String> output = id(list(s));
          }
        """);
  }

  @Test
  public void inlineIdFalsePositive() {
    check(
        """
          void b(@Nullable String s) { List<@Nullable String> output = id(list(s)); }
        """);
  }

  @Test
  public void anArgumentKeepsItsNestedNullabilityInEveryShapeOfGenericCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              static <E extends @Nullable Object> ArrayList<E> arrayList(E element) {
                throw new UnsupportedOperationException();
              }
              static <E extends @Nullable Object> List<List<@Nullable E>> nested(E element) {
                throw new UnsupportedOperationException();
              }
              static class Outer<E extends @Nullable Object> {
                class Inner {}
              }
              static <E extends @Nullable Object> Outer<E>.Inner inner(E element) {
                throw new UnsupportedOperationException();
              }
              static <U> void plain(U input) {}
              static <U> void pair(U first, U second) {}
              static <U> void varargs(U... inputs) {}
              static <U> void wildcard(List<? extends U> input) {}
              static <U> void varargsOfLists(List<U>... inputs) {}
              static <U> void wildcardOfLists(List<? extends List<U>> input) {}
              static class Plain {
                <U> Plain(U input) {}
              }
              static class Pair {
                <U> Pair(U first, U second) {}
              }
              static class Varargs {
                <U> Varargs(U... inputs) {}
              }
              static class Wildcard {
                <U> Wildcard(List<? extends U> input) {}
              }
              static class VarargsOfLists {
                <U> VarargsOfLists(List<U>... inputs) {}
              }
              static class WildcardOfLists {
                <U> WildcardOfLists(List<? extends List<U>> input) {}
              }
              void methods() {
                var input = list(null);
                plain(input);
                plain(inner(null));
                varargs(input, input);
                wildcard(nested("x"));
              }
              void constructors() {
                var input = list(null);
                new Plain(input);
                new Plain(inner(null));
                new Varargs(input, input);
                new Varargs();
                new Wildcard(nested("x"));
              }
              void anonymousClasses() {
                var input = list(null);
                new Plain(input) {};
                new Plain(inner(null)) {};
                new Varargs(input) {};
                new Wildcard(nested("x")) {};
              }
              void aSubtypeBeforeTheCallSiteType() {
                var subtype = arrayList(null);
                var input = list(null);
                pair(subtype, input);
                varargs(subtype, input);
                new Pair(subtype, input);
                new Varargs(subtype, input);
              }
              void arraysOfNonNullAndNullableComponents(String[] nonNull, @Nullable String[] nullable) {
                varargs(nonNull, nullable);
                varargs(nullable, nonNull);
                new Varargs(nonNull, nullable);
                new Varargs(nullable, nonNull);
              }
              void arraysOfASubtypeComponent(Number[] numbers, @Nullable Integer[] integers) {
                pair(numbers, integers);
                pair(integers, numbers);
                new Pair(numbers, integers);
                new Pair(integers, numbers);
              }
              void arraysNullableAtDifferentDimensions(
                  String[][] nonNull, @Nullable String[][] innermost, String[] @Nullable [] inner) {
                pair(nonNull, innermost);
                new Pair(nonNull, innermost);
                new Pair(innermost, nonNull);
                new Pair(inner, innermost);
                new Pair(innermost, inner);
              }
              void argumentsThatDisagree(List<@Nullable String> nullable, List<String> nonNull) {
                // BUG: Diagnostic contains: List<String> cannot be converted to List<@Nullable String>
                new Pair(nullable, nonNull);
              }
              void methodsIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                varargsOfLists(input);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                wildcardOfLists(nested("x"));
              }
              void constructorsIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                new VarargsOfLists(input);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new WildcardOfLists(nested("x"));
              }
              void anonymousClassesIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                new VarargsOfLists(input) {};
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new WildcardOfLists(nested("x")) {};
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aMethodTypeVariableBoundedByAClassTypeVariableTakesItsTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<E extends @Nullable Object> {
                Holder() {}
                <U extends E> Holder(U element) {}
                <U extends E> Holder(Box<U> box, int unused) {}
                <U extends E> void set(U element) {}
                <U extends E> U pass(U element) {
                  return element;
                }
                <A extends E, B extends A> Holder(B element, String unused) {}
                <A extends E, B extends A> A passThrough(B element) {
                  return element;
                }
              }
              static <A extends @Nullable Object, B extends A> List<A> wrap(B element) {
                throw new UnsupportedOperationException();
              }
              @NullUnmarked
              static class Unannotated<E> {
                <U extends E> U pass(U element) {
                  return element;
                }
                static <A, B extends A> List<A> wrap(B element) {
                  throw new UnsupportedOperationException();
                }
              }
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              void nullableTypeArgument(Holder<@Nullable String> holder, @Nullable String value) {
                new Holder<@Nullable String>(value);
                holder.set(value);
              }
              void nonNullTypeArgument(Holder<String> holder, String value) {
                new Holder<String>(value);
                holder.set(value);
              }
              void aWildcardTypeArgument(Holder<? super @Nullable String> holder, @Nullable String value) {
                holder.set(value);
                holder.set(null);
              }
              void aDeclarationInUnannotatedCode(Unannotated<String> unannotated, @Nullable String value) {
                String result = unannotated.pass(value);
                List<String> wrapped = Unannotated.wrap(value);
              }
              void anImplicitThisInAnAnonymousClass(@Nullable String value) {
                new Holder<@Nullable String>() {
                  void call() {
                    set(value);
                  }
                };
                new Holder<String>() {
                  void call() {
                    // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                    set(value);
                  }
                };
              }
              void aBoundReachedThroughAnotherVariable(
                  Holder<String> nonNullHolder, @Nullable String value) {
                new Holder<@Nullable String>(value, "");
                // BUG: Diagnostic contains: inference failure: type variable A is constrained to be @Nullable
                new Holder<String>(value, "");
                // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> nonNull = nonNullHolder::passThrough;
              }
              void aMethodReference(
                  Holder<@Nullable String> nullableHolder, Holder<String> nonNullHolder) {
                Function<@Nullable String, @Nullable String> nullable = nullableHolder::pass;
                BiFunction<Holder<@Nullable String>, @Nullable String, @Nullable String> unbound =
                    Holder::pass;
                // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> nonNull = nonNullHolder::pass;
                BiFunction<Holder<String>, @Nullable String, @Nullable String> nonNullUnbound =
                    // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                    Holder::pass;
              }
              void nestedCallsOfOneMethodWithDifferentTypeArguments(
                  Holder<List<@Nullable String>> outer, Holder<@Nullable String> inner, @Nullable String value) {
                outer.pass(list(inner.pass(value)));
              }
              void aMethodVariableBoundedByAnother(@Nullable String value) {
                List<@Nullable String> nullable = wrap(value);
                // BUG: Diagnostic contains: constrained to be both @NonNull and @Nullable
                List<String> nonNull = wrap(value);
              }
              void nestedCallsSharingTheVariables(@Nullable String value) {
                List<List<@Nullable String>> nested = wrap(wrap(value));
              }
              void aDiamondCall(@Nullable String value) {
                Holder<@Nullable String> nullable = new Holder<>(value);
                // BUG: Diagnostic contains: constrained to be both @NonNull and @Nullable
                Holder<String> nonNull = new Holder<>(value);
              }
              void aNullableValueIntoANonNullTypeArgument(
                  Holder<String> holder, @Nullable String value, Box<@Nullable String> box) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new Holder<String>(value);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new Holder<String>(box, 0);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                holder.set(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aFailedInferenceKeepsTheDeclaredAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <U> void copy(Box<? extends U> input, Box<? super @Nullable U> output) {}
              void intoNullableElements(Box<@Nullable String> input, Box<@Nullable String> output) {
                // BUG: Diagnostic contains: inference failure
                copy(input, output);
              }
              void intoNonNullElements(Box<@Nullable String> input, Box<String> output) {
                // BUG: Diagnostic contains: Box<String> cannot be converted to Box<? super @Nullable String>
                copy(input, output);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aGenericConstructorWithoutADiamondInfersItsTypeVariables() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {}
              static class Consumer {
                <U extends @Nullable Object> Consumer(Box<? extends U> input) {}
              }
              static class GenericConsumer<E> {
                <U extends @Nullable Object> GenericConsumer(Box<? extends U> input) {}
              }
              static class Pipe {
                <U extends @Nullable Object> Pipe(Box<? extends U> input, Box<? super U> output) {}
              }
              static class Holder {
                <U> Holder(U input) {}
              }
              static class StrictConsumer {
                <U> StrictConsumer(Box<? extends U> input) {}
              }
              static class ListHolder {
                <U> ListHolder(List<U> input) {}
              }
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              void inferredOnANonGenericClass(Box<T> input) {
                new Consumer(input);
              }
              void inferredWithExplicitClassTypeArguments(Box<T> input) {
                new GenericConsumer<String>(input);
              }
              void inferredIntoANullableOutput(Box<T> input, Box<@Nullable Object> output) {
                new Pipe(input, output);
              }
              void inferredIntoANonNullOutput(Box<T> input, Box<Object> output) {
                // U is inferred @NonNull from output, and the message prints Box<? extends @NonNull T>
                // without the annotation (#1828)
                // BUG: Diagnostic contains: Box<T> cannot be converted to Box<? extends T>
                new Pipe(input, output);
              }
              void explicitTypeVariable(Box<T> input) {
                new <T>Consumer(input);
              }
              void explicitNonNull(Box<T> input) {
                // BUG: Diagnostic contains: Box<T> cannot be converted to Box<? extends Object>
                new <Object>Consumer(input);
              }
              void aNullableTypeVariableIntoANonNullVariable(Box<T> input) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new StrictConsumer(input);
              }
              void aCapturedNullableBoundIntoANonNullVariable(Box<? extends @Nullable String> input) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new StrictConsumer(input);
              }
              void inferredWithNestedNullability() {
                var input = list(null);
                new Holder(input);
              }
              void inferredWithNestedNullabilityIntoANonNullVariable() {
                var input = list(null);
                // BUG: Diagnostic contains: List<@Nullable Object> cannot be converted to List<Object>
                new ListHolder(input);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aGenericMethodReferenceOutsideInferenceInfersAgainstItsTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.BiConsumer;
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <U extends @Nullable Object> List<U> singleton(U value) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> List<@Nullable U> nullableSingleton(U value) {
                throw new UnsupportedOperationException();
              }
              static <U> U id(U value) {
                return value;
              }
              static <U extends @Nullable Object> U nullableId(U value) {
                return value;
              }
              static class Box<E extends @Nullable Object> {
                Box(E element) {}
                void apply(Function<List<E>, List<E>> f) {}
                void applyToTwo(BiFunction<List<E>, List<E>, List<E>> f) {}
              }
              static <A extends @Nullable Object, B extends A> A upcast(B value) {
                return value;
              }
              @SafeVarargs
              static <U> U first(U... values) {
                return values[0];
              }
              @SafeVarargs
              static <U extends @Nullable Object> List<U> listOf(U... elements) {
                throw new UnsupportedOperationException();
              }
              static void use(Function<String, List<? extends Object>> f) {}
              static void useNullable(Function<@Nullable String, List<@Nullable String>> f) {}
              void inferred() {
                use(Test::singleton);
              }
              void inferredFromANullableTarget() {
                useNullable(Test::singleton);
                Function<@Nullable String, @Nullable String> f = Test::nullableId;
              }
              static void nonNullElements(String... elements) {}
              static void nullableElements(@Nullable String... elements) {}
              void inferredForVarargs() {
                BiFunction<@Nullable String, @Nullable String, List<@Nullable String>> f = Test::listOf;
                BiConsumer<String, @Nullable String> nullable = Test::nullableElements;
                // BUG: Diagnostic contains: parameter elements of referenced method is @NonNull
                BiConsumer<String, @Nullable String> nonNull = Test::nonNullElements;
              }
              void inferredFromATargetWithNestedNullability() {
                var box = new Box<>(null);
                box.apply(Test::id);
                box.applyToTwo(Test::first);
              }
              void aVariableBoundedByAnother() {
                Function<@Nullable String, @Nullable String> nullable = Test::upcast;
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull
                Function<@Nullable String, String> nonNull = Test::upcast;
              }
              void aNullableTargetIntoANonNullVariable() {
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> f = Test::id;
              }
              void inferredInAnAssignment() {
                Function<String, List<? extends Object>> f = Test::singleton;
              }
              void inferredWithNullableElements() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                use(Test::nullableSingleton);
              }
              void explicitNonNull() {
                use(Test::<String>singleton);
              }
              void explicitNullable() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                use(Test::<@Nullable String>singleton);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aGenericMethodReferenceReturnedFromALambdaLeavesInferenceToTheCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <U extends @Nullable Object> U id(U u) {
                return u;
              }
              static <U> U idNonNull(U u) {
                return u;
              }
              static <T extends @Nullable Object> Supplier<Function<T, T>> make(
                  Supplier<Function<T, T>> s) {
                return s;
              }
              static <T extends @Nullable Object> void use(Supplier<Function<T, T>> f, T x) {}
              void expressionBody() {
                Supplier<Function<@Nullable String, @Nullable String>> s = make(() -> Test::id);
              }
              void blockBody() {
                Supplier<Function<@Nullable String, @Nullable String>> s =
                    make(() -> {
                      return Test::id;
                    });
              }
              void beside(@Nullable String value) {
                use(() -> Test::id, value);
              }
              void inAConditional(boolean b, @Nullable String value) {
                use(() -> b ? Test::id : Test::id, value);
              }
              void besideANonNullReference(@Nullable String value) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                use(() -> Test::idNonNull, value);
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
