package org.example.rag.controller;

import org.example.rag.mapper.*;
import org.example.rag.model.*;
import org.example.rag.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** 管理端持久化任务接口。上传只等待文件保存，不等待模型完成。 */
@RestController
@RequestMapping("/api/index/jobs")
public class IndexJobController {
    private final IndexJobMapper jobs;
    private final IndexJobStore store;
    private final RagFileMapper files;
    private final RagIndexWriter writer;
    private final RagIndexService index;

    public IndexJobController(IndexJobMapper jobs,IndexJobStore store,RagFileMapper files,RagIndexWriter writer,RagIndexService index) {
        this.jobs=jobs; this.store=store; this.files=files; this.writer=writer; this.index=index;
    }

    @GetMapping
    public Map<String,Object> list(@RequestParam(required=false) String status,
                                  @RequestParam(required=false) UUID batchId,
                                  @RequestParam(defaultValue="1") int page,
                                  @RequestParam(defaultValue="50") int size) {
        if (status!=null && !Set.of("QUEUED","RUNNING","SUCCEEDED","SKIPPED","FAILED","CANCELLED").contains(status))
            throw new IllegalArgumentException("任务状态不正确");
        int limit=Math.min(Math.max(size,1),100);
        int safePage=Math.min(Math.max(page,1),10000);
        return Map.of("success",true,"data",jobs.list(status,batchId==null?null:batchId.toString(),limit,(safePage-1)*limit),
                "activeCount",jobs.activeCount(),"page",safePage);
    }

    @PostMapping("/upload")
    public Map<String,Object> upload(@RequestPart("files") List<MultipartFile> uploads,
            @RequestParam(required=false) Long folderId,@RequestParam(required=false) List<String> relativePaths) {
        if (uploads.isEmpty() || uploads.size()>100) throw new IllegalArgumentException("每批请选择 1～100 个文件");
        boolean paths=relativePaths!=null && !relativePaths.isEmpty();
        if (paths && relativePaths.size()!=uploads.size()) throw new IllegalArgumentException("文件与相对路径数量不一致");
        String batchId=UUID.randomUUID().toString();
        List<Map<String,Object>> results=new ArrayList<>();
        for (int i=0;i<uploads.size();i++) {
            MultipartFile upload=uploads.get(i);
            try {
                var target=index.resolveUploadTarget(upload,folderId,paths?relativePaths.get(i):null);
                byte[] bytes=upload.getBytes();
                var submission=store.upload(target.filename(),upload.getContentType(),bytes,target.folderId(),batchId);
                results.add(Map.of("success",true,"fileName",target.filename(),"fileId",submission.fileId(),"jobId",submission.jobId()));
            } catch (Exception ex) {
                results.add(Map.of("success",false,"fileName",Objects.toString(upload.getOriginalFilename(),"未知文件"),
                        "message",Objects.toString(ex.getMessage(),"任务提交失败")));
            }
        }
        return Map.of("success",true,"batchId",batchId,"data",results);
    }

    @PostMapping
    public Map<String,Object> rebuild(@RequestBody RebuildRequest request) {
        if (!request.all() && (request.fileIds()==null || request.fileIds().isEmpty()))
            throw new IllegalArgumentException("请选择需要重建的文件");
        if (request.fileIds()!=null && request.fileIds().size()>500) throw new IllegalArgumentException("每次最多选择 500 个文件");
        if (request.fileIds()!=null && request.fileIds().stream().anyMatch(id->id==null || id<=0))
            throw new IllegalArgumentException("文件 ID 不合法");
        String batchId=UUID.randomUUID().toString();
        List<Map<String,Object>> results=new ArrayList<>();
        long after=0;
        boolean limited=false;
        outer:
        do {
            List<Long> ids=request.all()?files.listIdsAfter(after,100):request.fileIds().stream().distinct().toList();
            if (ids.isEmpty()) break;
            for (Long id:ids) {
                if (results.size()>=10000 || store.remainingCapacity()==0) { limited=true; break outer; }
                if (id==null || id<=0) throw new IllegalArgumentException("文件 ID 不合法");
                try {
                    RagFileEntity file=files.selectMetadata(id);
                    if (file==null) throw new IllegalArgumentException("文件不存在");
                    String jobId=store.submit(file,batchId,null,null,request.force());
                    results.add(Map.of("success",true,"fileId",id,"jobId",jobId));
                } catch (Exception ex) {
                    results.add(Map.of("success",false,"fileId",id,"message",Objects.toString(ex.getMessage(),"任务提交失败")));
                }
            }
            after=ids.getLast();
        } while (request.all());
        return Map.of("success",true,"batchId",batchId,"data",results,"limited",limited,
                "message",limited?"达到队列容量或本批上限，余下文件未提交，请等待任务完成后分批选择重建":"");
    }

    @PostMapping("/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable UUID id) {
        if (jobs.cancel(id.toString())!=1) throw new IllegalStateException("任务不存在或已经结束");
        return Map.of("success",true,"message","任务已取消，已发布索引保留");
    }

    @PostMapping("/{id}/retry")
    public Map<String,Object> retry(@PathVariable UUID id) {
        store.retry(id.toString());
        return Map.of("success",true,"message","任务已重新排队");
    }

    public record RebuildRequest(List<Long> fileIds,boolean all,boolean force) { }
}
