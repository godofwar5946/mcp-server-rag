package org.example.rag.model;

/**
 * 索引处理结果。
 */
public record IndexResult(
        Long fileId,
        String fileName,
        boolean success,
        String message,
        boolean skipped
) {
    public IndexResult(Long fileId, String fileName, boolean success, String message) {
        this(fileId, fileName, success, message, false);
    }
}
