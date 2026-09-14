package com.claw.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class HttpUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static String doGet(String url) throws IOException {
        return doGet(url, true);
    }

    public static String doGet(String url, boolean logBody) throws IOException {
        return doGet(url, logBody, 15_000L);
    }

    public static String doGet(String url, boolean logBody, long timeoutMs) throws IOException {
        long effectiveTimeoutMs = Math.max(1_000L, timeoutMs);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();
        OkHttpClient requestClient = CLIENT.newBuilder()
                .connectTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

        try (Response response = requestClient.newCall(request).execute()) {
            log.info("GET request, url={}, status={}", safeUrl(url), response.code());
            String body = response.body() == null ? "" : response.body().string();
            log.info("GET response body:{}", logBody ? body : summarizeBody(body));
            if (!response.isSuccessful()) {
                throw new IOException("HTTP request failed, status=" + response.code());
            }
            return body;
        } catch (IOException e) {
            log.warn("HTTP request failed, url={}, reason={}", safeUrl(url), e.getMessage());
            throw new IOException("Network request failed: " + e.getMessage(), e);
        }
    }

    private static String safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return url;
            }
            String safeQuery = query.replaceAll("(?i)(^|&)key=[^&]*", "$1key=***");
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(),
                    safeQuery, uri.getRawFragment()).toString();
        } catch (Exception ignored) {
            return url.replaceAll("(?i)(key=)[^&\\s]*", "$1***");
        }
    }

    private static String summarizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 180) + "..." : normalized;
    }
}
