package com.cuenterprise.ai.rag.config;

/**
 * OpenInference semantic-convention attribute names.
 * <p>
 * Plain OpenTelemetry spans show up in Phoenix (and Langfuse) as kind "unknown": the UI cannot tell a
 * retrieval from a model call, so it cannot show documents, scores or prompts. These attributes are what
 * turn a generic span into one the AI tooling understands. They are also what makes the trace usable as the
 * D11 interaction log — every prompt, retrieved chunk, response and user in one record.
 */
public final class OI {
    private OI() {}

    public static final String SPAN_KIND = "openinference.span.kind";
    public static final String KIND_CHAIN = "CHAIN";
    public static final String KIND_RETRIEVER = "RETRIEVER";

    public static final String INPUT_VALUE = "input.value";
    public static final String OUTPUT_VALUE = "output.value";
    public static final String USER_ID = "user.id";
    public static final String SESSION_ID = "session.id";
    public static final String METADATA = "metadata";

    /** retrieval.documents.{i}.document.* — id, content, score, metadata */
    public static String doc(int i, String field) {
        return "retrieval.documents." + i + ".document." + field;
    }

    /** Attribute values are stored in the trace; keep chunk text short so traces stay readable. */
    public static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
