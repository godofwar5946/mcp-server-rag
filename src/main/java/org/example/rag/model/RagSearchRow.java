package org.example.rag.model;

/**
 * 向量检索原始结果（包含距离）。
 */
public record RagSearchRow(
        long fileId,
        String fileName,
        int chunkIndex,
        String content,
        double distance
) {
}
