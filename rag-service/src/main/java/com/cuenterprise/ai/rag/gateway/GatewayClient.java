package com.cuenterprise.ai.rag.gateway;

import com.cuenterprise.ai.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** All model calls go through the A2 gateway — never straight to Ollama/vLLM. */
@Component
public class GatewayClient {
    private final RestClient http;
    private final RagProperties props;

    public GatewayClient(RagProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(props.gatewayUrl())
                .defaultHeader("Authorization", "Bearer " + props.gatewayKey())
                .build();
    }

    public List<float[]> embed(List<String> texts) {
        JsonNode res = http.post().uri("/embeddings").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", props.embedModel(), "input", texts))
                .retrieve().body(JsonNode.class);
        return res.get("data").findValues("embedding").stream().map(n -> {
            float[] v = new float[n.size()];
            for (int i = 0; i < n.size(); i++) v[i] = (float) n.get(i).asDouble();
            return v;
        }).toList();
    }

    public String chat(List<Map<String, String>> messages, String endUser) {
        JsonNode res = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", props.chatModel(), "messages", messages,
                        "temperature", 0.1, "stream", false,
                        "user", endUser))                     // per-user spend + audit in the gateway
                .retrieve().body(JsonNode.class);
        return res.at("/choices/0/message/content").asText();
    }
}
