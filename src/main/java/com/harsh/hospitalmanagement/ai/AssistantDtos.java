package com.harsh.hospitalmanagement.ai;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class AssistantDtos {
    private AssistantDtos() {
    }

    public record CreateSession(@Size(max = 80) String title) {
    }

    public record SendMessage(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 2000) String content,
            Boolean includeAppointments) {
        public SendMessage(UUID requestId, String content) {
            this(requestId, content, false);
        }

        public boolean optInAppointments() {
            return Boolean.TRUE.equals(includeAppointments);
        }
    }

    public record Session(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        static Session of(AiChatSession session) {
            return new Session(session.getId(), session.getTitle(), session.getCreatedAt(), session.getUpdatedAt());
        }
    }

    public record Message(Long id, UUID requestId, AiChatMessage.Role role, String content, String source, LocalDateTime createdAt) {
        public Message(Long id, UUID requestId, AiChatMessage.Role role, String content, LocalDateTime createdAt) {
            this(id, requestId, role, content, null, createdAt);
        }

        static Message of(AiChatMessage message) {
            String src = message.getSource() != null ? message.getSource().name() : null;
            return new Message(message.getId(), message.getRequestId(), message.getRole(), message.getContent(), src, message.getCreatedAt());
        }
    }

    public record Turn(List<Message> messages) {
    }
}
