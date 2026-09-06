package org.example.rag.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.rag.config.AppProperties;
import org.example.rag.model.EmbeddingModel;
import org.example.rag.util.TextUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.example.rag.exception.EmbeddingUnavailableException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;

/** 批量向量化、输入完整性检查、有界缓存和并发隔离；仅在旧服务返回 404/405 时降级 API。 */
@Component
public class OllamaEmbeddingClient {
    private final RestClient restClient;
    private final AppProperties properties;
    private final MeterRegistry metrics;
    private final Semaphore calls;
    private final Semaphore background = new Semaphore(1, true);
    private final Map<String,double[]> cache = new LinkedHashMap<>(64, 0.75f, true);
    private volatile boolean legacyApi;

    public OllamaEmbeddingClient(RestClient ollamaRestClient, AppProperties properties, MeterRegistry metrics) {
        this.restClient = ollamaRestClient; this.properties = properties; this.metrics = metrics;
        this.calls = new Semaphore(properties.getEmbedding().getMaxConcurrentRequests(), true);
        this.legacyApi = !properties.getEmbedding().isBatchEnabled();
    }

    public double[] embed(String text) {
        return embed(text, EmbeddingModel.configured(properties));
    }

    public List<double[]> embedAll(List<String> texts) {
        return embedAll(texts, EmbeddingModel.configured(properties));
    }

    public double[] embed(String text, EmbeddingModel model) {
        return cachedEmbeddings(List.of(prepare(model.queryText(prepare(text)))), model).getFirst();
    }

    public List<double[]> embedAll(List<String> texts, EmbeddingModel model) {
        if (texts.isEmpty()) return List.of();
        // 后台导入至多占用一个模型请求，默认另外保留一个请求额度供在线检索使用。
        acquire(background, 60);
        try {
            List<double[]> result = new ArrayList<>();
            int size = properties.getEmbedding().getBatchSize();
            for (int i = 0; i < texts.size(); i += size) {
                result.addAll(cachedEmbeddings(texts.subList(i, Math.min(i + size, texts.size()))
                        .stream().map(this::prepare).toList(), model));
            }
            return result;
        } finally { background.release(); }
    }

    private List<double[]> cachedEmbeddings(List<String> texts, EmbeddingModel model) {
        Map<String,String> missing = new LinkedHashMap<>();
        Map<String,double[]> values = new HashMap<>();
        for (String text : texts) {
            String key = key(text, model);
            double[] cached;
            synchronized (cache) { cached = cache.get(key); }
            if (cached == null) missing.putIfAbsent(key, text);
            else { values.put(key, cached); metrics.counter("rag.embedding.cache.hits").increment(); }
        }
        if (!missing.isEmpty()) {
            List<double[]> computed = requestWithRetry(List.copyOf(missing.values()), model);
            if (computed.size() != missing.size()) throw new IllegalStateException("模型返回向量数量不正确");
            int i = 0;
            for (String key : missing.keySet()) {
                double[] vector = computed.get(i++);
                validate(vector, model.dimension());
                values.put(key, vector);
                synchronized (cache) {
                    if (properties.getEmbedding().getCacheSize() > 0) cache.put(key, vector);
                    while (cache.size() > properties.getEmbedding().getCacheSize()) cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        return texts.stream().map(text -> values.get(key(text, model)).clone()).toList();
    }

    private List<double[]> requestWithRetry(List<String> input, EmbeddingModel model) {
        long started = System.nanoTime();
        acquire(calls, 10);
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    verifyModel(model);
                    List<double[]> result = request(input, model);
                    // 同名模型在请求期间被替换时也不能写入旧的向量空间。
                    verifyModel(model);
                    return result;
                }
                catch (RestClientResponseException ex) {
                    if (attempt >= 2 || !(ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429)) {
                        metrics.counter("rag.embedding.errors").increment();
                        throw new EmbeddingUnavailableException("向量服务返回 HTTP " + ex.getStatusCode().value() + "，请检查模型服务", ex);
                    }
                    try { Thread.sleep(250L * (1L << attempt)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("索引已中断"); }
                } catch (RestClientException ex) {
                    metrics.counter("rag.embedding.errors").increment();
                    throw new EmbeddingUnavailableException("向量服务连接失败或超时，请检查 Ollama 服务与模型是否可用",ex);
                }
            }
        } finally {
            calls.release();
            metrics.timer("rag.embedding.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private List<double[]> request(List<String> input, EmbeddingModel model) {
        if (!legacyApi) {
            try {
                Map<String,Object> body = new LinkedHashMap<>(Map.of("model", model.modelName(), "input", input,
                        "truncate", false, "keep_alive", "10m"));
                if (model.outputDimension()!=null) body.put("dimensions", model.outputDimension());
                EmbedResponse response = restClient.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve().body(EmbedResponse.class);
                if (response == null || response.embeddings() == null) throw new IllegalStateException("向量服务返回为空");
                return response.embeddings().stream().map(this::unbox).toList();
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != 404 && ex.getStatusCode().value() != 405) throw ex;
                legacyApi = true;
            }
        }
        if (model.outputDimension()!=null)
            throw new IllegalStateException("当前 Ollama 接口不支持指定输出维度，请使用原生维度或升级 Ollama");
        List<double[]> vectors = new ArrayList<>();
        for (String text : input) {
            LegacyResponse response = restClient.post().uri("/api/embeddings").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model.modelName(), "prompt", text))
                    .retrieve().body(LegacyResponse.class);
            if (response == null || response.embedding() == null) throw new IllegalStateException("向量服务返回为空");
            vectors.add(unbox(response.embedding()));
        }
        return vectors;
    }

