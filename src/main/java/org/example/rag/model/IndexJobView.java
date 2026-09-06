package org.example.rag.model;

import java.time.LocalDateTime;

/** 任务列表不携带原始文件或向量，避免轮询时重复传输大字段。 */
public record IndexJobView(String id, String batchId, long fileId, String fileName, Long folderId,
                           String status, String stage, int completedChunks, int totalChunks,
                           int attempts, String message, LocalDateTime createdAt,
                           LocalDateTime updatedAt, LocalDateTime finishedAt) { }
