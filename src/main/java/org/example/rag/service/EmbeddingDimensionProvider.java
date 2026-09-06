package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 负责获取向量维度：优先使用配置，必要时通过 Ollama 自动探测。
 */
@Component
public class EmbeddingDimensionProvider {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionProvider.class);

    private final AppProperties properties;
    private final OllamaEmbeddingClient embeddingClient;
    private final AtomicInteger cachedDimension = new AtomicInteger(-1);

    public EmbeddingDimensionProvider(AppProperties properties, OllamaEmbeddingClient embeddingClient) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
    }

    public int getDimension() {
        int cached = cachedDimension.get();
        if (cached > 0) {
            return cached;
        }
        Integer configured = properties.getEmbedding().getDimension();
        if (configured != null && configured > 0) {
            cachedDimension.set(configured);
            return configured;
        }
        if (!properties.getEmbedding().isAutoDetect()) {
            throw new IllegalStateException("未配置 app.embedding.dimension，且关闭了自动探测。");
        }
        log.info("未配置向量维度，尝试通过 Ollama 自动探测...");
        double[] vector = embeddingClient.embed("dimension-probe");
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("自动探测向量维度失败，Ollama 返回为空。");
        }
        cachedDimension.set(vector.length);
        properties.getEmbedding().setDimension(vector.length);
        log.info("自动探测向量维度成功：{}", vector.length);
        return vector.length;
    }
}
