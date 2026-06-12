package com.acxiom.emailaudit.exceptions;

public class EmailAuditException extends RuntimeException {

    public EmailAuditException(String message) {
        super(message);
    }

    public EmailAuditException(String message, Throwable cause) {
        super(message, cause);
    }
}