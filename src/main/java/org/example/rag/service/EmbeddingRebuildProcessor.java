package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.example.rag.mapper.EmbeddingModelMapper;
import org.example.rag.util.VectorUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 可恢复的顺序扫描；游标之外的新增/更新切片在发布前再次补齐。 */
@Service
public class EmbeddingRebuildProcessor {
    private final EmbeddingModelMapper models;
    private final EmbeddingRebuildStore store;
    private final OllamaEmbeddingClient embeddings;
    private final EmbeddingVectorIndex indexes;
    private final AppProperties properties;
    public EmbeddingRebuildProcessor(EmbeddingModelMapper models,EmbeddingRebuildStore store,
            OllamaEmbeddingClient embeddings,EmbeddingVectorIndex indexes,AppProperties properties) {
        this.models=models; this.store=store; this.embeddings=embeddings; this.indexes=indexes; this.properties=properties;
    }
    public void process(String id,String owner) {
        var run=models.run(id);
        var model=models.model(run.modelId());
        embeddings.verifyModel(model);
        long cursor=run.afterChunkId();
        while (true) {
            if (Thread.currentThread().isInterrupted() || models.heartbeat(id,owner)!=1)
                throw new CancellationException("任务已取消或租约已失效");
            var batch=models.missing(model.id(),cursor,properties.getEmbedding().getBatchSize());
            if (!batch.isEmpty()) {
                var vectors=embeddings.embedAll(batch.stream().map(EmbeddingModelMapper.ChunkText::content).toList(),model);
                if (vectors.size()!=batch.size()) throw new IllegalStateException("模型返回向量数量与切片数量不一致");
                var writes=new ArrayList<EmbeddingModelMapper.ChunkVector>();
                for (int i=0;i<batch.size();i++)
                    writes.add(new EmbeddingModelMapper.ChunkVector(batch.get(i).id(),VectorUtils.toVectorString(vectors.get(i))));
                cursor=batch.getLast().id();
                store.save(id,owner,writes,cursor);
                continue;
            }
            store.stage(id,owner,"BUILDING_INDEX");
            indexes.ensure(model);
            embeddings.verifyModel(model);
            if (store.activate(id,owner)) return;
            cursor=0;
        }
    }
}
