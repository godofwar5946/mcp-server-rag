package org.example.rag.model;

/** 工作线程每次只加载一个任务的原始内容。 */
public record IndexJobPayload(String id, long fileId, long expectedRevision, byte[] contentBytes,
                              String contentType, boolean forceReindex, String leaseOwner) { }
