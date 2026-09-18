package com.cuenterprise.ai.rag.ingest;

import java.util.ArrayList;
import java.util.List;

/** Heading-aware chunker: splits on markdown headings, then by size with overlap. Prefixes the heading path. */
public final class Chunker {
    private Chunker() {}

    public static List<String> chunk(String markdown, String docTitle, int maxChars, int overlap) {
        List<String> out = new ArrayList<>();
        String heading = "";
        StringBuilder section = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("#")) {
                flush(out, docTitle, heading, section.toString(), maxChars, overlap);
                section.setLength(0);
                heading = line.replaceFirst("^#+\\s*", "");
            } else {
                section.append(line).append('\n');
            }
        }
        flush(out, docTitle, heading, section.toString(), maxChars, overlap);
        return out;
    }

    private static void flush(List<String> out, String title, String heading, String text, int max, int overlap) {
        String body = text.strip();
        if (body.isEmpty()) return;
        String prefix = title + (heading.isEmpty() ? "" : " > " + heading) + "\n";
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(body.length(), start + max);
            out.add(prefix + body.substring(start, end));
            if (end == body.length()) break;
            start = end - overlap;
        }
    }
}
