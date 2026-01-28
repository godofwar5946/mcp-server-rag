package org.example.rag.model;

/**
 * 向量检索结果：包含文件名、chunk 索引、内容与相似度。
 */
public record RagSearchResult(
        long fileId,
        String fileName,
        int chunkIndex,
        String content,
        double similarity
) {
}
