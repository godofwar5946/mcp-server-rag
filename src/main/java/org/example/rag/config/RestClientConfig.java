package org.example.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient 配置，用于调用 Ollama Embedding 接口。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient ollamaRestClient(AppProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        int connectTimeout = (int) Duration.ofSeconds(properties.getOllama().getConnectTimeoutSeconds()).toMillis();
        int readTimeout = (int) Duration.ofSeconds(properties.getOllama().getReadTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.getOllama().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
