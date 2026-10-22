package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

/**
 * A structured input names a seed, a configuration, and a sequence of edits, each applied to the
 * text the previous one left, and a raw input gets a path javac accepts for its public type.
 */
public class FuzzTargetsTest {

  private static final Seed FIRST = seed("class A { Object f; }");
  private static final Seed SECOND = seed("class B { int g; }");
  private static final Seed NULL_CHECK = seed("class C { boolean m(Object o) { return o == null; } }");
  private static final ImmutableList<Seed> SEEDS = ImmutableList.of(FIRST, SECOND, NULL_CHECK);

  private static Seed seed(String text) {
    return new Seed(text, ImmutableList.of(new Source("A.java", text)));
  }

  private static StructuredFuzzTarget.Decoded decode(int... bytes) {
    byte[] data = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      data[i] = (byte) bytes[i];
    }
    return StructuredFuzzTarget.decode(data, SEEDS);
  }

  @Test
  public void threeBytesNameASeedAndAConfigurationAndMutateNothing() {
    StructuredFuzzTarget.Decoded decoded = decode(0, 1, 2);
    assertThat(decoded.sources()).isEqualTo(SECOND.sources());
    assertThat(decoded.config()).isEqualTo("jspecify-nullmarked");
  }

  @Test
  public void theSeedIndexWrapsAroundTheSeedCount() {
    assertThat(decode(0, 3, 0).sources()).isEqualTo(FIRST.sources());
  }

  @Test
  public void anEmptyInputCompilesTheFirstSeedUnderTheFirstConfiguration() {
    StructuredFuzzTarget.Decoded decoded = decode();
    assertThat(decoded.sources()).isEqualTo(FIRST.sources());
    assertThat(decoded.config()).isEqualTo("legacy");
  }

  @Test
  public void anOperationAppliesTheAnnotationCandidateAtItsIndex() {
    // Kind 0 is an annotation mutation; candidate 1 of A's two is @Nullable on f.
    assertThat(decode(0, 0, 0, 0, 0, 1).sources().get(0).text())
        .isEqualTo("class A { @org.jspecify.annotations.Nullable Object f; }");
  }

  @Test
  public void aLaterOperationChoosesAmongTheCandidatesOfTheEditedText() {
    // After @Nullable on f, candidate 2 removes it again; the original text has only two.
    assertThat(decode(0, 0, 0, 0, 0, 1, 0, 0, 2).sources().get(0).text())
        .isEqualTo("class A {  Object f; }");
  }

  @Test
  public void aKindFromTheTypeMutationRangeAppliesThatMutation() {
    // Kind 128 is a TypeMutator mutation; its first candidate here wraps the returned expression.
    assertThat(decode(0, 2, 0, 128, 0, 0).sources().get(0).text())
        .isEqualTo("class C { boolean m(Object o) { return java.util.Objects.requireNonNull(o == null); } }");
  }

  @Test
  public void aKindFromTheRelationRangeAppliesThatRelation() {
    // Kind 193 is the second relation, null-check-swap.
    assertThat(decode(0, 2, 0, 193, 0, 0).sources().get(0).text())
        .isEqualTo("class C { boolean m(Object o) { return null == o; } }");
  }

  @Test
  public void aPublicTypeInAPackageGetsItsOwnPath() {
    assertThat(RawSourceFuzzTarget.pathOf("package com.uber;\nimport x.Y;\npublic final class Foo {}"))
        .isEqualTo("com/uber/Foo.java");
  }

  @Test
  public void aUnitWithoutAPublicTypeIsNamedTest() {
    assertThat(RawSourceFuzzTarget.pathOf("class Foo {}")).isEqualTo("Test.java");
  }
}
