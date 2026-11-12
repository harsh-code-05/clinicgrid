package com.harsh.hospitalmanagement.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public final class SensitiveResponseHeaders {

    public static final String CACHE_CONTROL_NO_STORE = "no-store";
    public static final String PRAGMA_NO_CACHE = "no-cache";
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String NOSNIFF = "nosniff";

    private SensitiveResponseHeaders() {
    }

    public static void apply(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, PRAGMA_NO_CACHE);
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
    }

    public static <B extends ResponseEntity.HeadersBuilder<?>> B apply(B builder) {
        builder
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .header(HttpHeaders.PRAGMA, PRAGMA_NO_CACHE)
                .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        return builder;
    }
}
