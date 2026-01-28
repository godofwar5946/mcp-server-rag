package org.example.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.config.AppProperties;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.IndexResult;
import org.example.rag.model.RagChunkEntity;
import org.example.rag.model.RagFileEntity;
import org.example.rag.model.RagFileRecord;
import org.example.rag.model.RagFileStatus;
import org.example.rag.util.TextUtils;
import org.example.rag.util.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文档索引服务：上传、解析、切片、向量化、入库。
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private static final Set<String> SUPPORTED_EXT = Set.of("txt", "md", "doc", "docx", "pdf", "xls", "xlsx");

    private final RagFileMapper ragFileMapper;
    private final RagChunkMapper ragChunkMapper;
    private final TikaTextExtractor textExtractor;
    private final TextChunker chunker;
    private final OllamaEmbeddingClient embeddingClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public RagIndexService(RagFileMapper ragFileMapper,
                           RagChunkMapper ragChunkMapper,
                           TikaTextExtractor textExtractor,
                           TextChunker chunker,
                           OllamaEmbeddingClient embeddingClient,
                           AppProperties properties,
                           ObjectMapper objectMapper) {
        this.ragFileMapper = ragFileMapper;
        this.ragChunkMapper = ragChunkMapper;
        this.textExtractor = textExtractor;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<IndexResult> upload(List<MultipartFile> files) {
        List<IndexResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(indexMultipartFile(file));
        }
        return results;
    }

    @Transactional
    public IndexResult reindexFile(long fileId) {
        RagFileEntity entity = ragFileMapper.selectById(fileId);
        Optional<RagFileRecord> recordOpt = Optional.ofNullable(entity == null ? null : toRecord(entity));
        if (recordOpt.isEmpty()) {
            return new IndexResult(fileId, null, false, "文件不存在");
        }
        RagFileRecord record = recordOpt.get();
        if (record.contentBytes() == null) {
            return new IndexResult(fileId, record.filename(), false, "文件未保存原始内容，无法重建索引");
        }
        return indexBytes(record.filename(), record.contentType(), record.contentBytes());
    }

    @Transactional
    public List<IndexResult> rebuildAll() {
        List<RagFileEntity> entities = ragFileMapper.selectList(null);
        List<RagFileRecord> files = entities.stream().map(this::toRecord).toList();
        List<IndexResult> results = new ArrayList<>();
        for (RagFileRecord record : files) {
            if (record.contentBytes() == null) {
                results.add(new IndexResult(record.id(), record.filename(), false, "缺少原始内容，无法重建"));
                continue;
            }
            results.add(indexBytes(record.filename(), record.contentType(), record.contentBytes()));
        }
        return results;
    }

    @Transactional
    public IndexResult indexMultipartFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unknown";
        }
        validateExtension(filename);
        try {
            byte[] bytes = file.getBytes();
            return indexBytes(filename, file.getContentType(), bytes);
        } catch (Exception e) {
            log.error("读取上传文件失败: {}", filename, e);
            return new IndexResult(null, filename, false, "读取文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public IndexResult indexBytes(String filename, String contentType, byte[] bytes) {
        long fileId = prepareFileRecord(filename, contentType, bytes);
        try {
            String parsedText = textExtractor.extract(bytes, filename, contentType);
            String normalized = TextUtils.normalizeNewlines(parsedText);
            ragFileMapper.updateContentAndStatus(
                    fileId,
                    contentType,
                    bytes.length,
                    bytes,
                    normalized,
                    RagFileStatus.PARSED,
                    null
            );

            List<String> chunks = chunker.chunk(normalized,
                    properties.getChunking().getChunkSize(),
                    properties.getChunking().getOverlap());
            List<RagChunkEntity> chunkData = buildChunkData(chunks, filename, fileId);

            // 先清理旧数据再写入
            ragChunkMapper.deleteByFileId(fileId);
            if (!chunkData.isEmpty()) {
                ragChunkMapper.insertBatch(fileId, chunkData);
            }

            if (chunkData.isEmpty()) {
                ragFileMapper.updateStatus(fileId, RagFileStatus.FAILED, "无有效向量切片");
                return new IndexResult(fileId, filename, false, "索引失败：无有效向量切片");
            }
            ragFileMapper.updateIndexStatus(fileId, RagFileStatus.INDEXED, null, LocalDateTime.now());
            return new IndexResult(fileId, filename, true, "索引成功，切片数：" + chunkData.size());
        } catch (Exception e) {
            log.error("索引失败: {}", filename, e);
            ragChunkMapper.deleteByFileId(fileId);
            ragFileMapper.updateStatus(fileId, RagFileStatus.FAILED, e.getMessage());
            return new IndexResult(fileId, filename, false, "索引失败: " + e.getMessage());
        }
    }

    @Transactional
    public void deleteFile(long fileId) {
        ragFileMapper.deleteById(fileId);
    }

    private long prepareFileRecord(String filename, String contentType, byte[] bytes) {
        RagFileEntity existing = ragFileMapper.selectOne(
                new LambdaQueryWrapper<RagFileEntity>().eq(RagFileEntity::getFilename, filename)
        );
        if (existing != null) {
            long fileId = existing.getId();
            ragFileMapper.updateContentAndStatus(
                    fileId,
                    contentType,
                    bytes.length,
                    bytes,
                    existing.getParsedText(),
                    RagFileStatus.PENDING,
                    null
            );
            return fileId;
        }
        RagFileEntity entity = new RagFileEntity();
        entity.setFilename(filename);
        entity.setContentType(contentType);
        entity.setSize(bytes.length);
        entity.setContentBytes(bytes);
        entity.setParsedText(null);
        entity.setStatus(RagFileStatus.PENDING);
        entity.setErrorMessage(null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setIndexedAt(null);
        ragFileMapper.insert(entity);
        return entity.getId() == null ? -1 : entity.getId();
    }

    private void validateExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index <= 0 || index == filename.length() - 1) {
            throw new IllegalArgumentException("文件格式不支持: " + filename);
        }
        String ext = filename.substring(index + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new IllegalArgumentException("文件格式不支持: " + filename);
        }
    }

    private List<RagChunkEntity> buildChunkData(List<String> chunks, String filename, long fileId) {
        List<RagChunkEntity> chunkData = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            double[] embedding = embeddingClient.embed(content);
            if (embedding == null || embedding.length == 0) {
                log.warn("跳过空向量切片: file={}, index={}", filename, i);
                continue;
            }
            String vector = VectorUtils.toVectorString(embedding);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", filename);
            metadata.put("chunkIndex", i);
            metadata.put("length", content.length());
            RagChunkEntity entity = new RagChunkEntity();
            entity.setFileId(fileId);
            entity.setChunkIndex(i);
            entity.setContent(content);
            entity.setEmbedding(vector);
            entity.setMetadataJson(toJson(metadata));
            chunkData.add(entity);
        }
        return chunkData;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
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
