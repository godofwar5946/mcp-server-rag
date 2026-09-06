package org.example.rag.service;

/** 工作线程在阶段/批次边界报告进度，同时检查任务是否已被取消。 */
@FunctionalInterface
public interface IndexProgress {
    IndexProgress NONE = (stage, completed, total) -> { };
    void update(String stage, int completed, int total);
}
