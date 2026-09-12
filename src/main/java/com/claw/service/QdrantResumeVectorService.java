package com.claw.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class QdrantResumeVectorService {
    private static final Logger log = LoggerFactory.getLogger(QdrantResumeVectorService.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ResumeEmbeddingService embeddingService;
    private final String baseUrl;
    private final String apiKey;
    private final String collectionName;
    private final int vectorSize;

    public QdrantResumeVectorService(ResumeEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.baseUrl = trimTrailingSlash(ConfigUtil.getQdrantUrl());
        this.apiKey = ConfigUtil.getQdrantApiKey();
        this.collectionName = ConfigUtil.getQdrantCollection();
        this.vectorSize = ConfigUtil.getQdrantVectorSize();
        ensureCollection();
    }

    public void upsertChunk(long pointId,
                            long userId,
                            long documentId,
                            int chunkIndex,
                            String chunkType,
                            String keywords,
                            String content) {
        double[] vector = embeddingService.embed(content);
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("documentId", documentId);
        payload.put("chunkIndex", chunkIndex);
        payload.put("chunkType", chunkType);
        payload.put("keywords", keywords);
        payload.put("content", content);

        JSONObject point = new JSONObject();
        point.put("id", pointId);
        point.put("vector", vectorToJson(vector));
        point.put("payload", payload);

        JSONObject body = new JSONObject();
        JSONArray points = new JSONArray();
        points.add(point);
        body.put("points", points);
        put("/collections/" + collectionName + "/points?wait=true", body);
    }

    public List<SearchHit> search(long userId, String query, int topK) {
        double[] vector = embeddingService.embed(query);
        JSONObject filter = new JSONObject();
        JSONArray must = new JSONArray();
        JSONObject userFilter = new JSONObject();
        userFilter.put("key", "userId");
        JSONObject match = new JSONObject();
        match.put("value", userId);
        userFilter.put("match", match);
        must.add(userFilter);
        filter.put("must", must);

        JSONObject body = new JSONObject();
        body.put("vector", vectorToJson(vector));
        body.put("limit", Math.max(1, topK));
        body.put("with_payload", true);
        body.put("filter", filter);

        JSONObject response = post("/collections/" + collectionName + "/points/search", body);
        JSONArray result = response != null ? response.getJSONArray("result") : null;
        List<SearchHit> hits = new ArrayList<>();
        if (result == null) {
            return hits;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject item = result.getJSONObject(i);
            if (item == null) {
                continue;
            }
            long pointId = parsePointId(item.get("id"));
            double score = item.getDoubleValue("score");
            JSONObject payload = item.getJSONObject("payload");
            hits.add(new SearchHit(pointId, score, payload));
        }
        return hits;
    }

    public void deleteByDocumentId(long documentId) {
        JSONObject filter = new JSONObject();
        JSONArray must = new JSONArray();
        JSONObject docFilter = new JSONObject();
        docFilter.put("key", "documentId");
        JSONObject match = new JSONObject();
        match.put("value", documentId);
        docFilter.put("match", match);
        must.add(docFilter);
        filter.put("must", must);

        JSONObject body = new JSONObject();
        body.put("filter", filter);
        post("/collections/" + collectionName + "/points/delete", body);
    }

    private void ensureCollection() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/collections/" + collectionName)
                    .get()
                    .header("Content-Type", "application/json")
                    .build();
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.newBuilder().header("api-key", apiKey).build();
            }
            try (Response response = execute(request)) {
                if (response.isSuccessful()) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("qdrant collection check failed: {}", e.getMessage());
        }

        JSONObject vectors = new JSONObject();
        vectors.put("size", vectorSize);
        vectors.put("distance", "Cosine");

        JSONObject body = new JSONObject();
        body.put("vectors", vectors);
        try {
            put("/collections/" + collectionName, body);
            log.info("qdrant collection ready: {}", collectionName);
        } catch (Exception e) {
            log.warn("qdrant collection create skipped: {}", e.getMessage());
        }
    }

    private JSONObject post(String path, JSONObject body) {
        return request("POST", path, body);
    }

    private JSONObject put(String path, JSONObject body) {
        return request("PUT", path, body);
    }

    private JSONObject request(String method, String path, JSONObject body) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + path)
                    .method(method, RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .header("Content-Type", "application/json")
                    .build();
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.newBuilder().header("api-key", apiKey).build();
            }
            try (Response response = httpClient.newCall(request).execute()) {
                String content = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Qdrant HTTP " + response.code() + ": " + content);
                }
                return content.isBlank() ? new JSONObject() : JSONObject.parseObject(content);
            }
        } catch (Exception e) {
            throw new RuntimeException("Qdrant request failed: " + path + ", " + e.getMessage(), e);
        }
    }

    private Response execute(Request request) throws IOException {
        return httpClient.newCall(request).execute();
    }

    private double[] vectorToJson(double[] vector) {
        return vector;
    }

    private long parsePointId(Object id) {
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (Exception ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record SearchHit(long pointId, double score, JSONObject payload) {
    }
}
