package org.example.rag.service;

import org.example.rag.config.AppProperties;
import org.example.rag.mapper.MaintenanceMapper;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="app.indexing",name="enabled",havingValue="true",matchIfMissing=true)
public class MaintenanceService {
    private final MaintenanceMapper mapper;
    private final AppProperties properties;
    private volatile boolean ready;
    public MaintenanceService(MaintenanceMapper mapper,AppProperties properties) { this.mapper=mapper; this.properties=properties; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready=true; }
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void prune() {
        if (!ready) return;
        int days=properties.getIndexing().getRetentionDays();
        if (days<=0) return;
        int jobs=mapper.pruneJobs(days),runs=mapper.pruneEvaluations(days);
        if (jobs+runs>0) LoggerFactory.getLogger(getClass()).info("清理过期运行记录: jobs={},evaluations={}",jobs,runs);
    }
}
