package com.cuenterprise.ai.rag.api;

import com.cuenterprise.ai.rag.ingest.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Admin-only in Phase 0 (localhost). Server PoC: separate ingestion job and role. */
@RestController
public class IngestController {
    private final IngestService ingest;
    public IngestController(IngestService ingest) { this.ingest = ingest; }

    @PostMapping("/admin/ingest")
    public Map<String, Integer> ingest() throws Exception { return ingest.ingestManifest(); }
}
