package org.example.rag.controller;

import org.example.rag.model.RerankSettings;
import org.example.rag.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 沿用 /api 管理接口的认证、CSRF 与白名单。 */
@RestController
@RequestMapping("/api/rerank")
public class RerankController {
    private final RerankSettingsService settings;
    private final RerankClient client;
    public RerankController(RerankSettingsService settings,RerankClient client) { this.settings=settings; this.client=client; }
    @GetMapping("/config") public Map<String,Object> get() { return ok(settings.current()); }
    @PostMapping("/config") public Map<String,Object> save(@RequestBody SaveRequest request) { return ok(settings.save(request.settings(),request.expectedVersion())); }
    @PostMapping("/probe") public Map<String,Object> probe(@RequestBody RerankSettings request) { return ok(client.probe(request)); }
    @PostMapping("/models") public Map<String,Object> models(@RequestBody RerankSettings request) { return ok(client.models(request)); }
    private Map<String,Object> ok(Object data) { return Map.of("success",true,"data",data); }
    public record SaveRequest(RerankSettings settings,String expectedVersion) { }
}
