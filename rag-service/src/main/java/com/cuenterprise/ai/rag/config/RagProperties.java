package com.cuenterprise.ai.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String gatewayUrl, String gatewayKey, String inboundKey,
        String chatModel, String embedModel,
        String doclingUrl, String doclingPath,
        String corpusDir, String usersFile, String userHeader,
        int topK, double minScore, int chunkChars, int chunkOverlap,
        String promptSuffix) {
}
