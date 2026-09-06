package org.example.rag.service;

import org.example.rag.model.IndexResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量上传编排服务。每个文件分别进入 RagIndexService 的事务，单个失败不会回滚整批文件。
 */
@Service
public class RagUploadService {

    private static final Logger log = LoggerFactory.getLogger(RagUploadService.class);

    private final RagIndexService ragIndexService;
    private final RagFolderService ragFolderService;

    public RagUploadService(RagIndexService ragIndexService, RagFolderService ragFolderService) {
        this.ragIndexService = ragIndexService;
        this.ragFolderService = ragFolderService;
    }

    public List<IndexResult> upload(List<MultipartFile> files,
                                    Long baseFolderId,
                                    List<String> relativePaths) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        Long normalizedBaseFolderId = ragFolderService.normalizeFolderId(baseFolderId);
        if (normalizedBaseFolderId != null) {
            ragFolderService.requireFolder(normalizedBaseFolderId);
        }
        boolean preservePaths = relativePaths != null && !relativePaths.isEmpty();
        if (preservePaths && relativePaths.size() != files.size()) {
            throw new IllegalArgumentException("文件数量与相对路径数量不一致");
        }

        List<IndexResult> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relativePath = preservePaths ? relativePaths.get(i) : null;
            try {
                results.add(ragIndexService.indexMultipartFile(file, normalizedBaseFolderId, relativePath));
            } catch (Exception e) {
                String filename = displayName(file);
                if (e instanceof IllegalArgumentException) {
                    log.warn("拒绝上传文件 {}: {}", filename, e.getMessage());
                } else {
                    log.error("上传文件失败: {}", filename, e);
                }
                results.add(new IndexResult(null, filename, false, "上传失败: " + e.getMessage()));
            }
        }
        return results;
    }

    private String displayName(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            return "unknown";
        }
        return file.getOriginalFilename();
    }
}
