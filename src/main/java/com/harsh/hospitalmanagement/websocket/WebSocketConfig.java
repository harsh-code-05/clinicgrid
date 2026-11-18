package com.harsh.hospitalmanagement.websocket;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final VideoConsultationService videoConsultationService;
    private final SubscribableChannel clientOutboundChannel;

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^/topic/video/room/(\\d+)$");
    private static final Pattern APP_PATTERN = Pattern.compile("^/app/video/signal/(\\d+)$");

    private static final java.util.Set<StompCommand> PERMITTED_CLIENT_COMMANDS = java.util.Set.of(
            StompCommand.CONNECT,
            StompCommand.STOMP,
            StompCommand.SUBSCRIBE,
            StompCommand.UNSUBSCRIBE,
            StompCommand.SEND,
            StompCommand.DISCONNECT,
            StompCommand.ACK,
            StompCommand.NACK
    );

    private final String frontendUrl;
    private final String additionalOrigins;
    private final boolean allowLocalhost;

    public WebSocketConfig(
            VideoConsultationService videoConsultationService,
            @Lazy @Qualifier("clientOutboundChannel") SubscribableChannel clientOutboundChannel,
            @org.springframework.beans.factory.annotation.Value("${app.frontend.url:http://localhost:5173/}") String frontendUrl,
            @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:}") String additionalOrigins,
            @org.springframework.beans.factory.annotation.Value("${app.cors.allow-localhost:true}") boolean allowLocalhost
    ) {
        this.videoConsultationService = videoConsultationService;
        this.clientOutboundChannel = clientOutboundChannel;
        this.frontendUrl = frontendUrl;
        this.additionalOrigins = additionalOrigins;
        this.allowLocalhost = allowLocalhost;
    }

    private String[] resolveAllowedOriginPatterns() {
        java.util.Set<String> patterns = new java.util.LinkedHashSet<>();
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            String norm = frontendUrl.replaceAll("/+$", "").trim();
            if (!norm.isEmpty()) patterns.add(norm);
        }
        if (allowLocalhost) {
            patterns.add("http://localhost:[*]");
            patterns.add("http://localhost:*");
            patterns.add("http://127.0.0.1:[*]");
            patterns.add("http://127.0.0.1:*");
            patterns.add("http://localhost:5173");
            patterns.add("http://127.0.0.1:5173");
        }
        if (additionalOrigins != null && !additionalOrigins.isBlank()) {
            for (String origin : additionalOrigins.split(",")) {
                String trimmed = origin.replaceAll("/+$", "").trim();
                if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
                    patterns.add(trimmed);
                }
            }
        }
        if (patterns.isEmpty()) {
            return new String[]{"*"};
        }
        return patterns.toArray(new String[0]);
    }

    private void sendErrorToClient(StompHeaderAccessor accessor, String errorMessage) {
        if (clientOutboundChannel != null && accessor != null && accessor.getSessionId() != null) {
            try {
                StompHeaderAccessor errorAccessor = StompHeaderAccessor.create(StompCommand.ERROR);
                errorAccessor.setMessage("Failed to send message: " + errorMessage);
                errorAccessor.setSessionId(accessor.getSessionId());
                if (accessor.getReceipt() != null) {
                    errorAccessor.setReceiptId(accessor.getReceipt());
                }
                clientOutboundChannel.send(MessageBuilder.createMessage(
                        new byte[0], errorAccessor.getMessageHeaders()
                ));
            } catch (Exception e) {
                // ignore delivery failure
            }
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        String[] allowedOrigins = resolveAllowedOriginPatterns();

        registry.addEndpoint("/ws/signaling")
                .setAllowedOriginPatterns(allowedOrigins)
                .addInterceptors(new HttpSessionHandshakeInterceptor());

        registry.addEndpoint("/ws/signaling")
                .setAllowedOriginPatterns(allowedOrigins)
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }

                StompCommand command = accessor.getCommand();
                if (command != null && !PERMITTED_CLIENT_COMMANDS.contains(command)) {
                    sendErrorToClient(accessor, "Client command not permitted: " + command);
                    throw new AccessDeniedException("Client command not permitted: " + command);
                }

                if (command == StompCommand.CONNECT) {
                    Authentication auth = (Authentication) accessor.getUser();
                    if (auth == null || !auth.isAuthenticated()) {
                        sendErrorToClient(accessor, "Unauthenticated WebSocket connection");
                        throw new AccessDeniedException("Unauthenticated WebSocket connection");
                    }
                } else if (command == StompCommand.SUBSCRIBE) {
                    String destination = accessor.getDestination();
                    if (destination == null) {
                        sendErrorToClient(accessor, "Destination required for subscription");
                        throw new AccessDeniedException("Destination required for subscription");
                    }
                    Matcher matcher = TOPIC_PATTERN.matcher(destination);
                    if (!matcher.matches()) {
                        sendErrorToClient(accessor, "Unauthorized subscription destination: " + destination);
                        throw new AccessDeniedException("Unauthorized subscription destination: " + destination);
                    }
                    Long appointmentId = Long.parseLong(matcher.group(1));
                    Authentication auth = (Authentication) accessor.getUser();
                    if (auth == null) {
                        sendErrorToClient(accessor, "Unauthenticated subscription");
                        throw new AccessDeniedException("Unauthenticated subscription");
                    }
                    LocalAccount actor = LocalAccount.require(auth);
                    try {
                        videoConsultationService.validateSignalingAccess(appointmentId, actor);
                    } catch (Exception ex) {
                        sendErrorToClient(accessor, ex.getMessage());
                        throw ex;
                    }
                } else if (command == StompCommand.SEND) {
                    String destination = accessor.getDestination();
                    if (destination == null) {
                        sendErrorToClient(accessor, "Destination required for send");
                        throw new AccessDeniedException("Destination required for send");
                    }
                    if (destination.startsWith("/topic/") || destination.startsWith("/queue/")) {
                        sendErrorToClient(accessor, "Direct publishing to broker destination is forbidden: " + destination);
                        throw new AccessDeniedException("Direct publishing to broker destination is forbidden: " + destination);
                    }
                    Matcher matcher = APP_PATTERN.matcher(destination);
                    if (!matcher.matches()) {
                        sendErrorToClient(accessor, "Unauthorized message destination: " + destination);
                        throw new AccessDeniedException("Unauthorized message destination: " + destination);
                    }
                    Long appointmentId = Long.parseLong(matcher.group(1));
                    Authentication auth = (Authentication) accessor.getUser();
                    if (auth == null) {
                        sendErrorToClient(accessor, "Unauthenticated signaling message");
                        throw new AccessDeniedException("Unauthenticated signaling message");
                    }
                    LocalAccount actor = LocalAccount.require(auth);
                    try {
                        videoConsultationService.validateSignalingAccess(appointmentId, actor);
                    } catch (Exception ex) {
                        sendErrorToClient(accessor, ex.getMessage());
                        throw ex;
                    }
                }

                return message;
            }
        });
    }
}
