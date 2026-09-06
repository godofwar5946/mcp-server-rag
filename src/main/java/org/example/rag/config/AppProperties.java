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
    private final Parsing parsing = new Parsing();
    private final Indexing indexing = new Indexing();
    public Parsing getParsing() { return parsing; }
    public Indexing getIndexing() { return indexing; }

    /** 有界解析，超限必须明确失败，不能悄悄发布截断后的知识。 */
    public static class Parsing {
        private int maxTextChars = 2_000_000;
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int value) {
            if (value < 1000 || value > 20_000_000) throw new IllegalArgumentException("解析上限应在 1000～20000000 字符之间");
            maxTextChars = value;
        }
    }

    /** 持久化任务的容量边界；检索使用独立的模型调用额度。 */
    public static class Indexing {
        private int retentionDays=30;
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int value) { retentionDays=Math.max(0,Math.min(3650,value)); }
        private boolean enabled = true;
        private int workers = 1;
        private int maxQueuedJobs = 2000;
        private int leaseSeconds = 180;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public int getWorkers() { return workers; }
        public void setWorkers(int value) { workers = Math.max(1, Math.min(value, 8)); }
        public int getMaxQueuedJobs() { return maxQueuedJobs; }
        public void setMaxQueuedJobs(int value) { maxQueuedJobs = Math.max(1, value); }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int value) { leaseSeconds = Math.max(90, value); }
    }

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
     * 安全相关配置：Session 登录 + IP 白名单。
     */
    public static class Security {
        private List<String> trustedProxies = new ArrayList<>();
        public List<String> getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(List<String> value) { trustedProxies = value; }
        private String username = "admin";
        private String password = "";
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
        private boolean hybrid = true;
        private int candidateCount = 40;
        private double minSimilarity = 0;
        private int maxCodeChars = 24000;
        public boolean isHybrid() { return hybrid; }
        public void setHybrid(boolean value) { hybrid = value; }
        public int getCandidateCount() { return candidateCount; }
        public void setCandidateCount(int value) { candidateCount = Math.max(10,Math.min(value,200)); }
        public double getMinSimilarity() { return minSimilarity; }
        public void setMinSimilarity(double value) {
            if (!Double.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("相关性阈值应在 0～1 之间");
            minSimilarity = value;
        }
        public int getMaxCodeChars() { return maxCodeChars; }
        public void setMaxCodeChars(int value) { maxCodeChars = Math.max(1000, Math.min(value,100000)); }
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
        private int batchSize = 16;
        private int cacheSize = 512;
        private int maxConcurrentRequests = 2;
        private boolean batchEnabled = true;
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = Math.max(1, Math.min(value, 128)); }
        public int getCacheSize() { return cacheSize; }
        public void setCacheSize(int value) { cacheSize = Math.max(0, Math.min(value, 10000)); }
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = Math.max(1, Math.min(value, 16)); }
        public boolean isBatchEnabled() { return batchEnabled; }
        public void setBatchEnabled(boolean value) { batchEnabled = value; }
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
        private boolean migrateSchema = true;
        public boolean isMigrateSchema() { return migrateSchema; }
        public void setMigrateSchema(boolean value) { migrateSchema = value; }
        private boolean initSchema = true;

        public boolean isInitSchema() {
            return initSchema;
        }

        public void setInitSchema(boolean initSchema) {
            this.initSchema = initSchema;
        }
    }
}
