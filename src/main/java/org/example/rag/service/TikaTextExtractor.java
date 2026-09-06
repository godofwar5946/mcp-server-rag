package org.example.rag.service;

import org.apache.tika.Tika;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.example.rag.config.AppProperties;

import java.io.ByteArrayInputStream;

/**
 * 文档解析器：统一使用 Apache Tika 解析 txt/doc/docx/pdf/xls/xlsx/md。
 */
@Component
public class TikaTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaTextExtractor.class);

    private final Tika tika = new Tika();
    private final AppProperties properties;

    public TikaTextExtractor(AppProperties properties) {
        this.properties = properties;
    }

    public String extract(byte[] bytes, String filename, String contentType) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            if (contentType != null) {
                metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            int limit = properties.getParsing().getMaxTextChars();
            // 多读取一个字符用于检测截断。不能把 Tika 默认的截断结果当作完整文档发布。
            String text = tika.parseToString(input, metadata, limit + 1);
            if (text.length() > limit) {
                throw new IllegalArgumentException("解析文本超过 " + limit
                        + " 字符上限，请拆分文档后上传；本次未发布不完整索引");
            }
            return text;
        } catch (Exception e) {
            log.error("解析文件失败: {}", filename, e);
            throw new IllegalStateException("文件解析失败: " + e.getMessage(), e);
        }
    }
}
