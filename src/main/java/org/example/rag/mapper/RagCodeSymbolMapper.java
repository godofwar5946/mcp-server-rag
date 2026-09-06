package org.example.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.example.rag.model.CodeSymbolView;
import org.example.rag.model.RagCodeSymbolEntity;

import java.util.List;

/**
 * 代码符号表 Mapper，复杂检索 SQL 位于同名 MyBatis XML 中。
 */
public interface RagCodeSymbolMapper extends BaseMapper<RagCodeSymbolEntity> {

    @Insert("""
            INSERT INTO rag_code_symbol (
              file_id, parent_id, language, symbol_type, simple_name, qualified_name, signature,
              start_line, end_line, start_column, end_column, start_offset, end_offset,
              metadata, created_at, updated_at
            ) VALUES (
              #{entity.fileId}, #{entity.parentId}, #{entity.language}, #{entity.symbolType},
              #{entity.simpleName}, #{entity.qualifiedName}, #{entity.signature},
              #{entity.startLine}, #{entity.endLine}, #{entity.startColumn}, #{entity.endColumn},
              #{entity.startOffset}, #{entity.endOffset}, #{entity.metadataJson}::jsonb,
              #{entity.createdAt}, #{entity.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "entity.id", keyColumn = "id")
    int insertSymbol(@Param("entity") RagCodeSymbolEntity entity);

    @Delete("DELETE FROM rag_code_symbol WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") long fileId);

    List<CodeSymbolView> search(@Param("query") String query,
                                @Param("category") String category,
                                @Param("folderId") Long folderId,
                                @Param("limit") int limit);

    CodeSymbolView findViewById(@Param("id") long id);

    List<CodeSymbolView> findClasses(@Param("className") String className,
                                     @Param("folderId") Long folderId,
                                     @Param("limit") int limit);

    List<CodeSymbolView> findMethods(@Param("className") String className,
                                     @Param("methodName") String methodName,
                                     @Param("signature") String signature,
                                     @Param("folderId") Long folderId,
                                     @Param("limit") int limit);

    List<CodeSymbolView> findSqlStatements(@Param("namespace") String namespace,
                                           @Param("statementId") String statementId,
                                           @Param("folderId") Long folderId,
                                           @Param("limit") int limit);

    List<CodeSymbolView> findByPathAndLine(@Param("filePath") String filePath,
                                           @Param("line") int line,
                                           @Param("folderId") Long folderId,
                                           @Param("limit") int limit);

    List<CodeSymbolView> outline(@Param("fileId") long fileId);
}
