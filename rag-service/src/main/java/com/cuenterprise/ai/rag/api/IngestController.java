package com.cuenterprise.ai.rag.api;

import com.cuenterprise.ai.rag.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Admin-only in Phase 0 (localhost). Server PoC: separate ingestion job and role. */
@RestController
public class IngestController {
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);
    private final IngestService ingest;
    public IngestController(IngestService ingest) { this.ingest = ingest; }

    /** Return the real reason instead of a blank 500 — the console is not the only place to look. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failed(Exception e) {
        log.error("Ingest failed", e);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "failed");
        body.put("error", e.getClass().getSimpleName());
        body.put("message", e.getMessage());
        body.put("cause", root.getClass().getSimpleName() + ": " + root.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @PostMapping("/admin/ingest")
    public Map<String, Integer> ingest() throws Exception { return ingest.ingestManifest(); }
}
