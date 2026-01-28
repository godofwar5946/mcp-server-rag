package org.example.rag.controller;

import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.FileChunkStat;
import org.example.rag.model.FileListItem;
import org.example.rag.model.IndexResult;
import org.example.rag.model.RagFileEntity;
import org.example.rag.model.RagFileRecord;
import org.example.rag.service.RagIndexService;
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
    private final RagFileMapper ragFileMapper;

    public AdminFileController(RagIndexService ragIndexService, RagFileMapper ragFileMapper) {
        this.ragIndexService = ragIndexService;
        this.ragFileMapper = ragFileMapper;
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestPart("files") List<MultipartFile> files) {
        List<IndexResult> results = ragIndexService.upload(files);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", results);
        return body;
    }

    @GetMapping("/files")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        List<FileListItem> items = ragFileMapper.listWithChunkCount(offset, safeSize);
        long total = ragFileMapper.selectCount(null);
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
                                       @RequestParam(defaultValue = "4000") int maxChars) {
        RagFileEntity entity = ragFileMapper.selectById(id);
        Optional<RagFileRecord> recordOpt = Optional.ofNullable(entity == null ? null : toRecord(entity));
        if (recordOpt.isEmpty()) {
            return Map.of("success", false, "message", "文件不存在");
        }
        String parsedText = recordOpt.get().parsedText();
        String preview = TextUtils.truncate(parsedText == null ? "" : parsedText, maxChars);
        return Map.of("success", true, "data", preview);
    }

    @GetMapping("/files/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        RagFileEntity entity = ragFileMapper.selectById(id);
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

    @PostMapping("/files/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable("id") long id) {
        IndexResult result = ragIndexService.reindexFile(id);
        return Map.of("success", result.success(), "data", result);
    }

    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuild() {
        List<IndexResult> results = ragIndexService.rebuildAll();
        return Map.of("success", true, "data", results);
    }

    @GetMapping("/files/{id}/chunks")
    public Map<String, Object> chunkStat(@PathVariable("id") long id) {
        FileChunkStat stat = ragFileMapper.getChunkStat(id);
        return Map.of("success", true, "data", stat);
    }

    private RagFileRecord toRecord(RagFileEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RagFileRecord(
                entity.getId(),
                entity.getFilename(),
                entity.getContentType(),
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
