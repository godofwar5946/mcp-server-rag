package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.example.rag.mapper.*;
import org.example.rag.model.RagFileEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 短事务提交和重试；相同文件同时最多有一个待处理任务。 */
@Service
public class IndexJobStore {
    private final IndexJobMapper jobs;
    private final RagFileMapper files;
    private final AppProperties properties;
    private final RagIndexWriter writer;
    public IndexJobStore(IndexJobMapper jobs, RagFileMapper files, AppProperties properties, RagIndexWriter writer) {
        this.jobs=jobs; this.files=files; this.properties=properties; this.writer=writer;
    }

    @Transactional
    public Submission upload(String filename,String type,byte[] bytes,Long folderId,String batchId) {
        RagFileEntity file=writer.register(filename,type,bytes,folderId);
        return new Submission(file.getId(),submit(file,batchId,bytes,type,false));
    }
    public long remainingCapacity() { return Math.max(0,properties.getIndexing().getMaxQueuedJobs()-jobs.activeCount()); }
    public record Submission(long fileId,String jobId) { }

    @Transactional
    public String submit(RagFileEntity expected, String batchId, byte[] bytes, String type, boolean force) {
        RagFileEntity current = files.lockForIndex(expected.getId());
        if (current == null || current.getRevision()!=expected.getRevision()) throw new IllegalStateException("文件已变化，请重新提交");
        checkCapacity();
        String id=UUID.randomUUID().toString();
        if (jobs.enqueue(id,batchId,current.getId(),current.getRevision(),bytes,type,force)!=1)
            throw new IllegalStateException("该文件已有排队或执行中的任务");
        return id;
    }

    @Transactional
    public void retry(String id) {
        Long fileId=jobs.fileId(id);
        if (fileId==null) throw new IllegalArgumentException("任务不存在");
        RagFileEntity file=files.lockForIndex(fileId);
        if (file==null) throw new IllegalArgumentException("文件已删除");
        checkCapacity();
        if (jobs.hasActiveFile(fileId)) throw new IllegalStateException("该文件已有排队或执行中的任务，请等待完成后再重试");
        if (jobs.retry(id,file.getRevision())!=1) throw new IllegalStateException("任务无法重试：状态不允许或文件版本已改变，请重新上传或重建当前文件");
    }

    private void checkCapacity() {
        jobs.lockQueue();
        if (jobs.activeCount()>=properties.getIndexing().getMaxQueuedJobs())
            throw new IllegalStateException("索引队列已满，请等待已有任务完成后再提交");
    }
}
