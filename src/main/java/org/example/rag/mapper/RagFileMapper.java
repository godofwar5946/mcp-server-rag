package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.rag.model.FileChunkStat;
import org.example.rag.model.FileListItem;
import org.example.rag.model.RagFileEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * rag_file 表 Mapper。
 */
public interface RagFileMapper extends BaseMapper<RagFileEntity> {

    @Update("""
            UPDATE rag_file
            SET content_type = #{contentType},
                size = #{size},
                content_bytes = #{contentBytes},
                parsed_text = #{parsedText},
                status = #{status},
                error_message = #{errorMessage},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateContentAndStatus(@Param("id") long id,
                               @Param("contentType") String contentType,
                               @Param("size") long size,
                               @Param("contentBytes") byte[] contentBytes,
                               @Param("parsedText") String parsedText,
                               @Param("status") String status,
                               @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE rag_file
            SET status = #{status},
                error_message = #{errorMessage},
                indexed_at = #{indexedAt},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateIndexStatus(@Param("id") long id,
                          @Param("status") String status,
                          @Param("errorMessage") String errorMessage,
                          @Param("indexedAt") LocalDateTime indexedAt);

    @Update("""
            UPDATE rag_file
            SET status = #{status},
                error_message = #{errorMessage},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT f.id AS id,
                   f.filename AS filename,
                   f.size AS size,
                   f.status AS status,
                   f.updated_at AS updatedAt,
                   f.indexed_at AS indexedAt,
                   COALESCE(c.cnt, 0) AS chunkCount
            FROM rag_file f
            LEFT JOIN (
                SELECT file_id, COUNT(*) AS cnt
                FROM rag_chunk
                GROUP BY file_id
            ) c ON f.id = c.file_id
            ORDER BY f.updated_at DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<FileListItem> listWithChunkCount(@Param("offset") int offset, @Param("size") int size);

    @Select("""
            SELECT f.id AS fileId,
                   COALESCE(c.cnt, 0) AS chunkCount,
                   f.indexed_at AS indexedAt
            FROM rag_file f
            LEFT JOIN (
                SELECT file_id, COUNT(*) AS cnt
                FROM rag_chunk
                GROUP BY file_id
            ) c ON f.id = c.file_id
            WHERE f.id = #{fileId}
            """)
    FileChunkStat getChunkStat(@Param("fileId") long fileId);
}
