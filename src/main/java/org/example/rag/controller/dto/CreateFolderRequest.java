package org.example.rag.controller.dto;

/**
 * 创建目录请求；parentId 为空表示在根目录下创建。
 */
public record CreateFolderRequest(Long parentId, String name) {
}
