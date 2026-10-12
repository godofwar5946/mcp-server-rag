package org.example.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.mapper.RerankSettingsMapper;
import org.example.rag.model.RerankSettings;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RerankSettingsService {
    private final RerankSettingsMapper mapper;
    private final ObjectMapper json;
    private final RerankClient client;
    public RerankSettingsService(RerankSettingsMapper mapper,ObjectMapper json,RerankClient client) {
        this.mapper=mapper; this.json=json; this.client=client;
    }
    public record Snapshot(String version,RerankSettings settings) { }
    public Snapshot current() { return parse(mapper.read()); }
    public Snapshot save(RerankSettings settings,String expectedVersion) {
        Objects.requireNonNull(settings,"重排配置不能为空");
        String original=mapper.read();
        if (!Objects.equals(parse(original).version(),expectedVersion)) throw conflict();
        // 网络验证不持有数据库事务或锁；启用时服务端重新验证，不能仅信任前端标记。
        if (settings.enabled()) client.probe(settings);
        var next=new Snapshot(UUID.randomUUID().toString(),settings);
        try { if (mapper.compareAndSet(json.writeValueAsString(next),original)!=1) throw conflict(); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("重排配置保存失败",ex); }
        return next;
    }
    private Snapshot parse(String raw) {
        if (raw==null) return new Snapshot("initial",RerankSettings.defaults());
        try { return json.readValue(raw,Snapshot.class); }
        catch (Exception ex) { throw new IllegalStateException("已保存的重排配置无效",ex); }
    }
    private IllegalStateException conflict() { return new IllegalStateException("重排配置已被其他操作更新，请重新加载后保存"); }
}
