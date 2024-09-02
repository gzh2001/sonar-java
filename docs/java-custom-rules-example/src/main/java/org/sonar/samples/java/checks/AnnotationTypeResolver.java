package org.sonar.samples.java.checks;

import org.sonar.plugins.java.api.tree.AnnotationTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.TypeTree;
import org.sonar.plugins.java.api.tree.Tree;


public class AnnotationTypeResolver {
  public static String getFullAnnotationTypeName(AnnotationTree annotationTree) {
    TypeTree typeTree = annotationTree.annotationType();
    return resolveFullName(typeTree);
  }

  private static String resolveFullName(TypeTree tree) {
    if (tree.is(Tree.Kind.MEMBER_SELECT)) {
      // 如果是成员选择表达式（点分式），递归处理前缀
      MemberSelectExpressionTree memberSelect = (MemberSelectExpressionTree) tree;
      return resolveFullName((TypeTree) memberSelect.expression()) + "." + memberSelect.identifier().name();
    } else if (tree.is(Tree.Kind.IDENTIFIER)) {
      // 如果是标识符，直接返回其名称
      return ((IdentifierTree) tree).name();
    }
    // 如果都不是，返回空字符串
    return "";
  }
}
