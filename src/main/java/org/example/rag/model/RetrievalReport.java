package org.example.rag.model;

import java.util.List;

/** 检索过程可审查，分数是相关性指标，不是答案正确率。 */
public record RetrievalReport(String query,String mode,String model,int candidateCount,
                              long embeddingMillis,long retrievalMillis,long totalMillis,
                              String context,boolean contextTruncated,List<Hit> hits) {
    public record Hit(RagSearchResult result,double rankScore,List<String> channels) { }
}
