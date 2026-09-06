package org.example.rag.controller;

import org.example.rag.model.CodeSourceResult;
import org.example.rag.model.CodeSymbolView;
import org.example.rag.service.CodeNavigationService;
import org.example.rag.service.RagSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Java 类、方法、行号以及 MyBatis XML SQL 的管理查询接口。
 */
@RestController
@RequestMapping("/api/code")
public class AdminCodeController {

    private final CodeNavigationService codeNavigationService;
    private final RagSearchService ragSearchService;

    public AdminCodeController(CodeNavigationService codeNavigationService,
                               RagSearchService ragSearchService) {
        this.codeNavigationService = codeNavigationService;
        this.ragSearchService = ragSearchService;
    }

    @GetMapping("/symbols")
    public Map<String, Object> searchSymbols(@RequestParam String query,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) Long folderId,
                                             @RequestParam(required = false) Integer limit) {
        List<CodeSymbolView> results = codeNavigationService.search(query, category, folderId, limit);
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/symbols/{id}/source")
    public Map<String, Object> symbolSource(@PathVariable long id) {
        return Map.of("success", true, "data", codeNavigationService.getSymbol(id));
    }

    @GetMapping("/classes/source")
    public Map<String, Object> classSource(@RequestParam String className,
                                           @RequestParam(required = false) Long folderId) {
        List<CodeSourceResult> results = codeNavigationService.getClasses(className, folderId);
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/methods/source")
    public Map<String, Object> methodSource(@RequestParam(required = false) String className,
                                            @RequestParam String methodName,
                                            @RequestParam(required = false) String signature,
                                            @RequestParam(required = false) Long folderId) {
        List<CodeSourceResult> results = codeNavigationService.getMethods(
                className,
                methodName,
                signature,
                folderId
        );
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/sql/source")
    public Map<String, Object> sqlSource(@RequestParam(required = false) String namespace,
                                         @RequestParam String statementId,
                                         @RequestParam(required = false) Long folderId) {
        List<CodeSourceResult> results = codeNavigationService.getSqlStatements(namespace, statementId, folderId);
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/location")
    public Map<String, Object> sourceAtLine(@RequestParam String filePath,
                                            @RequestParam int line,
                                            @RequestParam(required = false) Long folderId) {
        List<CodeSourceResult> results = codeNavigationService.getByPathAndLine(filePath, line, folderId);
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/files/{fileId}/outline")
    public Map<String, Object> outline(@PathVariable long fileId) {
        return Map.of("success", true, "data", codeNavigationService.outline(fileId));
    }

    @GetMapping("/search")
    public Map<String, Object> semanticSearch(@RequestParam String query,
                                              @RequestParam(required = false) Integer topK,
                                              @RequestParam(required = false) Long folderId) {
        return Map.of("success", true, "data", ragSearchService.searchCode(query, topK, folderId));
    }
}
