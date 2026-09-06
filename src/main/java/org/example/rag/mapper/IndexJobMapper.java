package org.example.rag.mapper;

import org.apache.ibatis.annotations.*;
import org.example.rag.model.*;
import java.util.List;

/** PostgreSQL 持久化队列；领取、租约和完成条件都在数据库中校验。 */
public interface IndexJobMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(current_schema() || ':rag-job-submit',0))")
    int lockQueue();

    @Select("SELECT COUNT(*) FROM rag_index_job WHERE status IN ('QUEUED','RUNNING')")
    long activeCount();

    @Select("SELECT EXISTS(SELECT 1 FROM rag_index_job WHERE file_id=#{fileId} AND status IN ('QUEUED','RUNNING'))")
    boolean hasActiveFile(long fileId);

    @Insert("""
            INSERT INTO rag_index_job(id,batch_id,file_id,expected_revision,content_bytes,content_type,force_reindex)
            VALUES(#{id}::uuid,#{batchId}::uuid,#{fileId},#{revision},#{bytes},#{contentType},#{force})
            ON CONFLICT DO NOTHING
            """)
    int enqueue(@Param("id") String id, @Param("batchId") String batchId, @Param("fileId") long fileId,
                @Param("revision") long revision, @Param("bytes") byte[] bytes,
                @Param("contentType") String contentType, @Param("force") boolean force);

    @Select(value = """
            WITH candidate AS (
              SELECT id FROM rag_index_job WHERE status='QUEUED' AND NOT cancel_requested
              ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            ) UPDATE rag_index_job j SET status='RUNNING',stage='STARTING',attempts=attempts+1,
                lease_owner=#{owner},lease_until=NOW()+#{seconds}*INTERVAL '1 second',updated_at=NOW()
              FROM candidate c WHERE j.id=c.id RETURNING j.id::text
            """, affectData = true)
    String claim(@Param("owner") String owner, @Param("seconds") int seconds);

    @Select("""
            SELECT j.id::text,j.file_id,j.expected_revision,COALESCE(j.content_bytes,f.content_bytes) AS content_bytes,
                COALESCE(j.content_type,f.content_type) AS content_type,j.force_reindex,j.lease_owner
            FROM rag_index_job j JOIN rag_file f ON f.id=j.file_id
            WHERE j.id=#{id}::uuid AND j.lease_owner=#{owner} AND j.status='RUNNING'
            """)
    IndexJobPayload payload(@Param("id") String id, @Param("owner") String owner);

    @Update("""
            UPDATE rag_index_job SET stage=#{stage},completed_chunks=#{completed},total_chunks=#{total},updated_at=NOW()
            WHERE id=#{id}::uuid AND status='RUNNING' AND lease_owner=#{owner}
              AND lease_until>NOW() AND NOT cancel_requested
            """)
    int progress(@Param("id") String id, @Param("owner") String owner, @Param("stage") String stage,
                 @Param("completed") int completed, @Param("total") int total);

    @Select("""
            SELECT NOT cancel_requested AND status='RUNNING' AND lease_owner=#{owner} AND lease_until>NOW()
            FROM rag_index_job WHERE id=#{id}::uuid FOR UPDATE
            """)
    Boolean lockLease(@Param("id") String id, @Param("owner") String owner);

    @Update("""
            UPDATE rag_index_job SET status=#{status},stage='FINISHED',message=#{message},finished_at=NOW(),updated_at=NOW(),
              lease_owner=NULL,lease_until=NULL,
              content_bytes=CASE WHEN #{status} IN ('SUCCEEDED','SKIPPED') THEN NULL ELSE content_bytes END
            WHERE id=#{id}::uuid AND status='RUNNING' AND lease_owner=#{owner} AND lease_until>NOW()
            """)
    int finish(@Param("id") String id, @Param("owner") String owner,
               @Param("status") String status, @Param("message") String message);

    @Update("""
            <script>
            UPDATE rag_index_job SET lease_until=NOW()+#{seconds}*INTERVAL '1 second'
            WHERE status='RUNNING' AND lease_until>NOW() AND lease_owner IN
              <foreach collection="owners" item="owner" open="(" close=")" separator=",">#{owner}</foreach>
            </script>
            """)
    int heartbeat(@Param("owners") List<String> owners, @Param("seconds") int seconds);

    @Update("""
            UPDATE rag_index_job SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'QUEUED' END,
                stage='WAITING',lease_owner=NULL,lease_until=NULL,updated_at=NOW(),
                message='工作线程中断，任务已恢复；连续中断三次后需手动重试',
                finished_at=CASE WHEN attempts>=3 THEN NOW() ELSE NULL END
            WHERE status='RUNNING' AND lease_until<NOW()
            """)
    int recoverExpired();

    @Update("""
            UPDATE rag_index_job SET status='QUEUED',stage='WAITING',lease_owner=NULL,lease_until=NULL,updated_at=NOW()
            WHERE status='RUNNING' AND lease_owner=#{owner}
            """)
    int release(String owner);

    String VIEW = "SELECT j.id::text,j.batch_id::text,j.file_id,f.filename AS file_name,f.folder_id,"
            + "j.status,j.stage,j.completed_chunks,j.total_chunks,j.attempts,j.message,j.created_at,j.updated_at,j.finished_at "
            + "FROM rag_index_job j JOIN rag_file f ON f.id=j.file_id ";

    @Select("<script>" + VIEW + "WHERE 1=1 "
            + "<if test='status != null'>AND j.status=#{status} </if>"
            + "<if test='batchId != null'>AND j.batch_id=#{batchId}::uuid </if>"
            + "ORDER BY j.created_at DESC,j.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<IndexJobView> list(@Param("status") String status, @Param("batchId") String batchId,
                            @Param("limit") int limit, @Param("offset") int offset);

    @Update("""
            UPDATE rag_index_job SET status='CANCELLED',cancel_requested=TRUE,stage='FINISHED',
              message='任务已取消，已发布的索引保留',finished_at=NOW(),updated_at=NOW()
            WHERE id=#{id}::uuid AND status IN ('QUEUED','RUNNING')
            """)
    int cancel(String id);

    @Select("SELECT file_id FROM rag_index_job WHERE id=#{id}::uuid")
    Long fileId(String id);

    @Update("""
            UPDATE rag_index_job SET status='QUEUED',stage='WAITING',expected_revision=#{revision},
              completed_chunks=0,total_chunks=0,attempts=0,cancel_requested=FALSE,message=NULL,
              lease_owner=NULL,lease_until=NULL,finished_at=NULL,updated_at=NOW()
            WHERE id=#{id}::uuid AND status IN ('FAILED','CANCELLED') AND expected_revision=#{revision}
            """)
    int retry(@Param("id") String id, @Param("revision") long revision);
}
