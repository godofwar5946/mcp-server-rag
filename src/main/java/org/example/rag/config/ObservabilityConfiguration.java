package org.example.rag.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class ObservabilityConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> ragMetrics() {
        return registry -> {
            Timer.builder("rag.search.duration").publishPercentiles(0.5,0.95,0.99).register(registry);
            Timer.builder("rag.embedding.duration").publishPercentiles(0.5,0.95,0.99).register(registry);
        };
    }
}
