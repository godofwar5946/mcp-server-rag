package org.example.rag.service;

import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.model.RagSearchRow;
import org.example.rag.model.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;

/** 模型请求结束后才进入短只读事务；两路召回读取同一数据库快照。 */
@Service
public class RagRetrievalStore {
    private final RagChunkMapper mapper;
    public RagRetrievalStore(RagChunkMapper mapper) { this.mapper=mapper; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Candidates retrieve(String query,String vector,int count,String type,Long folderId,boolean codeOnly,String mode,EmbeddingModel model) {
        mapper.configureSearch(Integer.toString(Math.max(80,count*2)));
        List<RagSearchRow> dense="keyword".equals(mode)?List.of():mapper.search(vector,count,type,folderId,codeOnly,model);
        String pattern="%"+query.toLowerCase(java.util.Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        List<RagSearchRow> lexical="vector".equals(mode)?List.of():mapper.searchLexical(query,pattern,vector,count,type,folderId,codeOnly,model);
        return new Candidates(dense,lexical);
    }

    public record Candidates(List<RagSearchRow> dense,List<RagSearchRow> lexical) { }
}
