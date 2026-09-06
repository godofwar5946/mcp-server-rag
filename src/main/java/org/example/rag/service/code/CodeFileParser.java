package org.example.rag.service.code;

import org.example.rag.model.ParsedCodeFile;

/**
 * 针对一种源码格式生成类、方法或 SQL 节点信息。
 */
public interface CodeFileParser {

    boolean supports(String extension);

    ParsedCodeFile parse(String filename, String source);
}
