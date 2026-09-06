package org.example.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.config.AppProperties;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.mapper.RagCodeSymbolMapper;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.mapper.IndexJobMapper;
import org.example.rag.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 原子发布边界：此类的方法不调用解析器或模型。异常必须越过事务代理，确保整份旧索引回滚恢复。
 * revision 用于拒绝已经过期的任务，避免覆盖并发上传、移动后的文件或重新创建已删除文件。
 */
@Service
public class RagIndexWriter {
    private final RagFileMapper files;
    private final RagChunkMapper chunks;
    private final RagCodeSymbolMapper symbols;
    private final RagFolderService folders;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final IndexJobMapper jobs;
    private final EmbeddingModelRegistry models;

    public RagIndexWriter(RagFileMapper files, RagChunkMapper chunks, RagCodeSymbolMapper symbols,
                          RagFolderService folders, AppProperties properties, ObjectMapper json, IndexJobMapper jobs,
                          EmbeddingModelRegistry models) {
        this.files = files; this.chunks = chunks; this.symbols = symbols;
        this.folders = folders; this.properties = properties; this.json = json;
        this.jobs = jobs;
        this.models = models;
    }

    @Transactional
    public RagFileEntity register(String filename, String contentType, byte[] bytes, Long folderId) {
        if (folderId != null) folders.requireFolder(folderId);
        RagFileEntity existing = files.findByLocation(filename, folderId);
        if (existing != null) return existing;
        RagFileEntity file = new RagFileEntity();
        file.setFilename(filename); file.setFolderId(folderId); file.setContentType(contentType);
        file.setKnowledgeType(folders.defaultKnowledgeType(folderId));
        file.setSize(bytes.length); file.setContentBytes(bytes);
        files.insertPlaceholder(file);
        RagFileEntity registered = files.findByLocation(filename, folderId);
        if (registered == null) throw new IllegalStateException("文件登记失败");
        return registered;
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(RagFileEntity expected, String contentType, byte[] bytes, PreparedIndex prepared) {
        publish(expected, contentType, bytes, prepared, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(RagFileEntity expected, String contentType, byte[] bytes, PreparedIndex prepared, IndexJobLease lease) {
        // 必须先锁模型状态，再锁文件；与全库切换使用相同锁顺序。
        models.pinForPublish(prepared.model().id());
        RagFileEntity current = files.lockForIndex(expected.getId());
        if (current == null || current.getRevision() != expected.getRevision()) {
            throw new IllegalStateException("文件已被移动、更新或删除，请刷新后重新提交任务");
        }
        if (lease != null && !Boolean.TRUE.equals(jobs.lockLease(lease.id(), lease.owner())))
            throw new java.util.concurrent.CancellationException("任务已取消或租约已失效");
        if (prepared.chunks().isEmpty()) throw new IllegalStateException("没有可发布的有效切片");
        chunks.deleteByFileId(current.getId());
        symbols.deleteByFileId(current.getId());
        Map<String, Long> symbolIds = insertSymbols(current.getId(), prepared.symbols());
        List<RagChunkEntity> batch = new ArrayList<>();
        for (PreparedIndex.Chunk draft : prepared.chunks()) {
            RagChunkEntity chunk = draft.entity();
            chunk.setFileId(current.getId());
            chunk.setCodeSymbolId(draft.symbolKey() == null ? null : symbolIds.get(draft.symbolKey()));
            batch.add(chunk);
            if (batch.size() == 200) { chunks.insertBatch(current.getId(), batch); batch = new ArrayList<>(); }
        }
        if (!batch.isEmpty()) chunks.insertBatch(current.getId(), batch);
        current.setContentBytes(bytes); current.setContentType(contentType); current.setSize(bytes.length);
        current.setParsedText(prepared.text()); current.setContentHash(prepared.contentHash());
        current.setIndexFingerprint(prepared.fingerprint());
        current.setEmbeddingModel(prepared.model().modelName());
        if (files.publishIndex(current) != 1) throw new IllegalStateException("索引版本发生冲突");
        // 任务成功与索引发布处于同一个事务，避免已发布却被页面标记为失败或取消。
        if (lease != null && jobs.finish(lease.id(), lease.owner(), "SUCCEEDED",
                "索引成功，共 " + prepared.chunks().size() + " 个切片") != 1)
            throw new java.util.concurrent.CancellationException("任务租约失效，未发布索引");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(RagFileEntity expected, String message) {
        files.recordIndexFailure(expected.getId(), expected.getRevision(),
                message == null ? "索引失败" : message.substring(0, Math.min(message.length(), 1600)));
    }

    private Map<String, Long> insertSymbols(long fileId, List<CodeSymbolDraft> drafts) {
        Map<String, Long> ids = new HashMap<>();
        for (CodeSymbolDraft draft : drafts) {
            Long parentId = draft.parentLocalKey() == null ? null : ids.get(draft.parentLocalKey());
            if (draft.parentLocalKey() != null && parentId == null) throw new IllegalStateException("代码符号父节点缺失");
            RagCodeSymbolEntity entity = new RagCodeSymbolEntity();
            entity.setFileId(fileId); entity.setParentId(parentId); entity.setLanguage(draft.language());
            entity.setSymbolType(draft.symbolType().name()); entity.setSimpleName(draft.simpleName());
            entity.setQualifiedName(draft.qualifiedName()); entity.setSignature(draft.signature());
            entity.setStartLine(draft.startLine()); entity.setEndLine(draft.endLine());
            entity.setStartColumn(draft.startColumn()); entity.setEndColumn(draft.endColumn());
            entity.setStartOffset(draft.startOffset()); entity.setEndOffset(draft.endOffset());
            try { entity.setMetadataJson(json.writeValueAsString(draft.metadata())); }
            catch (Exception ex) { throw new IllegalStateException("代码元数据序列化失败", ex); }
            entity.setCreatedAt(LocalDateTime.now()); entity.setUpdatedAt(LocalDateTime.now());
            symbols.insertSymbol(entity);
            if (entity.getId() == null) throw new IllegalStateException("代码符号入库失败");
            ids.put(draft.localKey(), entity.getId());
        }
        return ids;
    }
}
