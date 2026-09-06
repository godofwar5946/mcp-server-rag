package org.example.rag.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.model.RagSearchResult;
import org.example.rag.service.CodeNavigationService;
import org.example.rag.service.RagSearchService;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.service.RagFolderService;
import org.example.rag.model.RetrievalReport;
import org.example.rag.model.FolderTreeNode;
import org.example.rag.model.RagChunkEntity;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 使用 Spring AI MCP 注解实现的 RAG MCP 能力。
 * <p>
 * 说明：
 * - Resource 采用 URI 模板传参（MCP ReadResourceRequest 仅包含 uri 与 meta）。
 * - Tool 使用参数名映射 arguments，因此必须开启 -parameters 编译选项。
 */
@Component
public class RagMcpHandlers {

    private final RagSearchService ragSearchService;
    private final CodeNavigationService codeNavigationService;
    private final ObjectMapper objectMapper;
    private final RagFolderService folders;
    private final RagChunkMapper chunks;

    public RagMcpHandlers(RagSearchService ragSearchService,
                          CodeNavigationService codeNavigationService,
                          ObjectMapper objectMapper, RagFolderService folders, RagChunkMapper chunks) {
        this.ragSearchService = ragSearchService;
        this.codeNavigationService = codeNavigationService;
        this.objectMapper = objectMapper;
        this.folders = folders;
        this.chunks = chunks;
    }

