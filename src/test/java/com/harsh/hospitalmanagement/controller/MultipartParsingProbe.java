package com.harsh.hospitalmanagement.controller;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@TestConfiguration
class MultipartParsingProbe {

    private final AtomicInteger resolveMultipartCalls = new AtomicInteger();
    private final AtomicInteger servletPartCalls = new AtomicInteger();
    private final AtomicInteger servletParameterCalls = new AtomicInteger();

    void reset() {
        resolveMultipartCalls.set(0);
        servletPartCalls.set(0);
        servletParameterCalls.set(0);
    }

    int resolveMultipartCalls() {
        return resolveMultipartCalls.get();
    }

    int servletPartCalls() {
        return servletPartCalls.get();
    }

    int servletParameterCalls() {
        return servletParameterCalls.get();
    }

    void recordResolveMultipartCall() {
        resolveMultipartCalls.incrementAndGet();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    Filter multipartParsingCountingFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                if (request instanceof HttpServletRequest httpRequest && isMultipartPost(httpRequest)) {
                    chain.doFilter(new CountingMultipartRequestWrapper(
                            httpRequest, servletPartCalls, servletParameterCalls), response);
                } else {
                    chain.doFilter(request, response);
                }
            }
        };
    }

    @Bean
    static BeanPostProcessor multipartResolverCountingPostProcessor(ObjectProvider<MultipartParsingProbe> probeProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof MultipartResolver delegate) {
                    return new MultipartResolver() {
                        @Override
                        public boolean isMultipart(HttpServletRequest request) {
                            return delegate.isMultipart(request);
                        }

                        @Override
                        public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request)
                                throws MultipartException {
                            probeProvider.ifAvailable(MultipartParsingProbe::recordResolveMultipartCall);
                            return delegate.resolveMultipart(request);
                        }

                        @Override
                        public void cleanupMultipart(MultipartHttpServletRequest request) {
                            delegate.cleanupMultipart(request);
                        }
                    };
                }
                return bean;
            }
        };
    }

    record RawHttpResponse(int statusCode, String headersRaw, String body) {
        boolean hasCacheControlNoStore() {
            for (String line : headersRaw.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && "cache-control".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    if (line.substring(idx + 1).toLowerCase(Locale.ROOT).contains("no-store")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    static RawHttpResponse postChunkedRaw(
            int port,
            URI targetUri,
            HttpClient http,
            String csrfHeader,
            String csrfToken,
            String boundary,
            byte[] body
    ) throws IOException {
        return postRaw(port, targetUri, http, csrfHeader, csrfToken, boundary, body, true);
    }

    static RawHttpResponse postContentLengthRaw(
            int port,
            URI targetUri,
            HttpClient http,
            String csrfHeader,
            String csrfToken,
            String boundary,
            byte[] body
    ) throws IOException {
        return postRaw(port, targetUri, http, csrfHeader, csrfToken, boundary, body, false);
    }

    private static RawHttpResponse postRaw(
            int port,
            URI targetUri,
            HttpClient http,
            String csrfHeader,
            String csrfToken,
            String boundary,
            byte[] body,
            boolean chunked
    ) throws IOException {
        String cookieHeader = http.cookieHandler()
                .filter(CookieManager.class::isInstance)
                .map(CookieManager.class::cast)
                .map(cm -> {
                    List<HttpCookie> cookies = cm.getCookieStore().get(targetUri);
                    if (cookies.isEmpty()) {
                        cookies = cm.getCookieStore().getCookies();
                    }
                    return cookies.stream()
                            .map(c -> c.getName() + "=" + c.getValue())
                            .collect(Collectors.joining("; "));
                })
                .orElse("");

        String path = targetUri.getRawPath()
                + (targetUri.getRawQuery() != null ? "?" + targetUri.getRawQuery() : "");
        StringBuilder reqHeaders = new StringBuilder();
        reqHeaders.append("POST ").append(path).append(" HTTP/1.1\r\n");
        reqHeaders.append("Host: localhost:").append(port).append("\r\n");
        if (!cookieHeader.isEmpty()) {
            reqHeaders.append("Cookie: ").append(cookieHeader).append("\r\n");
        }
        reqHeaders.append(csrfHeader).append(": ").append(csrfToken).append("\r\n");
        reqHeaders.append("Content-Type: multipart/form-data; boundary=").append(boundary).append("\r\n");
        if (chunked) {
            reqHeaders.append("Transfer-Encoding: chunked\r\n");
        } else {
            reqHeaders.append("Content-Length: ").append(body.length).append("\r\n");
        }
        reqHeaders.append("Connection: close\r\n\r\n");

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream respBytes = new ByteArrayOutputStream();
            var responseComplete = new java.util.concurrent.CountDownLatch(1);
            var readerError = new java.util.concurrent.atomic.AtomicReference<IOException>();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        respBytes.write(buf, 0, n);
                    }
                } catch (IOException ex) {
                    if (respBytes.size() == 0) {
                        readerError.set(ex);
                    }
                } finally {
                    responseComplete.countDown();
                }
            }, "raw-http-reader");
            reader.setDaemon(true);
            reader.start();

            OutputStream out = socket.getOutputStream();
            out.write(reqHeaders.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            try {
                int chunkSize = 65_536;
                for (int offset = 0; offset < body.length && responseComplete.getCount() > 0; offset += chunkSize) {
                    int len = Math.min(chunkSize, body.length - offset);
                    if (chunked) {
                        out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write(body, offset, len);
                    if (chunked) {
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
                if (chunked && responseComplete.getCount() > 0) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException ignoredWhenServerRespondsEarly) {
                // Tomcat legitimately closes the socket early on 400 or 413 without draining large request bodies.
            }

            try {
                responseComplete.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for raw HTTP response", ie);
            }

            if (respBytes.size() == 0 && readerError.get() != null) {
                throw readerError.get();
            }

            String raw = respBytes.toString(StandardCharsets.UTF_8);
            int headerEnd = raw.indexOf("\r\n\r\n");
            String headersRaw = headerEnd >= 0 ? raw.substring(0, headerEnd) : raw;
            String respBody = headerEnd >= 0 ? raw.substring(headerEnd + 4) : "";
            String statusLine = headersRaw.split("\r\n", 2)[0];
            String[] parts = statusLine.split("\\s+");
            if (parts.length < 2) {
                throw new IOException("Malformed HTTP status line: " + statusLine);
            }
            int statusCode = Integer.parseInt(parts[1]);
            return new RawHttpResponse(statusCode, headersRaw, respBody);
        }
    }

    private static boolean isMultipartPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static final class CountingMultipartRequestWrapper extends HttpServletRequestWrapper {
        private final AtomicInteger partCalls;
        private final AtomicInteger paramCalls;

        private CountingMultipartRequestWrapper(
                HttpServletRequest request,
                AtomicInteger partCalls,
                AtomicInteger paramCalls
        ) {
            super(request);
            this.partCalls = partCalls;
            this.paramCalls = paramCalls;
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            partCalls.incrementAndGet();
            return super.getParts();
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            partCalls.incrementAndGet();
            return super.getPart(name);
        }

        @Override
        public String getParameter(String name) {
            paramCalls.incrementAndGet();
            return super.getParameter(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            paramCalls.incrementAndGet();
            return super.getParameterMap();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            paramCalls.incrementAndGet();
            return super.getParameterNames();
        }

        @Override
        public String[] getParameterValues(String name) {
            paramCalls.incrementAndGet();
            return super.getParameterValues(name);
        }
    }
}
