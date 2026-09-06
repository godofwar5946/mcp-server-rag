package org.example.rag.mapper;

import org.apache.ibatis.annotations.Select;

public interface SystemStatusMapper {
    @Select("""
            SELECT COUNT(*) AS files,COUNT(*) FILTER(WHERE status='INDEXED') AS indexed,
                COUNT(*) FILTER(WHERE status='FAILED') AS failed,COALESCE(SUM(size),0) AS bytes,
                (SELECT COUNT(*) FROM rag_chunk) AS chunks,
                (SELECT COUNT(*) FROM rag_folder) AS folders
            FROM rag_file
            """)
    Counts counts();
    record Counts(long files,long indexed,long failed,long bytes,long chunks,long folders) { }
}
