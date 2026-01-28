package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.model.RagSearchResult;
import org.example.rag.model.RagSearchRow;
import org.example.rag.util.TextUtils;
import org.example.rag.util.VectorUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 向量检索服务。
 */
@Service
public class RagSearchService {

    private final RagChunkMapper ragChunkMapper;
    private final OllamaEmbeddingClient embeddingClient;
    private final AppProperties properties;

    public RagSearchService(RagChunkMapper ragChunkMapper,
                            OllamaEmbeddingClient embeddingClient,
                            AppProperties properties) {
        this.ragChunkMapper = ragChunkMapper;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    public List<RagSearchResult> search(String query, Integer topK) {
        int limit = resolveTopK(topK);
        double[] embedding = embeddingClient.embed(query);
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("Ollama embedding 返回为空");
        }
        String vector = VectorUtils.toVectorString(embedding);
        List<RagSearchRow> rows = ragChunkMapper.search(vector, limit);
        return rows.stream().map(row -> {
            double similarity = Math.max(0d, 1d - row.distance());
            String content = TextUtils.truncate(row.content(), properties.getRag().getMaxChunkChars());
            return new RagSearchResult(
                    row.fileId(),
                    row.fileName(),
                    row.chunkIndex(),
                    content,
                    similarity
            );
        }).toList();
    }

    public String buildContext(List<RagSearchResult> results) {
        int maxChars = properties.getRag().getMaxContextChars();
        StringBuilder builder = new StringBuilder();
        for (RagSearchResult result : results) {
            String block = "[" + result.fileName() + "#" + result.chunkIndex() + "]\n" + result.content() + "\n\n";
            if (builder.length() + block.length() > maxChars) {
                break;
            }
            builder.append(block);
        }
        return builder.toString();
    }

    private int resolveTopK(Integer topK) {
        int limit = topK == null ? properties.getRag().getDefaultTopK() : topK;
        return Math.min(Math.max(limit, 1), properties.getRag().getMaxTopK());
    }
}
