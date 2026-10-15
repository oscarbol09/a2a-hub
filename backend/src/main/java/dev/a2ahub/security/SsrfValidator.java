package dev.a2ahub.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

@Component
public class SsrfValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfValidator.class);

    private static final Set<String> FORBIDDEN_HOSTNAMES = Set.of(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "ip6-loopback"
    );

    private final SecurityProperties securityProperties;

    public SsrfValidator(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * Validates that target URL is a safe, publicly reachable HTTP/HTTPS destination.
     * Throws IllegalArgumentException if the URL points to loopback, private ranges, link-local,
     * or metadata addresses.
     */
    public void validateSafeRemoteUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + urlString, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Unsupported scheme '" + scheme + "'. Only HTTP and HTTPS are permitted.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host component");
        }

        try {
            resolveAndValidate(host);
        } catch (UnknownHostException | SecurityException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Resolves all IP addresses for a host and validates that none are in restricted ranges.
     * Returns the resolved addresses to be used directly by the HTTP client (pinning DNS, zero TOCTOU).
     *
     * @param host the hostname or IP literal to resolve and validate
     * @return array of resolved safe InetAddress instances
     * @throws UnknownHostException if the host cannot be resolved or resolves to a restricted/forbidden address
     */
    public InetAddress[] resolveAndValidate(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("Host cannot be empty");
        }

        String cleanHost = host.trim();
        if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
            cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
        }

        if (securityProperties.getSsrf().isAllowPrivateNetworks()) {
            log.debug("SSRF protection bypassed (allowPrivateNetworks=true) for host: {}", cleanHost);
            return InetAddress.getAllByName(cleanHost);
        }

        if (FORBIDDEN_HOSTNAMES.contains(cleanHost.toLowerCase())) {
            log.warn("Blocked SSRF attempt targeting forbidden hostname: {}", cleanHost);
            throw new UnknownHostException("Access to loopback hostname '" + cleanHost + "' is forbidden");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(cleanHost);
        } catch (UnknownHostException e) {
            throw new UnknownHostException("Host could not be resolved: " + cleanHost);
        }

        for (InetAddress addr : addresses) {
            if (isForbiddenAddress(addr)) {
                log.warn("Blocked SSRF attempt targeting IP: {} (Host: {})", addr.getHostAddress(), cleanHost);
                throw new UnknownHostException("Destination IP address '" + addr.getHostAddress() + "' is in a restricted range");
            }
        }

        return addresses;
    }

    private boolean isForbiddenAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }

        // IPv6 loopback / unique local address check (fc00::/7)
        if (addr instanceof Inet6Address) {
            byte[] bytes = addr.getAddress();
            // fc00::/7 (Unique Local Address)
            if ((bytes[0] & (byte) 0xfe) == (byte) 0xfc) {
                return true;
            }
            // IPv4-mapped IPv6 check (::ffff:127.0.0.1 etc.)
            if (isIPv4MappedIPv6(bytes)) {
                try {
                    byte[] ipv4Bytes = new byte[4];
                    System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
                    InetAddress ipv4 = InetAddress.getByAddress(ipv4Bytes);
                    return isForbiddenAddress(ipv4);
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
        }

        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true;

            // 10.0.0.0/8 (RFC 1918 Private)
            if (b0 == 10) return true;

            // 100.64.0.0/10 (Carrier-grade NAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;

            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true;

            // 169.254.0.0/16 (Link-local / Cloud metadata: 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true;

            // 172.16.0.0/12 (RFC 1918 Private)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;

            // 192.0.0.0/24 (IETF Protocol Assignments) & 192.0.2.0/24 (TEST-NET-1 RFC 5737)
            if (b0 == 192 && b1 == 0 && (raw[2] & 0xFF) <= 2) return true;

            // 192.168.0.0/16 (RFC 1918 Private)
            if (b0 == 192 && b1 == 168) return true;

            // 198.18.0.0/15 (Benchmarking RFC 2544)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;

            // 198.51.100.0/24 (TEST-NET-2 RFC 5737)
            if (b0 == 198 && b1 == 51 && (raw[2] & 0xFF) == 100) return true;

            // 203.0.113.0/24 (TEST-NET-3 RFC 5737)
            if (b0 == 203 && b1 == 0 && (raw[2] & 0xFF) == 113) return true;

            // 224.0.0.0/4 (Multicast) & 240.0.0.0/4 (Reserved)
            if (b0 >= 224) return true;
        }

        return false;
    }

    private boolean isIPv4MappedIPv6(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }
}
