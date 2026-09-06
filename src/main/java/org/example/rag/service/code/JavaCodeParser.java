package org.example.rag.service.code;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.example.rag.model.CodeSymbolDraft;
import org.example.rag.model.CodeSymbolType;
import org.example.rag.model.ParsedCodeFile;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 使用 JDK 21 Compiler Tree API 解析 Java 声明，不编译或执行上传的代码。
 */
@Component
public class JavaCodeParser implements CodeFileParser {

    @Override
    public boolean supports(String extension) {
        return "java".equalsIgnoreCase(extension);
    }

    @Override
    public ParsedCodeFile parse(String filename, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前运行环境缺少 JDK Compiler，请使用完整 JDK 21 启动服务");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject sourceFile = new StringJavaFileObject(filename, source);
        JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostics,
                List.of("-proc:none"),
                null,
                List.of(sourceFile)
        );

        try {
            Iterable<? extends CompilationUnitTree> parsedUnits = task.parse();
            List<CompilationUnitTree> units = new ArrayList<>();
            parsedUnits.forEach(units::add);
            rejectSyntaxErrors(filename, diagnostics);
            if (units.isEmpty()) {
                throw new IllegalArgumentException("Java 源码中没有可解析的编译单元: " + filename);
            }

            CompilationUnitTree unit = units.get(0);
            Trees trees = Trees.instance(task);
            DocTrees docTrees = DocTrees.instance(task);
            SymbolScanner scanner = new SymbolScanner(
                    source,
                    unit,
                    trees.getSourcePositions(),
                    docTrees
            );
            scanner.scan(unit, SymbolContext.root());
            return new ParsedCodeFile(filename, "JAVA", source, List.copyOf(scanner.symbols()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Java 源码解析失败: " + filename + "，" + e.getMessage(), e);
        }
    }

    private void rejectSyntaxErrors(String filename, DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .limit(5)
                .map(item -> "第 " + item.getLineNumber() + " 行: " + item.getMessage(Locale.SIMPLIFIED_CHINESE))
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Java 源码语法错误 " + filename + "：" + String.join("；", errors));
        }
    }

    private static final class SymbolScanner extends TreePathScanner<Void, SymbolContext> {

        private final String source;
        private final CompilationUnitTree unit;
        private final SourcePositions sourcePositions;
        private final DocTrees docTrees;
        private final List<CodeSymbolDraft> symbols = new ArrayList<>();
        private int sequence;

        private SymbolScanner(String source,
                              CompilationUnitTree unit,
                              SourcePositions sourcePositions,
                              DocTrees docTrees) {
            this.source = source;
            this.unit = unit;
            this.sourcePositions = sourcePositions;
            this.docTrees = docTrees;
        }

        private List<CodeSymbolDraft> symbols() {
            return symbols;
        }

        @Override
        public Void visitClass(ClassTree node, SymbolContext context) {
            String simpleName = node.getSimpleName().toString();
            if (simpleName.isBlank()) {
                // 匿名类没有稳定的可查询名称，避免把其方法误归到外层类。
                return null;
            }
            SourceRange range = rangeOf(node);
            if (range == null) {
                return super.visitClass(node, context);
            }

            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            String qualifiedName;
            if (context.qualifiedClassName() != null) {
                qualifiedName = context.qualifiedClassName() + "." + simpleName;
            } else if (packageName.isBlank()) {
                qualifiedName = simpleName;
            } else {
                qualifiedName = packageName + "." + simpleName;
            }
            String localKey = nextKey("type");
            CodeSymbolType symbolType = classType(node.getKind());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("packageName", packageName);
            metadata.put("modifiers", node.getModifiers().getFlags().stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList());
            metadata.put("extends", node.getExtendsClause() == null ? null : node.getExtendsClause().toString());
            metadata.put("implements", node.getImplementsClause().stream().map(Object::toString).toList());
            putDocComment(metadata);

            symbols.add(toDraft(
                    localKey,
                    context.parentLocalKey(),
                    symbolType,
                    simpleName,
                    qualifiedName,
                    simpleName,
                    range,
                    metadata
            ));
            return super.visitClass(node, new SymbolContext(localKey, qualifiedName, simpleName));
        }

