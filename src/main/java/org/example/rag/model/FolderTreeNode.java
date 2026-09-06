package org.example.rag.model;

import java.util.List;

/**
 * 管理端目录树节点，fileCount 只统计当前目录中的文件。
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FolderTreeNode(
        long id,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) Long parentId,
        String name,
        long fileCount,
        List<FolderTreeNode> children
) {
}
