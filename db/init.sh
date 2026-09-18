#!/bin/bash
# Runs once, on an empty pgdata volume. Passes the reader password from the environment into init.sql.
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
     -v reader_pw="$RAG_READER_PASSWORD" -f /sql/init.sql
