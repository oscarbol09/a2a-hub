package dev.a2ahub.config;

import dev.a2ahub.agent.AgentRegistryService;
import dev.a2ahub.api.AgentController;
import dev.a2ahub.security.ApiKeyAuthFilter;
import dev.a2ahub.security.RateLimitFilter;
import dev.a2ahub.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class, properties = {
        "a2ahub.security.api-key=test",
        "a2ahub.security.rate-limit.capacity=1"
})
@Import({WebConfig.class, SecurityProperties.class, ApiKeyAuthFilter.class, RateLimitFilter.class})
class SecurityHeadersFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentRegistryService registryService;

    @Test
    void addsSecurityHeadersToUnauthorizedResponses() throws Exception {
        expectSecurityHeaders(mockMvc.perform(post("/api/v1/agents"))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void addsSecurityHeadersToRateLimitedResponses() throws Exception {
        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk());

        expectSecurityHeaders(mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isTooManyRequests()));
    }

    private static void expectSecurityHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        for (String name : List.of("Content-Security-Policy", "Strict-Transport-Security",
                "Referrer-Policy", "Permissions-Policy", "Cross-Origin-Opener-Policy",
                "Cross-Origin-Resource-Policy")) {
            result.andExpect(header().exists(name));
        }
    }
}
