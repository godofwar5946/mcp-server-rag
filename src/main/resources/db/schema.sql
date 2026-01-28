-- PostgreSQL 18.1 + pgvector 0.8.1
-- 如果向量维度未知，可先在应用中开启 auto-detect，再把探测结果填入这里。

CREATE EXTENSION IF NOT EXISTS vector;

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
);

-- 注意：vector(768) 需替换为实际 embedding 维度
CREATE TABLE IF NOT EXISTS rag_chunk (
  id BIGSERIAL PRIMARY KEY,
  file_id BIGINT NOT NULL REFERENCES rag_file(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  embedding vector(768) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_file_filename ON rag_file(filename);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_file_id ON rag_chunk(file_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_chunk_file_index ON rag_chunk(file_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw ON rag_chunk USING hnsw (embedding vector_cosine_ops);
