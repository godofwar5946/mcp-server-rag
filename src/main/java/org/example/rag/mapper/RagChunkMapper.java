package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.rag.model.RagChunkEntity;
import org.example.rag.model.RagSearchRow;

import java.util.List;

/**
 * rag_chunk 表 Mapper。
 */
public interface RagChunkMapper extends BaseMapper<RagChunkEntity> {

    @Delete("DELETE FROM rag_chunk WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") long fileId);

    @Insert("""
            <script>
            INSERT INTO rag_chunk (file_id, chunk_index, content, embedding, metadata, created_at, updated_at)
            VALUES
            <foreach collection="list" item="item" separator=",">
              (#{fileId}, #{item.chunkIndex}, #{item.content}, #{item.embedding}::vector, #{item.metadataJson}::jsonb, NOW(), NOW())
            </foreach>
            </script>
            """)
    int insertBatch(@Param("fileId") long fileId, @Param("list") List<RagChunkEntity> chunks);

    @Select("""
            WITH q AS (SELECT #{vector}::vector AS v)
            SELECT c.file_id AS fileId,
                   f.filename AS fileName,
                   c.chunk_index AS chunkIndex,
                   c.content AS content,
                   (c.embedding <=> q.v) AS distance
            FROM rag_chunk c
            JOIN rag_file f ON c.file_id = f.id
            JOIN q ON true
            WHERE f.status = 'INDEXED'
            ORDER BY c.embedding <=> q.v
            LIMIT #{limit}
            """)
    List<RagSearchRow> search(@Param("vector") String vector, @Param("limit") int limit);
}
