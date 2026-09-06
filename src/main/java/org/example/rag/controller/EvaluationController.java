package org.example.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.mapper.*;
import org.example.rag.model.RagKnowledgeType;
import org.example.rag.service.RagSearchService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 只接受人工标注的参考来源；与实际 MCP 调用使用同一个检索服务。 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationMapper cases;
    private final RagFileMapper files;
    private final RagSearchService search;
    private final ObjectMapper json;
    private final org.example.rag.service.RagFolderService folders;
    public EvaluationController(EvaluationMapper cases,RagFileMapper files,RagSearchService search,ObjectMapper json,
                                org.example.rag.service.RagFolderService folders) {
        this.cases=cases; this.files=files; this.search=search; this.json=json; this.folders=folders;
    }
    @GetMapping
    public Map<String,Object> list() { return Map.of("success",true,"data",cases.list(),"runs",cases.runs()); }
    @PostMapping
    public Map<String,Object> add(@RequestBody CaseRequest request) throws Exception {
        validateScope(request.folderId());
        if (request.question()==null || request.question().isBlank() || request.question().length()>2000)
            throw new IllegalArgumentException("问题应为 1～2000 字符");
        var ids=request.expectedFileIds()==null?List.<Long>of():request.expectedFileIds().stream().distinct().toList();
        if (ids.size()>100 || (request.expectEmpty()?!ids.isEmpty():ids.isEmpty()))
            throw new IllegalArgumentException("请填写参考文件 ID，或单独勾选“应无结果”");
        for (Long id:ids) if (id==null || files.selectMetadata(id)==null) throw new IllegalArgumentException("参考文件不存在："+id);
        if (cases.list().size()>=500) throw new IllegalStateException("评测集最多 500 条，请先整理现有问题");
        cases.add(request.question().trim(),request.folderId(),RagKnowledgeType.from(request.knowledgeType()).name(),
                json.writeValueAsString(ids),request.expectEmpty());
        return Map.of("success",true);
    }
    @DeleteMapping("/{id}")
    public Map<String,Object> delete(@PathVariable long id) { cases.delete(id); return Map.of("success",true); }
    @PostMapping("/{id}/run")
    public Map<String,Object> run(@PathVariable long id,@RequestBody RunRequest request) throws Exception {
        var sample=cases.get(id);
        if (sample==null) throw new IllegalArgumentException("评测问题不存在");
        validateScope(sample.folderId());
        List<Long> expected=json.readValue(sample.expectedFileIds(),new TypeReference<>() { });
        for (Long fileId:expected) if (files.selectMetadata(fileId)==null)
            throw new IllegalStateException("参考文件已删除，请重新标注评测问题");
        var report=search.retrieve(sample.question(),request.topK(),sample.folderId(),sample.knowledgeType(),request.mode(),request.minSimilarity());
        List<Long> found=report.hits().stream().map(hit->hit.result().fileId()).distinct().toList();
        long matched=expected.stream().filter(found::contains).count();
        int first=0;
        for (int i=0;i<report.hits().size();i++) if (expected.contains(report.hits().get(i).result().fileId())) { first=i+1; break; }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("hit",sample.expectEmpty()?found.isEmpty():matched>0);
        result.put("recall",expected.isEmpty()?null:(double)matched/expected.size());
        result.put("reciprocalRank",sample.expectEmpty()?null:(first==0?0:1.0/first));
        result.put("expectEmpty",sample.expectEmpty()); result.put("model",report.model());
        result.put("topK",request.topK()); result.put("minSimilarity",request.minSimilarity());
        result.put("totalMillis",report.totalMillis()); result.put("fileIds",found);
        result.put("sources",report.hits().stream().map(hit->hit.result().sourceUri()).toList());
        cases.save(id,report.mode(),json.writeValueAsString(result));
        return Map.of("success",true,"data",result);
    }
    public record CaseRequest(String question,Long folderId,String knowledgeType,List<Long> expectedFileIds,boolean expectEmpty) { }
    private void validateScope(Long id) {
        if (id!=null && id<0) throw new IllegalArgumentException("目录 ID 不合法");
        if (id!=null && id>0) folders.requireFolder(id);
    }
    public record RunRequest(String mode,Integer topK,Double minSimilarity) { }
}
