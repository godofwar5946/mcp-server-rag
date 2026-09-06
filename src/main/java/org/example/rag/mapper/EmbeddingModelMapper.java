package org.example.rag.mapper;

import org.apache.ibatis.annotations.*;
import org.example.rag.model.*;
import java.util.List;

public interface EmbeddingModelMapper {
    String MODEL = "id,model_name,model_digest,dimension,output_dimension,query_instruction";
    String RUN = "id::text,model_id,status,stage,total_chunks,completed_chunks,after_chunk_id,message,created_at,updated_at,finished_at";

    @Select("SELECT "+MODEL+" FROM rag_embedding_model WHERE id=(SELECT active_model_id FROM rag_embedding_state WHERE id=1)")
    EmbeddingModel current();
    @Select("SELECT "+MODEL+" FROM rag_embedding_model WHERE id=#{id}")
    EmbeddingModel model(long id);
    @Select("SELECT COUNT(*) FROM rag_chunk")
    long chunkCount();
    @Select("SELECT active_model_id FROM rag_embedding_state WHERE id=1 FOR SHARE")
    Long pinActive();
    @Select("SELECT active_model_id FROM rag_embedding_state WHERE id=1 FOR UPDATE")
    Long lockState();
    @Select(value="INSERT INTO rag_embedding_model(model_name,model_digest,dimension,output_dimension,query_instruction) VALUES(#{modelName},#{modelDigest},#{dimension},#{outputDimension},#{queryInstruction}) RETURNING id",affectData=true)
    long createModel(EmbeddingModel model);
    @Insert("INSERT INTO rag_embedding_rebuild(id,model_id,total_chunks) VALUES(#{id}::uuid,#{modelId},(SELECT COUNT(*) FROM rag_chunk))")
    int createRun(@Param("id") String id,@Param("modelId") long modelId);
    @Select("SELECT "+RUN+" FROM rag_embedding_rebuild ORDER BY created_at DESC,id DESC LIMIT 1")
    EmbeddingRebuild latest();
    @Select("SELECT "+RUN+" FROM rag_embedding_rebuild WHERE id=#{id}::uuid")
    EmbeddingRebuild run(String id);
    @Select("SELECT "+RUN+" FROM rag_embedding_rebuild WHERE id=#{id}::uuid AND status='RUNNING' AND lease_owner=#{owner} AND lease_until>NOW() FOR UPDATE")
    EmbeddingRebuild lockRun(@Param("id") String id,@Param("owner") String owner);
    @Select("SELECT EXISTS(SELECT 1 FROM rag_embedding_rebuild WHERE status IN ('QUEUED','RUNNING','FAILED'))")
    boolean hasPending();
    @Select(value="""
        WITH candidate AS (
          SELECT id FROM rag_embedding_rebuild WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<NOW())
          ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
        ) UPDATE rag_embedding_rebuild r SET status='RUNNING',lease_owner=#{owner},
            lease_until=NOW()+INTERVAL '180 seconds',updated_at=NOW()
          FROM candidate c WHERE r.id=c.id RETURNING r.id::text
        """,affectData=true)
    String claim(String owner);
    @Update("UPDATE rag_embedding_rebuild SET lease_until=NOW()+INTERVAL '180 seconds' WHERE id=#{id}::uuid AND lease_owner=#{owner} AND status='RUNNING' AND lease_until>NOW()")
    int heartbeat(@Param("id") String id,@Param("owner") String owner);
    @Select("""
        SELECT c.id,c.content FROM rag_chunk c LEFT JOIN rag_chunk_embedding e ON e.chunk_id=c.id AND e.model_id=#{modelId}
        WHERE c.id>#{afterId} AND e.chunk_id IS NULL ORDER BY c.id LIMIT #{limit}
        """)
    List<ChunkText> missing(@Param("modelId") long modelId,@Param("afterId") long afterId,@Param("limit") int limit);
    @Select("<script>SELECT id FROM rag_chunk WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach> ORDER BY id FOR KEY SHARE</script>")
    List<Long> pinChunks(@Param("ids") List<Long> ids);
    @Insert("""
        <script>
        INSERT INTO rag_chunk_embedding(model_id,chunk_id,dimension,embedding)
        SELECT #{modelId},c.id,#{dimension},v.embedding::vector FROM
          (VALUES <foreach collection="vectors" item="v" separator=",">(#{v.chunkId}::bigint,#{v.embedding}::text)</foreach>) AS v(id,embedding)
        JOIN rag_chunk c ON c.id=v.id ON CONFLICT(model_id,chunk_id) DO NOTHING
        </script>
        """)
    int saveVectors(@Param("modelId") long modelId,@Param("dimension") int dimension,@Param("vectors") List<ChunkVector> vectors);
    @Update("""
        UPDATE rag_embedding_rebuild SET after_chunk_id=#{afterId},stage='EMBEDDING',updated_at=NOW(),
          completed_chunks=completed_chunks+#{added},total_chunks=GREATEST(total_chunks,completed_chunks+#{added}) WHERE id=#{id}::uuid
        """)
    int advance(@Param("id") String id,@Param("afterId") long afterId,@Param("added") int added);
    @Update("""
        UPDATE rag_embedding_rebuild SET after_chunk_id=#{afterId},stage=#{stage},updated_at=NOW(),
          completed_chunks=(SELECT COUNT(*) FROM rag_chunk_embedding WHERE model_id=rag_embedding_rebuild.model_id),
          total_chunks=(SELECT COUNT(*) FROM rag_chunk) WHERE id=#{id}::uuid
        """)
    int progress(@Param("id") String id,@Param("afterId") long afterId,@Param("stage") String stage);
    @Update("UPDATE rag_embedding_state SET active_model_id=#{id} WHERE id=1")
    int activate(long id);
    @Update("UPDATE rag_embedding_model SET retired_at=NOW() WHERE id=#{id}")
    int retire(long id);
    @Update("UPDATE rag_runtime_setting SET value=#{identity} WHERE key='embedding.identity'")
    int updateIdentity(String identity);
    @Update("UPDATE rag_file SET embedding_model=#{model} WHERE status='INDEXED'")
    int updateFileModel(String model);
    @Update("UPDATE rag_embedding_rebuild SET status=#{status},stage='FINISHED',message=#{message},lease_owner=NULL,lease_until=NULL,updated_at=NOW(),finished_at=NOW() WHERE id=#{id}::uuid AND status='RUNNING' AND lease_owner=#{owner} AND lease_until>NOW()")
    int finish(@Param("id") String id,@Param("owner") String owner,@Param("status") String status,@Param("message") String message);
    @Update("UPDATE rag_embedding_rebuild SET status='QUEUED',message=NULL,lease_owner=NULL,lease_until=NULL,finished_at=NULL,updated_at=NOW() WHERE id=#{id}::uuid AND status='FAILED'")
    int retry(String id);
    @Update("UPDATE rag_embedding_rebuild SET status='CANCELLED',stage='FINISHED',lease_owner=NULL,lease_until=NULL,finished_at=NOW(),updated_at=NOW(),message='已取消，继续使用原模型与索引' WHERE id=#{id}::uuid AND status IN ('QUEUED','RUNNING','FAILED')")
    int cancel(String id);
    @Update("UPDATE rag_embedding_rebuild SET status='QUEUED',lease_owner=NULL,lease_until=NULL WHERE id=#{id}::uuid AND lease_owner=#{owner} AND status='RUNNING'")
    int release(@Param("id") String id,@Param("owner") String owner);
    @Select("""
        SELECT m.id FROM rag_embedding_model m WHERE m.cleaned_at IS NULL
          AND m.id<>(SELECT active_model_id FROM rag_embedding_state WHERE id=1)
          AND NOT EXISTS(SELECT 1 FROM rag_embedding_rebuild r WHERE r.model_id=m.id AND r.status IN ('QUEUED','RUNNING','FAILED'))
          AND (m.retired_at<NOW()-INTERVAL '1 hour' OR EXISTS(
            SELECT 1 FROM rag_embedding_rebuild r WHERE r.model_id=m.id AND r.status='CANCELLED' AND r.finished_at<NOW()-INTERVAL '1 hour'))
        ORDER BY m.id LIMIT 3
        """)
    List<Long> cleanableModels();
    @Delete("DELETE FROM rag_chunk_embedding WHERE model_id=#{id} AND chunk_id IN (SELECT chunk_id FROM rag_chunk_embedding WHERE model_id=#{id} LIMIT 10000)")
    int deleteOldVectors(long id);
    @Update("UPDATE rag_embedding_model SET cleaned_at=NOW() WHERE id=#{id}")
    int markCleaned(long id);
    record ChunkText(long id,String content) { }
    record ChunkVector(long chunkId,String embedding) { }
}
