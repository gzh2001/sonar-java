/*
 * SonarQube Java
 * Copyright (C) 2012-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.samples.java.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.java.model.SELiteralUtils;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.*;

import javax.annotation.CheckForNull;
import java.util.Optional;
import java.util.function.BiFunction;

public final class SEExpressionUtils {

  private static final Logger LOG = LoggerFactory.getLogger(SEExpressionUtils.class);

  private SEExpressionUtils() {
  }

  /**
   * Checks of is the given tree is selecting with <code>this</code> or <code>super</code>
   * @param tree The tree to check.
   * @return true when the tree is a select on <code>this</code> or <code>super</code>
   * @see #isSelectOnThisOrSuper(AssignmentExpressionTree)
   */
  public static boolean isSelectOnThisOrSuper(MemberSelectExpressionTree tree) {
    if (!tree.expression().is(Tree.Kind.IDENTIFIER)) {
      // This is no longer simple.
      return false;
    }

    String selectSourceName = ((IdentifierTree) tree.expression()).name();
    return "this".equalsIgnoreCase(selectSourceName) || "super".equalsIgnoreCase(selectSourceName);
  }

  /**
   * Checks of is the given tree is a {@link MemberSelectExpressionTree} which is selecting with <code>this</code> or <code>super</code>
   * @param tree The tree to check.
   * @return true when the tree is a select on <code>this</code> or <code>super</code>
   * @see #isSelectOnThisOrSuper(MemberSelectExpressionTree)
   */
  public static boolean isSelectOnThisOrSuper(AssignmentExpressionTree tree) {
    ExpressionTree variable = SEExpressionUtils.skipParentheses(tree.variable());
    return variable.is(Tree.Kind.MEMBER_SELECT) && isSelectOnThisOrSuper((MemberSelectExpressionTree) variable);
  }

  public static ExpressionTree skipParentheses(ExpressionTree tree) {
    ExpressionTree result = tree;
    while (result.is(Tree.Kind.PARENTHESIZED_EXPRESSION)) {
      result = ((ParenthesizedTree) result).expression();
    }
    return result;
  }

  private static Optional<IdentifierTree> extractIdentifier(ExpressionTree tree) {
    ExpressionTree cleanedExpression = SEExpressionUtils.skipParentheses(tree);
    if (cleanedExpression.is(Tree.Kind.IDENTIFIER)) {
      return Optional.of(((IdentifierTree) cleanedExpression));
    } else if (cleanedExpression.is(Tree.Kind.MEMBER_SELECT)) {
      MemberSelectExpressionTree selectTree = (MemberSelectExpressionTree) cleanedExpression;
      if (isSelectOnThisOrSuper(selectTree)) {
        return Optional.of(selectTree.identifier());
      }
    }
    return Optional.empty();
  }

  /**
   * In case of simple assignments, only the expression is evaluated, as we only use the reference to the variable to store the result.
   * For SE-Based checks, only a single value should be unstacked if its the case. For other cases, two values should be unstacked.
   * See JLS8-15.26
   *
   * @param tree The assignment tree
   * @return true if the tree is a simple assignment
   * @see #extractIdentifier(AssignmentExpressionTree)
   */
  public static boolean isSimpleAssignment(AssignmentExpressionTree tree) {
    if (!tree.is(Tree.Kind.ASSIGNMENT)) {
      // This can't possibly be a simple assignment.
      return false;
    }

    ExpressionTree variable = SEExpressionUtils.skipParentheses(tree.variable());
    return variable.is(Tree.Kind.IDENTIFIER) || isSelectOnThisOrSuper(tree);
  }

  /**
   * Retrieve the identifier corresponding to the method name associated to the method invocation
   */
  public static IdentifierTree methodName(MethodInvocationTree mit) {
    ExpressionTree methodSelect = mit.methodSelect();
    IdentifierTree id;
    if (methodSelect.is(Tree.Kind.IDENTIFIER)) {
      id = (IdentifierTree) methodSelect;
    } else {
      id = ((MemberSelectExpressionTree) methodSelect).identifier();
    }
    return id;
  }

  public static boolean isNullLiteral(ExpressionTree tree) {
    return skipParentheses(tree).is(Tree.Kind.NULL_LITERAL);
  }

  @CheckForNull
  public static Object resolveAsConstant(ExpressionTree tree) {
    ExpressionTree expression = tree;
    while (expression.is(Tree.Kind.PARENTHESIZED_EXPRESSION)) {
      expression = ((ParenthesizedTree) expression).expression();
    }
    if (expression.is(Tree.Kind.MEMBER_SELECT)) {
      expression = ((MemberSelectExpressionTree) expression).identifier();
    }
    if (expression.is(Tree.Kind.IDENTIFIER)) {
      return resolveIdentifier((IdentifierTree) expression);
    }
    if (expression.is(Tree.Kind.BOOLEAN_LITERAL)) {
      return Boolean.parseBoolean(((LiteralTree) expression).value());
    }
    if (expression.is(Tree.Kind.STRING_LITERAL, Tree.Kind.TEXT_BLOCK)) {
      return SELiteralUtils.getAsStringValue((LiteralTree) expression);
    }
    if (expression instanceof UnaryExpressionTree unaryExpressionTree) {
      return resolveUnaryExpression(unaryExpressionTree);
    }
    if (expression.is(Tree.Kind.INT_LITERAL)) {
      return SELiteralUtils.intLiteralValue(expression);
    }
    if (expression.is(Tree.Kind.LONG_LITERAL)) {
      return SELiteralUtils.longLiteralValue(expression);
    }
    if (expression.is(Tree.Kind.PLUS)) {
      return resolvePlus((BinaryExpressionTree) expression);
    }
    if (expression.is(Tree.Kind.OR)) {
      return resolveOr((BinaryExpressionTree) expression);
    }
    if (expression.is(Tree.Kind.MINUS)) {
      return resolveArithmeticOperation((BinaryExpressionTree) expression, (a, b) -> a - b, (a, b) -> a - b);
    }
    if (expression.is(Tree.Kind.MULTIPLY)) {
      return resolveArithmeticOperation((BinaryExpressionTree) expression, (a, b) -> a * b, (a, b) -> a * b);
    }
    if (expression.is(Tree.Kind.DIVIDE)) {
      return resolveArithmeticOperation((BinaryExpressionTree) expression, (a, b) -> a / b, (a, b) -> a / b);
    }
    if (expression.is(Tree.Kind.REMAINDER)) {
      return resolveArithmeticOperation((BinaryExpressionTree) expression, (a, b) -> a % b, (a, b) -> a % b);
    }
    return null;
  }

  @CheckForNull
  private static Object resolveIdentifier(IdentifierTree tree) {
    Symbol symbol = tree.symbol();
    if (!symbol.isVariableSymbol()) {
      return null;
    }
    Symbol owner = symbol.owner();
    if (owner.isTypeSymbol() && owner.type().is("java.lang.Boolean")) {
      if ("TRUE".equals(symbol.name())) {
        return Boolean.TRUE;
      } else if ("FALSE".equals(symbol.name())) {
        return Boolean.FALSE;
      }
    }
    return ((Symbol.VariableSymbol) symbol).constantValue().orElse(null);
  }

  public static IdentifierTree extractIdentifier(AssignmentExpressionTree tree) {
    Optional<IdentifierTree> identifier = extractIdentifier(tree.variable());

    if (identifier.isPresent()) {
      return identifier.get();
    }

    // This should not be possible.
    // If it happens anyway, you should make sure the assignment is simple (by calling isSimpleAssignment) before.
    throw new IllegalArgumentException("Can not extract identifier.");
  }

  /**
   * Checks if the given expression refers to "this"
   * @param expression the expression to check
   * @return true if this expression refers to "this"
   */
  public static boolean isThis(ExpressionTree expression) {
    ExpressionTree newExpression = SEExpressionUtils.skipParentheses(expression);
    return newExpression.is(Tree.Kind.IDENTIFIER) && "this".equals(((IdentifierTree) newExpression).name());
  }

  @CheckForNull
  private static Object resolveArithmeticOperation(Object left, Object right, BiFunction<Long, Long, Object> longOperation, BiFunction<Integer, Integer, Object> intOperation) {
    try {
      if (left instanceof Integer leftInt && right instanceof Integer rightInt) {
        return intOperation.apply(leftInt, rightInt);
      } else if ((left instanceof Long || right instanceof Long) && (left instanceof Integer || right instanceof Integer)) {
        return longOperation.apply(((Number) left).longValue(), ((Number) right).longValue());
      }
    } catch (ArithmeticException e) {
      LOG.debug("Arithmetic exception while resolving arithmetic operation value", e);
    }
    return null;
  }

  @CheckForNull
  private static Object resolveUnaryExpression(UnaryExpressionTree unaryExpression) {
    Object value = resolveAsConstant(unaryExpression.expression());
    if (unaryExpression.is(Tree.Kind.UNARY_PLUS)) {
      return value;
    } else if (unaryExpression.is(Tree.Kind.UNARY_MINUS)) {
      if (value instanceof Long longValue) {
        return -longValue;
      } else if (value instanceof Integer intValue) {
        return -intValue;
      }
    } else if (unaryExpression.is(Tree.Kind.BITWISE_COMPLEMENT)) {
      if (value instanceof Long longValue) {
        return ~longValue;
      } else if (value instanceof Integer intValue) {
        return ~intValue;
      }
    } else if (unaryExpression.is(Tree.Kind.LOGICAL_COMPLEMENT) && value instanceof Boolean bool) {
      return !bool;
    }
    return null;
  }

  @CheckForNull
  private static Object resolvePlus(BinaryExpressionTree binaryExpression) {
    Object left = resolveAsConstant(binaryExpression.leftOperand());
    Object right = resolveAsConstant(binaryExpression.rightOperand());
    if (left == null || right == null) {
      return null;
    } else if (left instanceof String leftString) {
      return leftString + right;
    } else if (right instanceof String rightString) {
      return left + rightString;
    }
    return resolveArithmeticOperation(left, right, Long::sum, Integer::sum);
  }

  @CheckForNull
  private static Object resolveArithmeticOperation(BinaryExpressionTree binaryExpression,
                                                   BiFunction<Long, Long, Object> longOperation,
                                                   BiFunction<Integer, Integer, Object> intOperation) {
    Object left = resolveAsConstant(binaryExpression.leftOperand());
    Object right = resolveAsConstant(binaryExpression.rightOperand());
    if (left == null || right == null) {
      return null;
    }
    return resolveArithmeticOperation(left, right, longOperation, intOperation);
  }

  @CheckForNull
  private static Object resolveOr(BinaryExpressionTree binaryExpression) {
    Object left = resolveAsConstant(binaryExpression.leftOperand());
    Object right = resolveAsConstant(binaryExpression.rightOperand());
    if (left == null || right == null) {
      return null;
    } else if (left instanceof Long leftLong && right instanceof Long rightLong) {
      return leftLong | rightLong;
    } else if (left instanceof Long leftLong && right instanceof Integer rightInt) {
      return leftLong | rightInt;
    } else if (left instanceof Integer leftInt && right instanceof Long rightLong) {
      return leftInt | rightLong;
    } else if (left instanceof Integer leftInt && right instanceof Integer rightInt) {
      return leftInt | rightInt;
    }
    return null;
  }

}
