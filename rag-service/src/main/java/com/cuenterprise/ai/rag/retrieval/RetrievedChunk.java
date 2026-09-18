package com.cuenterprise.ai.rag.retrieval;

public record RetrievedChunk(long id, String docId, String docTitle, String sourceUri,
                             int chunkNo, String content, double score) {
}
