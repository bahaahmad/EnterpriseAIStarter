package com.cuenterprise.ai.rag.ingest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    @Test
    void prefixesHeadingPathAndSplits() {
        var chunks = Chunker.chunk("# A\n## Limits\n" + "x".repeat(250), "Doc", 100, 20);
        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.get(0).startsWith("Doc > Limits"));
    }
}
