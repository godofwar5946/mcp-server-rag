package org.example.rag.controller.dto;

/**
 * 按目录批量修改文件知识分类的请求。
 */
public record UpdateFolderKnowledgeTypeRequest(
        String knowledgeType,
        Boolean recursive
) {
    public boolean includeDescendants() {
        return recursive == null || recursive;
    }
}
