-- PostgreSQL 18.1 + pgvector 0.8.1
-- 如果向量维度未知，可先在应用中开启 auto-detect，再把探测结果填入这里。

CREATE SCHEMA IF NOT EXISTS rag;
SET search_path TO rag, public;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS rag_folder (
  id BIGSERIAL PRIMARY KEY,
  parent_id BIGINT REFERENCES rag_folder(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_rag_folder_not_self CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE IF NOT EXISTS rag_file (
  id BIGSERIAL PRIMARY KEY,
  filename VARCHAR(512) NOT NULL,
  folder_id BIGINT,
  content_type VARCHAR(128),
  knowledge_type VARCHAR(16) NOT NULL DEFAULT 'ALL',
  size BIGINT NOT NULL,
  content_bytes BYTEA,
  parsed_text TEXT,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  indexed_at TIMESTAMP,
  CONSTRAINT ck_rag_file_knowledge_type
    CHECK (knowledge_type IN ('ALL', 'BUSINESS', 'CODE')),
  CONSTRAINT fk_rag_file_folder
    FOREIGN KEY (folder_id) REFERENCES rag_folder(id) ON DELETE CASCADE
);

-- 兼容已经创建过 rag_file 的数据库。
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS folder_id BIGINT;
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS knowledge_type VARCHAR(16) DEFAULT 'ALL';
UPDATE rag_file SET knowledge_type = 'ALL' WHERE knowledge_type IS NULL;
ALTER TABLE rag_file ALTER COLUMN knowledge_type SET DEFAULT 'ALL';
ALTER TABLE rag_file ALTER COLUMN knowledge_type SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'ck_rag_file_knowledge_type'
      AND conrelid = 'rag_file'::regclass
  ) THEN
    ALTER TABLE rag_file
      ADD CONSTRAINT ck_rag_file_knowledge_type
      CHECK (knowledge_type IN ('ALL', 'BUSINESS', 'CODE'));
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_rag_file_folder'
      AND conrelid = 'rag_file'::regclass
  ) THEN
    ALTER TABLE rag_file
      ADD CONSTRAINT fk_rag_file_folder
      FOREIGN KEY (folder_id) REFERENCES rag_folder(id) ON DELETE CASCADE;
  END IF;
END
$$;

CREATE TABLE IF NOT EXISTS rag_code_symbol (
  id BIGSERIAL PRIMARY KEY,
  file_id BIGINT NOT NULL REFERENCES rag_file(id) ON DELETE CASCADE,
  parent_id BIGINT REFERENCES rag_code_symbol(id) ON DELETE CASCADE,
  language VARCHAR(32) NOT NULL,
  symbol_type VARCHAR(64) NOT NULL,
  simple_name VARCHAR(512) NOT NULL,
  qualified_name VARCHAR(1536) NOT NULL,
  signature TEXT,
  start_line INT NOT NULL,
  end_line INT NOT NULL,
  start_column INT NOT NULL,
  end_column INT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_rag_code_symbol_lines CHECK (start_line > 0 AND end_line >= start_line),
  CONSTRAINT ck_rag_code_symbol_offsets CHECK (start_offset >= 0 AND end_offset >= start_offset)
);

-- 注意：vector(1024) 需替换为实际 embedding 维度
CREATE TABLE IF NOT EXISTS rag_chunk (
  id BIGSERIAL PRIMARY KEY,
  file_id BIGINT NOT NULL REFERENCES rag_file(id) ON DELETE CASCADE,
  code_symbol_id BIGINT REFERENCES rag_code_symbol(id) ON DELETE SET NULL,
  chunk_index INT NOT NULL,
  start_line INT,
  end_line INT,
  content TEXT NOT NULL,
  embedding vector(1024) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 兼容已经创建过 rag_chunk 的数据库。
ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS code_symbol_id BIGINT;
ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS start_line INT;
ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS end_line INT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_rag_chunk_code_symbol'
      AND conrelid = 'rag_chunk'::regclass
  ) THEN
    ALTER TABLE rag_chunk
      ADD CONSTRAINT fk_rag_chunk_code_symbol
      FOREIGN KEY (code_symbol_id) REFERENCES rag_code_symbol(id) ON DELETE SET NULL;
  END IF;
END
$$;

DROP INDEX IF EXISTS uk_rag_file_filename;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_folder_parent_name
  ON rag_folder (COALESCE(parent_id, 0), LOWER(name));
CREATE INDEX IF NOT EXISTS idx_rag_folder_parent_id ON rag_folder(parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_file_folder_filename
  ON rag_file (COALESCE(folder_id, 0), filename);
CREATE INDEX IF NOT EXISTS idx_rag_file_folder_id ON rag_file(folder_id);
CREATE INDEX IF NOT EXISTS idx_rag_file_knowledge_type ON rag_file(knowledge_type);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_file_id ON rag_code_symbol(file_id);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_parent_id ON rag_code_symbol(parent_id);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_simple_name ON rag_code_symbol(LOWER(simple_name));
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_qualified_name ON rag_code_symbol(LOWER(qualified_name));
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_file_lines ON rag_code_symbol(file_id, start_line, end_line);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_file_id ON rag_chunk(file_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_code_symbol_id ON rag_chunk(code_symbol_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_chunk_file_index ON rag_chunk(file_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw ON rag_chunk USING hnsw (embedding vector_cosine_ops);
