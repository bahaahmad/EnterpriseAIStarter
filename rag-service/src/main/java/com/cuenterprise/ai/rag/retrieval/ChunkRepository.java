package com.cuenterprise.ai.rag.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.List;

@Repository
public class ChunkRepository {
    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Hybrid search (vector + full text) fused with Reciprocal Rank Fusion.
     * The ACL filter sits INSIDE both candidate queries — never filter after ranking.
     */
    private static final String HYBRID_SQL = """
        WITH vec AS (
          SELECT id, row_number() OVER (ORDER BY embedding <=> ?::vector) AS r
          FROM kb_chunk
          WHERE allowed_groups && ?
          ORDER BY embedding <=> ?::vector
          LIMIT 40),
        kw AS (
          SELECT id, row_number() OVER (ORDER BY ts_rank_cd(tsv, q) DESC) AS r
          FROM kb_chunk, websearch_to_tsquery('english', ?) q
          WHERE allowed_groups && ? AND tsv @@ q
          ORDER BY ts_rank_cd(tsv, q) DESC
          LIMIT 40)
        SELECT c.id, c.doc_id, c.doc_title, c.source_uri, c.chunk_no, c.content,
               COALESCE(1.0/(60+vec.r),0) + COALESCE(1.0/(60+kw.r),0) AS score
        FROM kb_chunk c
        LEFT JOIN vec ON vec.id = c.id
        LEFT JOIN kw  ON kw.id  = c.id
        WHERE vec.id IS NOT NULL OR kw.id IS NOT NULL
        ORDER BY score DESC
        LIMIT ?
        """;

    @Transactional(readOnly = true)
    public List<RetrievedChunk> hybridSearch(float[] queryVec, String queryText, List<String> groups, int k) {
        if (groups == null || groups.isEmpty()) return List.of();          // fail closed
        // Keep recall when the ACL filter removes most HNSW candidates (pgvector >= 0.8)
        jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        String vec = toPgVector(queryVec);
        return jdbc.query(con -> {
            Array acl = con.createArrayOf("text", groups.toArray());
            var ps = con.prepareStatement(HYBRID_SQL);
            ps.setString(1, vec);
            ps.setArray(2, acl);
            ps.setString(3, vec);
            ps.setString(4, queryText);
            ps.setArray(5, acl);
            ps.setInt(6, k);
            return ps;
        }, (rs, i) -> new RetrievedChunk(rs.getLong("id"), rs.getString("doc_id"), rs.getString("doc_title"),
                rs.getString("source_uri"), rs.getInt("chunk_no"), rs.getString("content"), rs.getDouble("score")));
    }

    @Transactional
    public void replaceDocument(String docId, List<ChunkRow> rows) {
        jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);       // re-index = replace; no stale ACLs
        for (ChunkRow r : rows) {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                    INSERT INTO kb_chunk (doc_id, doc_title, source_uri, chunk_no, content, content_hash,
                                          classification, allowed_groups, embedding)
                    VALUES (?,?,?,?,?,?,?,?,?::vector)""");
                ps.setString(1, r.docId()); ps.setString(2, r.title()); ps.setString(3, r.sourceUri());
                ps.setInt(4, r.chunkNo()); ps.setString(5, r.content()); ps.setString(6, r.hash());
                ps.setString(7, r.classification());
                ps.setArray(8, con.createArrayOf("text", r.allowedGroups().toArray()));
                ps.setString(9, toPgVector(r.embedding()));
                return ps;
            });
        }
    }

    public record ChunkRow(String docId, String title, String sourceUri, int chunkNo, String content,
                           String hash, String classification, List<String> allowedGroups, float[] embedding) {}

    static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}
