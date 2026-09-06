package org.example.rag.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.rag.controller.dto.CreateFolderRequest;
import org.example.rag.controller.dto.RenameFolderRequest;
import org.example.rag.controller.dto.UpdateFolderKnowledgeTypeRequest;
import org.example.rag.model.RagFolderEntity;
import org.example.rag.model.RagKnowledgeType;
import org.example.rag.service.RagFolderExportService;
import org.example.rag.service.RagFolderService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 管理端目录 API。
 */
@RestController
@RequestMapping("/api/folders")
public class AdminFolderController {

    private final RagFolderService ragFolderService;
    private final RagFolderExportService ragFolderExportService;

    public AdminFolderController(RagFolderService ragFolderService,
                                 RagFolderExportService ragFolderExportService) {
        this.ragFolderService = ragFolderService;
        this.ragFolderExportService = ragFolderExportService;
    }

    @GetMapping("/tree")
    public Map<String, Object> tree() {
        return Map.of("success", true, "data", ragFolderService.getTree());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateFolderRequest request) {
        RagFolderEntity folder = ragFolderService.createFolder(request.parentId(), request.name());
        return Map.of("success", true, "data", folder);
    }

    @PutMapping("/{id}")
    public Map<String, Object> rename(@PathVariable("id") long id,
                                      @RequestBody RenameFolderRequest request) {
        RagFolderEntity folder = ragFolderService.renameFolder(id, request.name());
        return Map.of("success", true, "data", folder);
    }

    @PutMapping("/{id}/knowledge-type")
    public Map<String, Object> updateKnowledgeType(
            @PathVariable("id") long id,
            @RequestBody UpdateFolderKnowledgeTypeRequest request) {
        RagKnowledgeType knowledgeType = RagKnowledgeType.from(request.knowledgeType());
        boolean recursive = request.includeDescendants();
        int updatedCount = ragFolderService.updateKnowledgeType(id, knowledgeType.name(), recursive);
        return Map.of(
                "success", true,
                "updatedCount", updatedCount,
                "knowledgeType", knowledgeType.name(),
                "recursive", recursive
        );
    }

    /**
     * 将指定目录、所有下级目录及其中的原始文件流式导出为 ZIP。
     */
    @GetMapping("/{id}/export")
    public void export(@PathVariable("id") long id, HttpServletResponse response) throws IOException {
        RagFolderExportService.ExportPlan plan = ragFolderExportService.prepareExport(id);
        String disposition = ContentDisposition.attachment()
                .filename(plan.archiveFilename(), StandardCharsets.UTF_8)
                .build()
                .toString();
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        ragFolderExportService.writeArchive(plan, response.getOutputStream());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id,
                                      @RequestParam(defaultValue = "false") boolean recursive) {
        ragFolderService.deleteFolder(id, recursive);
        return Map.of("success", true, "message", "目录已删除");
    }
}
