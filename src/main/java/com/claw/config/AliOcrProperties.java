package com.claw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "aliyun.ocr")
public class AliOcrProperties {
    private boolean enabled;
    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String endpoint = "ocr.cn-shanghai.aliyuncs.com";
    private boolean autoVerify = true;
    private int maxFileSizeMb = 10;
    private int connectTimeoutMillis = 5000;
    private int readTimeoutMillis = 35000;
    private int socketTimeoutMillis = 35000;
    private List<String> defaultTicketKeys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isAutoVerify() {
        return autoVerify;
    }

    public void setAutoVerify(boolean autoVerify) {
        this.autoVerify = autoVerify;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getSocketTimeoutMillis() {
        return socketTimeoutMillis;
    }

    public void setSocketTimeoutMillis(int socketTimeoutMillis) {
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    public List<String> getDefaultTicketKeys() {
        return defaultTicketKeys;
    }

    public void setDefaultTicketKeys(List<String> defaultTicketKeys) {
        this.defaultTicketKeys = defaultTicketKeys;
    }
}
