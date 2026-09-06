package org.example.rag.model;

/**
 * 代码符号查询结果，包含可用于定位源码的文件路径和字符范围。
 */
public record CodeSymbolView(
        Long id,
        Long fileId,
        Long parentId,
        String fileName,
        String folderPath,
        String filePath,
        String language,
        String symbolType,
        String simpleName,
        String qualifiedName,
        String signature,
        Integer startLine,
        Integer endLine,
        Integer startColumn,
        Integer endColumn,
        Integer startOffset,
        Integer endOffset
) {
}
