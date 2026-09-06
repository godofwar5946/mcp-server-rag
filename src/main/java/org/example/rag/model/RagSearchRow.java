package org.example.rag.model;

/**
 * 向量检索原始结果（包含距离）。
 */
public record RagSearchRow(
        long fileId,
        String fileName,
        String knowledgeType,
        int chunkIndex,
        Long codeSymbolId,
        Integer startLine,
        Integer endLine,
        String symbolType,
        String qualifiedName,
        String content,
        double distance,
        String metadataJson,
        long revision
) {
    public RagSearchRow(long fileId,String fileName,String knowledgeType,int chunkIndex,Long codeSymbolId,
                        Integer startLine,Integer endLine,String symbolType,String qualifiedName,String content,double distance) {
        this(fileId,fileName,knowledgeType,chunkIndex,codeSymbolId,startLine,endLine,symbolType,qualifiedName,content,distance,"{}",0);
    }
}
