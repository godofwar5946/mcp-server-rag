package org.example.rag.exception;

public class EmbeddingUnavailableException extends IllegalStateException {
    public EmbeddingUnavailableException(String message) { super(message); }
    public EmbeddingUnavailableException(String message,Throwable cause) { super(message,cause); }
}
