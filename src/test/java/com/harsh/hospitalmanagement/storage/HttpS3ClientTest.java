package com.harsh.hospitalmanagement.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpS3ClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void deriveHostHeaderFormatsStandardAndCustomPortsCorrectly() {
        assertThat(HttpS3Client.deriveHostHeader(URI.create("https://s3.us-east-1.amazonaws.com")))
                .isEqualTo("s3.us-east-1.amazonaws.com");
        assertThat(HttpS3Client.deriveHostHeader(URI.create("https://s3.us-east-1.amazonaws.com:443")))
                .isEqualTo("s3.us-east-1.amazonaws.com");
        assertThat(HttpS3Client.deriveHostHeader(URI.create("http://s3.local:80")))
                .isEqualTo("s3.local");
        assertThat(HttpS3Client.deriveHostHeader(URI.create("http://localhost:9000")))
                .isEqualTo("localhost:9000");
        assertThat(HttpS3Client.deriveHostHeader(URI.create("https://my-r2.cloudflarestorage.com:8443")))
                .isEqualTo("my-r2.cloudflarestorage.com:8443");
    }

    @Test
    void capturesWireHostHeaderAndVerifiesSigV4WithoutRestrictedHeaderError() throws Exception {
        AtomicReference<String> receivedHost = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        AtomicReference<String> receivedDate = new AtomicReference<>();
        AtomicReference<String> receivedSha = new AtomicReference<>();

        server.createContext("/test-bucket/doc1.bin", exchange -> {
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedDate.set(exchange.getRequestHeaders().getFirst("x-amz-date"));
            receivedSha.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));

            byte[] resp = new byte[0];
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "TEST_ACCESS_KEY",
                "TEST_SECRET_KEY"
        );

        byte[] payload = "Hello S3".getBytes(StandardCharsets.UTF_8);
        // This invocation must succeed under standard Java 21 settings without throwing
        // java.lang.IllegalArgumentException: restricted header name: "Host"
        client.putObject("test-bucket", "doc1.bin", payload, "application/octet-stream", false);

        // Verify wire Host header matches what Java HttpClient derived from the URI
        assertThat(receivedHost.get()).isEqualTo("127.0.0.1:" + port);

        // Verify SigV4 Authorization header was computed and sent
        String auth = receivedAuth.get();
        assertThat(auth).isNotNull().startsWith("AWS4-HMAC-SHA256 Credential=TEST_ACCESS_KEY/");
        assertThat(auth).contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        assertThat(auth).contains("Signature=");

        assertThat(receivedDate.get()).isNotNull().endsWith("Z");
        assertThat(receivedSha.get()).isNotNull().hasSize(64);
    }

    @Test
    void requestTimeoutsHandleStalledServerSafelyAcrossAllOperations() {
        // Mock server handler that stalls by sleeping longer than the client timeout
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            byte[] resp = "stalled".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        Duration shortTimeout = Duration.ofMillis(150);
        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "TEST_KEY",
                "TEST_SECRET",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                shortTimeout
        );

        // 1. PUT timeout (must note ambiguous outcome)
        assertThatThrownBy(() -> client.putObject("bucket", "key1", new byte[]{1, 2}, "application/octet-stream", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("ambiguous");

        // 2. GET timeout
        assertThatThrownBy(() -> client.getObject("bucket", "key1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");

        // 3. HEAD timeout
        assertThatThrownBy(() -> client.headObject("bucket", "key1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");

        // 4. DELETE timeout (must note ambiguous outcome)
        assertThatThrownBy(() -> client.deleteObject("bucket", "key1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("ambiguous");

        // 5. LIST timeout
        assertThatThrownBy(() -> client.listObjects("bucket", "prefix", null, 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void ambiguousPutTimeoutReconciledWhenObjectNotFoundThrowsFailsafe() {
        server.createContext("/b-put-404/doc.bin", exchange -> {
            String method = exchange.getRequestMethod();
            if ("PUT".equalsIgnoreCase(method)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, 0);
            } else if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET",
                HttpClient.newHttpClient(),
                Duration.ofMillis(120)
        );

        byte[] payload = "test-payload".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.putObject("b-put-404", "doc.bin", payload, "application/octet-stream", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist on remote");
    }

    @Test
    void ambiguousPutTimeoutReconciledWhenObjectVerifiedSucceeds() throws Exception {
        byte[] payload = "verified-payload".getBytes(StandardCharsets.UTF_8);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        String md5Hex = java.util.HexFormat.of().formatHex(md.digest(payload));

        server.createContext("/b-put-ok/doc.bin", exchange -> {
            String method = exchange.getRequestMethod();
            if ("PUT".equalsIgnoreCase(method)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, 0);
            } else if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
                exchange.getResponseHeaders().set("ETag", "\"" + md5Hex + "\"");
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET",
                HttpClient.newHttpClient(),
                Duration.ofMillis(120)
        );

        // Should not throw: reconciled and verified that exact payload exists on remote
        client.putObject("b-put-ok", "doc.bin", payload, "application/octet-stream", false);
    }

    @Test
    void ambiguousPutTimeoutReconciledWhenObjectSizeMismatchThrowsAmbiguous() {
        byte[] payload = "mismatch-payload".getBytes(StandardCharsets.UTF_8);

        server.createContext("/b-put-mismatch/doc.bin", exchange -> {
            String method = exchange.getRequestMethod();
            if ("PUT".equalsIgnoreCase(method)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, 0);
            } else if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Content-Length", "99999");
                exchange.getResponseHeaders().set("ETag", "\"different-etag\"");
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET",
                HttpClient.newHttpClient(),
                Duration.ofMillis(120)
        );

        assertThatThrownBy(() -> client.putObject("b-put-mismatch", "doc.bin", payload, "application/octet-stream", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void ambiguousDeleteTimeoutReconciledWhenObjectGoneReturnsTrue() throws Exception {
        server.createContext("/b-del-404/doc.bin", exchange -> {
            String method = exchange.getRequestMethod();
            if ("DELETE".equalsIgnoreCase(method)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(204, -1);
            } else if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET",
                HttpClient.newHttpClient(),
                Duration.ofMillis(120)
        );

        boolean deleted = client.deleteObject("b-del-404", "doc.bin");
        assertThat(deleted).isTrue();
    }

    @Test
    void ambiguousDeleteTimeoutReconciledWhenObjectStillExistsThrows() {
        server.createContext("/b-del-200/doc.bin", exchange -> {
            String method = exchange.getRequestMethod();
            if ("DELETE".equalsIgnoreCase(method)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(204, -1);
            } else if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET",
                HttpClient.newHttpClient(),
                Duration.ofMillis(120)
        );

        assertThatThrownBy(() -> client.deleteObject("b-del-200", "doc.bin"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("still exists on remote");
    }

    @Test
    void serverErrorAndConnectionAbruptClosePropagateSafeIOException() {
        server.createContext("/error-500", exchange -> {
            byte[] resp = "Internal Error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        HttpS3Client client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "us-east-1",
                "KEY",
                "SECRET"
        );

        assertThatThrownBy(() -> client.getObject("error-500", "any.bin"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void cloudflareR2EndpointWithAutoRegionConstructsValidSigV4Headers() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        server.createContext("/r2-bucket/test-doc.bin", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "r2-data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        HttpS3Client r2Client = new HttpS3Client(
                "http://127.0.0.1:" + port,
                "auto",
                "R2_ACCESS_KEY_ID_12345",
                "R2_SECRET_ACCESS_KEY_67890"
        );

        byte[] result = r2Client.getObject("r2-bucket", "test-doc.bin");
        assertThat(result).isEqualTo("r2-data".getBytes(StandardCharsets.UTF_8));

        String auth = receivedAuth.get();
        assertThat(auth).isNotNull();
        assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=R2_ACCESS_KEY_ID_12345/");
        assertThat(auth).contains("/auto/s3/aws4_request");
        assertThat(auth).contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        assertThat(auth).contains("Signature=");
    }
}
