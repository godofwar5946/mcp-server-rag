package org.example.rag.model;

import org.example.rag.config.AppProperties;

/** 一次检索或索引任务固定使用同一份不可变模型配置。id 区分同维度的不同向量空间。 */
public record EmbeddingModel(long id, String modelName, String modelDigest, int dimension,
                             Integer outputDimension, String queryInstruction) {
    public static EmbeddingModel configured(AppProperties properties) {
        Integer dimension=properties.getEmbedding().getDimension();
        return new EmbeddingModel(0,properties.getOllama().getModelName(),null,
                dimension==null?0:dimension,null,"");
    }
    public String identity() { return id+"|"+modelName+"|"+modelDigest+"|"+dimension+"|"+outputDimension+"|"+queryInstruction; }
    public String queryText(String text) {
        return queryInstruction==null || queryInstruction.isBlank()?text:queryInstruction+"\n"+text;
    }
}
