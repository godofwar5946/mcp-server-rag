package org.example.rag.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RerankSettingsMapper {
    @Select("SELECT value FROM rag_runtime_setting WHERE key='rerank.config'")
    String read();

    @Insert("""
        INSERT INTO rag_runtime_setting(key,value) VALUES('rerank.config',#{value})
        ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value
        WHERE rag_runtime_setting.value=#{expected}
        """)
    int compareAndSet(@Param("value") String value,@Param("expected") String expected);
}
