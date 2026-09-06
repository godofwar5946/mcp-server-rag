package org.example.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.rag.config.AppProperties;
import org.example.rag.model.*;
import org.example.rag.util.VectorUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 统一的检索流水线供 MCP、调试台和评测调用，避免页面与客户端检索策略不一致。 */
@Service
public class RagSearchService {
    private final RagRetrievalStore store;
    private final OllamaEmbeddingClient embeddings;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final EmbeddingModelRegistry models;

    public RagSearchService(RagRetrievalStore store,OllamaEmbeddingClient embeddings,
                             AppProperties properties,ObjectMapper json,MeterRegistry metrics,EmbeddingModelRegistry models) {
        this.store=store; this.embeddings=embeddings; this.properties=properties; this.json=json; this.metrics=metrics;
        this.models=models;
    }

    public List<RagSearchResult> search(String query,Integer topK) { return search(query,topK,null); }
    public List<RagSearchResult> search(String query,Integer topK,Long folderId) {
        return results(retrieve(query,topK,folderId,null,null,null));
    }
    public List<RagSearchResult> searchBusiness(String query,Integer topK,Long folderId) {
        return results(retrieve(query,topK,folderId,"BUSINESS",null,null));
    }
    public List<RagSearchResult> searchCode(String query,Integer topK) { return searchCode(query,topK,null); }
    public List<RagSearchResult> searchCode(String query,Integer topK,Long folderId) {
        return results(retrieve(query,topK,folderId,"CODE",null,null));
    }

    public RetrievalReport retrieve(String query,Integer topK,Long folderId,String type,String mode,Double minSimilarity) {
        if (query==null || query.isBlank()) throw new IllegalArgumentException("检索内容不能为空");
        query=query.trim();
        if (query.length()>2000) throw new IllegalArgumentException("检索内容最多 2000 字符");
        if (folderId!=null && folderId<0) throw new IllegalArgumentException("目录 ID 不能为负数");
        type=type==null || "ALL".equalsIgnoreCase(type)?null:RagKnowledgeType.from(type).name();
        mode=mode==null?(properties.getRag().isHybrid()?"hybrid":"vector"):mode;
        if (!Set.of("vector","keyword","hybrid").contains(mode)) throw new IllegalArgumentException("检索模式不正确");
        double threshold=minSimilarity==null?properties.getRag().getMinSimilarity():minSimilarity;
        if (!Double.isFinite(threshold) || threshold<0 || threshold>1) throw new IllegalArgumentException("相关性阈值应在 0～1 之间");
        int limit=Math.min(Math.max(topK==null?properties.getRag().getDefaultTopK():topK,1),properties.getRag().getMaxTopK());
        int candidates="vector".equals(mode)?limit:Math.max(limit,properties.getRag().getCandidateCount());
        long started=System.nanoTime();
        try {
            EmbeddingModel model=models.current();
            String vector="keyword".equals(mode)?null:VectorUtils.toVectorString(embeddings.embed(query,model));
            long embedded=System.nanoTime();
            var rows=store.retrieve(query,vector,candidates,type,folderId,"CODE".equals(type),mode,model);
            long fetched=System.nanoTime();
            Map<String,Ranked> merged=new LinkedHashMap<>();
            accumulate(merged,rows.dense(),"vector");
            accumulate(merged,rows.lexical(),"keyword");
            List<RetrievalReport.Hit> hits=new ArrayList<>();
            Set<String> contentSeen=new HashSet<>();
            for (Ranked ranked:merged.values().stream().sorted(Comparator.comparingDouble((Ranked r)->r.score).reversed()).toList()) {
                if (!"keyword".equals(mode) && 1-ranked.row.distance()<threshold) continue;
                String identity=ranked.row.fileId()+":"+ranked.row.content().replaceAll("\\s+"," ").trim();
                if (!contentSeen.add(identity)) continue;
                hits.add(new RetrievalReport.Hit(toResult(ranked.row),ranked.score,List.copyOf(ranked.channels)));
                if (hits.size()==limit) break;
            }
            List<RagSearchResult> results=hits.stream().map(RetrievalReport.Hit::result).toList();
            String context=buildContext(results);
            boolean truncated=results.stream().anyMatch(RagSearchResult::truncated)
                    || results.stream().mapToInt(r->block(r).length()).sum()>context.length();
            return new RetrievalReport(query,mode,model.modelName(),merged.size(),
                    millis(embedded-started),millis(fetched-embedded),millis(System.nanoTime()-started),
                    context,truncated,hits);
        } finally { metrics.timer("rag.search.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
    }

    private void accumulate(Map<String,Ranked> merged,List<RagSearchRow> rows,String channel) {
        for (int i=0;i<rows.size();i++) {
            RagSearchRow row=rows.get(i);
            String key=row.fileId()+":"+row.revision()+":"+row.chunkIndex();
            Ranked ranked=merged.computeIfAbsent(key,ignored->new Ranked(row));
            ranked.score+=1.0/(60+i+1);
            ranked.channels.add(channel);
        }
    }

    private RagSearchResult toResult(RagSearchRow row) {
        String content=row.content();
        boolean truncated=content.length()>properties.getRag().getMaxChunkChars();
        if (truncated) content=content.substring(0,properties.getRag().getMaxChunkChars());
        Map<String,Object> metadata;
        try { metadata=row.metadataJson()==null?Map.of():json.readValue(row.metadataJson(),new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("检索来源元数据无效",ex); }
        return new RagSearchResult(row.fileId(),row.fileName(),row.knowledgeType(),row.chunkIndex(),row.codeSymbolId(),
                row.startLine(),row.endLine(),row.symbolType(),row.qualifiedName(),content,
                Math.min(1,Math.max(0,1-row.distance())),
                "rag://files/"+row.fileId()+"/revisions/"+row.revision()+"/chunks/"+row.chunkIndex(),
                row.revision(),truncated,metadata);
    }

    public String buildContext(List<RagSearchResult> results) {
        int max=properties.getRag().getMaxContextChars();
        StringBuilder context=new StringBuilder();
        Set<String> seen=new HashSet<>();
        for (RagSearchResult result:results) {
            if (!seen.add(result.sourceUri())) continue;
            String block=block(result);
            int remaining=max-context.length();
            if (remaining<=0) break;
            if (block.length()<=remaining) context.append(block);
            else {
                String marker="\n[内容已截断，可按来源读取后续片段]";
                if (remaining>marker.length()) context.append(block,0,remaining-marker.length()).append(marker);
                break;
            }
        }
        return context.toString();
    }

    private String block(RagSearchResult result) {
        String name=result.fileName();
        if (name.length()>200) name="…"+name.substring(name.length()-199);
        String location=result.startLine()==null?"#"+result.chunkIndex():":L"+result.startLine()+"-L"+result.endLine();
        Object page=result.metadata().get("page");
        if (page!=null) location+=" · 第"+page+"页";
        return "["+name+location+"]\n来源："+result.sourceUri()+"\n"+result.content()+"\n\n";
    }

    private List<RagSearchResult> results(RetrievalReport report) { return report.hits().stream().map(RetrievalReport.Hit::result).toList(); }
    private long millis(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    private static class Ranked {
        final RagSearchRow row;
        final Set<String> channels=new LinkedHashSet<>();
        double score;
        Ranked(RagSearchRow row) { this.row=row; }
    }
}
