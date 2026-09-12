package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
public class ConversationService {
    private static final String DEFAULT_REPLY = "暂时没有生成有效回复，请再试一次。";
    private static final String EMPTY_IMAGE_REPLY = "暂时没有识别出有效图片内容，请换一张更清晰的图。";

    private final BailianService bailianService;
    private final ConversationMemoryService memoryService;
    private final MessageRoutingService messageRoutingService;
    private final FileParseService fileParseService;
    private final AiMonitorService aiMonitorService;

    public ConversationService(BailianService bailianService,
                               ConversationMemoryService memoryService,
                               MessageRoutingService messageRoutingService,
                               FileParseService fileParseService,
                               AiMonitorService aiMonitorService) {
        this.bailianService = bailianService;
        this.memoryService = memoryService;
        this.messageRoutingService = messageRoutingService;
        this.fileParseService = fileParseService;
        this.aiMonitorService = aiMonitorService;
    }

    public String reply(String sessionId, String userMessage) {
        return replyInternal(sessionId, userMessage, null);
    }

    public String reply(UserSessionContext context, String userMessage) {
        if (context == null) {
            return reply("", userMessage);
        }
        return replyInternal(context.sessionId(), userMessage, context);
    }

    public String replyStream(String sessionId, String userMessage, Consumer<String> onDelta) {
        String normalized = normalize(userMessage);
        if (normalized.isEmpty()) {
            return "";
        }

        ToolExecutionContextHolder.set(UserSessionContext.fromSessionId(sessionId));
        try {
            MessageRoutingService.RouteResult routeResult = messageRoutingService.route(sessionId, normalized);
            recordMonitorRoute(routeResult);
            String reply;
            if (routeResult.replyText() != null) {
                reply = routeResult.replyText().isBlank() ? "" : finalizeReply(routeResult.replyText());
                if (!reply.isBlank() && onDelta != null) {
                    onDelta.accept(reply);
                }
            } else if (routeResult.forcedToolRoute() != null) {
                reply = finalizeReply(bailianService.replyWithForcedToolStream(
                        UserSessionContext.fromSessionId(sessionId),
                        userMessage,
                        routeResult.forcedToolRoute().functionName(),
                        routeResult.forcedToolRoute().arguments(),
                        routeResult.forcedToolRoute().renderWithModel(),
                        onDelta
                ));
            } else if (routeResult.preferredToolRoute() != null) {
                reply = processAgentMessage(sessionId, normalized, routeResult.preferredToolRoute());
                if (!reply.isBlank() && onDelta != null) {
                    onDelta.accept(reply);
                }
            } else if (routeResult.normalChat()) {
                List<ConversationMemoryService.ConversationMessage> history = memoryService.getMessages(sessionId);
                reply = finalizeReply(bailianService.streamPlainChat(history, normalized, onDelta));
            } else {
                reply = DEFAULT_REPLY;
                if (onDelta != null) {
                    onDelta.accept(reply);
                }
            }

            memoryService.addUserMessage(sessionId, normalized);
            memoryService.addAssistantMessage(sessionId, reply);
            return finalizeReply(reply);
        } finally {
            ToolExecutionContextHolder.clear();
        }
    }

    private String replyInternal(String sessionId, String userMessage, UserSessionContext context) {
        String normalized = normalize(userMessage);
        if (normalized.isEmpty()) {
            return "";
        }

        ToolExecutionContextHolder.set(context);
        try {
            MessageRoutingService.RouteResult routeResult = context == null
                    ? messageRoutingService.route(sessionId, normalized)
                    : messageRoutingService.route(context, normalized);
            recordMonitorRoute(routeResult);
            if (routeResult.replyText() != null) {
                return routeResult.replyText().isBlank() ? "" : finalizeReply(routeResult.replyText());
            }

            String reply;
            if (routeResult.forcedToolRoute() != null) {
                reply = bailianService.replyWithForcedTool(
                        context,
                        userMessage,
                        routeResult.forcedToolRoute().functionName(),
                        routeResult.forcedToolRoute().arguments(),
                        routeResult.forcedToolRoute().renderWithModel()
                );
            } else if (routeResult.preferredToolRoute() != null) {
                reply = processAgentMessage(sessionId, normalized, routeResult.preferredToolRoute());
            } else if (routeResult.normalChat()) {
                reply = processNormalMessage(sessionId, normalized);
            } else {
                reply = DEFAULT_REPLY;
            }

            memoryService.addUserMessage(sessionId, normalized);
            memoryService.addAssistantMessage(sessionId, reply);
            return finalizeReply(reply);
        } finally {
            ToolExecutionContextHolder.clear();
        }
    }

