package org.example.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.example.rag.config.AppProperties;
import org.example.rag.model.DocumentContent;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** PDF 保留页码，表格重复携带表头，Markdown 保留标题；其它格式继续使用固定版本 Tika。 */
@Component
public class DocumentParser {
    private final TikaTextExtractor tika;
    private final AppProperties properties;
    public DocumentParser(TikaTextExtractor tika, AppProperties properties) { this.tika=tika; this.properties=properties; }

    public DocumentContent parse(byte[] bytes, String filename, String contentType) {
        String ext=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        try {
            return switch (ext) {
                case "pdf" -> pdf(bytes);
                case "xls", "xlsx" -> spreadsheet(bytes);
                case "txt", "md" -> markdown(decodeText(bytes,filename,contentType));
                default -> markdown(tika.extract(bytes,filename,contentType));
            };
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("文档解析失败："+Objects.toString(ex.getMessage(),ext),ex); }
    }

    private DocumentContent pdf(byte[] bytes) throws Exception {
        List<DocumentContent.Section> sections=new ArrayList<>();
        StringBuilder full=new StringBuilder();
        try (var document=Loader.loadPDF(bytes)) {
            PDFTextStripper stripper=new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page=1;page<=document.getNumberOfPages();page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text=stripper.getText(document).trim();
                append(full,"[第 "+page+" 页]\n"+(text.isBlank()?"[本页未提取到文字，可能为空白页或需要 OCR]":text)+"\n\n");
                if (!text.isBlank()) sections.add(new DocumentContent.Section(text,Map.of("page",page)));
            }
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("PDF 未提取到文字，可能是扫描件；请先进行 OCR 后上传");
        return new DocumentContent(full.toString(),sections);
    }

    private DocumentContent spreadsheet(byte[] bytes) throws Exception {
        List<DocumentContent.Section> sections=new ArrayList<>();
        StringBuilder full=new StringBuilder();
        try (Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter=new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (Sheet sheet:workbook) {
                String header="";
                append(full,"[工作表："+sheet.getSheetName()+"]\n");
                for (Row row:sheet) {
                    StringJoiner cells=new StringJoiner(" | ");
                    if (row.getLastCellNum()>1024) throw new IllegalArgumentException("表格单行超过 1024 列，请拆分后上传；未发布截断内容");
                    for (int i=0;i<row.getLastCellNum();i++) cells.add(formatter.formatCellValue(row.getCell(i)));
                    String text=cells.toString();
                    if (text.replace("|","").isBlank()) continue;
                    append(full,"行 "+(row.getRowNum()+1)+"："+text+"\n");
                    if (header.isEmpty()) header=text;
                    String content="工作表："+sheet.getSheetName()+"\n表头："+header+"\n行 "+(row.getRowNum()+1)+"："+text;
                    sections.add(new DocumentContent.Section(content,Map.of("sheet",sheet.getSheetName(),"row",row.getRowNum()+1)));
                }
            }
        }
        return new DocumentContent(full.toString(),sections);
    }

    private DocumentContent markdown(String text) {
        text=text.replace("\r\n","\n").replace('\r','\n');
        checkLength(text.length());
        List<DocumentContent.Section> sections=new ArrayList<>();
        StringBuilder section=new StringBuilder();
        String heading="";
        int firstLine=1,line=0;
        boolean fenced=false;
        for (String part:text.split("\n",-1)) {
            line++;
            if (part.stripLeading().startsWith("\u0060\u0060\u0060") || part.stripLeading().startsWith("~~~")) fenced=!fenced;
            if (!fenced && part.matches("^#{1,6}\\s+.+")) {
                if (!section.isEmpty()) sections.add(new DocumentContent.Section(section.toString(),Map.of("heading",heading,"startLine",firstLine,"endLine",line-1)));
                section.setLength(0); heading=part.replaceFirst("^#+\\s+",""); firstLine=line;
            }
            section.append(part).append('\n');
        }
        if (!section.isEmpty()) sections.add(new DocumentContent.Section(section.toString(),Map.of("heading",heading,"startLine",firstLine,"endLine",line)));
        return new DocumentContent(text,List.copyOf(sections));
    }

    private void append(StringBuilder text,String part) { checkLength((long)text.length()+part.length()); text.append(part); }
    private String decodeText(byte[] bytes,String filename,String contentType) throws Exception {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF",""); }
        catch (java.nio.charset.CharacterCodingException ex) { return tika.extract(bytes,filename,contentType); }
    }
    private void checkLength(long length) {
        if (length>properties.getParsing().getMaxTextChars()) throw new IllegalArgumentException("解析文本超过容量上限，请拆分文档；未发布截断内容");
    }
}
