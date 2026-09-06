package org.example.rag.controller.dto;

/**
 * 移动文件请求；folderId 为空表示移动到根目录。
 */
public record MoveFileRequest(Long folderId) {
}
