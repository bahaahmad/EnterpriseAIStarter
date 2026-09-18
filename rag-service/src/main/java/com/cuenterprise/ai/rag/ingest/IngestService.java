package com.cuenterprise.ai.rag.ingest;

import com.cuenterprise.ai.rag.config.RagProperties;
import com.cuenterprise.ai.rag.gateway.GatewayClient;
import com.cuenterprise.ai.rag.retrieval.ChunkRepository;
import com.cuenterprise.ai.rag.retrieval.ChunkRepository.ChunkRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int EMBED_BATCH = 16;
    private final RagProperties props;
    private final DoclingClient docling;
    private final GatewayClient gateway;
    private final ChunkRepository repo;

    public IngestService(RagProperties props, DoclingClient docling, GatewayClient gateway, ChunkRepository repo) {
        this.props = props; this.docling = docling; this.gateway = gateway; this.repo = repo;
    }

    public Map<String, Integer> ingestManifest() throws Exception {
        Path dir = Path.of(props.corpusDir());
        JsonNode manifest = new ObjectMapper(new YAMLFactory()).readTree(dir.resolve("manifest.yaml").toFile());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonNode d : manifest.path("documents")) {
            String docId = d.path("doc_id").asText();
            List<String> acl = new ArrayList<>();
            d.path("allowed_groups").forEach(n -> acl.add(n.asText()));
            if (acl.isEmpty()) throw new IllegalStateException("No ACL for " + docId);   // REQ-GOV-02
            Path file = dir.resolve(d.path("file").asText());
            String title = d.path("title").asText();
            String md = docling.toMarkdown(file);
            List<String> chunks = Chunker.chunk(md, title, props.chunkChars(), props.chunkOverlap());

            List<ChunkRow> rows = new ArrayList<>();
            for (int b = 0; b < chunks.size(); b += EMBED_BATCH) {
                List<String> batch = chunks.subList(b, Math.min(chunks.size(), b + EMBED_BATCH));
                List<float[]> vecs = gateway.embed(batch);
                for (int i = 0; i < batch.size(); i++) {
                    rows.add(new ChunkRow(docId, title, file.getFileName().toString(), b + i, batch.get(i),
                            sha256(batch.get(i)), d.path("classification").asText(), acl, vecs.get(i)));
                }
            }
            repo.replaceDocument(docId, rows);
            result.put(docId, rows.size());
            log.info("Ingested {} chunks={} acl={}", docId, rows.size(), acl);
        }
        return result;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
