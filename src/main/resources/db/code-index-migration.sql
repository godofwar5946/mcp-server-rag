-- 已有数据库升级脚本：增加 Java / MyBatis XML 代码符号和行号索引。
-- 请在 rag schema 中执行；脚本可重复执行。

CREATE SCHEMA IF NOT EXISTS rag;
SET search_path TO rag, public;

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

CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_file_id ON rag_code_symbol(file_id);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_parent_id ON rag_code_symbol(parent_id);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_simple_name ON rag_code_symbol(LOWER(simple_name));
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_qualified_name ON rag_code_symbol(LOWER(qualified_name));
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_file_lines ON rag_code_symbol(file_id, start_line, end_line);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_code_symbol_id ON rag_chunk(code_symbol_id);
