package org.example.rag.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于标准化换行文本建立行号与字符偏移量之间的映射。
 */
public final class SourceTextIndex {

    private final String source;
    private final int[] lineStarts;

    public SourceTextIndex(String source) {
        this.source = source == null ? "" : source;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < this.source.length(); i++) {
            if (this.source.charAt(i) == '\n' && i + 1 <= this.source.length()) {
                starts.add(i + 1);
            }
        }
        this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineOfOffset(int offset) {
        int safeOffset = Math.max(0, Math.min(offset, source.length()));
        int low = 0;
        int high = lineStarts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lineStarts[middle] <= safeOffset) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return Math.max(1, high + 1);
    }

    public int columnOfOffset(int offset) {
        int line = lineOfOffset(offset);
        return Math.max(1, Math.min(offset, source.length()) - lineStartOffset(line) + 1);
    }

    public int lineStartOffset(int lineNumber) {
        int index = Math.max(0, Math.min(lineNumber - 1, lineStarts.length - 1));
        return lineStarts[index];
    }

    public int lineEndOffset(int lineNumber) {
        int index = Math.max(0, Math.min(lineNumber - 1, lineStarts.length - 1));
        if (index + 1 < lineStarts.length) {
            return lineStarts[index + 1];
        }
        return source.length();
    }
}
