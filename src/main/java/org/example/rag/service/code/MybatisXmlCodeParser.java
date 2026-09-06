package org.example.rag.service.code;

import org.example.rag.model.CodeSymbolDraft;
import org.example.rag.model.CodeSymbolType;
import org.example.rag.model.ParsedCodeFile;
import org.example.rag.util.SourceTextIndex;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析 MyBatis Mapper XML 中的 namespace、SQL ID 和对应源码行号。
 */
@Component
public class MybatisXmlCodeParser implements CodeFileParser {

    private static final Set<String> SQL_ELEMENTS = Set.of("select", "insert", "update", "delete", "sql");

    @Override
    public boolean supports(String extension) {
        return "xml".equalsIgnoreCase(extension);
    }

    @Override
    public ParsedCodeFile parse(String filename, String source) {
        XMLInputFactory factory = secureFactory();
        List<CodeSymbolDraft> symbols = new ArrayList<>();
        SourceTextIndex textIndex = new SourceTextIndex(source);
        String lowerSource = source.toLowerCase(Locale.ROOT);
        Deque<ActiveElement> statements = new ArrayDeque<>();
        ActiveElement mapper = null;
        String namespace = "";
        int mapperDepth = -1;
        int depth = 0;
        int sequence = 0;

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(source));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String localName = reader.getLocalName().toLowerCase(Locale.ROOT);
                    int reportedLine = safeLine(reader.getLocation().getLineNumber(), textIndex.lineCount());
                    int startOffset = findStartOffset(
                            lowerSource,
                            localName,
                            reader.getLocation().getCharacterOffset(),
                            textIndex.lineEndOffset(reportedLine)
                    );
                    int startLine = textIndex.lineOfOffset(startOffset);
                    int startColumn = textIndex.columnOfOffset(startOffset);
                    if (mapper == null && "mapper".equals(localName)) {
                        namespace = attribute(reader, "namespace");
                        mapperDepth = depth;
                        mapper = new ActiveElement(
                                "xml:mapper",
                                null,
                                localName,
                                namespaceSimpleName(namespace, filename),
                                namespace.isBlank() ? filename : namespace,
                                namespace,
                                CodeSymbolType.XML_MAPPER,
                                startLine,
                                startColumn,
                                startOffset,
                                depth,
                                attributes(reader)
                        );
                    } else if (mapper != null && depth == mapperDepth + 1 && SQL_ELEMENTS.contains(localName)) {
                        String id = attribute(reader, "id");
                        if (!id.isBlank()) {
                            String localKey = "xml:statement:" + (++sequence);
                            String qualifiedName = namespace.isBlank() ? id : namespace + "." + id;
                            Map<String, Object> metadata = new LinkedHashMap<>(attributes(reader));
                            metadata.put("namespace", namespace);
                            metadata.put("command", localName.toUpperCase(Locale.ROOT));
                            statements.push(new ActiveElement(
                                    localKey,
                                    mapper.localKey(),
                                    localName,
                                    id,
                                    qualifiedName,
                                    id,
                                    sqlType(localName),
                                    startLine,
                                    startColumn,
                                    startOffset,
                                    depth,
                                    metadata
                            ));
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (!statements.isEmpty()) {
                        ActiveElement statement = statements.peek();
                        if (statement.depth() == depth && statement.elementName().equals(localName)) {
                            int endOffset = findEndOffset(
                                    lowerSource,
                                    statement,
                                    reader.getLocation().getCharacterOffset(),
                                    source.length()
                            );
                            symbols.add(toDraft(statement, endOffset, source, textIndex));
                            statements.pop();
                        }
                    }
                    if (mapper != null && mapper.depth() == depth && mapper.elementName().equals(localName)) {
                        int endOffset = findEndOffset(
                                lowerSource,
                                mapper,
                                reader.getLocation().getCharacterOffset(),
                                source.length()
                        );
                        symbols.add(0, toDraft(mapper, endOffset, source, textIndex));
                    }
                    depth--;
                }
            }
            reader.close();
            return new ParsedCodeFile(filename, "XML", source, List.copyOf(symbols));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML 解析失败: " + filename + "，" + e.getMessage(), e);
        }
    }

    private CodeSymbolDraft toDraft(ActiveElement element,
                                    int requestedEndOffset,
                                    String source,
                                    SourceTextIndex textIndex) {
        int startOffset = Math.max(0, Math.min(element.startOffset(), source.length()));
        int endOffset = Math.max(startOffset, Math.min(requestedEndOffset, source.length()));
        int endPosition = Math.max(startOffset, endOffset - 1);
        int endLine = textIndex.lineOfOffset(endPosition);
        int endColumn = textIndex.columnOfOffset(endPosition);
        return new CodeSymbolDraft(
                element.localKey(),
                element.parentLocalKey(),
                "XML",
                element.symbolType(),
                element.simpleName(),
                element.qualifiedName(),
                element.signature(),
                element.startLine(),
                endLine,
                element.startColumn(),
                endColumn,
                startOffset,
                endOffset,
                Map.copyOf(element.metadata())
        );
    }

    private XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        return factory;
    }

    private void setProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // 不同 StAX 实现支持的安全属性不同，XMLResolver 仍会阻止外部资源访问。
        }
    }

    private Map<String, Object> attributes(XMLStreamReader reader) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            values.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
        }
        return values;
    }

    private String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    private int safeLine(int line, int lineCount) {
        return Math.max(1, Math.min(line, Math.max(1, lineCount)));
    }

    private int findStartOffset(String lowerSource,
                                String elementName,
                                int reportedOffset,
                                int fallbackOffset) {
        int searchFrom = reportedOffset >= 0
                ? Math.min(reportedOffset, lowerSource.length())
                : Math.min(fallbackOffset, lowerSource.length());
        int offset = lowerSource.lastIndexOf("<" + elementName, Math.max(0, searchFrom));
        return offset < 0 ? Math.max(0, Math.min(fallbackOffset, lowerSource.length())) : offset;
    }

    private int findEndOffset(String lowerSource,
                              ActiveElement element,
                              int reportedOffset,
                              int fallbackOffset) {
        int searchFrom = reportedOffset >= 0
                ? Math.min(reportedOffset, lowerSource.length())
                : Math.min(fallbackOffset, lowerSource.length());
        int closingStart = lowerSource.lastIndexOf("</" + element.elementName(), Math.max(0, searchFrom));
        int tagEnd;
        if (closingStart >= element.startOffset()) {
            tagEnd = lowerSource.indexOf('>', closingStart);
        } else {
            tagEnd = lowerSource.indexOf('>', element.startOffset());
        }
        return tagEnd < 0 ? searchFrom : tagEnd + 1;
    }

    private String namespaceSimpleName(String namespace, String filename) {
        if (namespace == null || namespace.isBlank()) {
            return filename;
        }
        int separator = namespace.lastIndexOf('.');
        return separator < 0 ? namespace : namespace.substring(separator + 1);
    }

    private CodeSymbolType sqlType(String elementName) {
        return switch (elementName) {
            case "select" -> CodeSymbolType.XML_SELECT;
            case "insert" -> CodeSymbolType.XML_INSERT;
            case "update" -> CodeSymbolType.XML_UPDATE;
            case "delete" -> CodeSymbolType.XML_DELETE;
            default -> CodeSymbolType.XML_SQL_FRAGMENT;
        };
    }

    private record ActiveElement(String localKey,
                                 String parentLocalKey,
                                 String elementName,
                                 String simpleName,
                                 String qualifiedName,
                                 String signature,
                                 CodeSymbolType symbolType,
                                 int startLine,
                                 int startColumn,
                                 int startOffset,
                                 int depth,
                                 Map<String, Object> metadata) {
    }
}
