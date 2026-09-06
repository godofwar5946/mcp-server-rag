package org.example.rag.service;

import org.example.rag.util.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单稳健的分段切片器：优先按段落（空行）切分。
 */
@Component
public class TextChunker {

    public List<String> chunk(String text, int chunkSize, int overlap) {
        if (chunkSize < 100 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("切片长度至少为 100，重叠长度必须在 0 与切片长度之间");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = TextUtils.normalizeNewlines(text).trim();
        List<String> paragraphs = splitParagraphs(normalized);
        List<String> baseChunks = buildChunks(paragraphs, chunkSize);
        return applyOverlap(baseChunks, overlap);
    }

    private List<String> splitParagraphs(String text) {
        String[] raw = text.split("\\n\\s*\\n");
        List<String> paragraphs = new ArrayList<>();
        for (String p : raw) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    private List<String> buildChunks(List<String> paragraphs, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > chunkSize) {
                flushCurrent(chunks, current);
                splitLongParagraph(paragraph, chunkSize, chunks);
                continue;
            }
            if (current.length() == 0) {
                current.append(paragraph);
                continue;
            }
            if (current.length() + paragraph.length() + 2 <= chunkSize) {
                current.append("\n\n").append(paragraph);
            } else {
                flushCurrent(chunks, current);
                current.append(paragraph);
            }
        }
        flushCurrent(chunks, current);
        return chunks;
    }

    private void splitLongParagraph(String paragraph, int chunkSize, List<String> chunks) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + chunkSize, paragraph.length());
            chunks.add(paragraph.substring(start, end));
            start = end;
        }
    }

    private void flushCurrent(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private List<String> applyOverlap(List<String> baseChunks, int overlap) {
        if (overlap <= 0 || baseChunks.size() <= 1) {
            return baseChunks;
        }
        List<String> result = new ArrayList<>();
        String previous = null;
        for (String chunk : baseChunks) {
            if (previous == null) {
                result.add(chunk);
                previous = chunk;
                continue;
            }
            String overlapText = previous.length() > overlap
                    ? previous.substring(previous.length() - overlap)
                    : previous;
            result.add(overlapText + "\n" + chunk);
            previous = chunk;
        }
        return result;
    }
}
