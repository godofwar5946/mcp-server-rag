package org.example.rag.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.model.RagSearchResult;
import org.example.rag.service.RagSearchService;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

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
    private final ObjectMapper objectMapper;

    public RagMcpHandlers(RagSearchService ragSearchService, ObjectMapper objectMapper) {
        this.ragSearchService = ragSearchService;
        this.objectMapper = objectMapper;
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
        String normalizedQuery = normalizeQuery(query);
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
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "[]";
        }
        List<RagSearchResult> results = ragSearchService.search(normalizedQuery, null);
        return toJson(results);
    }

    /**
     * rag.ask 工具：输入 question + topK，返回可直接用于回答的上下文块。
     */
    @McpTool(
            name = "rag.ask",
            title = "rag.ask",
            description = "基于向量检索返回可直接用于回答的上下文块"
    )
    public String ragAsk(
            @McpToolParam(required = true, description = "用户问题") String question,
            @McpToolParam(required = false, description = "返回切片数量") Integer topK
    ) {
        String normalizedQuestion = normalizeQuery(question);
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return "";
        }
        List<RagSearchResult> results = ragSearchService.search(normalizedQuestion, topK);
        return ragSearchService.buildContext(results);
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

    private String normalizeQuery(String value) {
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
            return "[]";
        }
    }
}
