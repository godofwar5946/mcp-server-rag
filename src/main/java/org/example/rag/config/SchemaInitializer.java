package org.example.rag.config;

import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import org.example.rag.service.EmbeddingDimensionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化数据库结构（可通过配置关闭）。
 * <p>
 * 说明：pgvector 的 vector(n) 必须固定维度，因此这里会读取/探测维度后建表。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final AppProperties properties;
    private final EmbeddingDimensionProvider dimensionProvider;

    public SchemaInitializer(AppProperties properties,
                             EmbeddingDimensionProvider dimensionProvider) {
        this.properties = properties;
        this.dimensionProvider = dimensionProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getDatabase().isInitSchema()) {
            log.info("已关闭自动建表 (app.database.init-schema=false)。");
            return;
        }
        int dimension = dimensionProvider.getDimension();
        log.info("准备初始化数据库结构，向量维度：{}", dimension);

        SqlRunner.db().update("CREATE EXTENSION IF NOT EXISTS vector");

        String createFileTable = """
                CREATE TABLE IF NOT EXISTS rag_file (
                  id BIGSERIAL PRIMARY KEY,
                  filename VARCHAR(512) NOT NULL,
                  content_type VARCHAR(128),
                  size BIGINT NOT NULL,
                  content_bytes BYTEA,
                  parsed_text TEXT,
                  status VARCHAR(32) NOT NULL,
                  error_message TEXT,
                  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                  indexed_at TIMESTAMP
                )
                """;
        SqlRunner.db().update(createFileTable);

        String createChunkTable = """
                CREATE TABLE IF NOT EXISTS rag_chunk (
                  id BIGSERIAL PRIMARY KEY,
                  file_id BIGINT NOT NULL REFERENCES rag_file(id) ON DELETE CASCADE,
                  chunk_index INT NOT NULL,
                  content TEXT NOT NULL,
                  embedding vector(%d) NOT NULL,
                  metadata JSONB,
                  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.formatted(dimension);
        SqlRunner.db().update(createChunkTable);

        SqlRunner.db().update("CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_file_filename ON rag_file(filename)");
        SqlRunner.db().update("CREATE INDEX IF NOT EXISTS idx_rag_chunk_file_id ON rag_chunk(file_id)");
        SqlRunner.db().update("CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_chunk_file_index ON rag_chunk(file_id, chunk_index)");
        SqlRunner.db().update("CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw ON rag_chunk USING hnsw (embedding vector_cosine_ops)");

        log.info("数据库结构初始化完成。\n");
    }
}
