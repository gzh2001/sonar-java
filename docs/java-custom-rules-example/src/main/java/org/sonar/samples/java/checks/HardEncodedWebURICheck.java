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

@Rule(key = "HardEncodedWebURICheck")
@Slf4j
public class HardEncodedWebURICheck extends IssuableSubscriptionVisitor {


  private static final String JAVA_LANG_STRING = "java.lang.String";
  /* 白名单
  private static final MethodMatchers MATCHERS = MethodMatchers.or(
    MethodMatchers.create()
      .ofTypes("java.net.URI")
      .constructor()
      .addParametersMatcher(JAVA_LANG_STRING).build(),
    MethodMatchers.create()
      .ofTypes("java.net.URL")
      .constructor()
      .addParametersMatcher(JAVA_LANG_STRING).build(),
    MethodMatchers.create()
      .ofTypes("java.io")
      .constructor()
      .addParametersMatcher(JAVA_LANG_STRING)
      .addParametersMatcher(ANY, JAVA_LANG_STRING)
      .build());
*/
//  黑名单。处理构造函数
  private final Set<String> MATCHERS = new HashSet<>(Arrays.asList(
             "QName"
           ));
  // 定义URI和IP地址的正则表达式
  private static final String SCHEME = "[a-zA-Z][a-zA-Z\\+\\.\\-]+";  // 协议头
  // 纯ip
  //  private static final String IP_REGEX = "([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?(/.*)?";
  // 非域名（ip）链接
  //  private static final String URI_REGEX = String.format("^%s://([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?(/.*)?$", SCHEME);

  // 文件变量特征值
  private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("filename|path|pureIP|website", Pattern.CASE_INSENSITIVE);
  // MIME类型
  private static final Pattern MIME_PATTERN = Pattern.compile("^(application|audio|image|message|multipart|text|video)/[a-zA-Z0-9.+-]+$", Pattern.CASE_INSENSITIVE);
  // 路径分隔符
  private static final Pattern PATH_DELIMETERS_PATTERN = Pattern.compile("\"/\"|\"//\"|\"\\\\\\\\\"|\"\\\\\\\\\\\\\\\\\"");
  // 回环地址
  private static final String IPV4_LOOPBACK_URI_REGEX = String.format("(%s://)?(127\\.0\\.0\\.1|192\\.168\\.\\d{1,3}\\.\\d{1,3})(:[0-9]{1,5})?(/.*)?", SCHEME);
//  private static final String IPV4_LOOPBACK_REGEX = "^(127\\.0\\.0\\.1|192\\.168\\.\\d{1,3}\\.\\d{1,3})(:[0-9]{1,5})?(/.*)?$";
  private static final Pattern IPV4_LOOPBACK_URI_PATTERN = Pattern.compile(IPV4_LOOPBACK_URI_REGEX );
  // WSDL关键字
  private static final Pattern WSDL_Keyword = Pattern.compile("wsdl", Pattern.CASE_INSENSITIVE);
  //文件夹路径，确保文件夹名称不包含以下字符：/, ?, %, *, :, \, |, ", <, >
  private static final String FOLDER_NAME = "[^/?%*:\\\\|\"<>]+";
  // 文件后缀名
  private static final String FILE_EXTENSION = "\\.[a-zA-Z0-9]+$";
  /*
  该正则表达式匹配的是本地 URI（统一资源标识符）。具体来说，它匹配以下几种形式的本地路径：
  1. ~/ 或 / 开头的路径。
  2. // 后跟一个或多个字母、数字、下划线或破折号的路径。
  3. （废弃）以协议头（如 http:）开头的路径。
  4. 以一个或多个 ./ 开头的路径。
  5. 以一个或多个字母、数字、下划线或破折号开头的路径。
   */
  // private static final String LOCAL_URI = String.format("^(~/|/|//[\\w-]+/|%s:/|(\\.*/)+|[\\w-]+/)(%s/)*%s/?", SCHEME, FOLDER_NAME, FOLDER_NAME);
  private static final String LOCAL_URI = String.format("^(~/|/|//[\\w-]+/|[A-Za-z]:/)(%s/)*%s%s",
    FOLDER_NAME, FOLDER_NAME, FILE_EXTENSION);
  // 同上，但反斜线
  // private static final String BACKSLASH_LOCAL_URI = String.format("^(~\\\\\\\\|\\\\\\\\\\\\\\\\[\\w-]+\\\\\\\\|%s:\\\\\\\\)(%s\\\\\\\\)*%s(\\\\\\\\)?", SCHEME, FOLDER_NAME, FOLDER_NAME);
  private static final String BACKSLASH_LOCAL_URI = String.format("^(~\\\\\\\\|\\\\\\\\\\\\\\\\[\\w-]+\\\\\\\\|[A-Za-z]:\\\\\\\\)(%s\\\\\\\\)*%s%s",
    FOLDER_NAME, FOLDER_NAME, FILE_EXTENSION);

