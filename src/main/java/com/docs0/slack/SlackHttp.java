package com.docs0.slack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** The few HTTPS calls to slack.com that OAuth needs. Never logs a code, a secret or a token. */
final class SlackHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private SlackHttp() {}

    static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** POSTs a form and returns Slack's JSON. Throws with only Slack's short error code on failure. */
    static JsonObject post(String uri, Map<String, String> params) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build();
        return send(request);
    }

    static JsonObject get(String uri, String bearer) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + bearer).header("Accept", "application/json").GET().build();
        return send(request);
    }

    private static JsonObject send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Slack");
        }
        JsonObject body = json(response.body());
        if (response.statusCode() != 200 || !bool(body, "ok")) {
            String error = text(body, "error");
            throw new IOException("Slack call failed: HTTP " + response.statusCode()
                    + (error == null ? "" : " (" + error.replaceAll("[^A-Za-z0-9_]", "") + ")"));
        }
        return body;
    }

    static JsonObject json(String body) {
        try {
            var parsed = JsonParser.parseString(body == null ? "" : body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static String text(JsonObject object, String field) {
        if (object == null) {
            return null;
        }
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    static boolean bool(JsonObject object, String field) {
        var value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    static JsonObject object(JsonObject parent, String field) {
        var value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
}
