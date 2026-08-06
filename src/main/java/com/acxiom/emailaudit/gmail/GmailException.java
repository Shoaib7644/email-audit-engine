package com.acxiom.emailaudit.gmail;

/**
 * Runtime exception for Gmail ingestion failures.
 */
public final class GmailException extends RuntimeException {

    public GmailException(final String message) {
        super(message);
    }

    public GmailException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
