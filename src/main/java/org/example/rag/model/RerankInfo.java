package org.example.rag.model;

public record RerankInfo(String status,String model,String configVersion,int scoredCount,long millis,String message) { }
