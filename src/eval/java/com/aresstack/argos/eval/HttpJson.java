package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Minimal JSON-over-HTTP for model endpoints. Errors name the endpoint and status, never the credentials. */
final class HttpJson {
    static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final Duration timeout;
    private final String apiKey;

    HttpJson(Duration timeout, String apiKey) {
        // System proxy settings apply (a remote GPT-OSS endpoint may sit behind one); none means direct.
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .proxy(ProxySelector.getDefault()).build();
        this.timeout = timeout;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    JsonNode post(String url, Object body) throws ChatModel.ModelException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response;
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException timedOut) {
            throw new ChatModel.ModelException("Model request to " + url + " timed out after " + timeout.toSeconds()
                    + " s (raise EVAL_REQUEST_TIMEOUT_S for slow local models)", timedOut);
        } catch (java.io.IOException unreachable) {
            throw new ChatModel.ModelException("Model endpoint " + url + " is not reachable: "
                    + unreachable.getClass().getSimpleName()
                    + (unreachable.getMessage() == null ? "" : " " + unreachable.getMessage()), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ChatModel.ModelException("Interrupted while waiting for " + url, interrupted);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ChatModel.ModelException("Model endpoint " + url + " answered HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        try {
            return JSON.readTree(response.body());
        } catch (Exception invalid) {
            throw new ChatModel.ModelException("Model endpoint " + url + " returned no JSON: "
                    + abbreviate(response.body()), invalid);
        }
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        String visible = apiKey == null ? text : text.replace(apiKey, "***");
        return visible.length() <= 400 ? visible : visible.substring(0, 400) + "...";
    }
}
