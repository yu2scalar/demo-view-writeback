package com.example.viewwb.api;

import com.example.viewwb.dto.ApiResponse;
import com.example.viewwb.exception.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Invalid request body: " + ex.getMessage()));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException ex) {
        HttpStatus status = switch (ex.getErrorCode() == null ? 500 : ex.getErrorCode()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * Error 系(NoClassDefFoundError 等)も ApiResponse に包む。素通しすると Spring 素の
     * 500 ボディになり GUI がメッセージを表示できない(2026-07-18 リフレッシュ障害の教訓)。
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleThrowable(Throwable ex) {
        String message;
        if (causeChainContains(ex, "MemoryUtil")) {
            message = "spark-connect の結果取得に失敗しました。JVM オプション "
                    + "--add-opens=java.base/java.nio=ALL-UNNAMED を付けて起動してください"
                    + "(./gradlew bootRun なら設定済み)";
        } else {
            message = "サーバー内部エラー: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, message));
    }

    private boolean causeChainContains(Throwable ex, String keyword) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
