CREATE DATABASE litellm;
CREATE DATABASE kb;
\connect kb
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_chunk (
  id              bigserial PRIMARY KEY,
  doc_id          text        NOT NULL,
  doc_title       text        NOT NULL,
  source_uri      text        NOT NULL,
  chunk_no        int         NOT NULL,
  content         text        NOT NULL,
  content_hash    text        NOT NULL,
  classification  text        NOT NULL,
  allowed_groups  text[]      NOT NULL CHECK (cardinality(allowed_groups) > 0), -- no ACL, no row
  embedding       vector(1024) NOT NULL,
  tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
  ingested_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (doc_id, chunk_no)
);
CREATE INDEX kb_chunk_emb_hnsw ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX kb_chunk_tsv      ON kb_chunk USING gin (tsv);
CREATE INDEX kb_chunk_acl      ON kb_chunk USING gin (allowed_groups);

-- Query-time role: can only read. Ingestion uses postgres in Phase 0; split roles on the server.
CREATE ROLE rag_reader LOGIN PASSWORD :'reader_pw';
GRANT SELECT ON kb_chunk TO rag_reader;
