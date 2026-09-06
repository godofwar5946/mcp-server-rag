-- 专业工作台升级：仅增加字段/表/索引，不清理历史文件或向量。
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS index_fingerprint VARCHAR(64);
ALTER TABLE rag_file ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(255);
ALTER TABLE rag_folder ADD COLUMN IF NOT EXISTS default_knowledge_type VARCHAR(16);

CREATE TABLE IF NOT EXISTS rag_index_job (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    file_id BIGINT NOT NULL REFERENCES rag_file(id) ON DELETE CASCADE,
    expected_revision BIGINT NOT NULL,
    content_bytes BYTEA,
    content_type VARCHAR(128),
    force_reindex BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
      CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','SKIPPED','FAILED','CANCELLED')),
    stage VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    completed_chunks INT NOT NULL DEFAULT 0,
    total_chunks INT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    message TEXT,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMP,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_index_job_active_file
    ON rag_index_job(file_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX IF NOT EXISTS idx_rag_index_job_queue ON rag_index_job(status, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_job_batch ON rag_index_job(batch_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_job_finished ON rag_index_job(finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rag_file_folder_updated ON rag_file(folder_id, updated_at DESC, id DESC);
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_rag_chunk_text_search ON rag_chunk USING gin(to_tsvector('simple',content));
CREATE INDEX IF NOT EXISTS idx_rag_chunk_content_trgm ON rag_chunk USING gin(lower(content) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_code_symbol_name_trgm ON rag_code_symbol USING gin(lower(simple_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_file_name_trgm ON rag_file USING gin(lower(filename) gin_trgm_ops);

CREATE TABLE IF NOT EXISTS rag_runtime_setting (key VARCHAR(100) PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS rag_evaluation_case (
    id BIGSERIAL PRIMARY KEY,
    question VARCHAR(2000) NOT NULL,
    -- 0 表示根目录；保留已删除目录 ID，运行时明确提示范围失效，避免变为全库评测。
    folder_id BIGINT CHECK(folder_id IS NULL OR folder_id>=0),
    knowledge_type VARCHAR(16) NOT NULL DEFAULT 'ALL',
    expected_file_ids JSONB NOT NULL DEFAULT '[]',
    expect_empty BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS rag_evaluation_run (
    id BIGSERIAL PRIMARY KEY,
    case_id BIGINT NOT NULL REFERENCES rag_evaluation_case(id) ON DELETE CASCADE,
    mode VARCHAR(16) NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_run_case ON rag_evaluation_run(case_id,id DESC);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_run_created ON rag_evaluation_run(created_at);
