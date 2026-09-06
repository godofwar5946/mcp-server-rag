package org.example.rag.mapper;

import org.apache.ibatis.annotations.*;

public interface MaintenanceMapper {
    @Delete("DELETE FROM rag_index_job WHERE id IN (SELECT id FROM rag_index_job WHERE finished_at < NOW()-#{days}*INTERVAL '1 day' AND status NOT IN ('QUEUED','RUNNING') ORDER BY finished_at LIMIT 500)")
    int pruneJobs(int days);
    @Delete("DELETE FROM rag_evaluation_run WHERE id IN (SELECT id FROM rag_evaluation_run WHERE created_at < NOW()-#{days}*INTERVAL '1 day' ORDER BY created_at LIMIT 1000)")
    int pruneEvaluations(int days);
}
