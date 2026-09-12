package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.function.Consumer;

/** Provider-neutral chat completion transport. */
public interface ModelClient {
    JSONObject complete(ModelRuntimeConfig config, JSONObject request) throws Exception;
    void stream(ModelRuntimeConfig config, JSONObject request, Consumer<String> onDelta) throws Exception;

    final class RequestException extends RuntimeException {
        private final int status;
        public RequestException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
