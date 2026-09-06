package org.example.rag.service;

import org.example.rag.mapper.EmbeddingModelMapper;
import org.example.rag.model.EmbeddingModel;
import org.springframework.stereotype.Service;

/** 数据库中的活动模型是唯一来源，切换后重启也不会退回 application.yml 中的初始模型。 */
@Service
public class EmbeddingModelRegistry {
    private final EmbeddingModelMapper models;
    public EmbeddingModelRegistry(EmbeddingModelMapper models) { this.models=models; }
    public EmbeddingModel current() {
        EmbeddingModel model=models.current();
        if (model==null) throw new IllegalStateException("模型配置未初始化，请先完成 embedding-model-1 数据库迁移");
        return model;
    }
    public void pinForPublish(long expectedId) {
        Long active=models.pinActive();
        if (active==null || active!=expectedId)
            throw new IllegalStateException("向量模型已切换，本次旧模型索引未发布，请重试任务");
    }
}
