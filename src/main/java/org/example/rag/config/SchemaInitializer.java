package org.example.rag.config;

import org.example.rag.service.EmbeddingDimensionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 统一以资源目录中的 SQL 为结构来源，避免 Java DDL 与手工脚本分叉。
 * DDL 使用 JDBC 整段提交以保留 PostgreSQL dollar-quoted 语句；业务读写继续使用 MyBatis。
 * 迁移在事务和数据库锁内执行，旧数据保留。禁用自动迁移时可手工执行同一脚本。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private final AppProperties properties;
    private final EmbeddingDimensionProvider dimensions;
    private final DataSource dataSource;

    public SchemaInitializer(AppProperties properties, EmbeddingDimensionProvider dimensions, DataSource dataSource) {
        this.properties = properties; this.dimensions = dimensions; this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.getDatabase().isInitSchema() && !properties.getDatabase().isMigrateSchema()) return;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended(current_schema() || ':rag-schema',0))");
                boolean exists;
                try (var result = statement.executeQuery("SELECT to_regclass(format('%I.rag_file',current_schema())) IS NOT NULL")) {
                    result.next(); exists = result.getBoolean(1);
                }
                if (!exists) {
                    if (!properties.getDatabase().isInitSchema())
                        throw new IllegalStateException("知识库表不存在，请先执行 db/schema.sql 或开启 app.database.init-schema");
                    int dimension=dimensions.getDimension();
                    if (dimension<1 || dimension>16000) throw new IllegalStateException("初始模型维度必须在 1～16000 之间");
                    String schema = read("db/schema.sql").replace("CREATE SCHEMA IF NOT EXISTS rag;", "")
                            .replace("SET search_path TO rag, public;", "")
                            .replace("DROP INDEX IF EXISTS uk_rag_file_filename;", "")
                            .replace("vector(1024)", "vector(" + dimension + ")");
                    if (dimension>2000) schema=schema.replace("CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw ON rag_chunk USING hnsw (embedding vector_cosine_ops);", "");
                    statement.execute(schema);
                }
                statement.execute("CREATE TABLE IF NOT EXISTS rag_schema_history (version VARCHAR(80) PRIMARY KEY, installed_at TIMESTAMP NOT NULL DEFAULT NOW())");
                boolean applied;
                try (var result = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM rag_schema_history WHERE version='professional-1')")) {
                    result.next(); applied = result.getBoolean(1);
                }
                if (!applied) {
                    statement.execute(read("db/professional-migration.sql"));
                    statement.execute("INSERT INTO rag_schema_history(version) VALUES ('professional-1')");
                }
                boolean modelMigration;
                try (var result=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM rag_schema_history WHERE version='embedding-model-1')")) {
                    result.next(); modelMigration=result.getBoolean(1);
                }
                if (!modelMigration) {
                    try (var result = statement.executeQuery("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid=to_regclass(format('%I.rag_chunk',current_schema())) AND attname='embedding'")) {
                        if (!result.next() || !("vector(" + dimensions.getDimension() + ")").equals(result.getString(1)))
                            throw new IllegalStateException("数据库向量维度与模型配置不一致；首次升级请保持原模型配置，升级后通过管理端切换");
                    }
                    String identity=properties.getOllama().getModelName()+"|"+dimensions.getDimension();
                    try (var insert=connection.prepareStatement("INSERT INTO rag_runtime_setting(key,value) VALUES('embedding.identity',?) ON CONFLICT DO NOTHING")) {
                        insert.setString(1,identity); insert.executeUpdate();
                    }
                    try (var result=statement.executeQuery("SELECT value,EXISTS(SELECT 1 FROM rag_chunk) AS populated FROM rag_runtime_setting WHERE key='embedding.identity'")) {
                        if (result.next() && !identity.equals(result.getString(1)) && result.getBoolean(2))
                            throw new IllegalStateException("模型与已发布索引不一致；首次升级请保持原模型配置，升级后通过管理端切换");
                    }
                    try (var update=connection.prepareStatement("UPDATE rag_runtime_setting SET value=? WHERE key='embedding.identity'")) {
                        update.setString(1,identity); update.executeUpdate();
                    }
                    statement.execute(read("db/embedding-model-migration.sql"));
                    statement.execute("INSERT INTO rag_schema_history(version) VALUES('embedding-model-1')");
                }
                try (var result=statement.executeQuery("SELECT m.model_name,m.dimension FROM rag_embedding_state s JOIN rag_embedding_model m ON m.id=s.active_model_id WHERE s.id=1")) {
                    if (!result.next()) throw new IllegalStateException("活动向量模型缺失，拒绝启动");
                    log.info("使用知识库持久化模型: {} ({} 维)",result.getString(1),result.getInt(2));
                }
                connection.commit();
                log.info("知识库结构已校验，迁移版本 embedding-model-1");
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
    }

    private String read(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
