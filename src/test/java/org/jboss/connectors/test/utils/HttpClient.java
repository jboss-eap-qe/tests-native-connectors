package org.jboss.connectors.test.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for making requests through the AJP proxy and verifying responses.
 * Wraps OkHttp with {@code Connection: close} headers to ensure each request
 * gets a fresh connection for accurate proxy testing.
 *
 * <p>Injected into tests by {@link org.jboss.connectors.test.base.ConnectorTestExtension}.
 */
public class HttpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    private final OkHttpClient client;

    public HttpClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .build();
    }

    public HttpResponse get(String url) throws IOException {
        return get(url, new HashMap<>());
    }

    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        headers.putIfAbsent("Connection", "close");
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);

        try (Response response = client.newCall(builder.build()).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    public HttpResponse getWithSession(String url, String sessionCookie) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", sessionCookie);
        return get(url, headers);
    }

    private Map<String, String> extractCookies(Response response) {
        Map<String, String> cookies = new HashMap<>();
        response.headers("Set-Cookie").forEach(cookie -> {
            String[] parts = cookie.split(";")[0].split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        });
        return cookies;
    }

    private Map<String, String> extractHeaders(Response response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().toMultimap().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        return headers;
    }

    public static class HttpResponse {
        private final int statusCode;
        private final String body;
        private final Map<String, String> cookies;
        private final Map<String, String> headers;

        public HttpResponse(int statusCode, String body, Map<String, String> cookies, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.cookies = cookies;
            this.headers = headers;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getCookies() {
            return cookies;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getCookie(String name) {
            return cookies.get(name);
        }

        public String getHeader(String name) {
            return headers.get(name);
        }
    }
}
