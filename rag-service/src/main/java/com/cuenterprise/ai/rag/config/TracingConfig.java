package com.cuenterprise.ai.rag.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    /** Tracer used for the hand-written spans that carry OpenInference attributes. */
    @Bean
    public Tracer ragTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.cuenterprise.ai.rag", "0.1.0");
    }
}
