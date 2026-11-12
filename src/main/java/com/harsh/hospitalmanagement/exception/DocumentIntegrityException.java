package com.harsh.hospitalmanagement.exception;

public class DocumentIntegrityException extends IllegalStateException {

    public DocumentIntegrityException(String message) {
        super(message);
    }

    public DocumentIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