  // 匹配Windows驱动器格式，如D:\\
  private static final String DISK_URI = "^[A-Za-z]:(/|\\\\)";

//  private static final Pattern URI_PATTERN = Pattern.compile(URI_REGEX + '|' + IP_REGEX + '|' + LOCAL_URI + '|' + DISK_URI + '|' + BACKSLASH_LOCAL_URI);
  private static final Pattern URI_PATTERN = Pattern.compile(LOCAL_URI + '|' + DISK_URI + '|' + BACKSLASH_LOCAL_URI);
  // 格式化日期
  private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile("(dd/MM/(yyyy|yy)|MM/dd/(yyyy|yy)|(yyyy|yy)/MM/dd)\\s?.*", Pattern.CASE_INSENSITIVE);

  private static final Set<String> MIMETYPES = new HashSet<>(Arrays.asList(
    "audio/aac",
    "application/x-abiword",
    "application/stream",
    "application/x-www-form-urlencoded",
    "image/apng",
    "application/x-freearc",
    "image/avif",
    "video/x-msvideo",
    "application/vnd.amazon.ebook",
    "application/octet-stream",
    "image/bmp",
    "application/x-bzip",
    "application/x-bzip2",
    "application/x-cdf",
    "application/x-csh",
    "text/css",
    "text/csv",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-fontobject",
    "application/epub+zip",
    "application/gzip",
    "image/gif",
    "text/html",
    "text/xml",
    "image/vnd.microsoft.icon",
    "text/calendar",
    "application/java-archive",
    "image/jpeg",
    "text/javascript",
    "application/json",
    "application/ld+json",
    "audio/midi",
    "audio/x-midi",
    "audio/mpeg",
    "video/mp4",
    "video/mpeg",
    "application/vnd.apple.installer+xml",
    "application/vnd.oasis.opendocument.presentation",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.text",
    "audio/ogg",
    "video/ogg",
    "application/ogg",
    "audio/opus",
    "font/otf",
    "image/png",
    "application/pdf",
    "application/x-httpd-php",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.rar",
    "application/rtf",
    "application/x-sh",
    "image/svg+xml",
    "application/x-tar",
    "image/tiff",
    "video/mp2t",
    "font/ttf",
    "text/plain",
    "text/json",
    "application/vnd.visio",
    "audio/wav",
    "audio/webm",
    "video/webm",
    "image/webp",
    "font/woff",
    "font/woff2",
    "application/xhtml+xml",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/xml",
    "application/vnd.mozilla.xul+xml",
    "application/zip",
    "video/3gpp",
    "audio/3gpp",
    "video/3gpp2",
    "audio/3gpp2",
    "application/x-7z-compressed"
  ));



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
    // else if (tree.is(Tree.Kind.STRING_LITERAL)) {
    //   checkStringLiteral((LiteralTree) tree);
    // }
  }

  private void checkNewClassTree(NewClassTree nct) {
//    log.debug("checkNewClassTree");
    // 检查新类实例是否匹配定义的构造函数匹配器
    if (!MATCHERS.contains(nct.symbolType().name())) {
      log.debug("checkNewClassTree MATCHERS.contains");
      nct.arguments().forEach(this::checkExpression);
    }
  }

  private void checkVariable(VariableTree tree) {
    // 检查变量名是否匹配文件名或路径模式
    log.debug("checkVariable:{}",tree.simpleName());
//    if (isFileNameVariable(tree.simpleName())) {
//      log.debug("checkVariable isFileNameVariable");
//      checkExpression(tree.initializer());
//    }
    checkExpression(tree.initializer());
//    checkExpression(tree.initializer());
  }

  private void checkAssignment(AssignmentExpressionTree tree) {
//    log.debug("checkAssignment:{}", tree);
    // 检查赋值表达式是否涉及文件名或路径变量，并且不属于注释的一部分
    if (!isPartOfAnnotation(tree)) {
      log.debug("checkAssignment isFileNameVariable");
      checkExpression(tree.expression());
    }
  }

  // private void checkStringLiteral(LiteralTree tree) {
  //   /*
  //    * 在字符串字面量扫描阶段进行过滤（有关WSDL导致的链接大多在该入口）
  //    * 关于QName -- 从链接字符串往上的树节点大多类似org.sonar.java.ast.parser.ArgumentListTreeImpl => org.sonar.java.model.expression.NewClassTreeImpl => org.sonar.java.model.declaration.VariableTreeImpl
  //    *              如果NewClassTree节点中，新建的类类名为QName则跳过扫描
  //    * 关于注解 -- 从链接字符串往上的树节点大多类似org.sonar.java.model.expression.AssignmentExpressionTreeImpl => org.sonar.java.ast.parser.ArgumentListTreeImpl => org.sonar.java.model.declaration.AnnotationTreeImpl
  //    *            如果AnnotationTree节点中，注解类型为javax.jws.WebService或者javax.xml.bind.annotation.XmlElementDecl就跳过扫描
  //    *
  //    * 注解示例：
  //    * Tree => org.sonar.java.model.expression.AssignmentExpressionTreeImpl@4cecbf3e
  //    * => org.sonar.java.ast.parser.ArgumentListTreeImpl@54039b8a
  //    * => org.sonar.java.model.declaration.AnnotationTreeImpl@7625f4a7
  //    * => org.sonar.java.model.declaration.ModifiersTreeImpl@183c6db3
  //    * => org.sonar.java.model.declaration.ClassTreeImpl@1015bd66
  //    * => org.sonar.java.model.JavaTree$CompilationUnitTreeImpl@3f314bad
  //    */

  //   Tree parent = tree.parent();
  //   while (parent != null){
  //     if (parent instanceof NewClassTree || parent instanceof AnnotationTree) {
  //       break;
  //     }
  //     parent = parent.parent();
  //   }
  //   if (parent != null) {
  //     // 判断父节点的类型并输出相关信息
  //     if (parent instanceof NewClassTree newClassTree) {
  //       TypeSymbol typeSymbol = null;
  //       typeSymbol = newClassTree.symbolType().symbol();
  //       // log.debug("===================typeSymbol.name()={}",typeSymbol.name());
  //       if(typeSymbol.name().equals("QName")){return ;};
  //     } else {
  //       // 校验注解
  //       AnnotationTree annotation = (AnnotationTree) parent;
  //       // String annotationType = annotation.annotationType().toString();
  //       String annotationType = AnnotationTypeResolver.getFullAnnotationTypeName(annotation);
  //       // log.debug("===========annotationType={}",annotationType);
  //       final Set<String> IGNORED_ANNOTATIONS = new HashSet<>(Arrays.asList(
  //         "WebService",
  //         "XmlElementDecl",
  //         "XmlType",
  //         "WebServiceClient",
  //         "WebParam",
  //         "XmlSchema",
  //         "javax.xml.bind.annotation.XmlSchema"
  //       ));
  //       if (IGNORED_ANNOTATIONS.contains(annotationType)) {
  //         return;
  //       }
  //     }
  //   }

  //   // 检查字符串字面值是否包含硬编码的URI
  //   if (isHardcodedURI(tree)) {
  //     reportHardcodedURI(tree);
  //   }
  // }

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
    // 日期格式过滤
//    if (DATE_FORMAT_STRINGS.contains(stringLiteral.toLowerCase().strip())) {
//      return false;
//    }
    if (DATE_FORMAT_PATTERN.matcher(stringLiteral.strip()).find()) {
      return false;
    }
    /*
    // MIME类型过滤
    if (MIME_PATTERN.matcher(stringLiteral.toLowerCase().strip()).find()) {
      for(String m : MIMETYPES) {
        if(stringLiteral.toLowerCase().strip().contains(m.toLowerCase())) {
          return false;
        }
      }
//      if(MIMETYPES.contains(stringLiteral.strip())) {
//        return false;
//      }
//      return false;
    }
     */
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
