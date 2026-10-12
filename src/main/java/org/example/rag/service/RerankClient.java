package org.example.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.model.RerankSettings;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** /rerank 协议：严格按 index 对齐，任何缺项或非法分数均交由调用方整批回退。 */
@Service
public class RerankClient {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slot=new Semaphore(1);
    public RerankClient(ObjectMapper json) { this.json=json; }

    public List<Double> score(RerankSettings settings,String query,List<String> documents) {
        if (documents.isEmpty()) return List.of();
        if (documents.size()>64) throw new IllegalArgumentException("重排候选超过上限");
        if (!slot.tryAcquire()) throw new IllegalStateException("重排服务繁忙");
        try {
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("model",settings.modelName()); body.put("query",query);
            body.put("documents",documents.stream().map(s->s.substring(0,Math.min(s.length(),settings.maxDocumentChars()))).toList());
            body.put("top_n",documents.size()); body.put("return_documents",false);
            if (!settings.instruction().isBlank()) body.put("instruction",settings.instruction());
            JsonNode response=call(settings,"/rerank",body);
            JsonNode results=response.path("results");
            if (!results.isArray() || results.size()!=documents.size()) throw invalid();
            Double[] scores=new Double[documents.size()];
            for (JsonNode item:results) {
                JsonNode index=item.path("index"), score=item.path("relevance_score");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || !score.isNumber()) throw invalid();
                int i=index.intValue(); double value=score.doubleValue();
                if (i<0 || i>=scores.length || scores[i]!=null || !Double.isFinite(value)) throw invalid();
                scores[i]=value;
            }
            return List.of(scores);
        } finally { slot.release(); }
    }
    public Map<String,Object> probe(RerankSettings settings) {
        long start=System.nanoTime();
        var scores=score(settings,"中国的首都是哪里？",List.of("中国的首都是北京。","香蕉是一种水果。"));
        if (scores.get(0)<=scores.get(1)) throw new IllegalStateException("接口可达，但相关文档得分未高于无关文档，请检查模型和评分模板");
        return Map.of("model",settings.modelName(),"millis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),
                "scores",scores,"message","接口与基础排序验证通过；实际效果请使用业务问题评测");
    }
    public List<String> models(RerankSettings settings) {
        JsonNode data=call(settings,"/v1/models",null).path("data");
        if (!data.isArray()) throw invalid();
        List<String> names=new ArrayList<>();
        data.forEach(item->{ if (item.path("id").isTextual()) names.add(item.path("id").asText()); });
        return names.stream().distinct().limit(100).toList();
    }
    private JsonNode call(RerankSettings settings,String path,Object body) {
        CompletableFuture<HttpResponse<String>> pending=null;
        try {
            var builder=HttpRequest.newBuilder(URI.create(settings.baseUrl()+path))
                    .timeout(Duration.ofMillis(settings.timeoutMillis())).header("Accept","application/json");
            if (body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            pending=http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofString());
            var response=pending.get(settings.timeoutMillis(),TimeUnit.MILLISECONDS);
            if (response.statusCode()!=200) throw new IllegalStateException("重排服务返回 HTTP "+response.statusCode());
            JsonNode result=json.readTree(response.body());
            if (result==null || !result.isObject()) throw invalid();
            return result;
        } catch (TimeoutException ex) { throw new IllegalStateException("重排服务超时"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("重排请求已中断"); }
        catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("重排服务连接失败或响应无效，请检查地址和服务状态"); }
        finally { if (pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    private IllegalStateException invalid() { return new IllegalStateException("重排响应无效：需要完整、不重复的索引及有限数值分数"); }
}
