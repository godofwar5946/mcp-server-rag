-- 已有数据库升级脚本：增加分级目录和目录内文件名唯一约束。
-- 在 application.yml 配置的 rag schema 中执行一次即可，脚本可重复执行。

CREATE SCHEMA IF NOT EXISTS rag;
SET search_path TO rag, public;

CREATE TABLE IF NOT EXISTS rag_folder (
  id BIGSERIAL PRIMARY KEY,
  parent_id BIGINT REFERENCES rag_folder(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_rag_folder_not_self CHECK (parent_id IS NULL OR parent_id <> id)
);

ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS folder_id BIGINT;

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

DROP INDEX IF EXISTS uk_rag_file_filename;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_folder_parent_name
  ON rag_folder (COALESCE(parent_id, 0), LOWER(name));
CREATE INDEX IF NOT EXISTS idx_rag_folder_parent_id ON rag_folder(parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_file_folder_filename
  ON rag_file (COALESCE(folder_id, 0), filename);
CREATE INDEX IF NOT EXISTS idx_rag_file_folder_id ON rag_file(folder_id);
