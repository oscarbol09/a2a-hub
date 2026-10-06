package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("WebSocketConfig Unit Tests")
class WebSocketConfigTest {

    @Test
    @DisplayName("Should configure broker prefixes for /topic and /app")
    void shouldConfigureMessageBroker() {
        SecurityProperties securityProperties = new SecurityProperties();
        WebSocketConfig config = new WebSocketConfig(securityProperties);

        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    @DisplayName("Should register STOMP and SockJS endpoints with configured allowed origins")
    void shouldRegisterStompEndpointsWithConfiguredOrigins() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setAllowedOrigins(List.of("https://hub.example.com"));

        WebSocketConfig config = new WebSocketConfig(securityProperties);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint(any())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any(String[].class))).thenReturn(registration);
        when(registration.withSockJS()).thenReturn(null);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(registry).addEndpoint("/ws/sockjs");
        verify(registration, times(2)).setAllowedOriginPatterns(new String[]{"https://hub.example.com"});
    }

    @Test
    @DisplayName("Should fallback to wildcard allowed origins when allowedOrigins is null or empty")
    void shouldFallbackToWildcardAllowedOrigins() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setAllowedOrigins(null);

        WebSocketConfig config = new WebSocketConfig(securityProperties);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint(any())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any(String[].class))).thenReturn(registration);
        when(registration.withSockJS()).thenReturn(null);

        config.registerStompEndpoints(registry);

        verify(registration, times(2)).setAllowedOriginPatterns(new String[]{"*"});
    }
}
