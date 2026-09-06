package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.rag.model.FolderListRow;
import org.example.rag.model.RagFolderEntity;

import java.util.List;

/**
 * rag_folder 表 Mapper。
 */
public interface RagFolderMapper extends BaseMapper<RagFolderEntity> {
    @org.apache.ibatis.annotations.Insert("INSERT INTO rag_folder(parent_id,name) VALUES(#{parentId},#{name}) ON CONFLICT DO NOTHING")
    int ensureFolder(@Param("parentId") Long parentId,@Param("name") String name);

    @org.apache.ibatis.annotations.Update("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM rag_folder WHERE id=#{id}
                UNION SELECT f.id FROM rag_folder f JOIN descendants d ON f.parent_id=d.id
            ) UPDATE rag_folder SET default_knowledge_type=#{type},updated_at=NOW()
              WHERE id IN (SELECT id FROM descendants)
            """)
    int setTreeDefaultKnowledgeType(@Param("id") long id,@Param("type") String type);
    @Select("""
            WITH RECURSIVE ancestors AS (
                SELECT id,parent_id,default_knowledge_type,0 AS depth FROM rag_folder WHERE id=#{folderId}
                UNION ALL
                SELECT f.id,f.parent_id,f.default_knowledge_type,a.depth+1
                FROM rag_folder f JOIN ancestors a ON a.parent_id=f.id WHERE a.depth<64
            ) SELECT default_knowledge_type FROM ancestors WHERE default_knowledge_type IS NOT NULL
              ORDER BY depth LIMIT 1
            """)
    String inheritedKnowledgeType(Long folderId);

    @org.apache.ibatis.annotations.Update("UPDATE rag_folder SET default_knowledge_type=#{type} WHERE id=#{id}")
    int setDefaultKnowledgeType(@Param("id") long id, @Param("type") String type);

    @Select("""
            SELECT d.id AS id,
                   d.parent_id AS parentId,
                   d.name AS name,
                   COUNT(f.id) AS fileCount
            FROM rag_folder d
            LEFT JOIN rag_file f ON f.folder_id = d.id
            GROUP BY d.id, d.parent_id, d.name
            ORDER BY LOWER(d.name), d.id
            """)
    List<FolderListRow> listWithFileCount();

    /**
     * 查询指定目录及全部下级目录，父目录排在子目录之前，供 ZIP 导出构建路径。
     * path_ids 同时用于阻止历史脏数据中的目录环导致递归查询无法结束。
     */
    @Select("""
            WITH RECURSIVE folder_tree AS (
                SELECT d.id,
                       d.parent_id,
                       d.name,
                       d.created_at,
                       d.updated_at,
                       0 AS depth,
                       ARRAY[d.id] AS path_ids
                FROM rag_folder d
                WHERE d.id = #{folderId}
                UNION ALL
                SELECT d.id,
                       d.parent_id,
                       d.name,
                       d.created_at,
                       d.updated_at,
                       ft.depth + 1,
                       ft.path_ids || d.id
                FROM rag_folder d
                JOIN folder_tree ft ON d.parent_id = ft.id
                WHERE NOT d.id = ANY(ft.path_ids)
            )
            SELECT id, parent_id, name, created_at, updated_at
            FROM folder_tree
            ORDER BY depth, LOWER(name), id
            """)
    List<RagFolderEntity> listSubtreeForExport(@Param("folderId") long folderId);

    @Select("""
            <script>
            SELECT id, parent_id, name, created_at, updated_at
            FROM rag_folder
            WHERE LOWER(name) = LOWER(#{name})
              <choose>
                <when test="parentId == null">AND parent_id IS NULL</when>
                <otherwise>AND parent_id = #{parentId}</otherwise>
              </choose>
            LIMIT 1
            </script>
            """)
    RagFolderEntity findByParentAndName(@Param("parentId") Long parentId,
                                        @Param("name") String name);

    @Select("SELECT COUNT(*) FROM rag_folder WHERE parent_id = #{folderId}")
    long countChildren(@Param("folderId") long folderId);

    @Select("SELECT COUNT(*) FROM rag_file WHERE folder_id = #{folderId}")
    long countFiles(@Param("folderId") long folderId);
}
