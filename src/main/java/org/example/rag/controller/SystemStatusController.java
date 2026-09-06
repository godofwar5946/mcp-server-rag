package org.example.rag.controller;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.rag.config.AppProperties;
import org.example.rag.mapper.SystemStatusMapper;
import org.example.rag.service.EmbeddingModelRegistry;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 只返回运行状态和公开能力，不把账号、密码、连接串或 Session 信息发送给浏览器。 */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {
    private final SystemStatusMapper mapper;
    private final AppProperties properties;
    private final MeterRegistry metrics;
    private final EmbeddingModelRegistry models;
    public SystemStatusController(SystemStatusMapper mapper,AppProperties properties,MeterRegistry metrics,EmbeddingModelRegistry models) {
        this.mapper=mapper; this.properties=properties; this.metrics=metrics;
        this.models=models;
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("counts",mapper.counts()); data.put("database","UP");
        var model=models.current();
        data.put("model",model.modelName()); data.put("dimension",model.dimension());
        data.put("mcpEndpoint",properties.getMcp().getEndpoint()); data.put("version",properties.getMcp().getVersion());
        data.put("chunkSize",properties.getChunking().getChunkSize()); data.put("overlap",properties.getChunking().getOverlap());
        data.put("maxTextChars",properties.getParsing().getMaxTextChars()); data.put("workers",properties.getIndexing().getWorkers());
        data.put("batchSize",properties.getEmbedding().getBatchSize()); data.put("search",timer("rag.search.duration"));
        data.put("embedding",timer("rag.embedding.duration")); data.put("embeddingCacheHits",counter("rag.embedding.cache.hits"));
        data.put("embeddingErrors",counter("rag.embedding.errors"));
        data.put("retentionDays",properties.getIndexing().getRetentionDays());
        return Map.of("success",true,"data",data);
    }
    private Map<String,Object> timer(String name) {
        var timer=metrics.find(name).timer();
        if (timer==null) return Map.of("count",0,"averageMillis",0,"maxMillis",0,"p95Millis",0);
        return Map.of("count",timer.count(),"averageMillis",timer.count()==0?0:timer.totalTime(TimeUnit.MILLISECONDS)/timer.count(),
                "maxMillis",timer.max(TimeUnit.MILLISECONDS),"p95Millis",Arrays.stream(timer.takeSnapshot().percentileValues())
                    .filter(value->value.percentile()==0.95).mapToDouble(value->value.value(TimeUnit.MILLISECONDS)).findFirst().orElse(0));
    }
    private double counter(String name) { var counter=metrics.find(name).counter(); return counter==null?0:counter.count(); }
}
