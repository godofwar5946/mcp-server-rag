package org.example.rag.model;

/**
 * 文件状态常量。
 */
public final class RagFileStatus {
    private RagFileStatus() {
    }

    public static final String PENDING = "PENDING";
    public static final String PARSED = "PARSED";
    public static final String INDEXED = "INDEXED";
    public static final String FAILED = "FAILED";
}
