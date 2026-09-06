-- 已有数据库升级脚本：增加文件知识分类和分类检索索引。
-- ALL 为默认值，会同时参加 BUSINESS 与 CODE 分类检索。

CREATE SCHEMA IF NOT EXISTS rag;
SET search_path TO rag, public;

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

CREATE INDEX IF NOT EXISTS idx_rag_file_knowledge_type ON rag_file(knowledge_type);
