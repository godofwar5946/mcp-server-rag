package org.example.rag.model;

import java.time.LocalDateTime;

/**
 * 文件夹导出的轻量文件清单，不直接携带原始二进制内容。
 */
public record FolderExportFile(
        long id,
        Long folderId,
        String filename,
        long size,
        LocalDateTime updatedAt,
        boolean contentAvailable
) {
}
