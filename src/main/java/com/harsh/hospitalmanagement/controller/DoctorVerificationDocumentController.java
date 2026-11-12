package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SensitiveResponseHeaders;
import com.harsh.hospitalmanagement.config.VerificationDocumentUploadRoute;
import com.harsh.hospitalmanagement.dto.DoctorVerificationDocumentResponse;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import com.harsh.hospitalmanagement.exception.DocumentUploadException;
import com.harsh.hospitalmanagement.service.DoctorVerificationDocumentService;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.util.WebUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(VerificationDocumentUploadRoute.BASE_PATH)
public class DoctorVerificationDocumentController {

    private static final Logger log = LoggerFactory.getLogger(DoctorVerificationDocumentController.class);

    private final DoctorVerificationDocumentService documentService;
    private final VerificationImageValidator imageValidator;

    public DoctorVerificationDocumentController(
            DoctorVerificationDocumentService documentService,
            @Autowired(required = false) VerificationImageValidator imageValidator
    ) {
        this.documentService = documentService;
        this.imageValidator = imageValidator != null ? imageValidator : new VerificationImageValidator();
    }

    @PostMapping(value = VerificationDocumentUploadRoute.KIND_SUBPATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DoctorVerificationDocumentResponse> uploadDocument(
            @PathVariable String doctorId,
            @PathVariable String kind,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletRequest rawRequest,
            Authentication authentication
    ) throws MissingServletRequestPartException {
        long validDoctorId = VerificationDocumentUploadRoute.parsePositiveDoctorId(doctorId);
        VerificationDocumentKind docKind = VerificationDocumentUploadRoute.parseKind(kind);

        // Enforce ownership and draft status BEFORE touching lazy multipart parts
        documentService.verifyUploadEligible(validDoctorId, authentication);

        if (!documentService.isStorageAvailable()) {
            throw new DocumentStorageUnavailableException("Document storage service is temporarily unavailable");
        }

        MultipartHttpServletRequest multipartRequest =
                WebUtils.getNativeRequest(rawRequest, MultipartHttpServletRequest.class);
        if (multipartRequest == null) {
            throw new MissingServletRequestPartException("file");
        }

        long totalSize = multipartRequest.getMultiFileMap().values().stream()
                .flatMap(List::stream)
                .mapToLong(MultipartFile::getSize)
                .sum();
        if (totalSize > VerificationImageValidator.MAX_REQUEST_BYTES) {
            throw new MaxUploadSizeExceededException(VerificationImageValidator.MAX_REQUEST_BYTES);
        }

        MultipartFile file = multipartRequest.getFile("file");
        if (file == null) {
            throw new MissingServletRequestPartException("file");
        }
        if (file.getSize() > VerificationImageValidator.MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(VerificationImageValidator.MAX_FILE_BYTES);
        }
        if (file.isEmpty()) {
            throw new BadRequestException("Verification document file must not be empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equalsIgnoreCase("image/png") && !contentType.equalsIgnoreCase("image/jpeg"))) {
            throw new BadRequestException("Only PNG and JPEG images are supported for verification documents");
        }

        String safeCorrelationId = DoctorVerificationDocumentService.sanitizeCorrelationId(correlationId);
        VerificationImageValidator.ValidatedImage validatedImage;
        StreamIoCapturingInputStream capturingInput = null;
        try (InputStream input = file.getInputStream()) {
            capturingInput = new StreamIoCapturingInputStream(input);
            validatedImage = imageValidator.validate(contentType, capturingInput);
        } catch (VerificationImageValidator.InvalidVerificationImageException e) {
            if (capturingInput != null && capturingInput.failure() != null) {
                logUploadFailure(validDoctorId, docKind, safeCorrelationId, capturingInput.failure());
                throw new DocumentUploadException(
                        "Verification upload could not be processed. Please try again.", capturingInput.failure());
            }
            if (e.reason() == VerificationImageValidator.InvalidVerificationImageException.Reason.QUEUE_FULL
                    || e.reason() == VerificationImageValidator.InvalidVerificationImageException.Reason.TIMED_OUT
                    || e.reason() == VerificationImageValidator.InvalidVerificationImageException.Reason.INTERRUPTED) {
                logUploadFailure(validDoctorId, docKind, safeCorrelationId, e);
            }
            throw translateImageValidationFailure(e);
        } catch (IOException e) {
            logUploadFailure(validDoctorId, docKind, safeCorrelationId, e);
            throw new DocumentUploadException("Verification upload could not be processed. Please try again.", e);
        }

        DoctorVerificationDocument document;
        try {
            document = documentService.attachDocument(
                    validDoctorId, docKind, validatedImage, authentication, safeCorrelationId);
        } catch (IllegalStateException e) {
            logUploadFailure(validDoctorId, docKind, safeCorrelationId, e);
            throw new DocumentUploadException("Verification upload could not be processed. Please try again.", e);
        }

        return SensitiveResponseHeaders.apply(ResponseEntity.status(HttpStatus.CREATED))
                .body(DoctorVerificationDocumentResponse.from(document));
    }

    @DeleteMapping(VerificationDocumentUploadRoute.KIND_SUBPATH)
    public ResponseEntity<Void> detachDocument(
            @PathVariable String doctorId,
            @PathVariable String kind,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            Authentication authentication
    ) {
        long validDoctorId = VerificationDocumentUploadRoute.parsePositiveDoctorId(doctorId);
        VerificationDocumentKind docKind = VerificationDocumentUploadRoute.parseKind(kind);

        documentService.detachDocument(validDoctorId, docKind, authentication, correlationId);
        return SensitiveResponseHeaders.apply(ResponseEntity.noContent()).build();
    }

    @GetMapping(VerificationDocumentUploadRoute.KIND_SUBPATH)
    public ResponseEntity<byte[]> readDocument(
            @PathVariable String doctorId,
            @PathVariable String kind,
            @RequestParam(value = "reviewVersion", required = false) String reviewVersion,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            Authentication authentication
    ) {
        long validDoctorId = VerificationDocumentUploadRoute.parsePositiveDoctorId(doctorId);
        VerificationDocumentKind docKind = VerificationDocumentUploadRoute.parseKind(kind);

        DocumentStorage.ReadDocument readDoc = documentService.readDocument(
                validDoctorId, docKind, reviewVersion, authentication, correlationId);

        String ext = readDoc.contentType().contains("png") ? "png" : "jpg";
        String filename = String.format("doctor-%d-%s.%s", validDoctorId, docKind.pathSegment(), ext);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(readDoc.contentType()));
        headers.setContentLength(readDoc.bytes().length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private, max-age=0");
        headers.setPragma("no-cache");

        return ResponseEntity.ok().headers(headers).body(readDoc.bytes());
    }

    @GetMapping
    public ResponseEntity<List<DoctorVerificationDocumentResponse>> getActiveDocuments(
            @PathVariable String doctorId,
            Authentication authentication
    ) {
        long validDoctorId = VerificationDocumentUploadRoute.parsePositiveDoctorId(doctorId);
        List<DoctorVerificationDocumentResponse> responses = documentService
                .getActiveDocuments(validDoctorId, authentication).stream()
                .map(DoctorVerificationDocumentResponse::from)
                .toList();
        return SensitiveResponseHeaders.apply(ResponseEntity.ok()).body(responses);
    }

    @GetMapping(VerificationDocumentUploadRoute.KIND_SUBPATH + "/history")
    public ResponseEntity<List<DoctorVerificationDocumentResponse>> getDocumentHistory(
            @PathVariable String doctorId,
            @PathVariable String kind,
            Authentication authentication
    ) {
        long validDoctorId = VerificationDocumentUploadRoute.parsePositiveDoctorId(doctorId);
        VerificationDocumentKind docKind = VerificationDocumentUploadRoute.parseKind(kind);

        List<DoctorVerificationDocumentResponse> responses = documentService
                .getDocumentHistory(validDoctorId, docKind, authentication).stream()
                .map(DoctorVerificationDocumentResponse::from)
                .toList();
        return SensitiveResponseHeaders.apply(ResponseEntity.ok()).body(responses);
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, Object>> badRequest(BadRequestException ex) {
        return SensitiveResponseHeaders.apply(ResponseEntity.badRequest())
                .body(Map.of("status", 400, "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> invalidDoctorId() {
        return SensitiveResponseHeaders.apply(ResponseEntity.badRequest())
                .body(Map.of("status", 400, "message", "Doctor ID must be a positive integer"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, Object>> missingPart() {
        return SensitiveResponseHeaders.apply(ResponseEntity.badRequest())
                .body(Map.of("status", 400, "message", "Required verification file part is missing"));
    }

    private static RuntimeException translateImageValidationFailure(
            VerificationImageValidator.InvalidVerificationImageException e) {
        return switch (e.reason()) {
            case FILE_TOO_LARGE, REQUEST_TOO_LARGE ->
                    new MaxUploadSizeExceededException(VerificationImageValidator.MAX_FILE_BYTES);
            case UNSUPPORTED_TYPE ->
                    new BadRequestException("Only PNG and JPEG images are supported for verification documents");
            case EXCESSIVE_DIMENSIONS ->
                    new BadRequestException("Invalid verification document image: Image dimensions exceed the allowed limit");
            case CORRUPT_IMAGE ->
                    new BadRequestException("Invalid verification document image: Uploaded file is not a valid JPEG or PNG image");
            case QUEUE_FULL, TIMED_OUT, INTERRUPTED ->
                    new BadRequestException("Invalid verification document image: Image validation could not be completed. Please try again.");
        };
    }

    private static void logUploadFailure(
            long doctorId,
            VerificationDocumentKind kind,
            String safeCorrelationId,
            Throwable failure) {
        log.warn(
                "Verification document upload processing failed: doctorId={}, kind={}, correlationId={}, exceptionClass={}",
                doctorId,
                kind.name(),
                safeCorrelationId,
                failure.getClass().getName()
        );
    }

    private static final class StreamIoCapturingInputStream extends FilterInputStream {
        private IOException failure;

        private StreamIoCapturingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException ex) {
                failure = ex;
                throw ex;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException ex) {
                failure = ex;
                throw ex;
            }
        }

        @Override
        public byte[] readNBytes(int len) throws IOException {
            try {
                return super.readNBytes(len);
            } catch (IOException ex) {
                failure = ex;
                throw ex;
            }
        }

        IOException failure() {
            return failure;
        }
    }
}
