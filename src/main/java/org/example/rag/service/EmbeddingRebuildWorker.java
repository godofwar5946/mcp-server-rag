package org.example.rag.service;

import jakarta.annotation.PreDestroy;
import org.example.rag.mapper.EmbeddingModelMapper;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(prefix="app.indexing",name="enabled",havingValue="true",matchIfMissing=true)
public class EmbeddingRebuildWorker {
    private static final Logger log=LoggerFactory.getLogger(EmbeddingRebuildWorker.class);
    private final EmbeddingModelMapper models;
    private final EmbeddingRebuildProcessor processor;
    private final EmbeddingVectorIndex indexes;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("rag-model-rebuild").factory());
    private volatile boolean ready;
    private volatile boolean busy;
    private volatile Lease active;
    public EmbeddingRebuildWorker(EmbeddingModelMapper models,EmbeddingRebuildProcessor processor,EmbeddingVectorIndex indexes) {
        this.models=models; this.processor=processor; this.indexes=indexes;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready=true; }
    @Scheduled(fixedDelay=1000)
    public synchronized void dispatch() {
        if (!ready || busy) return;
        try {
            String owner=UUID.randomUUID().toString();
            String id=models.claim(owner);
            if (id==null) return;
            active=new Lease(id,owner); busy=true;
            try { executor.execute(()->run(id,owner)); }
            catch (RejectedExecutionException ex) { models.release(id,owner); active=null; busy=false; }
        } catch (Exception ex) { log.warn("领取模型重建任务失败，将自动重试",ex); }
    }
    @Scheduled(fixedDelay=5000)
    public void heartbeat() {
        Lease lease=active;
        if (lease!=null) try { models.heartbeat(lease.id(),lease.owner()); }
        catch (Exception ex) { log.warn("模型重建心跳失败",ex); }
    }
    private void run(String id,String owner) {
        MDC.put("jobId",id);
        try { processor.process(id,owner); }
        catch (CancellationException ex) { models.release(id,owner); }
        catch (Exception ex) {
            log.error("模型重建失败，保留当前模型和已构建进度",ex);
            try {
                String message=ex instanceof org.springframework.dao.DataAccessException
                        ?"保存重建进度失败，请检查数据库连接及服务日志后重试":ex.getMessage()==null?"模型重建失败":ex.getMessage();
                models.finish(id,owner,"FAILED",message.substring(0,Math.min(1600,message.length())));
            } catch (Exception failure) { log.warn("无法保存模型任务结果，等待租约恢复",failure); }
        } finally { active=null; busy=false; MDC.remove("jobId"); }
    }
    @Scheduled(fixedDelay=60000)
    public synchronized void cleanOldModels() {
        if (!ready || busy) return;
        busy=true;
        try { executor.execute(()->{
            try {
                for (long id:models.cleanableModels()) {
                    indexes.drop(id);
                    while (!Thread.currentThread().isInterrupted() && models.deleteOldVectors(id)>0) { }
                    if (!Thread.currentThread().isInterrupted()) models.markCleaned(id);
                }
            } catch (Exception ex) { log.warn("旧模型索引清理失败，下次重试",ex); }
            finally { busy=false; }
        }); } catch (RejectedExecutionException ex) { busy=false; }
    }
    @PreDestroy
    public void stop() {
        ready=false; executor.shutdownNow();
        try { executor.awaitTermination(10,TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    private record Lease(String id,String owner) { }
}
