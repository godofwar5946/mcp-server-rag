package org.example.rag.controller;

import org.example.rag.mapper.*;
import org.example.rag.service.RagSearchService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 管理端检索调试与证据阅读，复用 MCP 的实际检索流水线。 */
@RestController
@RequestMapping("/api")
public class RetrievalController {
    private final RagSearchService search;
    private final RagChunkMapper chunks;
    private final RagFileMapper files;
    public RetrievalController(RagSearchService search,RagChunkMapper chunks,RagFileMapper files) {
        this.search=search; this.chunks=chunks; this.files=files;
    }
    @PostMapping("/search")
    public Map<String,Object> search(@RequestBody SearchRequest request) {
        return Map.of("success",true,"data",search.retrieve(request.query(),request.topK(),request.folderId(),
                request.knowledgeType(),request.mode(),request.minSimilarity()));
    }
    @GetMapping("/files/{id}")
    public Map<String,Object> detail(@PathVariable long id) {
        var file=files.selectMetadata(id);
        if (file==null) throw new IllegalArgumentException("文件不存在");
        return Map.of("success",true,"data",file);
    }
    @GetMapping("/files/{id}/chunk-items")
    @org.springframework.transaction.annotation.Transactional(readOnly=true,
            isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,timeout=10)
    public Map<String,Object> chunks(@PathVariable long id,@RequestParam(defaultValue="1") int page,
                                    @RequestParam(defaultValue="10") int size) {
        var file=files.selectMetadata(id);
        if (file==null) throw new IllegalArgumentException("文件不存在");
        int count=Math.min(Math.max(size,1),50);
        int offset=(Math.min(Math.max(page,1),100000)-1)*count;
        return Map.of("success",true,"data",chunks.listForFile(id,count,offset),"revision",file.getRevision());
    }
    @GetMapping("/files/{id}/chunks/{chunkIndex}/source")
    public Map<String,Object> source(@PathVariable long id,@PathVariable int chunkIndex,@RequestParam long revision) {
        var chunk=chunks.readRevisionChunk(id,chunkIndex,revision);
        if (chunk==null) throw new IllegalStateException("来源版本已更新或切片不存在，请重新检索");
        return Map.of("success",true,"data",chunk);
    }
    public record SearchRequest(String query,Integer topK,Long folderId,String knowledgeType,String mode,Double minSimilarity) { }
}
