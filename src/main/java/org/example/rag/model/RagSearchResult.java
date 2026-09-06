package org.example.rag.model;

/**
 * 向量检索结果：包含文件名、chunk 索引、内容与相似度。
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RagSearchResult(
        long fileId,
        String fileName,
        String knowledgeType,
        int chunkIndex,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) Long codeSymbolId,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) Integer startLine,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) Integer endLine,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) String symbolType,
        @com.fasterxml.jackson.annotation.JsonProperty(required=false) String qualifiedName,
        String content,
        double similarity,
        String sourceUri,
        long revision,
        boolean truncated,
        java.util.Map<String,Object> metadata
) {
    public RagSearchResult(long fileId,String fileName,String knowledgeType,int chunkIndex,Long codeSymbolId,
                           Integer startLine,Integer endLine,String symbolType,String qualifiedName,String content,double similarity) {
        this(fileId,fileName,knowledgeType,chunkIndex,codeSymbolId,startLine,endLine,symbolType,qualifiedName,content,similarity,
                "rag://files/"+fileId+"/revisions/0/chunks/"+chunkIndex,0,false,java.util.Map.of());
    }
}
