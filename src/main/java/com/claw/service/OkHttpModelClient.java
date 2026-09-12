package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.stereotype.Component;
import java.io.InterruptedIOException;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

@Component
public class OkHttpModelClient implements ModelClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();

    private String url(ModelRuntimeConfig c) { return c.baseUrl().endsWith("/chat/completions") ? c.baseUrl() : c.baseUrl() + "/chat/completions"; }
    private Request request(ModelRuntimeConfig c, JSONObject body) { return new Request.Builder().url(url(c)).header("Authorization", "Bearer " + c.apiKey()).header("Content-Type", "application/json").post(RequestBody.create(body.toJSONString(), JSON_TYPE)).build(); }

    @Override public JSONObject complete(ModelRuntimeConfig config, JSONObject body) throws Exception {
        try (Response response = client.newCall(request(config, body)).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new ModelClient.RequestException(response.code(), text);
            return JSON.parseObject(text);
        }
    }

    @Override public void stream(ModelRuntimeConfig config, JSONObject body, Consumer<String> onDelta) throws Exception {
        body.put("stream", true);
        try (Response response = client.newCall(request(config, body)).execute()) {
            String error = response.body() == null ? "" : (response.isSuccessful() ? "" : response.body().string());
            if (!response.isSuccessful() || response.body() == null) throw new ModelClient.RequestException(response.code(), error);
            try (var source = response.body().source()) {
                while (!source.exhausted()) {
                    String line = source.readUtf8Line(); if (line == null) break;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim(); if (payload.isBlank() || "[DONE]".equals(payload)) continue;
                    JSONObject event = JSON.parseObject(payload); if (event == null) continue;
                    JSONObject delta = event.getJSONArray("choices") == null || event.getJSONArray("choices").isEmpty() ? null : event.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
                    if (delta != null) { String content = delta.getString("content"); if (content != null && !content.isEmpty()) onDelta.accept(content); }
                }
            }
        }
    }

}
