package com.cuenterprise.ai.rag.identity;

import com.cuenterprise.ai.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Phase 0 stand-in for Keycloak group claims. Unknown or missing user => no groups => no results. */
@Component
public class UserDirectory {
    private final JsonNode root;

    public UserDirectory(RagProperties props) throws IOException {
        this.root = new ObjectMapper(new YAMLFactory()).readTree(new File(props.usersFile()));
    }

    public List<String> groupsFor(String email) {
        List<String> out = new ArrayList<>();
        if (email == null || email.isBlank()) return out;
        JsonNode g = root.path("users").path(email.trim().toLowerCase());
        g.forEach(n -> out.add(n.asText()));
        return out;
    }
}
