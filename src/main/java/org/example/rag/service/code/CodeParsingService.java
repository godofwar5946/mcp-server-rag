package org.example.rag.service.code;

import org.example.rag.model.ParsedCodeFile;
import org.example.rag.util.TextUtils;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 根据扩展名选择源码解析器，并严格使用 UTF-8 解码代码文件。
 */
@Service
public class CodeParsingService {

    private final List<CodeFileParser> parsers;

    public CodeParsingService(List<CodeFileParser> parsers) {
        this.parsers = parsers;
    }

    public boolean supports(String filename) {
        String extension = extension(filename);
        return parsers.stream().anyMatch(parser -> parser.supports(extension));
    }

    public ParsedCodeFile parse(String filename, byte[] bytes) {
        String extension = extension(filename);
        CodeFileParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的源码格式: " + filename));
        String source = decodeUtf8(filename, bytes);
        return parser.parse(filename, source);
    }

    private String decodeUtf8(String filename, byte[] bytes) {
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
                source = source.substring(1);
            }
            return TextUtils.normalizeNewlines(source);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("源码文件不是有效的 UTF-8 编码: " + filename, e);
        }
    }

    private String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int separator = filename.lastIndexOf('.');
        if (separator < 0 || separator == filename.length() - 1) {
            return "";
        }
        return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}
