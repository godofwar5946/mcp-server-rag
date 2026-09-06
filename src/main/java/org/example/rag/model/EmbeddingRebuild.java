package org.example.rag.model;

import java.time.LocalDateTime;

/** 模型切换是一个持久化全库任务，不受普通文件队列容量限制。 */
public record EmbeddingRebuild(String id, long modelId, String status, String stage, long totalChunks,
        long completedChunks, long afterChunkId, String message, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime finishedAt) { }
