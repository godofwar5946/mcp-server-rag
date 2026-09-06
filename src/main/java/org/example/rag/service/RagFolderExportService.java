package org.example.rag.service;

import org.example.rag.mapper.RagFileMapper;
import org.example.rag.mapper.RagFolderMapper;
import org.example.rag.model.FolderExportFile;
import org.example.rag.model.RagFileEntity;
import org.example.rag.model.RagFolderEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将数据库中的目录树与原始文件内容导出为 ZIP。
 *
 * <p>准备阶段只读取目录和文件元数据，用于在响应开始前验证完整性；真正写出时再逐个读取
 * {@code content_bytes}，避免把整个目录树的原始文件和 ZIP 同时堆积在内存中。</p>
 */
@Service
public class RagFolderExportService {

    private final RagFolderMapper ragFolderMapper;
    private final RagFileMapper ragFileMapper;

    public RagFolderExportService(RagFolderMapper ragFolderMapper, RagFileMapper ragFileMapper) {
        this.ragFolderMapper = ragFolderMapper;
        this.ragFileMapper = ragFileMapper;
    }

    /**
     * 构建并校验导出清单。ZIP 中始终保留所选目录本身作为顶层目录。
     */
    @Transactional(readOnly = true)
    public ExportPlan prepareExport(long folderId) {
        RagFolderEntity root = ragFolderMapper.selectById(folderId);
        if (root == null) {
            throw new IllegalArgumentException("目录不存在: " + folderId);
        }

        List<RagFolderEntity> folders = ragFolderMapper.listSubtreeForExport(folderId);
        if (folders == null || folders.isEmpty()) {
            throw new IllegalStateException("目录树读取失败: " + folderId);
        }
        Map<Long, RagFolderEntity> foldersById = new LinkedHashMap<>();
        for (RagFolderEntity folder : folders) {
            if (folder.getId() != null) {
                foldersById.put(folder.getId(), folder);
            }
        }
        if (!foldersById.containsKey(folderId)) {
            throw new IllegalStateException("导出目录不在目录树中: " + folderId);
        }

        Map<Long, String> folderPaths = new HashMap<>();
        for (RagFolderEntity folder : folders) {
            resolveFolderPath(folder.getId(), folderId, foldersById, folderPaths, new HashSet<>());
        }

        List<FolderExportFile> files = ragFileMapper.listForFolderTreeExport(folderId);
        Map<Long, List<FolderExportFile>> filesByFolder = new LinkedHashMap<>();
        if (files != null) {
            for (FolderExportFile file : files) {
                if (file.folderId() == null || !folderPaths.containsKey(file.folderId())) {
                    throw new IllegalStateException("文件所在目录不属于当前导出范围: " + file.filename());
                }
                if (!file.contentAvailable()) {
                    throw new IllegalStateException("文件缺少原始内容，无法导出: "
                            + folderPaths.get(file.folderId()) + file.filename());
                }
                filesByFolder.computeIfAbsent(file.folderId(), ignored -> new ArrayList<>()).add(file);
            }
        }

        List<ExportEntry> entries = new ArrayList<>();
        Set<String> entryPaths = new HashSet<>();
        for (RagFolderEntity folder : folders) {
            String folderPath = folderPaths.get(folder.getId());
            addUniqueEntry(entries, entryPaths, new ExportEntry(null, folderPath, folder.getUpdatedAt()));
            for (FolderExportFile file : filesByFolder.getOrDefault(folder.getId(), List.of())) {
                String filename = requireSafeSegment(file.filename(), "文件名");
                addUniqueEntry(
                        entries,
                        entryPaths,
                        new ExportEntry(file.id(), folderPath + filename, file.updatedAt())
                );
            }
        }

        String rootName = requireSafeSegment(root.getName(), "目录名称");
        return new ExportPlan(rootName + ".zip", List.copyOf(entries));
    }

    /**
     * 按清单逐项写出 ZIP。此方法不会关闭调用方传入的响应输出流。
     */
    public void writeArchive(ExportPlan plan, OutputStream outputStream) throws IOException {
        if (plan == null || plan.entries() == null || plan.entries().isEmpty()) {
            throw new IllegalArgumentException("导出清单不能为空");
        }
        if (outputStream == null) {
            throw new IllegalArgumentException("导出输出流不能为空");
        }

        ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8);
        for (ExportEntry entry : plan.entries()) {
            ZipEntry zipEntry = new ZipEntry(entry.path());
            applyTimestamp(zipEntry, entry.updatedAt());
            zip.putNextEntry(zipEntry);
            if (!entry.directory()) {
                RagFileEntity file = ragFileMapper.selectContentForExport(entry.fileId());
                if (file == null || file.getContentBytes() == null) {
                    throw new IllegalStateException("文件原始内容已不存在，导出已中止: " + entry.path());
                }
                zip.write(file.getContentBytes());
            }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    private String resolveFolderPath(Long folderId,
                                     long rootId,
                                     Map<Long, RagFolderEntity> foldersById,
                                     Map<Long, String> resolvedPaths,
                                     Set<Long> visiting) {
        if (folderId == null) {
            throw new IllegalStateException("目录树中存在无效目录 ID");
        }
        String cached = resolvedPaths.get(folderId);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(folderId)) {
            throw new IllegalStateException("目录树存在循环关系，无法导出");
        }

        RagFolderEntity folder = foldersById.get(folderId);
        if (folder == null) {
            throw new IllegalStateException("目录树结构不完整: " + folderId);
        }
        String segment = requireSafeSegment(folder.getName(), "目录名称");
        String path;
        if (folderId == rootId) {
            path = segment + "/";
        } else {
            Long parentId = folder.getParentId();
            if (parentId == null || !foldersById.containsKey(parentId)) {
                throw new IllegalStateException("目录树结构不完整: " + folderId);
            }
            path = resolveFolderPath(parentId, rootId, foldersById, resolvedPaths, visiting)
                    + segment + "/";
        }
        visiting.remove(folderId);
        resolvedPaths.put(folderId, path);
        return path;
    }

    private String requireSafeSegment(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + "不能为空");
        }
        String normalized = value.trim();
        if (".".equals(normalized) || "..".equals(normalized)
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalStateException(label + "不适合写入 ZIP: " + value);
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalStateException(label + "包含非法控制字符");
            }
        }
        return normalized;
    }

    private void addUniqueEntry(List<ExportEntry> entries,
                                Set<String> entryPaths,
                                ExportEntry entry) {
        if (!entryPaths.add(entry.path())) {
            throw new IllegalStateException("ZIP 中出现重复路径: " + entry.path());
        }
        entries.add(entry);
    }

    private void applyTimestamp(ZipEntry zipEntry, LocalDateTime updatedAt) {
        if (updatedAt != null) {
            zipEntry.setTime(updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
    }

    /**
     * 已完成路径和原始内容可用性校验的 ZIP 导出清单。
     */
    public record ExportPlan(String archiveFilename, List<ExportEntry> entries) {
    }

    /**
     * fileId 为空表示目录项，非空表示需要从数据库读取内容的文件项。
     */
    public record ExportEntry(Long fileId, String path, LocalDateTime updatedAt) {
        public boolean directory() {
            return fileId == null;
        }
    }
}
