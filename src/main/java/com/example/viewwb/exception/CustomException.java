package com.example.viewwb.exception;

public class CustomException extends RuntimeException {

    private final Integer errorCode;

    public Integer getErrorCode() {
        return errorCode;
    }

    public CustomException(String message, Integer errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public CustomException(String message, Throwable cause, Integer errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
