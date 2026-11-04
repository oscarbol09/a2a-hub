package dev.a2ahub.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Standard A2A v1.0 Agent Card representation.
 * Supports structured AgentInterface objects, resilient null defaults,
 * and backwards-compatible string array deserialization.
 */
public record AgentCard(
    String name,
    String description,
    String url,
    String version,
    List<Skill> skills,
    Map<String, Object> capabilities,
    List<Object> supportedInterfaces
) {
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    public AgentCard {
        skills = skills != null ? skills : List.of();
        capabilities = capabilities != null ? capabilities : Map.of();
        supportedInterfaces = supportedInterfaces != null ? supportedInterfaces : List.of();
    }

    public record Skill(String id, String name, String description, List<String> tags) {}

    public record AgentInterface(String url, String protocolBinding, String protocolVersion) {}

    /**
     * Normalized access to interfaces as type-safe AgentInterface records.
     */
    public List<AgentInterface> interfaces() {
        if (supportedInterfaces == null) {
            return List.of();
        }
        List<AgentInterface> result = new ArrayList<>();
        for (Object item : supportedInterfaces) {
            if (item instanceof AgentInterface ai) {
                result.add(ai);
            } else if (item instanceof String s) {
                String value = s.trim();
                // Recognize schemes and scheme-relative URLs before validation so invalid declarations
                // cannot silently become legacy protocol labels and trigger the /tasks fallback.
                result.add(URI_SCHEME.matcher(value).find() || value.startsWith("//")
                        ? new AgentInterface(value, "HTTP+JSON", "1.0")
                        : new AgentInterface(null, s, "1.0"));
            } else if (item instanceof Map<?, ?> m) {
                String u = m.get("url") != null ? m.get("url").toString() : null;
                String b = m.get("protocolBinding") != null ? m.get("protocolBinding").toString() : "HTTP+JSON";
                String v = m.get("protocolVersion") != null ? m.get("protocolVersion").toString() : "1.0";
                result.add(new AgentInterface(u, b, v));
            }
        }
        return result;
    }
}
