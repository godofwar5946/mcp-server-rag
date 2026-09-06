package org.example.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.mapper.RagFolderMapper;
import org.example.rag.model.FolderListRow;
import org.example.rag.model.FolderTreeNode;
import org.example.rag.model.RagFileEntity;
import org.example.rag.model.RagFolderEntity;
import org.example.rag.model.RagKnowledgeType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录维护服务：创建多级目录、构建目录树、重命名和安全删除。
 */
@Service
public class RagFolderService {

    private static final int MAX_DEPTH = 32;

    private final RagFolderMapper ragFolderMapper;
    private final RagFileMapper ragFileMapper;

    public RagFolderService(RagFolderMapper ragFolderMapper, RagFileMapper ragFileMapper) {
        this.ragFolderMapper = ragFolderMapper;
        this.ragFileMapper = ragFileMapper;
    }

    public List<FolderTreeNode> getTree() {
        List<FolderListRow> rows = ragFolderMapper.listWithFileCount();
        Map<Long, MutableFolderNode> nodes = new LinkedHashMap<>();
        for (FolderListRow row : rows) {
            nodes.put(row.id(), new MutableFolderNode(row));
        }

        List<MutableFolderNode> roots = new ArrayList<>();
        for (MutableFolderNode node : nodes.values()) {
            MutableFolderNode parent = node.parentId == null ? null : nodes.get(node.parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        Comparator<MutableFolderNode> comparator = Comparator.comparing(
                node -> node.name,
                String.CASE_INSENSITIVE_ORDER
        );
        sortNodes(roots, comparator);
        return roots.stream().map(MutableFolderNode::toTreeNode).toList();
    }

    @Transactional
    public RagFolderEntity createFolder(Long parentId, String name) {
        Long normalizedParentId = normalizeFolderId(parentId);
        if (normalizedParentId != null) {
            requireFolder(normalizedParentId);
        }
        String normalizedName = validateFolderName(name);
        RagFolderEntity existing = ragFolderMapper.findByParentAndName(normalizedParentId, normalizedName);
        if (existing != null) {
            throw new IllegalStateException("同级目录已存在: " + normalizedName);
        }
        return insertFolder(normalizedParentId, normalizedName);
    }

    /**
     * 按路径逐级查找或创建目录，供文件夹上传保留目录结构。
     */
    @Transactional
    public Long ensurePath(Long baseFolderId, List<String> segments) {
        Long parentId = normalizeFolderId(baseFolderId);
        if (parentId != null) {
            requireFolder(parentId);
        }
        if (segments == null || segments.isEmpty()) {
            return parentId;
        }
        if (segments.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("目录层级不能超过 " + MAX_DEPTH + " 层");
        }
        for (String segment : segments) {
            String name = validateFolderName(segment);
            RagFolderEntity folder = ragFolderMapper.findByParentAndName(parentId, name);
            if (folder == null) {
                // PostgreSQL 冲突不能在同一事务内捕获后继续查询，使用无异常的 upsert。
                ragFolderMapper.ensureFolder(parentId,name);
                folder=ragFolderMapper.findByParentAndName(parentId,name);
                if (folder==null) throw new IllegalStateException("目录创建失败");
            }
            parentId = folder.getId();
        }
        return parentId;
    }

    @Transactional
    public RagFolderEntity renameFolder(long folderId, String name) {
        RagFolderEntity folder = requireFolder(folderId);
        String normalizedName = validateFolderName(name);
        RagFolderEntity duplicate = ragFolderMapper.findByParentAndName(folder.getParentId(), normalizedName);
        if (duplicate != null && !duplicate.getId().equals(folder.getId())) {
            throw new IllegalStateException("同级目录已存在: " + normalizedName);
        }
        folder.setName(normalizedName);
        folder.setUpdatedAt(LocalDateTime.now());
        ragFolderMapper.updateById(folder);
        return folder;
    }

    @Transactional
    public void deleteFolder(long folderId, boolean recursive) {
        requireFolder(folderId);
        long childCount = ragFolderMapper.countChildren(folderId);
        long fileCount = ragFolderMapper.countFiles(folderId);
        if (!recursive && (childCount > 0 || fileCount > 0)) {
            throw new IllegalStateException("目录非空，请先清理内容或选择递归删除");
        }
        if (recursive) {
            // 先通过 Mapper 删除目录树中的文件，rag_chunk 再由外键级联删除。
            ragFileMapper.deleteByFolderTree(folderId);
        }
        ragFolderMapper.deleteById(folderId);
    }

    @Transactional
    public void moveFile(long fileId, Long targetFolderId) {
        RagFileEntity file = ragFileMapper.selectMetadata(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在");
        }
        Long normalizedFolderId = normalizeFolderId(targetFolderId);
        if (normalizedFolderId != null) {
            requireFolder(normalizedFolderId);
        }
        LambdaQueryWrapper<RagFileEntity> query = new LambdaQueryWrapper<RagFileEntity>()
                .eq(RagFileEntity::getFilename, file.getFilename())
                .ne(RagFileEntity::getId, fileId);
        if (normalizedFolderId == null) {
            query.isNull(RagFileEntity::getFolderId);
        } else {
            query.eq(RagFileEntity::getFolderId, normalizedFolderId);
        }
        if (ragFileMapper.selectCount(query) > 0) {
            throw new IllegalStateException("目标目录中已存在同名文件: " + file.getFilename());
        }
        ragFileMapper.moveToFolder(fileId, normalizedFolderId);
    }

    /**
     * 修改当前目录中的文件分类；recursive=true 时同时覆盖所有下级目录。
     * 分类位于 rag_file 上，因此修改后立即影响检索，不需要重新生成向量。
     */
    @Transactional
    public int updateKnowledgeType(long folderId, String knowledgeType, boolean recursive) {
        requireFolder(folderId);
        String normalizedType = RagKnowledgeType.from(knowledgeType).name();
        ragFolderMapper.setDefaultKnowledgeType(folderId, normalizedType);
        if (recursive) {
            ragFolderMapper.setTreeDefaultKnowledgeType(folderId, normalizedType);
            return ragFileMapper.updateKnowledgeTypeByFolderTree(folderId, normalizedType);
        }
        return ragFileMapper.updateKnowledgeTypeByFolder(folderId, normalizedType);
    }

    public RagFolderEntity requireFolder(long folderId) {
        RagFolderEntity folder = ragFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("目录不存在: " + folderId);
        }
        return folder;
    }

    public String defaultKnowledgeType(Long folderId) {
        String inherited = folderId == null ? null : ragFolderMapper.inheritedKnowledgeType(folderId);
        return inherited == null ? RagKnowledgeType.ALL.name() : inherited;
    }

    public Long normalizeFolderId(Long folderId) {
        return folderId == null || folderId <= 0 ? null : folderId;
    }

    private RagFolderEntity insertFolder(Long parentId, String name) {
        RagFolderEntity folder = new RagFolderEntity();
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        ragFolderMapper.insert(folder);
        return folder;
    }

    private String validateFolderName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("目录名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("目录名称不能超过 255 个字符");
        }
        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("目录名称不合法: " + normalized);
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isISOControl(ch) || "\\/:*?\"<>|".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("目录名称包含非法字符: " + normalized);
            }
        }
        return normalized;
    }

    private void sortNodes(List<MutableFolderNode> nodes, Comparator<MutableFolderNode> comparator) {
        nodes.sort(comparator);
        for (MutableFolderNode node : nodes) {
            sortNodes(node.children, comparator);
        }
    }

    private static final class MutableFolderNode {
        private final long id;
        private final Long parentId;
        private final String name;
        private final long fileCount;
        private final List<MutableFolderNode> children = new ArrayList<>();

        private MutableFolderNode(FolderListRow row) {
            this.id = row.id();
            this.parentId = row.parentId();
            this.name = row.name();
            this.fileCount = row.fileCount();
        }

        private FolderTreeNode toTreeNode() {
            return new FolderTreeNode(
                    id,
                    parentId,
                    name,
                    fileCount,
                    children.stream().map(MutableFolderNode::toTreeNode).toList()
            );
        }
    }
}
