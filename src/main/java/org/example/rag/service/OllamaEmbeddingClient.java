package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.example.rag.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Ollama Embedding 调用封装。
 */
@Component
public class OllamaEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final RestClient restClient;
    private final AppProperties properties;

    public OllamaEmbeddingClient(RestClient ollamaRestClient, AppProperties properties) {
        this.restClient = ollamaRestClient;
        this.properties = properties;
    }

    public double[] embed(String text) {
        String prepared = preparePrompt(text);
        if (prepared.isBlank()) {
            return new double[0];
        }
        try {
            return doEmbed(prepared);
        } catch (HttpServerErrorException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("NaN")) {
                log.warn("Ollama 返回 NaN，尝试降级处理输入后重试。响应={}", body);
                String fallback = fallbackPrompt(prepared);
                if (!fallback.equals(prepared)) {
                    return doEmbed(fallback);
                }
            }
            throw e;
        } catch (RestClientResponseException e) {
            throw e;
        }
    }

    private double[] doEmbed(String prepared) {
        EmbeddingRequest request = new EmbeddingRequest(properties.getOllama().getModelName(), prepared);
        EmbeddingResponse response = restClient.post()
                .uri("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.embedding == null || response.embedding.isEmpty()) {
            log.warn("Ollama embedding 返回为空");
            return new double[0];
        }
        double[] vector = new double[response.embedding.size()];
        for (int i = 0; i < response.embedding.size(); i++) {
            Double value = response.embedding.get(i);
            if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalStateException("Ollama embedding 返回非有限值");
            }
            vector[i] = value;
        }
        return vector;
    }

    private String preparePrompt(String text) {
        String normalized = TextUtils.normalizeNewlines(text == null ? "" : text);
        String cleaned = removeControlChars(normalized).trim();
        int maxChars = properties.getEmbedding().getMaxInputChars();
        if (maxChars > 0 && cleaned.length() > maxChars) {
            cleaned = cleaned.substring(0, maxChars);
        }
        return cleaned;
    }

    private String fallbackPrompt(String text) {
        String cleaned = removeControlChars(text);
        int maxChars = Math.min(300, cleaned.length());
        if (maxChars > 0) {
            cleaned = cleaned.substring(0, maxChars);
        }
        return cleaned.trim();
    }

    private String removeControlChars(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                builder.append(ch);
                continue;
            }
            if (Character.isISOControl(ch)) {
                builder.append(' ');
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    /**
     * Ollama Embedding 请求体。
     */
    private static class EmbeddingRequest {
        private final String model;
        private final String prompt;

        private EmbeddingRequest(String model, String prompt) {
            this.model = model;
            this.prompt = prompt;
        }

        public String getModel() {
            return model;
        }

        public String getPrompt() {
            return prompt;
        }
    }

    /**
     * Ollama Embedding 响应体。
     */
    private static class EmbeddingResponse {
        private List<Double> embedding;

        public List<Double> getEmbedding() {
            return embedding;
        }

        public void setEmbedding(List<Double> embedding) {
            this.embedding = embedding;
        }
    }
}
