package org.example.rag.model;

import java.time.LocalDateTime;

/**
 * 文件列表展示数据。
 */
public record FileListItem(
        long id,
        String filename,
        Long folderId,
        String folderPath,
        String knowledgeType,
        long size,
        String status,
        LocalDateTime updatedAt,
        LocalDateTime indexedAt,
        long chunkCount,
        String errorMessage,
        long revision
) {
}
