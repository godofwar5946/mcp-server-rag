package org.example.rag.controller;

import org.example.rag.controller.dto.MoveFileRequest;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.FileChunkStat;
import org.example.rag.model.FileListItem;
import org.example.rag.model.IndexResult;
import org.example.rag.model.RagFileEntity;
import org.example.rag.model.RagFileRecord;
import org.example.rag.service.RagIndexService;
import org.example.rag.service.RagFolderService;
import org.example.rag.service.RagUploadService;
import org.example.rag.util.TextUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理 API：文件上传、列表、预览、删除、重建索引。
 */
@RestController
@RequestMapping("/api")
public class AdminFileController {

    private final RagIndexService ragIndexService;
    private final RagUploadService ragUploadService;
    private final RagFileMapper ragFileMapper;
    private final RagFolderService ragFolderService;

    public AdminFileController(RagIndexService ragIndexService,
                               RagUploadService ragUploadService,
                               RagFileMapper ragFileMapper,
                               RagFolderService ragFolderService) {
        this.ragIndexService = ragIndexService;
        this.ragUploadService = ragUploadService;
        this.ragFileMapper = ragFileMapper;
        this.ragFolderService = ragFolderService;
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestPart("files") List<MultipartFile> files,
                                      @RequestParam(required = false) Long folderId,
                                      @RequestParam(required = false) List<String> relativePaths) {
        List<IndexResult> results = ragUploadService.upload(files, folderId, relativePaths);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", results);
        return body;
    }

    @GetMapping("/files")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) Long folderId,
                                    @RequestParam(required = false) String query,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String knowledgeType,
                                    @RequestParam(defaultValue = "updated") String sort) {
        int safePage = Math.min(Math.max(page, 1), 100000);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        if (folderId != null && folderId > 0) {
            ragFolderService.requireFolder(folderId);
        }
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim()
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String type = knowledgeType == null || knowledgeType.isBlank() ? null
                : org.example.rag.model.RagKnowledgeType.from(knowledgeType).name();
        if (status != null && !java.util.Set.of("PENDING","PARSED","INDEXED","FAILED").contains(status))
            throw new IllegalArgumentException("文件状态不正确");
        List<FileListItem> items = ragFileMapper.searchFiles(folderId, pattern, status, type, sort, offset, safeSize);
        long total = ragFileMapper.countFiltered(folderId, pattern, status, type);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", items);
        body.put("total", total);
        body.put("page", safePage);
        body.put("size", safeSize);
        return body;
    }

    @GetMapping("/files/{id}/preview")
    public Map<String, Object> preview(@PathVariable("id") long id,
                                       @RequestParam(defaultValue = "8000") int maxChars,
                                       @RequestParam(defaultValue = "0") int offset) {
        int safeOffset = Math.max(0, Math.min(offset, 20_000_000));
        int length = Math.max(100, Math.min(maxChars, 20000));
        RagFileEntity entity = ragFileMapper.previewWindow(id, safeOffset + 1, length);
        if (entity == null) throw new IllegalArgumentException("文件不存在");
        return Map.of("success", true, "data", entity.getParsedText() == null ? "" : entity.getParsedText(),
                "fileName", entity.getFilename(), "offset", safeOffset, "totalChars", entity.getSize(),
                "revision", entity.getRevision(), "hasMore", safeOffset + length < entity.getSize());
    }

    @GetMapping("/files/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        RagFileEntity entity = ragFileMapper.selectOriginal(id);
        Optional<RagFileRecord> recordOpt = Optional.ofNullable(entity == null ? null : toRecord(entity));
        if (recordOpt.isEmpty() || recordOpt.get().contentBytes() == null) {
            return ResponseEntity.notFound().build();
        }
        RagFileRecord record = recordOpt.get();
        String filename = record.filename() == null ? "file" : record.filename();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(record.contentBytes());
    }

    @DeleteMapping("/files/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        ragIndexService.deleteFile(id);
        return Map.of("success", true, "message", "已删除");
    }

    @PutMapping("/files/{id}/folder")
    public Map<String, Object> move(@PathVariable("id") long id,
                                    @RequestBody MoveFileRequest request) {
        ragFolderService.moveFile(id, request.folderId());
        return Map.of("success", true, "message", "文件已移动");
    }

    @PostMapping("/files/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable("id") long id) {
        IndexResult result = ragIndexService.reindexFile(id);
        return Map.of("success", result.success(), "data", result);
    }

    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuild() {
        List<IndexResult> results = ragIndexService.rebuildAll();
        long failed = results.stream().filter(result -> !result.success()).count();
        return Map.of("success", true, "data", results, "failed", failed,
                "succeeded", results.size() - failed, "message", "处理完成，失败 " + failed + " 个文件");
    }

    @PostMapping("/files/batch")
    public Map<String,Object> batch(@RequestBody BatchFilesRequest request) {
        if (request.fileIds() == null || request.fileIds().isEmpty() || request.fileIds().size() > 100)
            throw new IllegalArgumentException("每次请选择 1～100 个文件");
        if (request.action()==null || !java.util.Set.of("delete", "move", "classify").contains(request.action()))
            throw new IllegalArgumentException("批量操作不正确");
        String type = "classify".equals(request.action())
                ? org.example.rag.model.RagKnowledgeType.from(request.knowledgeType()).name() : null;
        List<Map<String,Object>> results = new java.util.ArrayList<>();
        for (Long id : request.fileIds().stream().distinct().toList()) {
            try {
                if (id == null || id <= 0) throw new IllegalArgumentException("文件 ID 不正确");
                switch (request.action()) {
                    case "delete" -> ragIndexService.deleteFile(id);
                    case "move" -> ragFolderService.moveFile(id, request.folderId());
                    case "classify" -> {
                        if (ragFileMapper.setKnowledgeType(id, type) != 1) throw new IllegalArgumentException("文件不存在");
                    }
                    default -> throw new IllegalArgumentException("操作不正确");
                }
                results.add(Map.of("fileId", id, "success", true));
            } catch (Exception ex) {
                results.add(Map.of("fileId", id == null ? -1 : id, "success", false, "message",
                        java.util.Objects.toString(ex.getMessage(), "操作失败")));
            }
        }
        return Map.of("success", true, "data", results);
    }

    public record BatchFilesRequest(List<Long> fileIds, String action, Long folderId, String knowledgeType) { }

    @GetMapping("/files/{id}/chunks")
    public Map<String, Object> chunkStat(@PathVariable("id") long id) {
        FileChunkStat stat = ragFileMapper.getChunkStat(id);
        if (stat==null) throw new IllegalArgumentException("文件不存在");
        return Map.of("success", true, "data", stat);
    }

    private RagFileRecord toRecord(RagFileEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RagFileRecord(
                entity.getId(),
                entity.getFilename(),
                entity.getFolderId(),
                entity.getContentType(),
                entity.getKnowledgeType(),
                entity.getSize(),
                entity.getContentBytes(),
                entity.getParsedText(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getIndexedAt()
        );
    }
}
