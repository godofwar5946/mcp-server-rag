package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.rag.model.RagChunkEntity;
import org.example.rag.model.RagSearchRow;
import org.example.rag.model.EmbeddingModel;

import java.util.List;

/**
 * rag_chunk 表 Mapper。
 */
public interface RagChunkMapper extends BaseMapper<RagChunkEntity> {

    @Delete("DELETE FROM rag_chunk WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") long fileId);

    @Insert("""
            <script>
            WITH source(code_symbol_id,chunk_index,start_line,end_line,content,embedding,metadata) AS (VALUES
            <foreach collection="list" item="item" separator=",">
              (
                #{item.codeSymbolId}::bigint, #{item.chunkIndex}::int, #{item.startLine}::int, #{item.endLine}::int,
                #{item.content}::text, #{item.embedding}::vector, #{item.metadataJson}::jsonb
              )
            </foreach>
            ), inserted AS (
              INSERT INTO rag_chunk(file_id,code_symbol_id,chunk_index,start_line,end_line,content,metadata,created_at,updated_at)
              SELECT #{fileId},code_symbol_id,chunk_index,start_line,end_line,content,metadata,NOW(),NOW() FROM source
              RETURNING id,chunk_index
            ) INSERT INTO rag_chunk_embedding(model_id,chunk_id,dimension,embedding)
              SELECT m.id,i.id,m.dimension,s.embedding FROM inserted i JOIN source s ON s.chunk_index=i.chunk_index
              CROSS JOIN rag_embedding_state a JOIN rag_embedding_model m ON m.id=a.active_model_id
            </script>
            """)
    int insertBatch(@Param("fileId") long fileId, @Param("list") List<RagChunkEntity> chunks);

    @Select("""
            <script>
            WITH RECURSIVE folder_paths AS (
                SELECT d.id, d.parent_id, d.name::TEXT AS path
                FROM rag_folder d
                WHERE d.parent_id IS NULL
                UNION ALL
                SELECT d.id, d.parent_id, fp.path || '/' || d.name
                FROM rag_folder d
                JOIN folder_paths fp ON d.parent_id = fp.id
            ), folder_scope AS (
                SELECT id FROM rag_folder WHERE id = #{folderId}
                UNION ALL
                SELECT d.id
                FROM rag_folder d
                JOIN folder_scope fs ON d.parent_id = fs.id
            ), q AS (SELECT #{vector}::vector AS v)
            SELECT c.file_id AS fileId,
                   CASE
                     WHEN fp.path IS NULL THEN f.filename
                     ELSE fp.path || '/' || f.filename
                   END AS fileName,
                   COALESCE(f.knowledge_type, 'ALL') AS knowledgeType,
                   c.chunk_index AS chunkIndex,
                   c.code_symbol_id AS codeSymbolId,
                   c.start_line AS startLine,
                   c.end_line AS endLine,
                   c.metadata ->> 'symbolType' AS symbolType,
                   c.metadata ->> 'qualifiedName' AS qualifiedName,
                   c.content AS content,
                   (e.embedding &lt;=&gt; q.v) AS distance,
                   c.metadata::text AS metadataJson,
                   f.revision AS revision
            FROM rag_chunk c
            JOIN rag_chunk_embedding e ON e.chunk_id=c.id AND e.model_id=${model.id}
            JOIN rag_file f ON c.file_id = f.id
            LEFT JOIN folder_paths fp ON fp.id = f.folder_id
            JOIN q ON true
            WHERE f.status = 'INDEXED'
              AND (
                CAST(#{folderId} AS BIGINT) IS NULL
                OR (#{folderId} = 0 AND f.folder_id IS NULL)
                OR (#{folderId} > 0 AND f.folder_id IN (SELECT id FROM folder_scope))
              )
              AND (
                CAST(#{knowledgeType} AS VARCHAR) IS NULL
                OR COALESCE(f.knowledge_type, 'ALL') IN ('ALL', #{knowledgeType})
              )
              AND (
                NOT #{codeOnly}
                OR c.metadata ->> 'language' IN ('JAVA', 'XML')
              )
            ORDER BY
            <choose><when test="model.dimension &lt;= 2000">e.embedding::vector(${model.dimension})</when>
              <otherwise>e.embedding</otherwise></choose> &lt;=&gt; q.v
            LIMIT #{limit}
            </script>
            """)
    List<RagSearchRow> search(@Param("vector") String vector,
                              @Param("limit") int limit,
                              @Param("knowledgeType") String knowledgeType,
                              @Param("folderId") Long folderId,
                              @Param("codeOnly") boolean codeOnly,@Param("model") EmbeddingModel model);

    @Select("""
            SELECT 1 WHERE set_config('hnsw.ef_search',#{efSearch},true) IS NOT NULL
              AND set_config('hnsw.iterative_scan','strict_order',true) IS NOT NULL
              AND set_config('statement_timeout','5000',true) IS NOT NULL
            """)
    int configureSearch(String efSearch);

    @Select("""
            <script>
            WITH RECURSIVE scope AS (
              SELECT id FROM rag_folder WHERE id=#{folderId}
              UNION ALL SELECT d.id FROM rag_folder d JOIN scope s ON d.parent_id=s.id
            ), hits AS MATERIALIZED (
              SELECT c.*,e.embedding,f.filename,f.folder_id,f.knowledge_type,f.revision,
                ts_rank_cd(to_tsvector('simple',c.content),plainto_tsquery('simple',#{query})) AS lexical_rank
              FROM rag_chunk c JOIN rag_file f ON f.id=c.file_id
              JOIN rag_chunk_embedding e ON e.chunk_id=c.id AND e.model_id=#{model.id}
              WHERE f.status='INDEXED'
                AND (CAST(#{folderId} AS BIGINT) IS NULL OR (#{folderId}=0 AND f.folder_id IS NULL)
                    OR f.folder_id IN (SELECT id FROM scope))
                AND (CAST(#{knowledgeType} AS TEXT) IS NULL OR f.knowledge_type IN ('ALL',#{knowledgeType}))
                AND (NOT #{codeOnly} OR c.metadata->>'language' IN ('JAVA','XML'))
                AND (to_tsvector('simple',c.content) @@ plainto_tsquery('simple',#{query})
                     OR lower(c.content) LIKE #{pattern} ESCAPE '!')
              ORDER BY lexical_rank DESC,c.id LIMIT #{limit}
            ) SELECT h.file_id AS fileId,
                CASE WHEN p.path IS NULL THEN h.filename ELSE p.path||'/'||h.filename END AS fileName,
                h.knowledge_type AS knowledgeType,h.chunk_index AS chunkIndex,h.code_symbol_id AS codeSymbolId,
                h.start_line AS startLine,h.end_line AS endLine,h.metadata->>'symbolType' AS symbolType,
                h.metadata->>'qualifiedName' AS qualifiedName,h.content,
                CASE WHEN CAST(#{vector} AS TEXT) IS NULL THEN 1.0 ELSE h.embedding &lt;=&gt; #{vector}::vector END AS distance,
                h.metadata::text AS metadataJson,h.revision
              FROM hits h LEFT JOIN LATERAL (
                WITH RECURSIVE ancestors AS (
                  SELECT id,parent_id,name,0 AS depth FROM rag_folder WHERE id=h.folder_id
                  UNION ALL SELECT d.id,d.parent_id,d.name,a.depth+1 FROM rag_folder d
                    JOIN ancestors a ON d.id=a.parent_id WHERE a.depth &lt; 64
                ) SELECT string_agg(name,'/' ORDER BY depth DESC) AS path FROM ancestors
              ) p ON TRUE ORDER BY h.lexical_rank DESC,h.id
            </script>
            """)
    List<RagSearchRow> searchLexical(@Param("query") String query,@Param("pattern") String pattern,
        @Param("vector") String vector,@Param("limit") int limit,@Param("knowledgeType") String knowledgeType,
        @Param("folderId") Long folderId,@Param("codeOnly") boolean codeOnly,@Param("model") EmbeddingModel model);

    @Select("""
            SELECT id,file_id,chunk_index,start_line,end_line,substring(content FROM 1 FOR 20000) AS content,
              length(content)>20000 AS truncated,metadata::text AS metadata_json FROM rag_chunk WHERE file_id=#{fileId}
            ORDER BY chunk_index LIMIT #{limit} OFFSET #{offset}
            """)
    List<RagChunkEntity> listForFile(@Param("fileId") long fileId,@Param("limit") int limit,@Param("offset") int offset);

    @Select("""
            SELECT c.id,c.file_id,c.chunk_index,c.start_line,c.end_line,substring(c.content FROM 1 FOR 20000) AS content,
              length(c.content)>20000 AS truncated,c.metadata::text AS metadata_json FROM rag_chunk c JOIN rag_file f ON f.id=c.file_id
            WHERE c.file_id=#{fileId} AND c.chunk_index=#{chunkIndex} AND f.revision=#{revision}
            """)
    RagChunkEntity readRevisionChunk(@Param("fileId") long fileId,@Param("chunkIndex") int chunkIndex,@Param("revision") long revision);
}
