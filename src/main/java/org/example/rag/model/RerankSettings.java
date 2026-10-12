package org.example.rag.model;

import java.net.URI;

/** 独立于 embedding 的运行时配置，修改后无需重建向量。 */
public record RerankSettings(boolean enabled, String baseUrl, String modelName, int candidateCount,
                             int timeoutMillis, int maxDocumentChars, int maxPerFile, String instruction) {
    public static RerankSettings defaults() {
        return new RerankSettings(false,"http://localhost:7998","Qwen/Qwen3-Reranker-4B",32,10000,4000,2,
                "Given a question, retrieve relevant passages that answer the question.");
    }
    public RerankSettings {
        if (baseUrl==null || baseUrl.isBlank() || baseUrl.length()>500) throw new IllegalArgumentException("请填写重排服务地址");
        baseUrl=baseUrl.trim().replaceAll("/+$","");
        URI uri;
        try { uri=URI.create(baseUrl); } catch (Exception ex) { throw new IllegalArgumentException("重排服务地址不正确"); }
        if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("重排地址应为 HTTP(S) 服务根地址，不含账号、查询或片段");
        if (modelName==null || modelName.isBlank() || modelName.length()>200) throw new IllegalArgumentException("请填写重排模型名");
        modelName=modelName.trim();
        if (candidateCount<5 || candidateCount>64) throw new IllegalArgumentException("重排候选数应在 5～64 之间");
        if (timeoutMillis<100 || timeoutMillis>60000) throw new IllegalArgumentException("重排超时应在 100～60000 毫秒之间");
        if (maxDocumentChars<256 || maxDocumentChars>12000) throw new IllegalArgumentException("重排单篇长度应在 256～12000 字符之间");
        if (maxPerFile<0 || maxPerFile>10) throw new IllegalArgumentException("单文件优先片段数应在 0～10 之间（0 表示不限）");
        instruction=instruction==null?"":instruction.trim();
        if (instruction.length()>1000) throw new IllegalArgumentException("重排指令最多 1000 字符");
    }
}
