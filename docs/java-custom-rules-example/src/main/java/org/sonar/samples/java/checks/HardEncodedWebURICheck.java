package org.sonar.samples.java.checks;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.check.Rule;
import org.sonar.samples.java.model.ExpressionUtils;
import org.sonar.samples.java.model.LiteralUtils;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.MethodMatchers;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

import static org.sonar.plugins.java.api.semantic.MethodMatchers.ANY;

@Rule(key = "HardEncodedWebURICheck")
public class HardEncodedWebURICheck extends IssuableSubscriptionVisitor {
  private static final String JAVA_LANG_STRING = "java.lang.String";
  private static final MethodMatchers MATCHERS = MethodMatchers.or(
    MethodMatchers.create()
      .ofTypes("java.net.URI")
      .constructor()
      .addParametersMatcher(JAVA_LANG_STRING).build(),
    MethodMatchers.create()
      .ofTypes("java.io.File")
      .constructor()
      .addParametersMatcher(JAVA_LANG_STRING)
      .addParametersMatcher(ANY, JAVA_LANG_STRING)
      .build());

  // 定义URI和IP地址的正则表达式
  private static final String SCHEME = "[a-zA-Z][a-zA-Z\\+\\.\\-]+";
  private static final String URI_REGEX = String.format("^%s://.+", SCHEME);
  private static final String IP_REGEX = "^([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?(/.*)?$";

  private static final Pattern URI_PATTERN = Pattern.compile(URI_REGEX + "|" + IP_REGEX);
  private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("filename|path", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATH_DELIMETERS_PATTERN = Pattern.compile("\"/\"|\"//\"|\"\\\\\\\\\"|\"\\\\\\\\\\\\\\\\\"");

  @Override
  public List<Tree.Kind> nodesToVisit() {
    // 返回此规则感兴趣的节点类型
    return Arrays.asList(Tree.Kind.NEW_CLASS, Tree.Kind.VARIABLE, Tree.Kind.ASSIGNMENT, Tree.Kind.STRING_LITERAL);
  }

  @Override
  public void visitNode(Tree tree) {
    // 根据节点类型调用相应的方法
    if (tree.is(Tree.Kind.NEW_CLASS)) {
      checkNewClassTree((NewClassTree) tree);
    } else if (tree.is(Tree.Kind.VARIABLE)) {
      checkVariable((VariableTree) tree);
    } else if (tree.is(Tree.Kind.ASSIGNMENT)) {
      checkAssignment((AssignmentExpressionTree) tree);
    } else if (tree.is(Tree.Kind.STRING_LITERAL)) {
      checkStringLiteral((LiteralTree) tree);
    }
  }

  private void checkNewClassTree(NewClassTree nct) {
    // 检查新类实例是否匹配定义的构造函数匹配器
    if (MATCHERS.matches(nct)) {
      nct.arguments().forEach(this::checkExpression);
    }
  }

  private void checkVariable(VariableTree tree) {
    // 检查变量名是否匹配文件名或路径模式
    if (isFileNameVariable(tree.simpleName())) {
      checkExpression(tree.initializer());
    }
  }

  private void checkAssignment(AssignmentExpressionTree tree) {
    // 检查赋值表达式是否涉及文件名或路径变量，并且不属于注释的一部分
    if (isFileNameVariable(getVariableIdentifier(tree)) && !isPartOfAnnotation(tree)) {
      checkExpression(tree.expression());
    }
  }

  private void checkStringLiteral(LiteralTree tree) {
    // 检查字符串字面值是否包含硬编码的URI
    if (isHardcodedURI(tree)) {
      reportHardcodedURI(tree);
    }
  }

  private static boolean isPartOfAnnotation(AssignmentExpressionTree tree) {
    // 辅助方法，确定树是否是注释的一部分
    Tree parent = tree.parent();
    while (parent != null) {
      if (parent.is(Tree.Kind.ANNOTATION)) {
        return true;
      }
      parent = parent.parent();
    }
    return false;
  }

  private static boolean isFileNameVariable(@Nullable IdentifierTree variable) {
    // 检查变量名称是否匹配文件名或路径模式
    return variable != null && VARIABLE_NAME_PATTERN.matcher(variable.name()).find();
  }

  private void checkExpression(@Nullable ExpressionTree expr) {
    // 检查表达式是否包含硬编码的URI或路径分隔符
    if (expr != null) {
      if (isHardcodedURI(expr)) {
        reportHardcodedURI(expr);
      } else {
        reportStringConcatenationWithPathDelimiter(expr);
      }
    }
  }

  private static boolean isHardcodedURI(ExpressionTree expr) {
    // 使用正则表达式确定给定的表达式是否为硬编码的URI
    ExpressionTree newExpr = ExpressionUtils.skipParentheses(expr);
    if (!newExpr.is(Tree.Kind.STRING_LITERAL)) {
      return false;
    }
    String stringLiteral = LiteralUtils.trimQuotes(((LiteralTree) newExpr).value());
    if(stringLiteral.contains("*") || stringLiteral.contains("$")) {
      return false;
    }
    return URI_PATTERN.matcher(stringLiteral).find();
  }

  private void reportHardcodedURI(ExpressionTree hardcodedURI) {
    // 报告硬编码的URI问题
    reportIssue(hardcodedURI, "Refactor your code to get this URI from a customizable parameter.");
  }

  private void reportStringConcatenationWithPathDelimiter(ExpressionTree expr) {
    // 报告涉及路径分隔符的字符串连接问题
    expr.accept(new StringConcatenationVisitor());
  }

  private class StringConcatenationVisitor extends BaseTreeVisitor {
    @Override
    public void visitBinaryExpression(BinaryExpressionTree tree) {
      // 检查二元表达式（字符串连接）是否包含硬编码的路径分隔符
      if (tree.is(Tree.Kind.PLUS)) {
        checkPathDelimiter(tree.leftOperand());
        checkPathDelimiter(tree.rightOperand());
      }
      super.visitBinaryExpression(tree);
    }

    private void checkPathDelimiter(ExpressionTree expr) {
      ExpressionTree newExpr = ExpressionUtils.skipParentheses(expr);
      if (newExpr.is(Tree.Kind.STRING_LITERAL) && PATH_DELIMETERS_PATTERN.matcher(((LiteralTree) newExpr).value()).find()) {
        reportIssue(newExpr, "Remove this hard-coded path-delimiter.");
      }
    }
  }

  @CheckForNull
  private static IdentifierTree getVariableIdentifier(AssignmentExpressionTree tree) {
    // 辅助方法，获取赋值表达式中变量的标识符，跳过任何括号
    ExpressionTree variable = ExpressionUtils.skipParentheses(tree.variable());
    if (variable.is(Tree.Kind.IDENTIFIER)) {
      return (IdentifierTree) variable;
    } else if (variable.is(Tree.Kind.MEMBER_SELECT)) {
      return ((MemberSelectExpressionTree) variable).identifier();
    }
    // 忽略数组中的赋值
    return null;
  }
}
