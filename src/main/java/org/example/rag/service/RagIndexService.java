package org.example.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.config.AppProperties;
import org.example.rag.mapper.RagFileMapper;
import org.example.rag.model.*;
import org.example.rag.service.code.CodeChunkBuilder;
import org.example.rag.service.code.CodeParsingService;
import org.example.rag.util.TextUtils;
import org.example.rag.util.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 先在事务外准备完整索引，最后交给独立事务服务发布；失败不会删除原索引。 */
@Service
public class RagIndexService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final Set<String> SUPPORTED_EXT = Set.of(
            "txt", "md", "doc", "docx", "pdf", "xls", "xlsx", "java", "xml");
    private final RagFileMapper ragFileMapper;
    private final RagFolderService ragFolderService;
    private final RagIndexWriter writer;
    private final DocumentParser documentParser;
    private final TextChunker chunker;
    private final CodeParsingService codeParsingService;
    private final CodeChunkBuilder codeChunkBuilder;
    private final OllamaEmbeddingClient embeddingClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final EmbeddingModelRegistry models;

    public RagIndexService(RagFileMapper files, RagFolderService folders, RagIndexWriter writer,
                           DocumentParser parser, TextChunker chunker,
                           CodeParsingService parsing, CodeChunkBuilder codeChunks,
                           OllamaEmbeddingClient embeddings, AppProperties properties, ObjectMapper json, EmbeddingModelRegistry models) {
        this.ragFileMapper = files; this.ragFolderService = folders; this.writer = writer;
        this.documentParser = parser; this.chunker = chunker; this.codeParsingService = parsing;
        this.codeChunkBuilder = codeChunks; this.embeddingClient = embeddings;
        this.properties = properties; this.objectMapper = json;
        this.models = models;
    }

    public IndexResult reindexFile(long fileId) {
        RagFileEntity file = ragFileMapper.selectMetadata(fileId);
        RagFileEntity original = ragFileMapper.selectOriginal(fileId);
        if (file == null || original == null || original.getContentBytes() == null)
            return new IndexResult(fileId, null, false, "文件不存在或缺少原始内容");
        return indexRegistered(file, original.getContentType(), original.getContentBytes(), true, IndexProgress.NONE);
    }

    /** 兼容原同步 API，但不再全库加载二进制内容，也不再使用一个全库事务。 */
    public List<IndexResult> rebuildAll() {
        List<IndexResult> results = new ArrayList<>();
        long afterId = 0;
        while (true) {
            List<Long> ids = ragFileMapper.listIdsAfter(afterId, 100);
            if (ids.isEmpty()) break;
            for (long id : ids) results.add(reindexFile(id));
            afterId = ids.getLast();
        }
        return results;
    }

    public IndexResult indexMultipartFile(MultipartFile file) { return indexMultipartFile(file, null, null); }

    public IndexResult indexMultipartFile(MultipartFile file, Long folderId, String relativePath) {
        UploadTarget target = resolveUploadTarget(file, folderId, relativePath);
        try { return indexBytes(target.filename(), file.getContentType(), file.getBytes(), target.folderId()); }
        catch (java.io.IOException e) { return new IndexResult(null, target.filename(), false, "读取上传文件失败"); }
    }

    public IndexResult indexBytes(String filename, String contentType, byte[] bytes) {
        return indexBytes(filename, contentType, bytes, null);
    }

    public IndexResult indexBytes(String filename, String contentType, byte[] bytes, Long folderId) {
        filename = safeFileName(filename);
        validateExtension(filename);
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("上传文件不能为空");
        RagFileEntity file = writer.register(filename, contentType, bytes, ragFolderService.normalizeFolderId(folderId));
        return indexRegistered(file, contentType, bytes, false, IndexProgress.NONE);
    }

    public IndexResult indexRegistered(RagFileEntity file, String contentType, byte[] bytes,
                                       boolean force, IndexProgress progress) {
        return indexRegistered(file, contentType, bytes, force, progress, null);
    }

    public IndexResult indexRegistered(RagFileEntity file, String contentType, byte[] bytes,
                                       boolean force, IndexProgress progress, IndexJobLease lease) {
        try {
            String hash = hash(bytes);
            EmbeddingModel model = models.current();
            String fingerprint = fingerprint(model);
            if (!force && RagFileStatus.INDEXED.equals(file.getStatus())
                    && hash.equals(file.getContentHash()) && fingerprint.equals(file.getIndexFingerprint())) {
                progress.update("UNCHANGED", 0, 0);
                return new IndexResult(file.getId(), file.getFilename(), true, "内容与索引策略未变化，已跳过", true);
            }
            progress.update("PARSING", 0, 0);
            ParsedCodeFile parsed = codeParsingService.supports(file.getFilename())
                    ? codeParsingService.parse(file.getFilename(), bytes) : null;
            DocumentContent document = parsed == null ? documentParser.parse(bytes, file.getFilename(), contentType) : null;
            String text = document == null ? parsed.source() : document.text();
            if (text == null || text.isBlank()) throw new IllegalStateException("未提取到有效文本，请检查扫描件或文件内容");
            if (text.length() > properties.getParsing().getMaxTextChars())
                throw new IllegalStateException("解析文本超过容量上限，请拆分文件后上传");
            List<PreparedIndex.Chunk> drafts = buildChunks(file, document, parsed);
            if (drafts.isEmpty()) throw new IllegalStateException("文件没有有效切片");
            int completed = 0;
            int batchSize = properties.getEmbedding().getBatchSize();
            progress.update("EMBEDDING", 0, drafts.size());
            for (int start = 0; start < drafts.size(); start += batchSize) {
                List<PreparedIndex.Chunk> batch = drafts.subList(start, Math.min(start + batchSize, drafts.size()));
                List<double[]> vectors = embeddingClient.embedAll(batch.stream().map(c -> c.entity().getContent()).toList(), model);
                if (vectors.size() != batch.size()) throw new IllegalStateException("模型返回向量数量与切片数量不一致");
                for (int i = 0; i < batch.size(); i++) {
                    double[] vector = vectors.get(i);
                    if (vector == null || vector.length == 0) throw new IllegalStateException("模型返回空向量，本次索引未发布");
                    batch.get(i).entity().setEmbedding(VectorUtils.toVectorString(vector));
                }
                completed += batch.size();
                progress.update("EMBEDDING", completed, drafts.size());
            }
            progress.update("PUBLISHING", completed, drafts.size());
            writer.publish(file, contentType, bytes, new PreparedIndex(text,
                    parsed == null ? List.of() : parsed.symbols(), drafts, hash, fingerprint, model), lease);
            return new IndexResult(file.getId(), file.getFilename(), true, "索引成功，共 " + drafts.size() + " 个切片");
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("索引失败，保留已发布版本: fileId={}", file.getId(), e);
            try { writer.recordFailure(file, e.getMessage()); }
            catch (Exception secondary) { log.warn("保存索引失败信息时发生异常: fileId={}", file.getId(), secondary); }
            return new IndexResult(file.getId(), file.getFilename(), false, e.getMessage());
        }
    }

    private List<PreparedIndex.Chunk> buildChunks(RagFileEntity file, DocumentContent document, ParsedCodeFile parsed) {
        int size = properties.getChunking().getChunkSize();
        int overlap = properties.getChunking().getOverlap();
        List<PreparedIndex.Chunk> result = new ArrayList<>();
        if (parsed == null) {
            for (DocumentContent.Section section : document.sections()) {
                for (String content : chunker.chunk(section.text(), size, overlap)) {
                    PreparedIndex.Chunk draft = chunk(file, result.size(), content, null);
                    try {
                        var metadata = objectMapper.readValue(draft.entity().getMetadataJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
                        metadata.putAll(section.metadata());
                        draft.entity().setMetadataJson(objectMapper.writeValueAsString(metadata));
                    } catch (Exception ex) { throw new IllegalStateException("文档定位信息保存失败", ex); }
                    result.add(draft);
                }
            }
        } else {
            for (CodeChunkDraft code : codeChunkBuilder.build(parsed, size, overlap))
                result.add(chunk(file, result.size(), code.content(), code));
        }
        return result;
    }

    private PreparedIndex.Chunk chunk(RagFileEntity file, int index, String content, CodeChunkDraft code) {
        RagChunkEntity entity = new RagChunkEntity();
        entity.setFileId(file.getId()); entity.setChunkIndex(index); entity.setContent(content);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("fileName", file.getFilename()); metadata.put("fileId", file.getId());
        metadata.put("chunkIndex", index); metadata.put("length", content.length());
        if (code != null) {
            entity.setStartLine(code.startLine()); entity.setEndLine(code.endLine());
            metadata.put("language", code.language());
            metadata.put("symbolType", code.symbolType() == null ? "SOURCE_FILE" : code.symbolType().name());
            metadata.put("qualifiedName", code.qualifiedName()); metadata.put("signature", code.signature());
            metadata.put("startLine", code.startLine()); metadata.put("endLine", code.endLine());
        }
        try { entity.setMetadataJson(objectMapper.writeValueAsString(metadata)); }
        catch (Exception e) { throw new IllegalStateException("切片元数据序列化失败", e); }
        return new PreparedIndex.Chunk(code == null ? null : code.symbolLocalKey(), entity);
    }

    public String fingerprint() {
        return fingerprint(models.current());
    }

    private String fingerprint(EmbeddingModel model) {
        return hash(("index-v3|" + model.identity()
                + "|" + properties.getChunking().getChunkSize() + "|" + properties.getChunking().getOverlap()
                + "|" + properties.getEmbedding().getMaxInputChars()).getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("无法计算内容指纹", e); }
    }

    @Transactional
    public void deleteFile(long fileId) { ragFileMapper.deleteById(fileId); }

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

    public UploadTarget resolveUploadTarget(MultipartFile file, Long baseFolderId, String relativePath) {
        baseFolderId=ragFolderService.normalizeFolderId(baseFolderId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (relativePath == null || relativePath.isBlank()) {
            String filename = safeFileName(file.getOriginalFilename());
            validateExtension(filename);
            return new UploadTarget(filename, baseFolderId);
        }

        String normalizedPath = relativePath.replace('\\', '/').trim();
        if (normalizedPath.length() > 4096
                || normalizedPath.startsWith("/")
                || normalizedPath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("文件相对路径不合法: " + relativePath);
        }
        String[] parts = normalizedPath.split("/", -1);
        if (parts.length == 0) {
            throw new IllegalArgumentException("文件相对路径不能为空");
        }
        List<String> directorySegments = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isBlank() || ".".equals(parts[i]) || "..".equals(parts[i])) {
                throw new IllegalArgumentException("文件相对路径包含非法目录: " + relativePath);
            }
            directorySegments.add(parts[i]);
        }
        String filename = safeFileName(parts[parts.length - 1]);
        validateExtension(filename);
        Long targetFolderId = ragFolderService.ensurePath(baseFolderId, directorySegments);
        return new UploadTarget(filename, targetFolderId);
    }

    private String safeFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        String normalized = filename.trim().replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        if (normalized.length() > 512) {
            throw new IllegalArgumentException("文件名不能超过 512 个字符");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException("文件名包含非法字符");
            }
        }
        return normalized;
    }


    public record UploadTarget(String filename, Long folderId) { }
}
