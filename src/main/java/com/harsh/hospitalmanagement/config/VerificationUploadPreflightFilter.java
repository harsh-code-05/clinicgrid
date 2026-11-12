package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.MultipartStagingManager;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Enumeration;

@Component
@Order(0)
public class VerificationUploadPreflightFilter extends OncePerRequestFilter {

    private final DocumentStorage documentStorage;
    private final MultipartStagingManager stagingManager;

    public VerificationUploadPreflightFilter(
            @Autowired(required = false) DocumentStorage documentStorage,
            @Autowired(required = false) MultipartStagingManager stagingManager
    ) {
        this.documentStorage = documentStorage;
        this.stagingManager = stagingManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        MultipartStagingManager.StagingDirectoryPin stagingPin = null;
        if (VerificationDocumentUploadRoute.matchUpload(request).isPresent()) {
            long contentLength = resolveContentLength(request);
            if (contentLength > VerificationImageValidator.MAX_REQUEST_BYTES) {
                response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
                SensitiveResponseHeaders.apply(response);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(String.format(
                        "{\"timestamp\":\"%s\",\"status\":413,\"message\":\"Uploaded file exceeds the maximum allowed size\"}",
                        LocalDateTime.now()
                ));
                return;
            }

            if (documentStorage == null) {
                sendUnavailable(response, "Document storage service is temporarily unavailable");
                return;
            }
            if (stagingManager != null) {
                try {
                    stagingPin = stagingManager.validateAndPinStagingDirectory();
                } catch (Exception e) {
                    sendUnavailable(response, "Document storage service is temporarily unavailable");
                    return;
                }
            }
        }

        try (var activePin = stagingPin) {
            filterChain.doFilter(request, response);
        }
    }

    private static long resolveContentLength(HttpServletRequest request) {
        long resolved = request.getContentLengthLong();
        Enumeration<String> headers = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String raw = headers.nextElement();
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    long parsed = Long.parseLong(raw.strip());
                    if (parsed > resolved) {
                        resolved = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Let servlet container / multipart resolver reject invalid header syntax if applicable
                }
            }
        }
        return resolved;
    }

    private void sendUnavailable(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        SensitiveResponseHeaders.apply(response);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(
                "{\"timestamp\":\"%s\",\"status\":503,\"message\":\"%s\"}",
                LocalDateTime.now(),
                message
        ));
    }
}
