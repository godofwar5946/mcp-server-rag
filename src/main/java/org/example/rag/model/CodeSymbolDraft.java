package org.example.rag.model;

import java.util.Map;

/**
 * 解析阶段生成的代码符号，localKey 用于入库前表达父子关系。
 */
public record CodeSymbolDraft(
        String localKey,
        String parentLocalKey,
        String language,
        CodeSymbolType symbolType,
        String simpleName,
        String qualifiedName,
        String signature,
        int startLine,
        int endLine,
        int startColumn,
        int endColumn,
        int startOffset,
        int endOffset,
        Map<String, Object> metadata
) {
}
