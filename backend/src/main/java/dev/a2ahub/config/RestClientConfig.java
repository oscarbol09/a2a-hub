package dev.a2ahub.config;

import dev.a2ahub.security.SsrfValidator;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    private final SsrfValidator ssrfValidator;

    public RestClientConfig(SsrfValidator ssrfValidator) {
        this.ssrfValidator = ssrfValidator;
    }

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        HttpComponentsClientHttpRequestFactory requestFactory =
                createRequestFactory(ssrfValidator, Duration.ofSeconds(3), Duration.ofSeconds(60));

        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    /**
     * Creates an HttpComponentsClientHttpRequestFactory pinned to the given SsrfValidator via a custom DnsResolver.
     * Prevents TOCTOU DNS rebinding attacks (CWE-367 / CWE-918) at socket level.
     */
    public static HttpComponentsClientHttpRequestFactory createRequestFactory(
            SsrfValidator ssrfValidator,
            Duration connectTimeout,
            Duration readTimeout) {

        DnsResolver dnsResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return ssrfValidator.resolveAndValidate(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                InetAddress[] addresses = ssrfValidator.resolveAndValidate(host);
                if (addresses != null && addresses.length > 0) {
                    return addresses[0].getCanonicalHostName();
                }
                return host;
            }
        };

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .build())
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(connectTimeout);
        return factory;
    }
}
