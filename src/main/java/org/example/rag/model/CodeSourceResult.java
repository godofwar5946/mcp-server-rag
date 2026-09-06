package org.example.rag.model;

/**
 * 完整类、方法或 XML SQL 源码及其原始行号。
 */
public record CodeSourceResult(
        Long symbolId,
        Long fileId,
        String filePath,
        String language,
        String symbolType,
        String simpleName,
        String qualifiedName,
        String signature,
        Integer startLine,
        Integer endLine,
        String source,
        String numberedSource,
        boolean truncated,
        int totalChars
) {
}
