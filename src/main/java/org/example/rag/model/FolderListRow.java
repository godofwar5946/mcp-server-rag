package org.example.rag.model;

/**
 * 构建目录树所需的扁平查询结果。
 */
public record FolderListRow(
        long id,
        Long parentId,
        String name,
        long fileCount
) {
}