        @Override
        public Void visitMethod(MethodTree node, SymbolContext context) {
            if (context.qualifiedClassName() == null) {
                return super.visitMethod(node, context);
            }
            SourceRange range = rangeOf(node);
            if (range == null) {
                return super.visitMethod(node, context);
            }

            boolean constructor = node.getReturnType() == null || "<init>".contentEquals(node.getName());
            String simpleName = constructor ? context.classSimpleName() : node.getName().toString();
            List<String> parameterTypes = node.getParameters().stream()
                    .map(VariableTree::getType)
                    .map(Object::toString)
                    .toList();
            String signature = simpleName + "(" + String.join(", ", parameterTypes) + ")";
            String qualifiedName = context.qualifiedClassName() + "#" + signature;
            String localKey = nextKey("method");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("className", context.qualifiedClassName());
            metadata.put("returnType", node.getReturnType() == null ? null : node.getReturnType().toString());
            metadata.put("parameterTypes", parameterTypes);
            metadata.put("parameterNames", node.getParameters().stream()
                    .map(parameter -> parameter.getName().toString())
                    .toList());
            metadata.put("throws", node.getThrows().stream().map(Object::toString).toList());
            metadata.put("modifiers", node.getModifiers().getFlags().stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList());
            putDocComment(metadata);

            symbols.add(toDraft(
                    localKey,
                    context.parentLocalKey(),
                    constructor ? CodeSymbolType.JAVA_CONSTRUCTOR : CodeSymbolType.JAVA_METHOD,
                    simpleName,
                    qualifiedName,
                    signature,
                    range,
                    metadata
            ));
            return super.visitMethod(node, new SymbolContext(
                    localKey,
                    context.qualifiedClassName(),
                    context.classSimpleName()
            ));
        }

        private CodeSymbolDraft toDraft(String localKey,
                                        String parentLocalKey,
                                        CodeSymbolType symbolType,
                                        String simpleName,
                                        String qualifiedName,
                                        String signature,
                                        SourceRange range,
                                        Map<String, Object> metadata) {
            Map<String, Object> safeMetadata = new LinkedHashMap<>();
            metadata.forEach((key, value) -> {
                if (value != null) {
                    safeMetadata.put(key, value);
                }
            });
            return new CodeSymbolDraft(
                    localKey,
                    parentLocalKey,
                    "JAVA",
                    symbolType,
                    simpleName,
                    qualifiedName,
                    signature,
                    range.startLine(),
                    range.endLine(),
                    range.startColumn(),
                    range.endColumn(),
                    range.startOffset(),
                    range.endOffset(),
                    Map.copyOf(safeMetadata)
            );
        }

        private SourceRange rangeOf(Tree tree) {
            long start = sourcePositions.getStartPosition(unit, tree);
            long end = sourcePositions.getEndPosition(unit, tree);
            if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS || start < 0 || end <= start
                    || end > source.length()) {
                return null;
            }
            int adjustedStart = includeLeadingJavaDoc((int) start);
            LineMap lineMap = unit.getLineMap();
            int inclusiveEnd = (int) end - 1;
            return new SourceRange(
                    adjustedStart,
                    (int) end,
                    (int) lineMap.getLineNumber(adjustedStart),
                    (int) lineMap.getLineNumber(inclusiveEnd),
                    (int) lineMap.getColumnNumber(adjustedStart),
                    (int) lineMap.getColumnNumber(inclusiveEnd)
            );
        }

        private int includeLeadingJavaDoc(int declarationStart) {
            int commentEnd = source.lastIndexOf("*/", declarationStart);
            if (commentEnd < 0 || !source.substring(commentEnd + 2, declarationStart).isBlank()) {
                return declarationStart;
            }
            int commentStart = source.lastIndexOf("/**", commentEnd);
            return commentStart < 0 ? declarationStart : commentStart;
        }

        private void putDocComment(Map<String, Object> metadata) {
            try {
                if (getCurrentPath() != null && docTrees.getDocCommentTree(getCurrentPath()) != null) {
                    metadata.put("docComment", docTrees.getDocCommentTree(getCurrentPath()).toString());
                }
            } catch (RuntimeException ignored) {
                // JavaDoc 不影响源码结构索引。
            }
        }

        private String nextKey(String prefix) {
            return prefix + ":" + (++sequence);
        }

        private CodeSymbolType classType(Tree.Kind kind) {
            return switch (kind) {
                case INTERFACE -> CodeSymbolType.JAVA_INTERFACE;
                case ENUM -> CodeSymbolType.JAVA_ENUM;
                case RECORD -> CodeSymbolType.JAVA_RECORD;
                case ANNOTATION_TYPE -> CodeSymbolType.JAVA_ANNOTATION;
                default -> CodeSymbolType.JAVA_CLASS;
            };
        }
    }

    private record SymbolContext(String parentLocalKey,
                                 String qualifiedClassName,
                                 String classSimpleName) {

        private static SymbolContext root() {
            return new SymbolContext(null, null, null);
        }
    }

    private record SourceRange(int startOffset,
                               int endOffset,
                               int startLine,
                               int endLine,
                               int startColumn,
                               int endColumn) {
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {

        private final String source;
        private final String displayName;

        private StringJavaFileObject(String displayName, String source) {
            super(URI.create("string:///UploadedSource.java"), Kind.SOURCE);
            this.source = source;
            this.displayName = displayName;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }

        @Override
        public String getName() {
            return displayName;
        }
    }
}
