package com.cuenterprise.ai.rag.ingest;

import com.cuenterprise.ai.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * D4: converts any supported file (pdf, docx, md, images with OCR) to markdown via docling-serve.
 * <p>
 * The multipart body is assembled by hand rather than through a message converter: each part carries an
 * explicit filename and content type, and the whole body is a byte[], so the request is sent with a
 * Content-Length instead of chunked. Some uvicorn builds reject the chunked, filename-less form with
 * "Invalid HTTP request received" before the request reaches the API.
 */
@Component
public class DoclingClient {
    private static final Logger log = LoggerFactory.getLogger(DoclingClient.class);
    private final RestClient http;
    private final RagProperties props;

    public DoclingClient(RagProperties props, RestClient.Builder outbound) {
        this.props = props;
        this.http = outbound.clone().baseUrl(props.doclingUrl()).build();
    }

    public String toMarkdown(Path file) {
        String boundary = "----docling" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, file, Map.of("to_formats", "md"));
        log.debug("Docling POST {}{} file={} bytes={}", props.doclingUrl(), props.doclingPath(),
                file.getFileName(), body.length);

        JsonNode res = http.post().uri(props.doclingPath())
                .contentType(MediaType.parseMediaType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=" + boundary))
                .body(body)
                .retrieve().body(JsonNode.class);

        String md = res == null ? "" : res.at("/document/md_content").asText("");
        if (md.isBlank()) {
            throw new IllegalStateException("Docling returned no markdown for " + file.getFileName()
                    + "; response keys=" + (res == null ? "null" : res.fieldNames().next())
                    + " (check rag.docling-path and the docling-serve version)");
        }
        log.debug("Docling returned {} chars of markdown for {}", md.length(), file.getFileName());
        return md;
    }

    private static byte[] multipart(String boundary, Path file, Map<String, String> fields) {
        try {
            var out = new ByteArrayOutputStream();
            for (var e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                        + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            String name = file.getFileName().toString();
            String type = URLConnection.guessContentTypeFromName(name);
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\n"
                    + "Content-Type: " + (type == null ? "application/octet-stream" : type) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(file));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }
}
