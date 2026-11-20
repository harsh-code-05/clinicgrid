package com.harsh.hospitalmanagement.ai;

/**
 * Result of an AI inference operation with its identified origin source.
 */
public record AiResponse(String content, AiResponseSource source) {
}
