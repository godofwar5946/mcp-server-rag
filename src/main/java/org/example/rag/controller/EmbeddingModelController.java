package org.example.rag.controller;

import org.example.rag.config.AppProperties;
import org.example.rag.mapper.EmbeddingModelMapper;
import org.example.rag.model.*;
import org.example.rag.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 继承管理 API 的 Session、CSRF 和 IP 白名单保护。 */
@RestController
@RequestMapping("/api/embedding")
public class EmbeddingModelController {
    private final EmbeddingModelMapper models;
    private final EmbeddingModelRegistry registry;
    private final OllamaEmbeddingClient embeddings;
    private final EmbeddingRebuildStore store;
    private final AppProperties properties;
    public EmbeddingModelController(EmbeddingModelMapper models,EmbeddingModelRegistry registry,
            OllamaEmbeddingClient embeddings,EmbeddingRebuildStore store,AppProperties properties) {
        this.models=models; this.registry=registry; this.embeddings=embeddings; this.store=store; this.properties=properties;
    }
    @GetMapping("/models")
    public Map<String,Object> catalog() { return ok(embeddings.catalog()); }
    @GetMapping("/status")
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,timeout=10)
    public Map<String,Object> status() {
        var run=models.latest();
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("active",registry.current()); data.put("run",run);
        data.put("target",run==null?null:models.model(run.modelId()));
        data.put("totalChunks",models.chunkCount());
        data.put("enabled",properties.getIndexing().isEnabled());
        return ok(data);
    }
    @PostMapping("/probe")
    public Map<String,Object> probe(@RequestBody ModelRequest request) {
        return ok(embeddings.probe(request.modelName(),request.outputDimension(),request.queryInstruction()));
    }
    @PostMapping("/switch")
    public Map<String,Object> switchModel(@RequestBody SwitchRequest request) {
        requireEnabled();
        if (models.hasPending()) throw new IllegalStateException("已有模型重建任务，请先完成、重试或取消");
        EmbeddingModel model=embeddings.probe(request.modelName(),request.outputDimension(),request.queryInstruction());
        if (!Objects.equals(model.modelDigest(),request.expectedDigest()) || model.dimension()!=request.expectedDimension())
            throw new IllegalStateException("目标模型版本或维度已变化，请重新验证后切换");
        return ok(store.start(model,request.expectedActiveId()));
    }
    @PostMapping("/rebuilds/{id}/retry")
    public Map<String,Object> retry(@PathVariable UUID id) { requireEnabled(); return ok(store.retry(id.toString())); }
    @PostMapping("/rebuilds/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable UUID id) { return ok(store.cancel(id.toString())); }
    private void requireEnabled() {
        if (!properties.getIndexing().isEnabled()) throw new IllegalStateException("后台索引工作器已禁用，请启用 app.indexing.enabled 后重试");
    }
    private Map<String,Object> ok(Object data) { return Map.of("success",true,"data",data); }
    public record ModelRequest(String modelName,Integer outputDimension,String queryInstruction) { }
    public record SwitchRequest(String modelName,Integer outputDimension,String queryInstruction,
                                long expectedActiveId,String expectedDigest,int expectedDimension) { }
}
