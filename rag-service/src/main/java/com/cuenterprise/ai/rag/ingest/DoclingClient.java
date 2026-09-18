package com.cuenterprise.ai.rag.ingest;

import com.cuenterprise.ai.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/** D4: converts any supported file (pdf, docx, md, images with OCR) to markdown via docling-serve. */
@Component
public class DoclingClient {
    private final RestClient http;
    private final RagProperties props;

    public DoclingClient(RagProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.doclingUrl()).build();
    }

    public String toMarkdown(Path file) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("files", new FileSystemResource(file));
        form.add("to_formats", "md");
        JsonNode res = http.post().uri(props.doclingPath())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(JsonNode.class);
        String md = res.at("/document/md_content").asText("");
        if (md.isBlank()) throw new IllegalStateException("Docling returned no markdown for " + file);
        return md;
    }
}
