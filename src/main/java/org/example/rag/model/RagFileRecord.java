package org.example.rag.model;

import java.time.LocalDateTime;

/**
 * 文件记录（数据库 rag_file 对应）。
 */
public record RagFileRecord(
        Long id,
        String filename,
        String contentType,
        long size,
        byte[] contentBytes,
        String parsedText,
        String status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime indexedAt
) {
}