    public String replyToImage(String sessionId, String imageSummary) {
        String normalizedSummary = normalize(imageSummary);
        if (normalizedSummary.isEmpty()) {
            return EMPTY_IMAGE_REPLY;
        }

        String syntheticUserMessage = "I sent an image. The image summary is: " + normalizedSummary;
        String reply = processNormalMessage(sessionId, syntheticUserMessage);
        memoryService.addUserMessage(sessionId, syntheticUserMessage);
        memoryService.addAssistantMessage(sessionId, reply);
        return finalizeReply(reply);
    }

    public List<ConversationMemoryService.ConversationMessage> getHistory(String sessionId) {
        return memoryService.getMessages(sessionId);
    }

    public void clearMemory(String sessionId) {
        memoryService.clearSession(sessionId);
        messageRoutingService.clearSessionState(sessionId);
        fileParseService.clearSessionContext(sessionId);
    }

    public void clearMemory(UserSessionContext context) {
        if (context == null) {
            return;
        }
        clearMemory(context.sessionId());
    }

    public ConversationMemoryService.ResumeSnapshot forceLoadHistory(String sessionId) {
        return memoryService.forceResumeSession(sessionId);
    }

    public ConversationMemoryService.ResumeSnapshot forceLoadHistory(UserSessionContext context) {
        return context == null ? null : forceLoadHistory(context.sessionId());
    }

    public int getMemorySize(String sessionId) {
        return memoryService.getMessageCount(sessionId);
    }

    public int getMemorySize(UserSessionContext context) {
        return context == null ? 0 : getMemorySize(context.sessionId());
    }

    public boolean hasMemory(String sessionId) {
        return memoryService.hasAnySessionData(sessionId);
    }

    public void shutdown() {
        memoryService.shutdown();
    }

    private String processNormalMessage(String sessionId, String text) {
        List<ConversationMemoryService.ConversationMessage> history = memoryService.getMessages(sessionId);
        String reply = bailianService.chatWithTools(history, text);
        return finalizeReply(reply);
    }

    private String processAgentMessage(String sessionId, String text, ToolIntentRouter.ToolRoute preferredRoute) {
        List<ConversationMemoryService.ConversationMessage> history = memoryService.getMessages(sessionId);
        String reply = bailianService.chatWithTools(history, text, preferredRoute);
        return finalizeReply(reply);
    }

    private void recordMonitorRoute(MessageRoutingService.RouteResult routeResult) {
        if (routeResult == null) {
            return;
        }
        if (routeResult.replyText() != null) {
            aiMonitorService.markRoute("handled_reply", null);
            return;
        }
        if (routeResult.forcedToolRoute() != null) {
            aiMonitorService.markRoute("force_tool", routeResult.forcedToolRoute().functionName());
            return;
        }
        if (routeResult.preferredToolRoute() != null) {
            aiMonitorService.markRoute("prefer_agent", routeResult.preferredToolRoute().functionName());
            return;
        }
        if (routeResult.normalChat()) {
            aiMonitorService.markRoute("normal_chat", null);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private String finalizeReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return DEFAULT_REPLY;
        }
        String cleaned = reply
                .replaceAll("(?s)```(?:json|JSON)?\\s*.*?```", "")
                .replaceAll("(?m)^.*(?:正在调用|调用`?parse_file`?|工具解析|工具调用).*$", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)<p\\s*>", "")
                .replaceAll("(?i)<[^>]+>", "")
                .replaceAll("(?s)【[^】]*】", "")
                .replaceAll("(?s)\\[[^\\]]*\\]", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return cleaned.isBlank() ? DEFAULT_REPLY : cleaned;
    }
}