    private String prepare(String text) {
        if (text == null) throw new IllegalArgumentException("向量化内容不能为空");
        String normalized = TextUtils.normalizeNewlines(text).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("向量化内容不能为空");
        int limit = properties.getEmbedding().getMaxInputChars();
        if (limit > 0 && normalized.length() > limit)
            throw new IllegalArgumentException("向量化内容超过 " + limit + " 字符，请缩短查询或调整切片策略");
        return normalized;
    }

    private void validate(double[] vector, int dimension) {
        if (vector == null || vector.length == 0) throw new IllegalStateException("模型返回空向量");
        if (vector.length > 16000) throw new IllegalStateException("向量维度超过 pgvector 的 16000 维存储上限");
        if (dimension > 0 && vector.length != dimension)
            throw new IllegalStateException("向量维度不一致：配置 " + dimension + "，实际 " + vector.length);
        double norm = 0;
        for (double value : vector) {
            if (!Double.isFinite(value)) throw new IllegalStateException("模型返回非有限向量值");
            norm += value * value;
        }
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalStateException("模型返回无效的零向量");
    }

    private String key(String text, EmbeddingModel model) {
        return RagIndexService.hash((model.identity() + "|" + text).getBytes(StandardCharsets.UTF_8));
    }

    public List<InstalledModel> catalog() {
        try {
            TagsResponse response=restClient.get().uri("/api/tags").retrieve().body(TagsResponse.class);
            if (response==null || response.models()==null) throw new IllegalStateException("模型列表返回为空");
            return response.models().stream().filter(m->m.name()!=null && !m.name().isBlank())
                    .sorted(Comparator.comparing(InstalledModel::name)).toList();
        } catch (RestClientException ex) {
            throw new EmbeddingUnavailableException("无法读取 Ollama 模型列表，请检查模型服务",ex);
        }
    }

    public void verifyModel(EmbeddingModel model) {
        if (model.modelDigest()==null || model.modelDigest().isBlank()) return;
        InstalledModel installed=findInstalled(model.modelName());
        if (!model.modelDigest().equals(installed.digest()))
            throw new IllegalStateException("Ollama 中的模型版本已改变，请在模型管理中重新验证并重建索引："+model.modelName());
    }

    /** 实际请求向量接口，不能仅凭名称判断一个已安装模型是否支持 embedding。 */
    public EmbeddingModel probe(String name, Integer outputDimension, String queryInstruction) {
        if (name==null || name.isBlank() || name.length()>255 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请选择有效的已安装模型");
        if (outputDimension!=null && (outputDimension<1 || outputDimension>16000))
            throw new IllegalArgumentException("输出维度必须在 1～16000 之间");
        String instruction=queryInstruction==null?"":queryInstruction.trim();
        if (instruction.length()>1000) throw new IllegalArgumentException("查询指令最多 1000 字符");
        InstalledModel installed=findInstalled(name.trim());
        if (installed.digest()==null || installed.digest().isBlank())
            throw new IllegalStateException("模型未返回版本摘要，无法安全切换");
        EmbeddingModel candidate=new EmbeddingModel(0,installed.name(),installed.digest(),
                outputDimension==null?0:outputDimension,outputDimension,instruction);
        List<double[]> vectors=requestWithRetry(List.of("知识库索引模型验证",candidate.queryText("如何检索资料？")),candidate);
        if (vectors.size()!=2) throw new IllegalStateException("模型返回向量数量不正确");
        validate(vectors.getFirst(),candidate.dimension());
        validate(vectors.getLast(),vectors.getFirst().length);
        return new EmbeddingModel(0,installed.name(),installed.digest(),vectors.getFirst().length,outputDimension,instruction);
    }

    private InstalledModel findInstalled(String name) {
        return catalog().stream().filter(m->m.name().equals(name) || m.name().equals(name+":latest"))
                .findFirst().orElseThrow(()->new IllegalStateException("Ollama 中未安装模型："+name));
    }

    public record InstalledModel(String name, String digest, long size) { }
    public record TagsResponse(List<InstalledModel> models) { }

    private void acquire(Semaphore semaphore, int seconds) {
        try {
            if (!semaphore.tryAcquire(seconds, TimeUnit.SECONDS)) throw new EmbeddingUnavailableException("向量服务繁忙，请稍后重试");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException("任务已中断"); }
    }

    private double[] unbox(List<Double> values) {
        if (values==null || values.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("模型返回缺失的向量值");
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
    public record EmbedResponse(List<List<Double>> embeddings) { }
    public record LegacyResponse(List<Double> embedding) { }
}
