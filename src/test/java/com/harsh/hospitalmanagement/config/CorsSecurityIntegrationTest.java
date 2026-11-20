package com.harsh.hospitalmanagement.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:cors_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "app.frontend.url=http://localhost:5173/",
        "app.cors.allowed-origins=https://my-hms-frontend.vercel.app,https://my-hms-frontend.onrender.com"
})
public class CorsSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void optionsPreflightFromAllowedLocalDevOriginReturns200WithCorsHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN,Content-Type")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .contains("true");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
                .contains("GET");
    }

    @Test
    void optionsPreflightFromConfiguredCloudOriginReturns200WithCorsHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://my-hms-frontend.vercel.app")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN,Content-Type")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("https://my-hms-frontend.vercel.app");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .contains("true");
    }

    @Test
    void optionsPreflightFromDisallowedOriginRejectsOrOmitsAllowOriginHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.untrusted-site.com")
                .header("Access-Control-Request-Method", "GET")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Either forbidden (403) or 200 without Access-Control-Allow-Origin for untrusted origin
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void credentialedGetFromAllowedOriginReturns200AndValidCsrfToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .GET()
                .header("Origin", "http://localhost:5173")
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .contains("true");
        assertThat(response.body()).contains("\"headerName\":\"X-CSRF-TOKEN\"");
        assertThat(response.body()).contains("\"token\":");
    }

    @Test
    void disallowedOriginOnActualRequestOmitsAllowOriginHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .GET()
                .header("Origin", "https://evil.untrusted-site.com")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
