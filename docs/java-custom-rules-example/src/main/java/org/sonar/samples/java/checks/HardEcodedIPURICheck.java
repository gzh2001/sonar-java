package org.sonar.samples.java.checks;

import java.util.*;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.*;
import org.sonar.samples.java.model.ExpressionUtils;
import org.sonar.samples.java.model.LiteralUtils;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;

@Rule(key = "S50001")
@Slf4j
public class HardEcodedIPURICheck extends IssuableSubscriptionVisitor {
  private static final String JAVA_LANG_STRING = "java.lang.String";
  //  黑名单。处理构造函数
  private final Set<String> MATCHERS = new HashSet<>(Arrays.asList(
    "QName"
  ));
  // 定义URI和IP地址的正则表达式
  private static final String SCHEME = "[a-zA-Z][a-zA-Z\\+\\.\\-]+";
  private static final String IP_REGEX = "([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?(/.*)?";
  // WSDL
  private static final Pattern WSDL_Keyword = Pattern.compile("wsdl", Pattern.CASE_INSENSITIVE);
  // 回环地址
  private static final String IPV4_LOOPBACK_URI_REGEX = String.format("(%s://)?(127\\.0\\.0\\.1|192\\.168\\.\\d{1,3}\\.\\d{1,3})(:[0-9]{1,5})?(/.*)?", SCHEME);
  private static final Pattern IPV4_LOOPBACK_URI_PATTERN = Pattern.compile(IPV4_LOOPBACK_URI_REGEX );
  // 路径分隔符
  private static final Pattern PATH_DELIMETERS_PATTERN = Pattern.compile("\"/\"|\"//\"|\"\\\\\\\\\"|\"\\\\\\\\\\\\\\\\\"");
  private static final Pattern URI_PATTERN = Pattern.compile(IP_REGEX);
  // 文件变量特征值
  private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("filename|path|pureIP|website", Pattern.CASE_INSENSITIVE);


  @Override
  public List<Tree.Kind> nodesToVisit() {
    // 返回此规则感兴趣的节点类型
    return Arrays.asList(Tree.Kind.NEW_CLASS, Tree.Kind.VARIABLE, Tree.Kind.ASSIGNMENT);
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
    }
  }

  private void checkNewClassTree(NewClassTree nct) {
//    log.debug("checkNewClassTree");
    // 检查新类实例是否匹配定义的构造函数匹配器
    if (!MATCHERS.contains(nct.symbolType().name())) {
      log.debug("checkNewClassTree MATCHERS.contains");
      nct.arguments().forEach(this::checkExpression);
    }
  }

  private static boolean isFileNameVariable(@Nullable IdentifierTree variable) {
    return variable != null && VARIABLE_NAME_PATTERN.matcher(variable.name()).find();
  }

  private void checkVariable(VariableTree tree) {
    // 检查变量名是否匹配文件名或路径模式
    log.debug("checkVariable:{}",tree.simpleName());
//    if (isFileNameVariable(tree.simpleName())) {
//      log.debug("checkVariable isFileNameVariable");
//      checkExpression(tree.initializer());
//    }
    checkExpression(tree.initializer());
  }

  private void checkAssignment(AssignmentExpressionTree tree) {
//    log.debug("checkAssignment:{}", tree);
    // 检查赋值表达式是否涉及文件名或路径变量，并且不属于注释的一部分
    if (!isPartOfAnnotation(tree)) {
      log.debug("checkAssignment isFileNameVariable");
      checkExpression(tree.expression());
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
    // 有关过滤规则的写在这里
    ExpressionTree newExpr = ExpressionUtils.skipParentheses(expr);
    if (!newExpr.is(Tree.Kind.STRING_LITERAL)) {
      return false;
    }
    String stringLiteral = LiteralUtils.trimQuotes(((LiteralTree) newExpr).value());
    log.debug("====================================\n字面量={}", stringLiteral);
    if(stringLiteral.contains("*") || stringLiteral.contains("$")) {
      return false;
    }

    // 首先判断是否是带协议和路径的 IPv4 回环地址，如果是则不报告问题
    if (IPV4_LOOPBACK_URI_PATTERN.matcher(stringLiteral).find()) {
      return false;
    }

    // 判断链接是否包含wsdl，是则不报告
    if (WSDL_Keyword.matcher(stringLiteral).find()) {
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
    expr.accept(new HardEcodedIPURICheck.StringConcatenationVisitor());
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
