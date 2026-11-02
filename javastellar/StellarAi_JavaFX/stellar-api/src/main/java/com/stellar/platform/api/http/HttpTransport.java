package com.stellar.platform.api.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellar.platform.api.support.ApiJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

final class HttpTransport {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    HttpTransport(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), ApiJson.mapper());
    }

    HttpTransport(String baseUrl, HttpClient httpClient, ObjectMapper mapper) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    String baseUrl() {
        return baseUrl;
    }

    <T> T get(String path, Class<T> type) {
        return exchange("GET", path, null, type);
    }

    <T> T get(String path, TypeReference<T> type) {
        return exchange("GET", path, null, type);
    }

    <T> T post(String path, Object body, Class<T> type) {
        return exchange("POST", path, body, type);
    }

    <T> T put(String path, Object body, Class<T> type) {
        return exchange("PUT", path, body, type);
    }

    <T> T putBytes(String path, byte[] body, String contentType, Class<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(30))
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                String detail = response.body() == null || response.body().length == 0
                        ? ""
                        : new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException("PUT " + path + " failed: HTTP " + response.statusCode()
                        + (detail.isBlank() ? "" : " — " + detail));
            }
            if (type == Void.class || response.body() == null || response.body().length == 0) {
                return null;
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }

    void delete(String path) {
        exchange("DELETE", path, null, Void.class);
    }

    static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private <T> T exchange(String method, String path, Object body, Class<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "application/json");
            if (body != null) {
                byte[] bytes = mapper.writeValueAsBytes(body);
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else if ("POST".equals(method) || "PUT".equals(method)) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                String detail = response.body() == null || response.body().length == 0
                        ? ""
                        : new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException(method + " " + path + " failed: HTTP " + response.statusCode()
                        + (detail.isBlank() ? "" : " — " + detail));
            }
            if (type == Void.class || response.body() == null || response.body().length == 0) {
                return null;
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }

    private <T> T exchange(String method, String path, Object body, TypeReference<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "application/json");
            if (body != null) {
                byte[] bytes = mapper.writeValueAsBytes(body);
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                String detail = response.body() == null || response.body().length == 0
                        ? ""
                        : new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException(method + " " + path + " failed: HTTP " + response.statusCode()
                        + (detail.isBlank() ? "" : " — " + detail));
            }
            if (response.body() == null || response.body().length == 0) {
                return null;
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }
}
