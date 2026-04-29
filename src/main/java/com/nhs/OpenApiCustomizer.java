package com.nhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Intercepts HAPI's generated OpenAPI spec and adds application/fhir+xml
 * as a supported content type, so it appears in Swagger UI dropdowns.
 * Handles both YAML (default) and JSON formats.
 */
@Component
public class OpenApiCustomizer extends OncePerRequestFilter {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!request.getRequestURI().contains("api-docs")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrapper =
                new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        String originalBody = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        String contentType = wrapper.getContentType();
        String acceptHeader = request.getHeader("Accept");
        boolean isYaml = (contentType != null && contentType.contains("yaml"))
                || (acceptHeader != null && acceptHeader.contains("yaml"))
                || !request.getRequestURI().contains("format=json");

        try {
            ObjectMapper reader = isYaml ? yamlMapper : jsonMapper;
            ObjectMapper writer = isYaml ? yamlMapper : jsonMapper;

            JsonNode root = reader.readTree(originalBody);
            addXmlContentTypes(root);

            byte[] modified = writer.writeValueAsBytes(root);
            response.setContentLength(modified.length);
            response.getOutputStream().write(modified);

            System.out.println("[OPENAPI] Injected application/fhir+xml into spec.");
        } catch (Exception e) {
            System.err.println("[OPENAPI] Failed to patch spec: " + e.getMessage());
            wrapper.copyBodyToResponse();
        }
    }

    private void addXmlContentTypes(JsonNode root) {
        JsonNode paths = root.path("paths");
        if (paths.isMissingNode()) return;

        paths.fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                    JsonNode op = methodEntry.getValue();
                    addXmlToContent(op.path("requestBody").path("content"));
                    JsonNode responses = op.path("responses");
                    if (!responses.isMissingNode()) {
                        responses.fields().forEachRemaining(r ->
                                addXmlToContent(r.getValue().path("content")));
                    }
                }));
    }

    private void addXmlToContent(JsonNode content) {
        if (content.isMissingNode() || !content.isObject()) return;
        ObjectNode node = (ObjectNode) content;
        JsonNode json = node.get("application/fhir+json");
        if (json != null && !node.has("application/fhir+xml")) {
            node.set("application/fhir+xml", json.deepCopy());
        }
    }
}