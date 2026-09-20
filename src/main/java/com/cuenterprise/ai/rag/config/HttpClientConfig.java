package com.cuenterprise.ai.rag.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Outbound HTTP for every client in this service: HTTP/1.1, explicit timeouts, and W3C trace-context
 * propagation.
 */
@Configuration
public class HttpClientConfig {

    /**
     * Forced to HTTP/1.1: the JDK HttpClient defaults to HTTP/2 and, on a plaintext connection, attaches the
     * h2c upgrade headers ("Connection: Upgrade, HTTP2-Settings"). uvicorn/h11 — which serves docling-serve
     * and LiteLLM — rejects that with a bare 400 "Invalid HTTP request received" before the request reaches
     * the API. curl works against the same endpoint because it speaks HTTP/1.1.
     */
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMinutes(10));   // model load + generation on CPU
        return factory;
    }

    /**
     * Adds the W3C {@code traceparent} header to every outbound call, so the receiver's spans join this
     * request's trace instead of starting their own. Without it, one question produces two unrelated traces:
     * ours (CHAIN + RETRIEVER) and LiteLLM's (GUARDRAIL + EMBEDDING + LLM).
     */
    @Bean
    public ClientHttpRequestInterceptor traceContextInterceptor(OpenTelemetry openTelemetry) {
        return (request, body, execution) -> {
            openTelemetry.getPropagators().getTextMapPropagator().inject(
                    Context.current(), request.getHeaders(),
                    (headers, key, value) -> { if (headers != null) headers.set(key, value); });
            return execution.execute(request, body);
        };
    }

    /** Base builder for every outbound client. Each client clones it and sets its own base URL. */
    @Bean
    public RestClient.Builder outboundRestClientBuilder(ClientHttpRequestFactory factory,
                                                        ClientHttpRequestInterceptor traceContextInterceptor) {
        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(traceContextInterceptor);
    }
}
