package org.example.rag.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface EvaluationMapper {
    record Case(long id,String question,Long folderId,String knowledgeType,String expectedFileIds,boolean expectEmpty) { }
    record Run(long id,long caseId,String mode,String result,String createdAt) { }

    @Select("SELECT id,question,folder_id,knowledge_type,expected_file_ids::text,expect_empty FROM rag_evaluation_case ORDER BY id LIMIT 500")
    List<Case> list();
    @Select("SELECT id,question,folder_id,knowledge_type,expected_file_ids::text,expect_empty FROM rag_evaluation_case WHERE id=#{id}")
    Case get(long id);
    @Insert("INSERT INTO rag_evaluation_case(question,folder_id,knowledge_type,expected_file_ids,expect_empty) VALUES(#{question},#{folderId},#{type},CAST(#{expected} AS jsonb),#{empty})")
    void add(@Param("question") String question,@Param("folderId") Long folderId,@Param("type") String type,
             @Param("expected") String expected,@Param("empty") boolean empty);
    @Delete("DELETE FROM rag_evaluation_case WHERE id=#{id}")
    int delete(long id);
    @Insert("INSERT INTO rag_evaluation_run(case_id,mode,result) VALUES(#{id},#{mode},CAST(#{result} AS jsonb))")
    void save(@Param("id") long id,@Param("mode") String mode,@Param("result") String result);
    @Select("SELECT id,case_id,mode,result::text,created_at::text FROM rag_evaluation_run ORDER BY id DESC LIMIT 200")
    List<Run> runs();
}
