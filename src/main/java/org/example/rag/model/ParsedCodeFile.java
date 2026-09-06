package org.example.rag.model;

import java.util.List;

/**
 * 源码解析结果。source 与符号偏移量必须基于同一份标准化文本。
 */
public record ParsedCodeFile(
        String filename,
        String language,
        String source,
        List<CodeSymbolDraft> symbols
) {
}
