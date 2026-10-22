package com.uber.nullaway.oracles;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/**
 * Describes the structure of the types and trees a NullAway check receives, without their names,
 * so that two calls with differently shaped arguments get different descriptions.
 *
 * <p>Coverage of NullAway's branches barely grows once its own tests are in the corpus, while the
 * crashes found so far each needed a particular combination, such as a {@code super} wildcard
 * whose bound is {@code @Nullable} inside a type argument. {@link ShapeHooks} reports the hash of
 * a description to Jazzer as a state to explore, so an input that reaches a check with a new
 * combination counts as progress.
 *
 * <p>A type is described down to {@value #MAX_DEPTH} levels: its kind, a {@code ?} for a type-use
 * annotation named {@code Nullable} and a {@code !} for one named {@code NonNull}, and its type
 * arguments, wildcard bound, or component type. A tree is described by its kind, plus for a
 * lambda whether its parameters are typed, for a method reference its mode, and for a call
 * whether it has explicit type arguments. Any other argument is described as {@code _}.
 */
final class TypeShapes {

  private static final int MAX_DEPTH = 3;

  private TypeShapes() {}

  /** Returns the hash of the descriptions of {@code arguments}, in order. */
  static int hash(Object[] arguments) {
    StringBuilder result = new StringBuilder();
    for (Object argument : arguments) {
      result.append(describe(argument)).append(';');
    }
    return result.toString().hashCode();
  }

  /** Returns the description of one argument of a check. */
  static String describe(Object argument) {
    if (argument instanceof TypeMirror type) {
      return describe(type, MAX_DEPTH);
    }
    if (argument instanceof Tree tree) {
      return describe(tree);
    }
    return "_";
  }

  private static String describe(TypeMirror type, int depth) {
    String annotations = annotations(type.getAnnotationMirrors());
    if (depth == 0) {
      return annotations + "*";
    }
    if (type instanceof DeclaredType declared) {
      List<? extends TypeMirror> arguments = declared.getTypeArguments();
      if (arguments.isEmpty()) {
        return annotations + "C";
      }
      StringBuilder result = new StringBuilder(annotations).append("C<");
      for (int i = 0; i < arguments.size(); i++) {
        result.append(i == 0 ? "" : ",").append(describe(arguments.get(i), depth - 1));
      }
      return result.append('>').toString();
    }
    if (type instanceof WildcardType wildcard) {
      if (wildcard.getExtendsBound() != null) {
        return annotations + "W+" + describe(wildcard.getExtendsBound(), depth - 1);
      }
      if (wildcard.getSuperBound() != null) {
        return annotations + "W-" + describe(wildcard.getSuperBound(), depth - 1);
      }
      return annotations + "W";
    }
    if (type instanceof ArrayType array) {
      return annotations + "A" + describe(array.getComponentType(), depth - 1);
    }
    if (type instanceof TypeVariable variable) {
      // A captured wildcard is a type variable too; its upper bound is described, as for any
      // variable, one level deep, since a bound can mention the variable itself.
      String kind = type.getClass().getSimpleName().contains("Captured") ? "K" : "V";
      return annotations + kind + annotations(variable.getUpperBound().getAnnotationMirrors());
    }
    return annotations + type.getKind();
  }

  private static String annotations(List<? extends AnnotationMirror> mirrors) {
    StringBuilder result = new StringBuilder();
    for (AnnotationMirror mirror : mirrors) {
      String name = mirror.getAnnotationType().asElement().getSimpleName().toString();
      if (name.equals("Nullable")) {
        result.append('?');
      } else if (name.equals("NonNull")) {
        result.append('!');
      }
    }
    return result.toString();
  }

  private static String describe(Tree tree) {
    if (tree instanceof LambdaExpressionTree lambda) {
      // Attribution gives an implicitly typed parameter a type tree, so ask javac how it was written.
      boolean typed =
          lambda instanceof JCTree.JCLambda jc
              && jc.paramKind == JCTree.JCLambda.ParameterKind.EXPLICIT;
      return "LAMBDA" + lambda.getParameters().size() + (typed ? "T" : "") + lambda.getBodyKind();
    }
    if (tree instanceof MemberReferenceTree reference) {
      return "REF" + reference.getMode();
    }
    if (tree instanceof MethodInvocationTree invocation) {
      return "CALL" + (invocation.getTypeArguments().isEmpty() ? "" : "<>");
    }
    return tree.getKind().toString();
  }
}
