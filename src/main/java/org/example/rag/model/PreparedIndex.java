package org.example.rag.model;

import java.util.List;

/** 事务外生成的完整候选索引，只有全部校验成功才允许发布。 */
public record PreparedIndex(String text, List<CodeSymbolDraft> symbols,
                            List<Chunk> chunks, String contentHash, String fingerprint, EmbeddingModel model) {
    public record Chunk(String symbolKey, RagChunkEntity entity) { }
}
