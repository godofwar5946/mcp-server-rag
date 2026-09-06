package org.example.rag.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：返回统一 JSON 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "资源不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,
                "上传内容超过服务端大小限制：单文件最大 50MB，单次请求最大 100MB");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        if (hasCause(ex, "FileCountLimitExceededException")) {
            log.warn("单次上传的 multipart part 数量超过 Tomcat 限制");
            return error(HttpStatus.PAYLOAD_TOO_LARGE,
                    "单次上传的文件数量过多，请使用管理页面分批上传");
        }
        log.warn("Multipart 请求解析失败", ex);
        return error(HttpStatus.BAD_REQUEST, "上传请求解析失败：" + rootMessage(ex));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<Map<String,Object>> handleModelUnavailable(EmbeddingUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,ex.getMessage());
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String,Object>> handleInvalidInput(Exception ex) {
        return error(HttpStatus.BAD_REQUEST,"请求参数格式不正确，请检查输入后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        log.error("请求处理异常", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "服务处理失败，请查看服务日志后重试");
        body.put("requestId", org.slf4j.MDC.get("requestId"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private boolean hasCause(Throwable throwable, String simpleClassName) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getClass().getSimpleName().equals(simpleClassName)) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }
}
