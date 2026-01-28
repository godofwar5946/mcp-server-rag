package org.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用自定义配置。
 * <p>
 * 统一放在 app.* 下，便于前端、MCP 与后端逻辑共享配置。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Security security = new Security();
    private final Ollama ollama = new Ollama();
    private final Chunking chunking = new Chunking();
    private final Rag rag = new Rag();
    private final Mcp mcp = new Mcp();
    private final Embedding embedding = new Embedding();
    private final Database database = new Database();

    public Security getSecurity() {
        return security;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public Chunking getChunking() {
        return chunking;
    }

    public Rag getRag() {
        return rag;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Database getDatabase() {
        return database;
    }

    /**
     * 安全相关配置：Basic Auth + IP 白名单。
     */
    public static class Security {
        private String username = "admin";
        private String password = "admin123";
        private List<String> ipWhitelist = new ArrayList<>(List.of("127.0.0.1", "::1"));

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getIpWhitelist() {
            return ipWhitelist;
        }

        public void setIpWhitelist(List<String> ipWhitelist) {
            this.ipWhitelist = ipWhitelist;
        }
    }

    /**
     * Ollama Embedding 调用配置。
     */
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "nomic-embed-text";
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 60;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
    }

    /**
     * 文本切片配置。
     */
    public static class Chunking {
        private int chunkSize = 1000;
        private int overlap = 150;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = overlap;
        }
    }

    /**
     * RAG 查询相关配置。
     */
    public static class Rag {
        private int defaultTopK = 5;
        private int maxTopK = 20;
        private int maxChunkChars = 1200;
        private int maxContextChars = 4000;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public int getMaxTopK() {
            return maxTopK;
        }

        public void setMaxTopK(int maxTopK) {
            this.maxTopK = maxTopK;
        }

        public int getMaxChunkChars() {
            return maxChunkChars;
        }

        public void setMaxChunkChars(int maxChunkChars) {
            this.maxChunkChars = maxChunkChars;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }
    }

    /**
     * MCP Server 配置。
     */
    public static class Mcp {
        private String endpoint = "/mcp";
        private String name = "rag-mcp-server";
        private String version = "1.0.0";
        private String instructions = "RAG MCP Server";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getInstructions() {
            return instructions;
        }

        public void setInstructions(String instructions) {
            this.instructions = instructions;
        }
    }

    /**
     * Embedding 向量维度配置。
     */
    public static class Embedding {
        private Integer dimension;
        private boolean autoDetect = true;
        private int maxInputChars = 2000;

        public Integer getDimension() {
            return dimension;
        }

        public void setDimension(Integer dimension) {
            this.dimension = dimension;
        }

        public boolean isAutoDetect() {
            return autoDetect;
        }

        public void setAutoDetect(boolean autoDetect) {
            this.autoDetect = autoDetect;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }
    }

    /**
     * 数据库初始化配置。
     */
    public static class Database {
        private boolean initSchema = true;

        public boolean isInitSchema() {
            return initSchema;
        }

        public void setInitSchema(boolean initSchema) {
            this.initSchema = initSchema;
        }
    }
}
