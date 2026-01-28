package org.example.rag.model;

import java.time.LocalDateTime;

/**
 * 文件切片统计信息。
 */
public record FileChunkStat(
        long fileId,
        long chunkCount,
        LocalDateTime indexedAt
) {
}
