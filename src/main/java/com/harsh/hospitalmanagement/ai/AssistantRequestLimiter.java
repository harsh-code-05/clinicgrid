package com.harsh.hospitalmanagement.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Component
class AssistantRequestLimiter {
    private final Map<Long, Window> windows = new HashMap<>();

    @Value("${hms.ai.rate-limit.max-per-minute:30}")
    private int maxPerMinute = 30;

    AssistantRequestLimiter() {
    }

    AssistantRequestLimiter(int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    synchronized void acquire(long userId) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Window window = windows.get(userId);
        if (window == null) {
            if (windows.size() >= 10000) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is busy. Please try again later.");
            }
            window = new Window(now + 60000);
            windows.put(userId, window);
        }
        if (window.attempts >= maxPerMinute) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait a minute before sending another message.");
        }
        window.attempts++;
    }

    private static class Window {
        final long expiresAt;
        int attempts;

        Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
