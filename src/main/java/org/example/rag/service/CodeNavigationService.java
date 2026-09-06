package org.example.rag.service;

import org.example.rag.mapper.RagCodeSymbolMapper;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.CodeSourceResult;
import org.example.rag.model.CodeSymbolView;
import org.example.rag.model.RagFileEntity;
import org.example.rag.util.SourceTextIndex;
import org.springframework.stereotype.Service;
import org.example.rag.config.AppProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按符号名、文件路径和代码行精确返回完整类、方法或 XML SQL。
 */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly=true,
        isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,timeout=10)
public class CodeNavigationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final RagCodeSymbolMapper codeSymbolMapper;
    private final RagFileMapper fileMapper;
    private final RagFolderService folderService;
    private final AppProperties properties;

    public CodeNavigationService(RagCodeSymbolMapper codeSymbolMapper,
                                 RagFileMapper fileMapper,
                                 RagFolderService folderService, AppProperties properties) {
        this.codeSymbolMapper = codeSymbolMapper;
        this.fileMapper = fileMapper;
        this.folderService = folderService;
        this.properties = properties;
    }

    public List<CodeSymbolView> search(String query, String category, Long folderId, Integer limit) {
        String normalizedQuery = requireText(query, "搜索内容不能为空");
        return codeSymbolMapper.search(
                normalizedQuery,
                normalizeCategory(category),
                normalizeFolderId(folderId),
                normalizeLimit(limit)
        );
    }

    public CodeSourceResult getSymbol(long symbolId) {
        CodeSymbolView symbol = codeSymbolMapper.findViewById(symbolId);
        if (symbol == null) {
            throw new IllegalArgumentException("代码符号不存在: " + symbolId);
        }
        return toSource(symbol);
    }

    public List<CodeSourceResult> getClasses(String className, Long folderId) {
        String normalizedName = requireText(className, "类名不能为空");
        return toSources(codeSymbolMapper.findClasses(
                normalizedName,
                normalizeFolderId(folderId),
                DEFAULT_LIMIT
        ));
    }

    public List<CodeSourceResult> getMethods(String className,
                                             String methodName,
                                             String signature,
                                             Long folderId) {
        String normalizedMethod = requireText(methodName, "方法名不能为空");
        return toSources(codeSymbolMapper.findMethods(
                trimToNull(className),
                normalizedMethod,
                trimToNull(signature),
                normalizeFolderId(folderId),
                DEFAULT_LIMIT
        ));
    }

    public List<CodeSourceResult> getSqlStatements(String namespace,
                                                   String statementId,
                                                   Long folderId) {
        String normalizedId = requireText(statementId, "SQL ID 不能为空");
        return toSources(codeSymbolMapper.findSqlStatements(
                trimToNull(namespace),
                normalizedId,
                normalizeFolderId(folderId),
                DEFAULT_LIMIT
        ));
    }

    public List<CodeSourceResult> getByPathAndLine(String filePath, int line, Long folderId) {
        String normalizedPath = requireText(filePath, "文件路径不能为空").replace('\\', '/');
        if (line <= 0) {
            throw new IllegalArgumentException("代码行号必须大于 0");
        }
        List<CodeSymbolView> symbols = codeSymbolMapper.findByPathAndLine(
                normalizedPath,
                line,
                normalizeFolderId(folderId),
                MAX_LIMIT
        );
        Map<Long, CodeSymbolView> innermostByFile = new LinkedHashMap<>();
        for (CodeSymbolView symbol : symbols) {
            innermostByFile.putIfAbsent(symbol.fileId(), symbol);
        }
        return toSources(List.copyOf(innermostByFile.values()));
    }

    public List<CodeSymbolView> outline(long fileId) {
        if (fileMapper.selectMetadata(fileId) == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return codeSymbolMapper.outline(fileId);
    }

    private List<CodeSourceResult> toSources(List<CodeSymbolView> symbols) {
        Map<Long,RagFileEntity> sources = new LinkedHashMap<>();
        java.util.ArrayList<CodeSourceResult> results = new java.util.ArrayList<>();
        int remaining = properties.getRag().getMaxCodeChars();
        for (CodeSymbolView symbol : symbols) {
            if (remaining < 100) break;
            RagFileEntity file = sources.computeIfAbsent(symbol.fileId(), fileMapper::selectSource);
            CodeSourceResult result = toSource(symbol, file, remaining);
            results.add(result);
            remaining -= result.source().length();
        }
        return results;
    }

    private CodeSourceResult toSource(CodeSymbolView symbol) {
        return toSource(symbol, fileMapper.selectSource(symbol.fileId()), properties.getRag().getMaxCodeChars());
    }

    private CodeSourceResult toSource(CodeSymbolView symbol, RagFileEntity file, int maxChars) {
        if (file == null || file.getParsedText() == null) {
            throw new IllegalStateException("源码内容不存在或尚未解析: " + symbol.filePath());
        }
        String fullSource = file.getParsedText();
        int start = valueOr(symbol.startOffset(), -1);
        int end = valueOr(symbol.endOffset(), -1);
        if (start < 0 || end < start || end > fullSource.length()) {
            SourceTextIndex index = new SourceTextIndex(fullSource);
            start = index.lineStartOffset(valueOr(symbol.startLine(), 1));
            end = index.lineEndOffset(valueOr(symbol.endLine(), valueOr(symbol.startLine(), 1)));
        }
        int originalLength = end - start;
        boolean truncated = originalLength > maxChars;
        String source = fullSource.substring(start, Math.min(end, start + maxChars));
        return new CodeSourceResult(
                symbol.id(),
                symbol.fileId(),
                symbol.filePath(),
                symbol.language(),
                symbol.symbolType(),
                symbol.simpleName(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startLine(),
                symbol.endLine(),
                source,
                addLineNumbers(
                        source,
                        valueOr(symbol.startLine(), 1),
                        valueOr(symbol.startColumn(), 1)
                ),
                truncated,
                originalLength
        );
    }

    private String addLineNumbers(String source, int firstLine, int firstColumn) {
        String[] lines = source.split("\\n", -1);
        int length = lines.length;
        if (length > 1 && lines[length - 1].isEmpty()) {
            length--;
        }
        int width = Math.max(4, Integer.toString(firstLine + Math.max(length - 1, 0)).length());
        StringBuilder numbered = new StringBuilder(source.length() + length * (width + 3));
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                numbered.append('\n');
            }
            numbered.append(String.format(Locale.ROOT, "%" + width + "d | ", firstLine + i));
            if (i == 0 && firstColumn > 1) {
                numbered.append(" ".repeat(firstColumn - 1));
            }
            numbered.append(lines[i]);
        }
        return numbered.toString();
    }

    private Long normalizeFolderId(Long folderId) {
        if (folderId == null) {
            return null;
        }
        if (folderId < 0) {
            throw new IllegalArgumentException("目录 ID 不能小于 0");
        }
        if (folderId > 0) {
            folderService.requireFolder(folderId);
        }
        return folderId;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String normalizeCategory(String category) {
        String normalized = trimToNull(category);
        if (normalized == null || "ALL".equalsIgnoreCase(normalized)) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!List.of("CLASS", "METHOD", "SQL").contains(normalized)) {
            throw new IllegalArgumentException("符号分类仅支持 CLASS、METHOD、SQL");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        if (normalized.length() > 1536) {
            throw new IllegalArgumentException("查询内容过长");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
