package org.example.rag.service;

import jakarta.annotation.PreDestroy;
import org.example.rag.config.AppProperties;
import org.example.rag.mapper.IndexJobMapper;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** 有界工作池、租约心跳、异常恢复；实例停止或进程崩溃后任务会重新进入队列。 */
@Component
@ConditionalOnProperty(prefix="app.indexing", name="enabled", havingValue="true", matchIfMissing=true)
public class IndexJobWorker {
    private static final Logger log=LoggerFactory.getLogger(IndexJobWorker.class);
    private final IndexJobMapper jobs;
    private final RagFileMapper files;
    private final RagIndexService index;
    private final AppProperties properties;
    private final ExecutorService executor;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    public IndexJobWorker(IndexJobMapper jobs, RagFileMapper files, RagIndexService index, AppProperties properties) {
        this.jobs=jobs; this.files=files; this.index=index; this.properties=properties;
        executor=Executors.newFixedThreadPool(properties.getIndexing().getWorkers(),
                Thread.ofPlatform().name("rag-index-",0).factory());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready=true; }

    @Scheduled(fixedDelay=1000)
    public synchronized void dispatch() {
        if (!ready) return;
        try {
            jobs.recoverExpired();
            while (active.size()<properties.getIndexing().getWorkers()) {
                String owner=UUID.randomUUID().toString();
                String id=jobs.claim(owner,properties.getIndexing().getLeaseSeconds());
                if (id==null) break;
                active.add(owner);
                try { executor.execute(() -> run(id,owner)); }
                catch (RejectedExecutionException ex) { active.remove(owner); jobs.release(owner); break; }
            }
        } catch (Exception ex) { log.warn("索引任务领取失败，将自动重试: {}",ex.getMessage()); }
    }

    @Scheduled(fixedDelay=5000)
    public void heartbeat() {
        List<String> owners=List.copyOf(active);
        if (!owners.isEmpty()) {
            try { jobs.heartbeat(owners,properties.getIndexing().getLeaseSeconds()); }
            catch (Exception ex) { log.warn("任务心跳失败: {}",ex.getMessage()); }
        }
    }

    private void run(String id, String owner) {
        org.slf4j.MDC.put("jobId",id);
        try {
            IndexJobPayload payload=jobs.payload(id,owner);
            if (payload==null) return;
            RagFileEntity file=files.selectMetadata(payload.fileId());
            if (file==null || file.getRevision()!=payload.expectedRevision())
                throw new IllegalStateException("文件已更新或移动，请重新提交当前文件的索引任务");
            if (payload.contentBytes()==null) throw new IllegalStateException("文件缺少原始内容");
            IndexProgress progress=(stage,completed,total) -> {
                if (Thread.currentThread().isInterrupted() || jobs.progress(id,owner,stage,completed,total)!=1)
                    throw new CancellationException("任务已取消或租约已失效");
            };
            IndexResult result=index.indexRegistered(file,payload.contentType(),payload.contentBytes(),
                    payload.forceReindex(),progress,new IndexJobLease(id,owner));
            if (result.skipped()) jobs.finish(id,owner,"SKIPPED",result.message());
            else if (!result.success()) jobs.finish(id,owner,"FAILED",safeMessage(result.message()));
        } catch (CancellationException ex) {
            if (Thread.currentThread().isInterrupted()) jobs.release(owner);
            else jobs.finish(id,owner,"CANCELLED","任务已取消或租约已失效，原索引保留");
        } catch (Exception ex) {
            log.error("索引任务失败: {}",id,ex);
            try { jobs.finish(id,owner,"FAILED",safeMessage(ex.getMessage())); }
            catch (Exception failure) { log.warn("保存失败结果时连接中断，等待租约恢复: {}",id); }
        } finally { active.remove(owner); org.slf4j.MDC.remove("jobId"); }
    }

    private String safeMessage(String message) {
        return message==null ? "索引任务失败" : message.substring(0,Math.min(message.length(),1600));
    }

    @PreDestroy
    public void stop() {
        ready=false;
        executor.shutdownNow();
        try { executor.awaitTermination(10,TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
