package com.claw.service;

import com.claw.dto.DesktopMonitorRecentRequestResponse;
import com.claw.dto.DesktopMonitorSummaryResponse;
import com.claw.util.ConfigUtil;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AiMonitorService {
    private static final int MAX_RECENT_REQUESTS = 120;

    private final Deque<RequestSnapshot> recentRequests = new ConcurrentLinkedDeque<>();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder totalDurationMs = new LongAdder();
    private final LongAdder totalFirstTokenMs = new LongAdder();
    private final LongAdder firstTokenSamples = new LongAdder();
    private final LongAdder totalPromptTokens = new LongAdder();
    private final LongAdder totalCompletionTokens = new LongAdder();
    private final LongAdder totalTokens = new LongAdder();

    public void beginDesktopRequest(String sessionId, String userMessage, boolean stream) {
        RequestTrace trace = new RequestTrace(
                "desktop-" + UUID.randomUUID(),
                sessionId == null ? "" : sessionId,
                "desktop",
                stream ? "chat_stream" : "chat",
                stream,
                Instant.now(),
                userMessage == null ? 0 : userMessage.length()
        );
        AiMonitorContextHolder.set(trace);
    }

    public void clearCurrentRequest() {
        AiMonitorContextHolder.clear();
    }

    public void markRoute(String routeType, String routeTarget) {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null) {
            return;
        }
        trace.routeType = routeType;
        trace.routeTarget = routeTarget;
    }

    public void recordToolCall(String functionName) {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null || functionName == null || functionName.isBlank()) {
            return;
        }
        trace.toolCallCount++;
        trace.routeTarget = trace.routeTarget == null || trace.routeTarget.isBlank()
                ? functionName
                : trace.routeTarget;
        trace.toolNames.add(functionName);
    }

    public void recordModelSelection(String model) {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null || model == null || model.isBlank()) {
            return;
        }
        trace.model = model;
        trace.models.add(model);
    }

    public void recordTokenUsage(String model, Integer promptTokens, Integer completionTokens, Integer totalTokenCount) {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null) {
            return;
        }
        recordModelSelection(model);

        long prompt = promptTokens == null ? 0L : Math.max(promptTokens, 0);
        long completion = completionTokens == null ? 0L : Math.max(completionTokens, 0);
        long total = totalTokenCount == null ? prompt + completion : Math.max(totalTokenCount, 0);
        if (prompt == 0L && completion == 0L && total == 0L) {
            return;
        }

        trace.promptTokens += prompt;
        trace.completionTokens += completion;
        trace.totalTokens += total;
    }

    public void markFirstResponseChunk() {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null) {
            return;
        }
        trace.markFirstToken();
    }

    public void finishCurrentSuccess() {
        finishCurrent(true, null);
    }

    public void finishCurrentFailure(String errorMessage) {
        finishCurrent(false, errorMessage);
    }

    public DesktopMonitorSummaryResponse buildDesktopSummary() {
        long total = totalRequests.sum();
        long success = successRequests.sum();
        long failed = failedRequests.sum();
        long avgDuration = total == 0 ? 0L : totalDurationMs.sum() / total;
        long firstTokenCount = firstTokenSamples.sum();
        Long avgFirstToken = firstTokenCount == 0 ? null : totalFirstTokenMs.sum() / firstTokenCount;

        List<DesktopMonitorRecentRequestResponse> recent = new ArrayList<>();
        for (RequestSnapshot snapshot : recentRequests) {
            recent.add(snapshot.toResponse());
        }

        return new DesktopMonitorSummaryResponse(
                ConfigUtil.getBailianModel(),
                total,
                success,
                failed,
                avgDuration,
                avgFirstToken,
                totalPromptTokens.sum(),
                totalCompletionTokens.sum(),
                totalTokens.sum(),
                recent
        );
    }

    private void finishCurrent(boolean success, String errorMessage) {
        RequestTrace trace = AiMonitorContextHolder.get();
        if (trace == null || !trace.completed.compareAndSet(false, true)) {
            return;
        }

        long durationMs = Math.max(0L, Instant.now().toEpochMilli() - trace.startedAt.toEpochMilli());
        Long firstTokenMs = trace.firstTokenAt == null
                ? null
                : Math.max(0L, trace.firstTokenAt.toEpochMilli() - trace.startedAt.toEpochMilli());

        totalRequests.increment();
        totalDurationMs.add(durationMs);
        totalPromptTokens.add(trace.promptTokens);
        totalCompletionTokens.add(trace.completionTokens);
        totalTokens.add(trace.totalTokens);
        if (firstTokenMs != null) {
            totalFirstTokenMs.add(firstTokenMs);
            firstTokenSamples.increment();
        }
        if (success) {
            successRequests.increment();
        } else {
            failedRequests.increment();
        }

        recentRequests.addFirst(new RequestSnapshot(
                trace.requestId,
                trace.sessionId,
                trace.channel,
                trace.requestType,
                defaultText(trace.routeType, "unknown"),
                trace.routeTarget,
                trace.model,
                trace.stream,
                success,
                durationMs,
                firstTokenMs,
                trace.promptTokens,
                trace.completionTokens,
                trace.totalTokens,
                trace.toolCallCount,
                trace.startedAt,
                errorMessage
        ));
        while (recentRequests.size() > MAX_RECENT_REQUESTS) {
            recentRequests.pollLast();
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static final class RequestTrace {
        private final String requestId;
        private final String sessionId;
        private final String channel;
        private final String requestType;
        private final boolean stream;
        private final Instant startedAt;
        @SuppressWarnings("unused")
        private final int messageLength;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final Set<String> toolNames = new java.util.LinkedHashSet<>();
        private final Set<String> models = new java.util.LinkedHashSet<>();
        private volatile Instant firstTokenAt;
        private volatile String routeType = "unclassified";
        private volatile String routeTarget;
        private volatile String model;
        private volatile long promptTokens;
        private volatile long completionTokens;
        private volatile long totalTokens;
        private volatile int toolCallCount;

        private RequestTrace(String requestId,
                             String sessionId,
                             String channel,
                             String requestType,
                             boolean stream,
                             Instant startedAt,
                             int messageLength) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.channel = channel;
            this.requestType = requestType;
            this.stream = stream;
            this.startedAt = startedAt;
            this.messageLength = messageLength;
        }

        private void markFirstToken() {
            if (firstTokenAt == null) {
                firstTokenAt = Instant.now();
            }
        }
    }

    private record RequestSnapshot(
            String requestId,
            String sessionId,
            String channel,
            String requestType,
            String routeType,
            String routeTarget,
            String model,
            boolean stream,
            boolean success,
            long durationMs,
            Long firstTokenMs,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            int toolCallCount,
            Instant startedAt,
            String errorMessage
    ) {
        private DesktopMonitorRecentRequestResponse toResponse() {
            return new DesktopMonitorRecentRequestResponse(
                    requestId,
                    sessionId,
                    channel,
                    requestType,
                    routeType,
                    routeTarget,
                    model,
                    stream,
                    success,
                    durationMs,
                    firstTokenMs,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    toolCallCount,
                    startedAt,
                    errorMessage
            );
        }
    }
}
