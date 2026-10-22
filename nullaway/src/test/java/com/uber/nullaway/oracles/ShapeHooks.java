package com.uber.nullaway.oracles;

import com.code_intelligence.jazzer.api.HookType;
import com.code_intelligence.jazzer.api.Jazzer;
import com.code_intelligence.jazzer.api.MethodHook;
import java.lang.invoke.MethodHandle;

/**
 * Jazzer hooks that report the shape of the arguments of NullAway's generics checks as a state to
 * explore, so the fuzzer keeps an input that reaches a check with a new combination of types even
 * when it covers no new branch.
 *
 * <p>Pass {@code --custom_hooks=com.uber.nullaway.oracles.ShapeHooks} to Jazzer. A hook fires
 * only where an instrumented class calls the method, so the hooked methods are ones NullAway calls
 * itself rather than Error Prone's entry points. Each hooked call site gets 64 state identifiers
 * of 256 states each: the low byte of the {@link TypeShapes#hash} is the state, and the next six
 * bits pick the identifier.
 */
public final class ShapeHooks {

  private ShapeHooks() {}

  /** Reports the shape of the arguments before each hooked method runs. */
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.generics.TypeSubstitutionUtils",
      targetMethod = "restoreExplicitNullabilityAnnotations")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.generics.GenericsChecks",
      targetMethod = "checkTypeParameterNullnessForAssignability")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.generics.GenericsChecks",
      targetMethod = "compareGenericTypeParameterNullabilityForCall")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.generics.GenericsChecks",
      targetMethod = "checkTypeParameterNullnessForMethodOverriding")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.generics.GenericsChecks",
      targetMethod = "getGenericReturnNullnessAtInvocation")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.NullAway",
      targetMethod = "mayBeNullExpr")
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "com.uber.nullaway.NullAway",
      targetMethod = "checkParamOverriding")
  public static void beforeCheck(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId) {
    int hash = TypeShapes.hash(arguments);
    Jazzer.exploreState((byte) hash, hookId * 64 + ((hash >>> 8) & 63));
  }
}
