package com.harsh.hospitalmanagement.storage;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lightweight, zero-dependency S3-compatible HTTP client implementing AWS Signature Version 4.
 * Operates portably on any Java 21 runtime over standard java.net.http.HttpClient.
 */
public final class HttpS3Client implements S3Client {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter ISO_BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final URI endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpS3Client(String endpoint, String region, String accessKey, String secretKey) {
        this(endpoint, region, accessKey, secretKey,
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpS3Client(String endpoint, String region, String accessKey, String secretKey, HttpClient httpClient) {
        this(endpoint, region, accessKey, secretKey, httpClient, DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpS3Client(String endpoint, String region, String accessKey, String secretKey,
                        HttpClient httpClient, Duration requestTimeout) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("S3 endpoint must not be blank");
        }
        String cleanEndpoint = endpoint.trim().replaceAll("/+$", "");
        this.endpoint = URI.create(cleanEndpoint);
        this.region = (region != null && !region.isBlank()) ? region.trim() : "us-east-1";
        this.accessKey = accessKey != null ? accessKey.trim() : "";
        this.secretKey = secretKey != null ? secretKey.trim() : "";
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.requestTimeout = (requestTimeout != null && !requestTimeout.isNegative() && !requestTimeout.isZero())
                ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    @Override
    public void putObject(String bucket, String key, byte[] content, String contentType, boolean ifNoneMatch) throws IOException {
        URI uri = buildObjectUri(bucket, key, null);
        String sha256 = hexSha256(content);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .header("Content-Type", (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream");

        if (ifNoneMatch) {
            builder.header("If-None-Match", "*");
        }

        HttpRequest request = signRequest(builder, "PUT", uri, content, sha256);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 412) {
                throw new FileAlreadyExistsException("Object already exists at S3 key: " + key);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("S3 PutObject failed with HTTP " + status + ": " + sanitize(response.body()));
            }
        } catch (HttpTimeoutException e) {
            // Reconcile ambiguous PUT timeout using HEAD before retrying or failing
            HeadResponse head;
            try {
                head = executeHead(bucket, key);
            } catch (Exception headEx) {
                throw new IOException("S3 PutObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Subsequent HEAD reconciliation failed: " + headEx.getMessage()
                        + ". The remote write state is ambiguous.", e);
            }

            if (head.statusCode() == 404) {
                throw new IOException("S3 PutObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Reconciliation confirmed object does not exist on remote.", e);
            } else if (head.statusCode() == 200) {
                String expectedMd5 = hexMd5(content);
                boolean sizeMatches = head.contentLength() == -1 || head.contentLength() == content.length;
                boolean etagMatches = head.eTag() != null && (
                        head.eTag().equalsIgnoreCase(expectedMd5) || head.eTag().equalsIgnoreCase(sha256)
                );

                if (sizeMatches && etagMatches) {
                    // Object identity and checksum verified: upload succeeded on remote
                    return;
                }
                throw new IOException("S3 PutObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Remote object exists but verification failed (sizeMatches="
                        + sizeMatches + ", etagMatches=" + etagMatches + "). The remote write state is ambiguous.", e);
            } else {
                throw new IOException("S3 PutObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Subsequent HEAD returned HTTP " + head.statusCode()
                        + ". The remote write state is ambiguous.", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 PutObject was interrupted at key: " + key, e);
        }
    }

    @Override
    public byte[] getObject(String bucket, String key) throws IOException {
        URI uri = buildObjectUri(bucket, key, null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET();
        HttpRequest request = signRequest(builder, "GET", uri, new byte[0], EMPTY_BODY_SHA256);
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 404) {
                throw new NoSuchFileException("Object not found in S3: " + key);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("S3 GetObject failed with HTTP " + status);
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new IOException("S3 GetObject timed out after " + requestTimeout.toSeconds()
                    + "s at key: " + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 GetObject was interrupted at key: " + key, e);
        }
    }

    record HeadResponse(int statusCode, long contentLength, String eTag) {}

    private HeadResponse executeHead(String bucket, String key) throws IOException {
        URI uri = buildObjectUri(bucket, key, null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        HttpRequest request = signRequest(builder, "HEAD", uri, new byte[0], EMPTY_BODY_SHA256);
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            long len = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            String etag = response.headers().firstValue("ETag").orElse(null);
            if (etag != null) {
                etag = etag.replaceAll("^\"|\"$", "").trim();
            }
            return new HeadResponse(status, len, etag);
        } catch (HttpTimeoutException e) {
            throw new IOException("S3 HeadObject timed out after " + requestTimeout.toSeconds()
                    + "s at key: " + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 HeadObject was interrupted at key: " + key, e);
        }
    }

    @Override
    public boolean headObject(String bucket, String key) throws IOException {
        HeadResponse res = executeHead(bucket, key);
        if (res.statusCode() == 200) return true;
        if (res.statusCode() == 404) return false;
        throw new IOException("S3 HeadObject failed with HTTP " + res.statusCode());
    }

    @Override
    public boolean deleteObject(String bucket, String key) throws IOException {
        URI uri = buildObjectUri(bucket, key, null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .DELETE();
        HttpRequest request = signRequest(builder, "DELETE", uri, new byte[0], EMPTY_BODY_SHA256);
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 200 || status == 204 || status == 404) {
                return status != 404;
            }
            throw new IOException("S3 DeleteObject failed with HTTP " + status);
        } catch (HttpTimeoutException e) {
            // Reconcile ambiguous DELETE timeout using HEAD before reporting success
            HeadResponse head;
            try {
                head = executeHead(bucket, key);
            } catch (Exception headEx) {
                throw new IOException("S3 DeleteObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Subsequent HEAD reconciliation failed: " + headEx.getMessage()
                        + ". The remote delete state is ambiguous.", e);
            }

            if (head.statusCode() == 404) {
                // Confirmed absent on remote: delete succeeded
                return true;
            } else if (head.statusCode() == 200) {
                throw new IOException("S3 DeleteObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Reconciliation confirmed object still exists on remote.", e);
            } else {
                throw new IOException("S3 DeleteObject timed out after " + requestTimeout.toSeconds()
                        + "s at key: " + key + ". Subsequent HEAD returned HTTP " + head.statusCode()
                        + ". The remote delete state is ambiguous.", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 DeleteObject was interrupted at key: " + key, e);
        }
    }

    @Override
    public S3ListResult listObjects(String bucket, String prefix, String continuationToken, int maxKeys) throws IOException {
        StringBuilder query = new StringBuilder("list-type=2");
        if (prefix != null && !prefix.isBlank()) {
            query.append("&prefix=").append(urlEncode(prefix));
        }
        if (continuationToken != null && !continuationToken.isBlank()) {
            query.append("&continuation-token=").append(urlEncode(continuationToken));
        }
        if (maxKeys > 0) {
            query.append("&max-keys=").append(maxKeys);
        }

        URI uri = buildObjectUri(bucket, null, query.toString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET();
        HttpRequest request = signRequest(builder, "GET", uri, new byte[0], EMPTY_BODY_SHA256);
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                throw new IOException("S3 ListObjects failed with HTTP " + status);
            }
            return parseListObjectsResponse(response.body());
        } catch (HttpTimeoutException e) {
            throw new IOException("S3 ListObjects timed out after " + requestTimeout.toSeconds() + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 ListObjects was interrupted", e);
        }
    }

    private URI buildObjectUri(String bucket, String key, String query) {
        StringBuilder path = new StringBuilder();
        if (!endpoint.getPath().isEmpty() && !"/".equals(endpoint.getPath())) {
            path.append(endpoint.getPath());
        }
        path.append("/").append(bucket);
        if (key != null && !key.isBlank()) {
            String cleanKey = key.startsWith("/") ? key.substring(1) : key;
            path.append("/").append(cleanKey);
        }
        String queryString = (query != null && !query.isBlank()) ? "?" + query : "";
        return endpoint.resolve(path.toString() + queryString);
    }

    /**
     * Derives the Host header matching standard HTTP client wire transmission.
     * Standard ports (80 for http, 443 for https) omit the port suffix.
     */
    public static String deriveHostHeader(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        String scheme = uri.getScheme();
        boolean isDefaultPort = (port == -1)
                || ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return isDefaultPort ? host : host + ":" + port;
    }

    private HttpRequest signRequest(HttpRequest.Builder builder, String method, URI uri, byte[] body, String contentSha256) throws IOException {
        Instant now = Instant.now();
        String amzDate = ISO_BASIC.format(now);
        String dateStamp = DATE_ONLY.format(now);

        builder.header("x-amz-date", amzDate);
        builder.header("x-amz-content-sha256", contentSha256);

        // If credentials are empty (e.g. public emulator), build request without authorization
        if (accessKey.isBlank() || secretKey.isBlank()) {
            return builder.build();
        }

        // Canonical Host matching Java HttpClient wire derivation.
        // DO NOT invoke builder.header("Host", hostHeader) directly, as "Host" is in Java 21's
        // restricted-headers set. Java HttpClient automatically derives the Host header from the URI.
        String hostHeader = deriveHostHeader(uri);

        // Canonical Headers
        Map<String, String> headersToSign = new TreeMap<>();
        headersToSign.put("host", hostHeader);
        headersToSign.put("x-amz-content-sha256", contentSha256);
        headersToSign.put("x-amz-date", amzDate);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> entry : headersToSign.entrySet()) {
            canonicalHeaders.append(entry.getKey()).append(":").append(entry.getValue().trim()).append("\n");
            if (!signedHeaders.isEmpty()) signedHeaders.append(";");
            signedHeaders.append(entry.getKey());
        }

        String canonicalUri = uri.getRawPath();
        String canonicalQuery = uri.getRawQuery() != null ? canonicalizeQuery(uri.getRawQuery()) : "";

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + contentSha256;

        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hexSha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
        String signature;
        try {
            signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to calculate AWS SigV4 signature", e);
        }

        String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        builder.header("Authorization", authHeader);
        return builder.build();
    }

    private static String canonicalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        String[] pairs = rawQuery.split("&");
        Map<String, String> sorted = new TreeMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                sorted.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                sorted.put(pair, "");
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private static S3ListResult parseListObjectsResponse(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));
            doc.getDocumentElement().normalize();

            List<S3ObjectEntry> objects = new ArrayList<>();
            NodeList contentsList = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contentsList.getLength(); i++) {
                Element contentEl = (Element) contentsList.item(i);
                String key = getTagText(contentEl, "Key");
                String lastModStr = getTagText(contentEl, "LastModified");
                String sizeStr = getTagText(contentEl, "Size");

                Instant lastMod = Instant.now();
                if (lastModStr != null && !lastModStr.isBlank()) {
                    try { lastMod = Instant.parse(lastModStr); } catch (Exception ignored) {}
                }
                long size = 0;
                if (sizeStr != null && !sizeStr.isBlank()) {
                    try { size = Long.parseLong(sizeStr); } catch (Exception ignored) {}
                }
                if (key != null && !key.isBlank()) {
                    objects.add(new S3ObjectEntry(key, lastMod, size));
                }
            }

            String nextContinuation = getTagText(doc.getDocumentElement(), "NextContinuationToken");
            String isTruncatedStr = getTagText(doc.getDocumentElement(), "IsTruncated");
            boolean isTruncated = "true".equalsIgnoreCase(isTruncatedStr);

            return new S3ListResult(List.copyOf(objects), nextContinuation, isTruncated);
        } catch (Exception e) {
            throw new IOException("Failed to parse S3 ListObjects XML response", e);
        }
    }

    private static String getTagText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0 && list.item(0).getFirstChild() != null) {
            return list.item(0).getFirstChild().getNodeValue();
        }
        return null;
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) throws IOException {
        try {
            byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
            byte[] kDate = hmacSha256(kSecret, dateStamp);
            byte[] kRegion = hmacSha256(kDate, regionName);
            byte[] kService = hmacSha256(kRegion, serviceName);
            return hmacSha256(kService, "aws4_request");
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to generate AWS SigV4 signing key", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexSha256(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static String hexMd5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\r\n\t]+", " ").trim();
    }
}
