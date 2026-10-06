package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SecurityProperties securityProperties;

    public WebSocketConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = securityProperties.getAllowedOrigins() != null && !securityProperties.getAllowedOrigins().isEmpty()
                ? securityProperties.getAllowedOrigins().toArray(new String[0])
                : new String[]{"*"};

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);

        registry.addEndpoint("/ws/sockjs")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String configuredApiKey = securityProperties.getApiKey();
                    if (configuredApiKey != null && !configuredApiKey.isBlank()) {
                        String apiKey = accessor.getFirstNativeHeader("X-API-Key");
                        if (apiKey == null || apiKey.isBlank()) {
                            String authHeader = accessor.getFirstNativeHeader("Authorization");
                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                apiKey = authHeader.substring(7).trim();
                            }
                        }
                        if (apiKey == null || !MessageDigest.isEqual(
                                apiKey.getBytes(StandardCharsets.UTF_8),
                                configuredApiKey.getBytes(StandardCharsets.UTF_8))) {
                            throw new IllegalArgumentException("Unauthorized: Missing or invalid API key for WebSocket connection");
                        }
                    }
                }
                return message;
            }
        });
    }
}
