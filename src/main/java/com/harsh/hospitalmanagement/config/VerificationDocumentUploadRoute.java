package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Locale;
import java.util.Optional;

public final class VerificationDocumentUploadRoute {

    public static final String BASE_PATH = "/api/doctors/{doctorId}/verification/documents";
    public static final String KIND_SUBPATH = "/{kind}";
    public static final String FULL_UPLOAD_PATH = BASE_PATH + KIND_SUBPATH;

    private static final PathPattern UPLOAD_PATTERN = PathPatternParser.defaultInstance.parse(FULL_UPLOAD_PATH);

    private VerificationDocumentUploadRoute() {
    }

    public static Optional<UploadRouteMatch> matchUpload(HttpServletRequest request) {
        if (request == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return Optional.empty();
        }

        RequestPath requestPath;
        if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
            requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
        } else {
            String requestUri = request.getRequestURI();
            if (requestUri == null) {
                return Optional.empty();
            }
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty()
                    && !requestUri.equals(contextPath) && !requestUri.startsWith(contextPath + "/")) {
                return Optional.empty();
            }
            requestPath = RequestPath.parse(requestUri, contextPath);
        }

        PathContainer withinApplication = requestPath.pathWithinApplication();
        PathPattern.PathMatchInfo matchInfo = UPLOAD_PATTERN.matchAndExtract(withinApplication);
        if (matchInfo == null) {
            return Optional.empty();
        }

        String rawDoctorId = matchInfo.getUriVariables().get("doctorId");
        String rawKind = matchInfo.getUriVariables().get("kind");

        Optional<Long> doctorId = tryParsePositiveDoctorId(rawDoctorId);
        Optional<VerificationDocumentKind> kind = tryParseKind(rawKind);
        if (doctorId.isEmpty() || kind.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new UploadRouteMatch(doctorId.get(), kind.get()));
    }

    public static Optional<Long> tryParsePositiveDoctorId(String rawDoctorId) {
        if (rawDoctorId == null || rawDoctorId.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < rawDoctorId.length(); i++) {
            char ch = rawDoctorId.charAt(i);
            if (ch < '0' || ch > '9') {
                return Optional.empty();
            }
        }
        try {
            long value = Long.parseLong(rawDoctorId);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static long parsePositiveDoctorId(String rawDoctorId) {
        return tryParsePositiveDoctorId(rawDoctorId)
                .orElseThrow(() -> new BadRequestException("Doctor ID must be a positive integer"));
    }

    public static Optional<VerificationDocumentKind> tryParseKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawKind.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replace('_', '-');
        for (VerificationDocumentKind docKind : VerificationDocumentKind.values()) {
            if (docKind.pathSegment().equals(normalized) || docKind.name().equalsIgnoreCase(trimmed)) {
                return Optional.of(docKind);
            }
        }
        return Optional.empty();
    }

    public static VerificationDocumentKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("Verification document kind is required");
        }
        return tryParseKind(kind)
                .orElseThrow(() -> new BadRequestException("Invalid verification document kind: " + kind));
    }

    public record UploadRouteMatch(long doctorId, VerificationDocumentKind kind) {
    }
}
