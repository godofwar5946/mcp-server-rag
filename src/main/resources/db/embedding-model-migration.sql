-- 将向量与正文切片分离：不同模型代次独立存储，完成后只切换活动模型指针。
CREATE TABLE rag_embedding_model (
  id BIGSERIAL PRIMARY KEY,
  model_name VARCHAR(255) NOT NULL,
  model_digest VARCHAR(128),
  dimension INT NOT NULL CHECK (dimension BETWEEN 1 AND 16000),
  output_dimension INT CHECK (output_dimension BETWEEN 1 AND 16000),
  query_instruction VARCHAR(1000) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  retired_at TIMESTAMP,
  cleaned_at TIMESTAMP,
  UNIQUE(id, dimension)
);
CREATE TABLE rag_embedding_state (
  id INT PRIMARY KEY CHECK (id=1),
  active_model_id BIGINT NOT NULL REFERENCES rag_embedding_model(id)
);
CREATE TABLE rag_chunk_embedding (
  model_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL REFERENCES rag_chunk(id) ON DELETE CASCADE,
  dimension INT NOT NULL,
  embedding vector NOT NULL,
  PRIMARY KEY(model_id, chunk_id),
  FOREIGN KEY(model_id, dimension) REFERENCES rag_embedding_model(id, dimension),
  CHECK (vector_dims(embedding)=dimension)
);
CREATE INDEX idx_rag_chunk_embedding_chunk ON rag_chunk_embedding(chunk_id);
CREATE TABLE rag_embedding_rebuild (
  id UUID PRIMARY KEY,
  model_id BIGINT NOT NULL REFERENCES rag_embedding_model(id),
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
    CHECK(status IN ('QUEUED','RUNNING','FAILED','SUCCEEDED','CANCELLED')),
  stage VARCHAR(32) NOT NULL DEFAULT 'WAITING',
  total_chunks BIGINT NOT NULL DEFAULT 0,
  completed_chunks BIGINT NOT NULL DEFAULT 0,
  after_chunk_id BIGINT NOT NULL DEFAULT 0,
  message VARCHAR(1600),
  lease_owner VARCHAR(64),
  lease_until TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  finished_at TIMESTAMP
);
CREATE UNIQUE INDEX uk_rag_embedding_rebuild_pending ON rag_embedding_rebuild((1))
  WHERE status IN ('QUEUED','RUNNING','FAILED');

-- 首次迁移沿用已校验的旧模型名称/维度，完整转存旧向量后才移除旧列。
INSERT INTO rag_embedding_model(model_name, dimension)
  SELECT split_part(value,'|',1), split_part(value,'|',2)::int
  FROM rag_runtime_setting WHERE key='embedding.identity';
INSERT INTO rag_embedding_state(id,active_model_id) SELECT 1,MIN(id) FROM rag_embedding_model;
INSERT INTO rag_chunk_embedding(model_id,chunk_id,dimension,embedding)
  SELECT m.id,c.id,m.dimension,c.embedding FROM rag_chunk c
  CROSS JOIN rag_embedding_state s JOIN rag_embedding_model m ON m.id=s.active_model_id;
DO $$
DECLARE current_model rag_embedding_model%ROWTYPE;
BEGIN
  IF (SELECT COUNT(*) FROM rag_chunk_embedding) <> (SELECT COUNT(*) FROM rag_chunk) THEN
    RAISE EXCEPTION '旧向量转存数量不一致，取消模型管理迁移';
  END IF;
  SELECT m.* INTO current_model FROM rag_embedding_model m JOIN rag_embedding_state s ON s.active_model_id=m.id;
  IF current_model.dimension <= 2000 THEN
    EXECUTE format('CREATE INDEX idx_rag_embedding_model_%s ON rag_chunk_embedding USING hnsw ((embedding::vector(%s)) vector_cosine_ops) WHERE model_id=%s',
      current_model.id,current_model.dimension,current_model.id);
  END IF;
END $$;
ALTER TABLE rag_chunk DROP COLUMN embedding;
