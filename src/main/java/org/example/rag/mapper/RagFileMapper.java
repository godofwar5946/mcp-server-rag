package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.rag.model.FileChunkStat;
import org.example.rag.model.FolderExportFile;
import org.example.rag.model.FileListItem;
import org.example.rag.model.RagFileEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * rag_file 表 Mapper。
 */
public interface RagFileMapper extends BaseMapper<RagFileEntity> {
    String INDEX_FIELDS = "id,filename,folder_id,content_type,knowledge_type,size,status,error_message,"
            + "created_at,updated_at,indexed_at,revision,content_hash,index_fingerprint,embedding_model";

    @Select("SELECT " + INDEX_FIELDS + " FROM rag_file WHERE id = #{id}")
    RagFileEntity selectMetadata(long id);

    @Select("SELECT " + INDEX_FIELDS + " FROM rag_file WHERE id = #{id} FOR UPDATE")
    RagFileEntity lockForIndex(long id);

    @Select("SELECT " + INDEX_FIELDS + " FROM rag_file WHERE filename = #{filename} "
            + "AND COALESCE(folder_id,0) = COALESCE(#{folderId},0)")
    RagFileEntity findByLocation(@Param("filename") String filename, @Param("folderId") Long folderId);

    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO rag_file(filename,folder_id,content_type,knowledge_type,size,content_bytes,status)
            VALUES(#{filename},#{folderId},#{contentType},#{knowledgeType},#{size},#{contentBytes},'PENDING')
            ON CONFLICT DO NOTHING
            """)
    int insertPlaceholder(RagFileEntity file);

    @Select("SELECT id FROM rag_file WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Long> listIdsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT id,parsed_text,revision FROM rag_file WHERE id = #{id}")
    RagFileEntity selectSource(long id);

    @Select("SELECT id,filename,folder_id,content_type,content_bytes,revision FROM rag_file WHERE id = #{id}")
    RagFileEntity selectOriginal(long id);

    @Update("""
            UPDATE rag_file SET content_type=#{contentType},size=#{size},content_bytes=#{contentBytes},
              parsed_text=#{parsedText},status='INDEXED',error_message=NULL,indexed_at=NOW(),updated_at=NOW(),
              revision=revision+1,content_hash=#{contentHash},index_fingerprint=#{indexFingerprint},
              embedding_model=#{embeddingModel}
            WHERE id=#{id} AND revision=#{revision}
            """)
    int publishIndex(RagFileEntity file);

    @Update("""
            UPDATE rag_file SET error_message=#{message},
              status=CASE WHEN status='INDEXED' THEN status ELSE 'FAILED' END
            WHERE id=#{id} AND revision=#{revision}
            """)
    int recordIndexFailure(@Param("id") long id, @Param("revision") long revision, @Param("message") String message);

    @Select("""
            <script>
            SELECT f.id,f.filename,f.folder_id AS folderId,COALESCE(fp.path,'') AS folderPath,
              f.knowledge_type AS knowledgeType,f.size,f.status,f.updated_at AS updatedAt,
              f.indexed_at AS indexedAt,c.cnt AS chunkCount,f.error_message AS errorMessage,f.revision
            FROM (
              SELECT id,filename,folder_id,knowledge_type,size,status,updated_at,indexed_at,error_message,revision FROM rag_file
              WHERE (CAST(#{folderId} AS BIGINT) IS NULL OR COALESCE(folder_id,0)=#{folderId})
                AND (CAST(#{query} AS TEXT) IS NULL OR LOWER(filename) LIKE LOWER(#{query}) ESCAPE '!')
                AND (CAST(#{status} AS TEXT) IS NULL OR status=#{status})
                AND (CAST(#{type} AS TEXT) IS NULL OR knowledge_type=#{type})
              <choose>
                <when test="sort == 'name'">ORDER BY LOWER(filename),id</when>
                <when test="sort == 'size'">ORDER BY size DESC,id DESC</when>
                <otherwise>ORDER BY updated_at DESC,id DESC</otherwise>
              </choose>
              LIMIT #{size} OFFSET #{offset}
            ) f
            LEFT JOIN LATERAL (
              WITH RECURSIVE ancestors AS (
                SELECT id,parent_id,name,0 AS depth FROM rag_folder WHERE id=f.folder_id
                UNION ALL SELECT d.id,d.parent_id,d.name,a.depth+1
                FROM rag_folder d JOIN ancestors a ON d.id=a.parent_id WHERE a.depth&lt;64
              ) SELECT string_agg(name,'/' ORDER BY depth DESC) AS path FROM ancestors
            ) fp ON TRUE
            LEFT JOIN LATERAL (SELECT COUNT(*) AS cnt FROM rag_chunk WHERE file_id=f.id) c ON TRUE
            <choose>
              <when test="sort == 'name'">ORDER BY LOWER(f.filename),f.id</when>
              <when test="sort == 'size'">ORDER BY f.size DESC,f.id DESC</when>
              <otherwise>ORDER BY f.updated_at DESC,f.id DESC</otherwise>
            </choose>
            </script>
            """)
    List<FileListItem> searchFiles(@Param("folderId") Long folderId,@Param("query") String query,
            @Param("status") String status,@Param("type") String type,@Param("sort") String sort,
            @Param("offset") int offset,@Param("size") int size);

    @Select("""
            SELECT COUNT(*) FROM rag_file
            WHERE (CAST(#{folderId} AS BIGINT) IS NULL OR COALESCE(folder_id,0)=#{folderId})
              AND (CAST(#{query} AS TEXT) IS NULL OR LOWER(filename) LIKE LOWER(#{query}) ESCAPE '!')
              AND (CAST(#{status} AS TEXT) IS NULL OR status=#{status})
              AND (CAST(#{type} AS TEXT) IS NULL OR knowledge_type=#{type})
            """)
    long countFiltered(@Param("folderId") Long folderId,@Param("query") String query,
                       @Param("status") String status,@Param("type") String type);

    @Select("SELECT id,filename,substring(parsed_text FROM #{start} FOR #{length}) AS parsed_text,"
            + "length(parsed_text) AS size,revision FROM rag_file WHERE id=#{id}")
    RagFileEntity previewWindow(@Param("id") long id,@Param("start") int start,@Param("length") int length);

    @Update("UPDATE rag_file SET knowledge_type=#{type},updated_at=NOW() WHERE id=#{id}")
    int setKnowledgeType(@Param("id") long id,@Param("type") String type);

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
            <script>
            WITH RECURSIVE folder_paths AS (
                SELECT d.id, d.parent_id, d.name, d.name::TEXT AS path
                FROM rag_folder d
                WHERE d.parent_id IS NULL
                UNION ALL
                SELECT d.id, d.parent_id, d.name, fp.path || '/' || d.name
                FROM rag_folder d
                JOIN folder_paths fp ON d.parent_id = fp.id
            ), chunk_counts AS (
                SELECT file_id, COUNT(*) AS cnt
                FROM rag_chunk
                GROUP BY file_id
            )
            SELECT f.id AS id,
                   f.filename AS filename,
                   f.folder_id AS folderId,
                   COALESCE(fp.path, '') AS folderPath,
                   COALESCE(f.knowledge_type, 'ALL') AS knowledgeType,
                   f.size AS size,
                   f.status AS status,
                   f.updated_at AS updatedAt,
                   f.indexed_at AS indexedAt,
                   COALESCE(c.cnt, 0) AS chunkCount,
                   f.error_message AS errorMessage,f.revision
            FROM rag_file f
            LEFT JOIN folder_paths fp ON fp.id = f.folder_id
            LEFT JOIN chunk_counts c ON f.id = c.file_id
            <if test="folderId != null">
              <choose>
                <when test="folderId == 0">WHERE f.folder_id IS NULL</when>
                <otherwise>WHERE f.folder_id = #{folderId}</otherwise>
              </choose>
            </if>
            ORDER BY f.updated_at DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<FileListItem> listWithChunkCount(@Param("folderId") Long folderId,
                                          @Param("offset") int offset,
                                          @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM rag_file
            <if test="folderId != null">
              <choose>
                <when test="folderId == 0">WHERE folder_id IS NULL</when>
                <otherwise>WHERE folder_id = #{folderId}</otherwise>
              </choose>
            </if>
            </script>
            """)
    long countByFolder(@Param("folderId") Long folderId);

    /**
     * 只读取导出清单所需的元数据，不在此处加载可能很大的原始文件字节。
     */
    @Select("""
            WITH RECURSIVE folder_tree AS (
                SELECT d.id, 0 AS depth, ARRAY[d.id] AS path_ids
                FROM rag_folder d
                WHERE d.id = #{folderId}
                UNION ALL
                SELECT d.id, ft.depth + 1, ft.path_ids || d.id
                FROM rag_folder d
                JOIN folder_tree ft ON d.parent_id = ft.id
                WHERE NOT d.id = ANY(ft.path_ids)
            )
            SELECT f.id AS id,
                   f.folder_id AS folderId,
                   f.filename AS filename,
                   f.size AS size,
                   f.updated_at AS updatedAt,
                   (f.content_bytes IS NOT NULL) AS contentAvailable
            FROM rag_file f
            JOIN folder_tree ft ON ft.id = f.folder_id
            ORDER BY ft.depth, LOWER(f.filename), f.id
            """)
    List<FolderExportFile> listForFolderTreeExport(@Param("folderId") long folderId);

    /**
     * ZIP 写出时逐个加载原始内容，避免一次把整棵目录树的二进制文件放进 JVM 堆。
     */
    @Select("SELECT id, content_bytes FROM rag_file WHERE id = #{fileId}")
    RagFileEntity selectContentForExport(@Param("fileId") long fileId);

    @Update("""
            UPDATE rag_file
            SET folder_id = #{folderId}, updated_at = NOW(), revision = revision + 1
            WHERE id = #{fileId}
            """)
    int moveToFolder(@Param("fileId") long fileId, @Param("folderId") Long folderId);

    @Update("""
            UPDATE rag_file
            SET knowledge_type = #{knowledgeType}, updated_at = NOW()
            WHERE folder_id = #{folderId}
            """)
    int updateKnowledgeTypeByFolder(@Param("folderId") long folderId,
                                    @Param("knowledgeType") String knowledgeType);

    @Update("""
            WITH RECURSIVE folder_tree AS (
                SELECT id FROM rag_folder WHERE id = #{folderId}
                UNION ALL
                SELECT d.id
                FROM rag_folder d
                JOIN folder_tree ft ON d.parent_id = ft.id
            )
            UPDATE rag_file
            SET knowledge_type = #{knowledgeType}, updated_at = NOW()
            WHERE folder_id IN (SELECT id FROM folder_tree)
            """)
    int updateKnowledgeTypeByFolderTree(@Param("folderId") long folderId,
                                        @Param("knowledgeType") String knowledgeType);

    @Delete("""
            WITH RECURSIVE folder_tree AS (
                SELECT id FROM rag_folder WHERE id = #{folderId}
                UNION ALL
                SELECT d.id
                FROM rag_folder d
                JOIN folder_tree ft ON d.parent_id = ft.id
            )
            DELETE FROM rag_file WHERE folder_id IN (SELECT id FROM folder_tree)
            """)
    int deleteByFolderTree(@Param("folderId") long folderId);

    @Select("""
            SELECT f.id AS fileId,
                   COALESCE(c.cnt, 0) AS chunkCount,
                   f.indexed_at AS indexedAt
            FROM rag_file f
            LEFT JOIN (
                SELECT file_id, COUNT(*) AS cnt
                FROM rag_chunk
                WHERE file_id = #{fileId}
                GROUP BY file_id
            ) c ON f.id = c.file_id
            WHERE f.id = #{fileId}
            """)
    FileChunkStat getChunkStat(@Param("fileId") long fileId);
}
