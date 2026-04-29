package com.nhs;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service("terminologyInterceptor")
public class TerminologyInterceptor implements IClientInterceptor {

    // --- Config (validated eagerly at startup) ---
    private final String TOKEN_URL;
    private final String CLIENT_ID;
    private final String CLIENT_SECRET;

    // --- Token state ---
    private volatile String token = "";
    private volatile Instant expiry = Instant.MIN;

    // --- Reusable HTTP client & JSON parser ---
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TerminologyInterceptor() {
        this.TOKEN_URL     = requireEnv("ONTO_AUTH_URL");
        this.CLIENT_ID     = requireEnv("ONTO_CLIENT_ID");
        this.CLIENT_SECRET = requireEnv("ONTO_CLIENT_SECRET");
    }

    @Override
    public void interceptRequest(IHttpRequest theRequest) {
        if (token.isEmpty() || Instant.now().isAfter(expiry.minusSeconds(60))) {
            refreshToken();
        }
        if (!token.isEmpty()) {
            theRequest.addHeader("Authorization", "Bearer " + token);
        }
    }

    @Override
    public void interceptResponse(IHttpResponse theResponse) {
        // No-op
    }

    private synchronized void refreshToken() {
        if (!token.isEmpty() && Instant.now().isBefore(expiry.minusSeconds(60))) {
            return;
        }

        System.out.println("[INTERCEPTOR] Refreshing OAuth2 token...");

        try {
            String form = "grant_type=client_credentials"
                    + "&client_id="     + URLEncoder.encode(CLIENT_ID,     StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());

                this.token = Objects.requireNonNull(
                        json.path("access_token").asText(null),
                        "Response JSON did not contain 'access_token'"
                );

                long expiresIn = json.path("expires_in").asLong(300);
                this.expiry = Instant.now().plusSeconds(expiresIn);

                System.out.printf("[INTERCEPTOR] Token refreshed. Expires in %d s.%n", expiresIn);

            } else {
                System.err.printf("[INTERCEPTOR ERROR] Auth server returned %d: %s%n",
                        response.statusCode(), response.body());
            }

        } catch (Exception e) {
            System.err.println("[INTERCEPTOR ERROR] Exception during token refresh: " + e.getMessage());
        }
    }

    public String getBearerToken() {
        if (token.isEmpty() || Instant.now().isAfter(expiry.minusSeconds(60))) {
            refreshToken();
        }
        return token;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[INTERCEPTOR] Required environment variable '" + name + "' is not set."
            );
        }
        return value;
    }
}