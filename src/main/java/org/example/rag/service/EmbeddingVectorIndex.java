package org.example.rag.service;

import org.example.rag.model.EmbeddingModel;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;

/** 不持有数据事务构建 HNSW，在线检索和文件上传可继续；崩溃留下的无效索引可恢复。 */
@Service
public class EmbeddingVectorIndex {
    private final DataSource dataSource;
    public EmbeddingVectorIndex(DataSource dataSource) { this.dataSource=dataSource; }

    public void ensure(EmbeddingModel model) {
        if (model.dimension()>2000) return; // 原生高维向量采用精确检索，不做隐式截断。
        if (model.dimension()<1) throw new IllegalArgumentException("模型维度无效");
        withLock(model.id(),connection->{
            String name=name(model.id());
            Boolean valid=null;
            try (PreparedStatement query=connection.prepareStatement("SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relname=?")) {
                query.setString(1,name);
                try (ResultSet row=query.executeQuery()) { if (row.next()) valid=row.getBoolean(1); }
            }
            try (Statement sql=connection.createStatement()) {
                if (Boolean.TRUE.equals(valid)) return;
                if (Boolean.FALSE.equals(valid)) sql.execute("DROP INDEX CONCURRENTLY "+name);
                sql.execute("CREATE INDEX CONCURRENTLY "+name+" ON rag_chunk_embedding USING hnsw ((embedding::vector("+
                        model.dimension()+")) vector_cosine_ops) WHERE model_id="+model.id());
            }
        });
    }

    public void drop(long modelId) {
        withLock(modelId,connection->{
            try (Statement sql=connection.createStatement()) { sql.execute("DROP INDEX CONCURRENTLY IF EXISTS "+name(modelId)); }
        });
    }

    private String name(long id) {
        if (id<=0) throw new IllegalArgumentException("模型版本无效");
        return "idx_rag_embedding_model_"+id;
    }

    private void withLock(long id,SqlAction action) {
        name(id);
        // 独立连接上的会话锁串行化同一代索引 DDL，避免租约恢复期间竞争建索引。
        try (Connection connection=dataSource.getConnection()) {
            connection.setAutoCommit(true);
            boolean locked=false;
            try {
                try (Statement sql=connection.createStatement()) {
                    sql.execute("SET statement_timeout='30min'");
                    sql.execute("SET lock_timeout='30s'");
                }
                try (PreparedStatement lock=connection.prepareStatement("SELECT pg_advisory_lock(18642017,?::int)")) {
                    lock.setLong(1,id); lock.execute(); locked=true;
                }
                action.run(connection);
            }
            finally {
                // 即使等锁超时，连接归还池前也必须恢复参数；恢复失败则丢弃物理连接。
                try {
                    if (locked) try (PreparedStatement unlock=connection.prepareStatement("SELECT pg_advisory_unlock(18642017,?::int)")) {
                        unlock.setLong(1,id); unlock.execute();
                    }
                    try (Statement sql=connection.createStatement()) {
                        sql.execute("RESET statement_timeout"); sql.execute("RESET lock_timeout");
                    }
                } catch (SQLException cleanupFailure) { connection.abort(Runnable::run); throw cleanupFailure; }
            }
        } catch (SQLException ex) { throw new IllegalStateException("向量索引构建或清理失败，请查看服务日志并重试",ex); }
    }
    private interface SqlAction { void run(Connection connection) throws SQLException; }
}
