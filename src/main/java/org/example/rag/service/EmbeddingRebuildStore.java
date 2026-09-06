package org.example.rag.service;

import org.example.rag.mapper.EmbeddingModelMapper;
import org.example.rag.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 全库模型切换的事务边界。耗时的模型调用与索引 DDL 均在事务外执行。 */
@Service
public class EmbeddingRebuildStore {
    private final EmbeddingModelMapper models;
    public EmbeddingRebuildStore(EmbeddingModelMapper models) { this.models=models; }

    @Transactional
    public EmbeddingRebuild start(EmbeddingModel candidate, long expectedActiveId) {
        Long active=models.lockState();
        if (active==null || active!=expectedActiveId)
            throw new IllegalStateException("当前模型已变化，请刷新模型管理后重试");
        if (models.hasPending()) throw new IllegalStateException("已有模型重建任务，请等待完成，或先重试、取消该任务");
        long target=models.createModel(candidate);
        String id=UUID.randomUUID().toString();
        models.createRun(id,target);
        return models.run(id);
    }

    @Transactional
    public void save(String id,String owner,List<EmbeddingModelMapper.ChunkVector> vectors,long afterId) {
        EmbeddingRebuild run=requireLease(id,owner);
        EmbeddingModel model=models.model(run.modelId());
        int added=0;
        if (!vectors.isEmpty()) {
            // 与文件删除/更新互斥，防止 SELECT 与外键检查之间文件恰好消失。
            Set<Long> alive=new HashSet<>(models.pinChunks(vectors.stream().map(EmbeddingModelMapper.ChunkVector::chunkId).toList()));
            List<EmbeddingModelMapper.ChunkVector> remaining=vectors.stream().filter(v->alive.contains(v.chunkId())).toList();
            if (!remaining.isEmpty()) added=models.saveVectors(model.id(),model.dimension(),remaining);
        }
        // 每批只增量累计；阶段切换时再核对全库数量，避免每 16 个切片就扫描全库 COUNT。
        models.advance(id,afterId,added);
    }

    @Transactional
    public void stage(String id,String owner,String stage) {
        var run=requireLease(id,owner);
        models.progress(id,run.afterChunkId(),stage);
    }

    @Transactional
    public boolean activate(String id,String owner) {
        // 与文件发布约定：先锁模型状态，后锁任务/文件。
        Long previous=models.lockState();
        EmbeddingRebuild run=requireLease(id,owner);
        if (!models.missing(run.modelId(),0,1).isEmpty()) {
            models.progress(id,0,"CATCHING_UP");
            return false;
        }
        EmbeddingModel model=models.model(run.modelId());
        models.retire(previous);
        models.activate(model.id());
        models.updateIdentity(model.modelName()+"|"+model.dimension());
        models.updateFileModel(model.modelName());
        models.progress(id,run.afterChunkId(),"FINISHED");
        if (models.finish(id,owner,"SUCCEEDED","全库向量索引已重建，模型已切换")!=1)
            throw new CancellationException("租约已失效，未切换模型");
        return true;
    }

    @Transactional
    public EmbeddingRebuild retry(String id) {
        models.lockState();
        if (models.retry(id)!=1) throw new IllegalStateException("仅失败的模型重建任务可以重试");
        return models.run(id);
    }

    @Transactional
    public EmbeddingRebuild cancel(String id) {
        models.lockState();
        if (models.cancel(id)!=1) throw new IllegalStateException("该任务已经结束，请刷新状态");
        return models.run(id);
    }

    private EmbeddingRebuild requireLease(String id,String owner) {
        EmbeddingRebuild run=models.lockRun(id,owner);
        if (run==null || Thread.currentThread().isInterrupted())
            throw new CancellationException("任务已取消或租约已失效");
        return run;
    }
}
