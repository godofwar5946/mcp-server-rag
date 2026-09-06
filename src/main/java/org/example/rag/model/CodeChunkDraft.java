package org.example.rag.model;

/**
 * 代码语义切片，symbolLocalKey 在符号入库后转换成数据库主键。
 */
public record CodeChunkDraft(
        String symbolLocalKey,
        String language,
        CodeSymbolType symbolType,
        String symbolName,
        String qualifiedName,
        String signature,
        int startLine,
        int endLine,
        String content
) {
}
