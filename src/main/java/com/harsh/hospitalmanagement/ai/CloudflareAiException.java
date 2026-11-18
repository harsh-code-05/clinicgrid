package com.harsh.hospitalmanagement.ai;

/**
 * Exception thrown when a Cloudflare Workers AI call fails.
 */
public class CloudflareAiException extends RuntimeException {

    public enum Reason {
        AUTH_FAILURE,
        QUOTA_EXCEEDED,
        SERVER_ERROR,
        TIMEOUT,
        CONCURRENCY_LIMIT,
        CLIENT_ERROR,
        MALFORMED_RESPONSE
    }

    private final Reason reason;
    private final int statusCode;

    public CloudflareAiException(Reason reason, String message, int statusCode) {
        super(message);
        this.reason = reason;
        this.statusCode = statusCode;
    }

    public CloudflareAiException(Reason reason, String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.statusCode = statusCode;
    }

    public Reason getReason() {
        return reason;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
