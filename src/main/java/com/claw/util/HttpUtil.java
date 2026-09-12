package com.claw.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);
    private static final OkHttpClient client = new OkHttpClient.Builder()
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
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            log.info("GET请求地址:{},状态码:{}", url, response.code());
            String body = "";
            if (response.body() != null) {
                body = response.body().string();
            }
            log.info("返回数据:{}", logBody ? body : summarizeBody(body));
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败，状态码：" + response.code());
            }
            return body;
        } catch (IOException e) {
            log.error("HTTP请求失败 url:{}", url, e);
            throw new IOException("网络请求异常：" + e.getMessage());
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