    /**
     * rag.search 资源：支持 query + topK。
     * 访问示例：rag.search?query=向量检索&topK=5
     */
    @McpResource(
            name = "rag.search",
            title = "rag.search",
            uri = "rag.search?query={query}&topK={topK}",
            description = "向量检索资源，输入 query + topK，返回匹配切片列表（JSON）",
            mimeType = "application/json"
    )
    public String ragSearchWithTopK(String query, String topK) {
        String normalizedQuery = normalizeResourceQuery(query);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "[]";
        }
        Integer limit = parseTopK(topK);
        List<RagSearchResult> results = ragSearchService.search(normalizedQuery, limit);
        return toJson(results);
    }

    /**
     * rag.search 资源：仅传 query，topK 使用服务默认值。
     * 访问示例：rag.search?query=向量检索
     */
    @McpResource(
            name = "rag.search",
            title = "rag.search",
            uri = "rag.search?query={query}",
            description = "向量检索资源（query 必填，topK 省略时使用默认值）",
            mimeType = "application/json"
    )
    public String ragSearch(String query) {
        String normalizedQuery = normalizeResourceQuery(query);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "[]";
        }
        List<RagSearchResult> results = ragSearchService.search(normalizedQuery, null);
        return toJson(results);
    }

    /**
     * rag.ask 工具：输入 question + topK，返回可直接用于回答的上下文块。
     */
    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "rag.ask",
            title = "rag.ask",
            description = "基于向量检索返回可直接用于回答的上下文块"
    )
    public String ragAsk(
            @McpToolParam(required = true, description = "用户问题") String question,
            @McpToolParam(required = false, description = "返回切片数量") Integer topK,
            @McpToolParam(required = false, description = "目录 ID，包含下级目录；0 表示根目录直属文件") Long folderId
    ) {
        String normalizedQuestion = normalizeQuery(question);
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return "";
        }
        List<RagSearchResult> results = ragSearchService.search(normalizedQuestion, topK, folderId);
        return ragSearchService.buildContext(results);
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.find",
            title = "code.find",
            description = "按 Java 类名、方法名、签名或 MyBatis SQL ID 搜索代码符号"
    )
    public String codeFind(
            @McpToolParam(required = true, description = "类名、方法名、签名或 SQL ID") String query,
            @McpToolParam(required = false, description = "CLASS、METHOD、SQL 或留空查询全部") String category,
            @McpToolParam(required = false, description = "可选的项目根目录 ID，会包含所有下级目录") Long folderId,
            @McpToolParam(required = false, description = "最多返回数量，默认 20，最大 100") Integer limit
    ) {
        return toJson(codeNavigationService.search(query, category, folderId, limit));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.get_class",
            title = "code.get_class",
            description = "根据简单类名或完整类名返回完整 Java 类源码和原始行号"
    )
    public String codeGetClass(
            @McpToolParam(required = true, description = "简单类名或包名加类名") String className,
            @McpToolParam(required = false, description = "可选的项目根目录 ID") Long folderId
    ) {
        return toJson(codeNavigationService.getClasses(className, folderId));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.get_method",
            title = "code.get_method",
            description = "根据类名、方法名和可选签名返回完整 Java 方法源码；重载方法会返回多个候选"
    )
    public String codeGetMethod(
            @McpToolParam(required = false, description = "简单类名或完整类名，可留空") String className,
            @McpToolParam(required = true, description = "方法名") String methodName,
            @McpToolParam(required = false, description = "可选签名，例如 findById(Long)") String signature,
            @McpToolParam(required = false, description = "可选的项目根目录 ID") Long folderId
    ) {
        return toJson(codeNavigationService.getMethods(className, methodName, signature, folderId));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.get_sql",
            title = "code.get_sql",
            description = "根据 MyBatis Mapper namespace 和 SQL ID 返回完整 XML SQL"
    )
    public String codeGetSql(
            @McpToolParam(required = false, description = "Mapper namespace 或 Mapper 名，可留空") String namespace,
            @McpToolParam(required = true, description = "select、insert、update、delete 或 sql 节点的 id") String statementId,
            @McpToolParam(required = false, description = "可选的项目根目录 ID") Long folderId
    ) {
        return toJson(codeNavigationService.getSqlStatements(namespace, statementId, folderId));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.get_by_line",
            title = "code.get_by_line",
            description = "根据源码相对路径和行号返回该行所在的最内层 Java 方法、类或 XML SQL"
    )
    public String codeGetByLine(
            @McpToolParam(required = true, description = "源码相对路径，例如 src/main/java/demo/UserService.java") String filePath,
            @McpToolParam(required = true, description = "从 1 开始的原始代码行号") Integer line,
            @McpToolParam(required = false, description = "可选的项目根目录 ID") Long folderId
    ) {
        if (line == null) {
            throw new IllegalArgumentException("代码行号不能为空");
        }
        return toJson(codeNavigationService.getByPathAndLine(filePath, line, folderId));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "business.search",
            title = "business.search",
            description = "按语义检索 BUSINESS 与默认 ALL 类型的项目业务资料，可限制在指定目录及其下级目录"
    )
    public String businessSearch(
            @McpToolParam(required = true, description = "要查找的业务概念、规则或流程问题") String query,
            @McpToolParam(required = false, description = "返回切片数量") Integer topK,
            @McpToolParam(required = false, description = "可选的项目根目录 ID，会包含所有下级目录") Long folderId
    ) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "[]";
        }
        return toJson(ragSearchService.searchBusiness(normalizedQuery, topK, folderId));
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),
            name = "code.search",
            title = "code.search",
            description = "按语义检索 CODE 与默认 ALL 类型中的 Java 方法和 MyBatis XML SQL，可限制在指定目录及其下级目录"
    )
    public String codeSearch(
            @McpToolParam(required = true, description = "要查找的代码逻辑或业务问题") String query,
            @McpToolParam(required = false, description = "返回切片数量") Integer topK,
            @McpToolParam(required = false, description = "可选的项目根目录 ID，会包含所有下级目录") Long folderId
    ) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "[]";
        }
        return toJson(ragSearchService.searchCode(normalizedQuery, topK, folderId));
    }

    private Integer parseTopK(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),name="rag.retrieve",title="检索知识与来源",generateOutputSchema=true,
            description="返回结构化检索结果、来源 URI、版本、召回通道、耗时与有界上下文。支持 hybrid/vector/keyword。相似度不是答案可信度。")
    public RetrievalReport retrieve(
            @McpToolParam(required=true,description="用户问题，最多 2000 字符") String query,
            @McpToolParam(required=false,description="返回数量，最多 20") Integer topK,
            @McpToolParam(required=false,description="项目目录 ID，包含下级目录") Long folderId,
            @McpToolParam(required=false,description="ALL、BUSINESS 或 CODE") String knowledgeType,
            @McpToolParam(required=false,description="hybrid、vector 或 keyword") String mode,
            @McpToolParam(required=false,description="最低向量相似度，0～1；关键词模式不应用此阈值") Double minSimilarity) {
        return ragSearchService.retrieve(query,topK,folderId,knowledgeType,mode,minSimilarity);
    }

    @McpTool(annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false),name="kb.list",title="查看知识库目录",generateOutputSchema=true,
            description="发现知识库目录、目录 ID 和文件数量，供后续限定检索范围")
    public KnowledgeBases listKnowledgeBases() { return new KnowledgeBases(folders.getTree()); }

    @McpResource(name="rag.chunk",title="读取检索证据",mimeType="application/json",
            uri="rag://files/{fileId}/revisions/{revision}/chunks/{chunkIndex}",
            description="读取检索结果指向的原始切片；版本发生变化时要求重新检索")
    public String readChunk(String fileId,String revision,String chunkIndex) {
        RagChunkEntity chunk=chunks.readRevisionChunk(Long.parseLong(fileId),Integer.parseInt(chunkIndex),Long.parseLong(revision));
        if (chunk==null) throw new IllegalStateException("来源版本已更新或切片不存在，请重新检索");
        return toJson(new ChunkSource(chunk.getFileId(),chunk.getChunkIndex(),chunk.getStartLine(),chunk.getEndLine(),chunk.getContent(),chunk.getMetadataJson(),chunk.isTruncated()));
    }

    @McpResource(name="rag.search.scoped",title="按目录检索",mimeType="application/json",
            uri="rag.search?query={query}&topK={topK}&folderId={folderId}",description="限制项目目录范围的知识检索")
    public String scopedSearch(String query,String topK,String folderId) {
        return toJson(ragSearchService.search(normalizeResourceQuery(query),parseTopK(topK),Long.parseLong(folderId)));
    }

    public record KnowledgeBases(List<FolderTreeNode> folders) { }
    public record ChunkSource(Long fileId,int chunkIndex,Integer startLine,Integer endLine,String content,String metadataJson,boolean truncated) { }

    private String normalizeQuery(String value) {
        // tools/call 已经通过 JSON 解码；再次 URL 解码会破坏 C++、百分号等用户输入。
        return value == null ? null : value.trim();
    }

    private String normalizeResourceQuery(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.contains("%") || trimmed.contains("+")) {
            return URLDecoder.decode(trimmed, StandardCharsets.UTF_8);
        }
        return trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("检索结果序列化失败", e);
        }
    }
}
