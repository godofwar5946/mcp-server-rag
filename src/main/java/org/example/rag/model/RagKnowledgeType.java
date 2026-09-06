package org.example.rag.model;

import java.util.Locale;

/**
 * 文件的知识分类。ALL 是默认值，表示不限制业务或代码分类检索。
 */
public enum RagKnowledgeType {
    ALL,
    BUSINESS,
    CODE;

    public static RagKnowledgeType from(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("文件类型仅支持 ALL、BUSINESS、CODE");
        }
    }
}
