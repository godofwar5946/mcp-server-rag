package org.example.rag.util;

/**
 * 文本处理工具。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 统一换行符，避免 \r\n 带来切片差异。
     */
    public static String normalizeNewlines(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 简单按字符截断。
     */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
