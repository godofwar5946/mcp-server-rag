package org.example.rag.service.code;

import org.example.rag.model.CodeChunkDraft;
import org.example.rag.model.CodeSymbolDraft;
import org.example.rag.model.CodeSymbolType;
import org.example.rag.model.ParsedCodeFile;
import org.example.rag.util.SourceTextIndex;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 Java 方法、完整类概要和 MyBatis SQL 节点生成语义切片。
 */
@Component
public class CodeChunkBuilder {

    public List<CodeChunkDraft> build(ParsedCodeFile parsedFile, int chunkSize, int overlap) {
        SourceTextIndex textIndex = new SourceTextIndex(parsedFile.source());
        int safeChunkSize = Math.max(300, chunkSize);
        int safeOverlap = Math.min(Math.max(overlap, 0), safeChunkSize / 3);
        List<CodeSymbolDraft> symbols = parsedFile.symbols();
        if (symbols.isEmpty()) {
            return splitSource(
                    parsedFile,
                    null,
                    null,
                    parsedFile.filename(),
                    parsedFile.filename(),
                    parsedFile.filename(),
                    0,
                    parsedFile.source().length(),
                    safeChunkSize,
                    safeOverlap,
                    textIndex
            );
        }

        Map<String, List<CodeSymbolDraft>> children = new HashMap<>();
        for (CodeSymbolDraft symbol : symbols) {
            if (symbol.parentLocalKey() != null) {
                children.computeIfAbsent(symbol.parentLocalKey(), ignored -> new ArrayList<>()).add(symbol);
            }
        }

        List<CodeChunkDraft> chunks = new ArrayList<>();
        for (CodeSymbolDraft symbol : symbols) {
            if (symbol.symbolType().isMethodType() || symbol.symbolType().isSqlType()) {
                chunks.addAll(splitSymbol(parsedFile, symbol, safeChunkSize, safeOverlap, textIndex));
            } else if (symbol.symbolType().isClassType() || symbol.symbolType() == CodeSymbolType.XML_MAPPER) {
                CodeChunkDraft overview = buildOverview(parsedFile, symbol, children.getOrDefault(symbol.localKey(), List.of()), safeChunkSize);
                for (String part : new org.example.rag.service.TextChunker().chunk(overview.content(), safeChunkSize, safeOverlap)) {
                    chunks.add(new CodeChunkDraft(overview.symbolLocalKey(),overview.language(),overview.symbolType(),
                            overview.symbolName(),overview.qualifiedName(),overview.signature(),overview.startLine(),overview.endLine(),part));
                }
            }
        }
        return chunks;
    }

    private List<CodeChunkDraft> splitSymbol(ParsedCodeFile parsedFile,
                                              CodeSymbolDraft symbol,
                                              int chunkSize,
                                              int overlap, SourceTextIndex textIndex) {
        return splitSource(
                parsedFile,
                symbol.localKey(),
                symbol.symbolType(),
                symbol.simpleName(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startOffset(),
                symbol.endOffset(),
                chunkSize,
                overlap,
                textIndex
        );
    }

    private List<CodeChunkDraft> splitSource(ParsedCodeFile parsedFile,
                                              String symbolLocalKey,
                                              CodeSymbolType symbolType,
                                              String symbolName,
                                              String qualifiedName,
                                              String signature,
                                              int requestedStart,
                                              int requestedEnd,
                                              int chunkSize,
                                              int overlap, SourceTextIndex textIndex) {
        String source = parsedFile.source();
        int start = Math.max(0, Math.min(requestedStart, source.length()));
        int end = Math.max(start, Math.min(requestedEnd, source.length()));
        if (start == end) {
            return List.of();
        }

        List<CodeChunkDraft> chunks = new ArrayList<>();
        int cursor = start;
        while (cursor < end) {
            int chunkEnd = Math.min(cursor + chunkSize, end);
            if (chunkEnd < end) {
                int newline = source.lastIndexOf('\n', chunkEnd - 1);
                if (newline >= cursor + chunkSize / 2) {
                    chunkEnd = newline + 1;
                }
            }
            if (chunkEnd <= cursor) {
                chunkEnd = Math.min(cursor + chunkSize, end);
            }

            int startLine = textIndex.lineOfOffset(cursor);
            int endLine = textIndex.lineOfOffset(Math.max(cursor, chunkEnd - 1));
            String code = source.substring(cursor, chunkEnd).stripTrailing();
            if (!code.isBlank()) {
                String content = prefix(
                        parsedFile,
                        symbolType,
                        symbolName,
                        qualifiedName,
                        signature,
                        startLine,
                        endLine
                ) + code;
                chunks.add(new CodeChunkDraft(
                        symbolLocalKey,
                        parsedFile.language(),
                        symbolType,
                        symbolName,
                        qualifiedName,
                        signature,
                        startLine,
                        endLine,
                        content
                ));
            }

            if (chunkEnd >= end) {
                break;
            }
            int next = Math.max(cursor + 1, chunkEnd - overlap);
            int lineStart = source.lastIndexOf('\n', Math.max(cursor, next - 1)) + 1;
            if (lineStart > cursor && lineStart < chunkEnd) {
                next = lineStart;
            }
            cursor = next >= chunkEnd ? chunkEnd : next;
        }
        return chunks;
    }

    private CodeChunkDraft buildOverview(ParsedCodeFile parsedFile,
                                         CodeSymbolDraft symbol,
                                         List<CodeSymbolDraft> children,
                                         int chunkSize) {
        List<CodeSymbolDraft> orderedChildren = children.stream()
                .sorted(Comparator.comparingInt(CodeSymbolDraft::startOffset))
                .toList();
        StringBuilder content = new StringBuilder(prefix(
                parsedFile,
                symbol.symbolType(),
                symbol.simpleName(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startLine(),
                symbol.endLine()
        ));
        if (!orderedChildren.isEmpty()) {
            content.append("成员:\n");
            orderedChildren.forEach(child -> content
                    .append("- ")
                    .append(child.symbolType().name())
                    .append(' ')
                    .append(child.signature())
                    .append(" [")
                    .append(child.startLine())
                    .append('-')
                    .append(child.endLine())
                    .append("]\n"));
            content.append("\n");
        }

        int sourceEnd = orderedChildren.isEmpty()
                ? symbol.endOffset()
                : orderedChildren.get(0).startOffset();
        int safeStart = Math.max(0, Math.min(symbol.startOffset(), parsedFile.source().length()));
        int safeEnd = Math.max(safeStart, Math.min(sourceEnd, parsedFile.source().length()));
        String declaration = parsedFile.source().substring(safeStart, safeEnd).stripTrailing();
        if (!declaration.isBlank()) {
            content.append("声明与字段:\n").append(declaration);
        }
        return new CodeChunkDraft(
                symbol.localKey(),
                parsedFile.language(),
                symbol.symbolType(),
                symbol.simpleName(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startLine(),
                symbol.endLine(),
                content.toString()
        );
    }

    private String prefix(ParsedCodeFile parsedFile,
                          CodeSymbolType symbolType,
                          String symbolName,
                          String qualifiedName,
                          String signature,
                          int startLine,
                          int endLine) {
        String type = symbolType == null ? "SOURCE_FILE" : symbolType.name();
        return "语言: " + parsedFile.language() + "\n"
                + "文件: " + parsedFile.filename() + "\n"
                + "符号类型: " + type + "\n"
                + "名称: " + value(symbolName) + "\n"
                + "完整名称: " + value(qualifiedName) + "\n"
                + "签名: " + value(signature) + "\n"
                + "代码行: " + startLine + "-" + endLine + "\n"
                + "代码:\n";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
