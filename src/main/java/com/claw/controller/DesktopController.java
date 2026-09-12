package com.claw.controller;

import com.claw.dto.ChatRequest;
import com.claw.dto.ChatResponse;
import com.claw.dto.DesktopChatStreamEvent;
import com.claw.dto.DesktopFileUploadResponse;
import com.claw.dto.DesktopMessageResponse;
import com.claw.dto.DesktopMonitorSummaryResponse;
import com.claw.dto.DesktopOverviewResponse;
import com.claw.dto.DesktopSessionSummaryResponse;
import com.claw.dto.InvoiceExportResponse;
import com.claw.dto.InvoiceRecognitionRequest;
import com.claw.dto.InvoiceRecognitionResult;
import com.claw.entity.ChatMessageEntity;
import com.claw.entity.ChatSessionEntity;
import com.claw.repository.ChatMessageRepository;
import com.claw.repository.ChatSessionRepository;
import com.claw.service.AiMonitorService;
import com.claw.service.BailianService;
import com.claw.service.BotFacadeService;
import com.claw.service.ConversationMemoryService;
import com.claw.service.DesktopImageSessionService;
import com.claw.service.DesktopVoiceControlService;
import com.claw.service.ExcelMcpService;
import com.claw.service.FileParseService;
import com.claw.service.InvoiceRecognitionService;
import com.claw.service.MessageRoutingService;
import com.claw.service.ResumeKnowledgeService;
import com.claw.service.UserSessionContext;
import com.claw.service.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/desktop")
public class DesktopController {
    private static final String INVOICE_SHEET_NAME = "invoice_ledger";
    private static final Logger log = LoggerFactory.getLogger(DesktopController.class);

    private final BotFacadeService botFacadeService;
    private final BailianService bailianService;
    private final ConversationMemoryService conversationMemoryService;
    private final FileParseService fileParseService;
    private final ExcelMcpService excelMcpService;
    private final DesktopImageSessionService desktopImageSessionService;
    private final InvoiceRecognitionService invoiceRecognitionService;
    private final DesktopVoiceControlService desktopVoiceControlService;
    private final MessageRoutingService messageRoutingService;
    private final ResumeKnowledgeService resumeKnowledgeService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final AiMonitorService aiMonitorService;

    public DesktopController(BotFacadeService botFacadeService,
                             BailianService bailianService,
                             ConversationMemoryService conversationMemoryService,
                             FileParseService fileParseService,
                             ExcelMcpService excelMcpService,
                             DesktopImageSessionService desktopImageSessionService,
                             InvoiceRecognitionService invoiceRecognitionService,
                             DesktopVoiceControlService desktopVoiceControlService,
                             MessageRoutingService messageRoutingService,
                             ResumeKnowledgeService resumeKnowledgeService,
                             ChatSessionRepository chatSessionRepository,
                             ChatMessageRepository chatMessageRepository,
                             ObjectMapper objectMapper,
                             AiMonitorService aiMonitorService) {
        this.botFacadeService = botFacadeService;
        this.bailianService = bailianService;
        this.conversationMemoryService = conversationMemoryService;
        this.fileParseService = fileParseService;
        this.excelMcpService = excelMcpService;
        this.desktopImageSessionService = desktopImageSessionService;
        this.invoiceRecognitionService = invoiceRecognitionService;
        this.desktopVoiceControlService = desktopVoiceControlService;
        this.messageRoutingService = messageRoutingService;
        this.resumeKnowledgeService = resumeKnowledgeService;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.aiMonitorService = aiMonitorService;
    }

    @GetMapping("/overview")
    public DesktopOverviewResponse overview() {
        return new DesktopOverviewResponse(
                "306-token desktop",
                true,
                botFacadeService.hasDashScopeKey(),
                botFacadeService.isWeChatRunning(),
                botFacadeService.ttsModel(),
                botFacadeService.ttsVoiceId()
        );
    }

    @GetMapping("/monitor/summary")
    public DesktopMonitorSummaryResponse monitorSummary() {
        return aiMonitorService.buildDesktopSummary();
    }

    @GetMapping("/sessions")
    public List<DesktopSessionSummaryResponse> sessions(HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        return chatSessionRepository.findTop20ByUserIdOrderByLastActiveDesc(user.userId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @PostMapping("/sessions")
    public DesktopSessionSummaryResponse createSession(HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        ChatSessionEntity session = new ChatSessionEntity("desktop:" + user.userId() + ":" + UUID.randomUUID());
        session.setUserId(user.userId());
        session.setChannelType("desktop");
        chatSessionRepository.save(session);
        return toSummary(session);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String sessionId, HttpServletRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        requireOwnedSession(sessionId, currentUser(request));
        conversationMemoryService.clearSession(sessionId);
        fileParseService.clearSessionContext(sessionId);
        desktopImageSessionService.clearSession(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<DesktopMessageResponse> sessionMessages(@PathVariable String sessionId, HttpServletRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        requireOwnedSession(sessionId, currentUser(request));
        return conversationMemoryService.getMessagesForceLoad(sessionId).stream()
                .map(message -> new DesktopMessageResponse(message.role(), message.content()))
                .toList();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        requireOwnedSession(request.sessionId(), currentUser(httpRequest));
        aiMonitorService.beginDesktopRequest(request.sessionId(), request.message(), false);
        try {
            String controlReply = desktopVoiceControlService.handleMessage(request.sessionId(), request.message());
            if (controlReply != null) {
                aiMonitorService.markRoute("handled_reply", "voice_control");
                conversationMemoryService.addUserMessage(request.sessionId(), request.message());
                conversationMemoryService.addAssistantMessage(request.sessionId(), controlReply);
                aiMonitorService.finishCurrentSuccess();
                return new ChatResponse(request.sessionId(), request.message(), controlReply);
            }

            String reply = botFacadeService.chat(request.sessionId(), request.message());
            aiMonitorService.finishCurrentSuccess();
            return new ChatResponse(request.sessionId(), request.message(), reply);
        } catch (RuntimeException e) {
            aiMonitorService.finishCurrentFailure(e.getMessage());
            throw e;
        } finally {
            aiMonitorService.clearCurrentRequest();
        }
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public StreamingResponseBody chatStream(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        requireOwnedSession(request.sessionId(), currentUser(httpRequest));
        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            aiMonitorService.beginDesktopRequest(request.sessionId(), request.message(), true);
            try {
                writeEvent(writer, new DesktopChatStreamEvent(
                        "status",
                        request.sessionId(),
                        request.message(),
                        null,
                        null,
                        null
                ));

                String controlReply = desktopVoiceControlService.handleMessage(request.sessionId(), request.message());
                if (controlReply != null) {
                    aiMonitorService.markRoute("handled_reply", "voice_control");
                    conversationMemoryService.addUserMessage(request.sessionId(), request.message());
                    conversationMemoryService.addAssistantMessage(request.sessionId(), controlReply);
                    writeDeltaEvent(writer, request.sessionId(), controlReply);
                    writeEvent(writer, new DesktopChatStreamEvent("done", request.sessionId(), null, null, controlReply, null));
                    aiMonitorService.finishCurrentSuccess();
                    return;
                }

                if (desktopImageSessionService.shouldHandleInvoiceQuestion(request.sessionId(), request.message())) {
                    aiMonitorService.markRoute("handled_reply", "invoice_image");
                    DesktopImageSessionService.SessionImage image = desktopImageSessionService.getActiveImage(request.sessionId());
                    String reply = image == null ? "未找到当前会话中的图片，请先上传图片。" : handleInvoiceQuestion(request.sessionId(), request.message(), image);
                    writeDeltaEvent(writer, request.sessionId(), reply);
                    writeEvent(writer, new DesktopChatStreamEvent("done", request.sessionId(), null, null, reply, null));
                    aiMonitorService.finishCurrentSuccess();
                    return;
                }

                if (desktopImageSessionService.shouldHandleGeneralImageQuestion(request.sessionId(), request.message())) {
                    aiMonitorService.markRoute("handled_reply", "general_image");
                    DesktopImageSessionService.SessionImage image = desktopImageSessionService.getActiveImage(request.sessionId());
                    String reply = image == null ? "未找到当前会话中的图片，请先上传图片。" : handleGeneralImageQuestion(request.sessionId(), request.message(), image);
                    writeDeltaEvent(writer, request.sessionId(), reply);
                    writeEvent(writer, new DesktopChatStreamEvent("done", request.sessionId(), null, null, reply, null));
                    aiMonitorService.finishCurrentSuccess();
                    return;
                }

                String reply = botFacadeService.chatStream(
                        request.sessionId(),
                        request.message(),
                        delta -> writeDeltaEventSafely(writer, request.sessionId(), delta)
                );

                writeEvent(writer, new DesktopChatStreamEvent(
                        "done",
                        request.sessionId(),
                        null,
                        null,
                        reply == null ? "" : reply,
                        null
                ));
                aiMonitorService.finishCurrentSuccess();
            } catch (Exception e) {
                aiMonitorService.finishCurrentFailure(e.getMessage());
                if (isClientAbortException(e)) {
                    log.info("Desktop chat stream closed by client, sessionId={}", request.sessionId());
                    return;
                }
                try {
                    writeEvent(writer, new DesktopChatStreamEvent(
                            "error",
                            request.sessionId(),
                            null,
                            null,
                            null,
                            e.getMessage()
                    ));
                } catch (Exception ignored) {
                }
            } finally {
                aiMonitorService.clearCurrentRequest();
            }
        };
    }

    @PostMapping("/files/upload")
    public DesktopFileUploadResponse uploadFile(@RequestParam String sessionId,
                                                HttpServletRequest httpRequest,
                                                @RequestPart("file") MultipartFile file) throws Exception {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        requireOwnedSession(sessionId, currentUser(httpRequest));
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }

        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "uploaded-file"
                : file.getOriginalFilename().trim();
        byte[] bytes = file.getBytes();
        String fileType = fileParseService.detectFileType(fileName, bytes);
        boolean image = desktopImageSessionService.isSupportedImage(fileName, file.getContentType());
        boolean parseable = FileParseService.isParseable(fileName);
        boolean workbook = excelMcpService != null && excelMcpService.supportsWorkbook(fileName);

        String message;
        String persistedUserMessage;
        if (image) {
            persistedUserMessage = "[上传图片] " + fileName;
            message = handleImageUpload(sessionId, fileName, file.getContentType(), bytes);
        } else if (workbook) {
            persistedUserMessage = "[上传文件] " + fileName;
            String storedWorkbook = excelMcpService.storeWorkbook(fileName, bytes);
            fileParseService.rememberSessionFileReference(sessionId, storedWorkbook);
            messageRoutingService.bindExcelSession(sessionId, storedWorkbook);
            message = "已收到 Excel 文件，可以继续提问。";
        } else if (parseable) {
            persistedUserMessage = "[上传文件] " + fileName;
            fileParseService.cacheSessionFile(sessionId, fileName, bytes);
            messageRoutingService.bindFileSession(sessionId, fileName);
            message = autoIngestResumeIfNeeded(sessionId, fileName, bytes)
                    ? "已收到简历，可以继续提问。"
                    : "已收到文件，可以继续提问。";
        } else {
            persistedUserMessage = "[上传文件] " + fileName;
            fileParseService.rememberSessionFileReference(sessionId, fileName);
            message = "已收到文件，可以继续提问。";
        }

        conversationMemoryService.addUserMessage(sessionId, persistedUserMessage);
        conversationMemoryService.addAssistantMessage(sessionId, message);

        return new DesktopFileUploadResponse(
                sessionId,
                fileName,
                fileType,
                parseable || workbook || image,
                "",
                message
        );
    }

    private String handleGeneralImageQuestion(String sessionId, String question, DesktopImageSessionService.SessionImage image) {
        String prompt = """
                请仅根据图片中实际可见内容回答，不要编造。
                如果用户要求重点描述某一部分，请优先回答那一部分。
                如果图片中的文字或细节看不清，请明确说明看不清。
                用户问题：
                """ + question;
        String reply = bailianService.chatWithImage(image.bytes(), prompt);
        String finalReply = reply == null || reply.isBlank()
                ? "图片处理中未返回有效结果，请重试。"
                : reply.trim();
        conversationMemoryService.addUserMessage(sessionId, question);
        conversationMemoryService.addAssistantMessage(sessionId, finalReply);
        return finalReply;
    }

    private String handleImageUpload(String sessionId, String fileName, String contentType, byte[] bytes) {
        desktopImageSessionService.cacheImage(sessionId, fileName, contentType, bytes);
        return "已收到图片。";
    }

    private boolean autoIngestResumeIfNeeded(String sessionId, String fileName, byte[] bytes) {
        if (!isLikelyResumeFile(fileName) || resumeKnowledgeService == null) {
            return false;
        }
        try {
            UserSessionContext context = UserSessionContext.fromSessionId(sessionId);
            Long userId = context == null ? null : context.userId();
            if (userId == null) {
                return false;
            }
            resumeKnowledgeService.ingestResume(userId, fileName, fileName, bytes);
            return true;
        } catch (Exception e) {
            log.warn("auto ingest resume failed, sessionId={}, fileName={}", sessionId, fileName, e);
            return false;
        }
    }

    private boolean isLikelyResumeFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String normalized = fileName.trim().toLowerCase();
        return normalized.contains("简历")
                || normalized.contains("履历")
                || normalized.contains("resume")
                || normalized.contains("cv");
    }

    private String handleInvoiceQuestion(String sessionId, String question, DesktopImageSessionService.SessionImage image) {
        try {
            InvoiceExportResponse response = invoiceRecognitionService.recognizeAndExport(
                    image.bytes(),
                    image.fileName(),
                    new InvoiceRecognitionRequest(),
                    UserSessionContext.fromSessionId(sessionId)
            );
            String message = buildInvoiceUploadMessage(response);
            conversationMemoryService.addUserMessage(sessionId, question);
            conversationMemoryService.addAssistantMessage(sessionId, message);
            desktopImageSessionService.clearSession(sessionId);
            return message;
        } catch (Exception e) {
            String message = "发票识别失败，请重新上传清晰的发票图片。";
            conversationMemoryService.addUserMessage(sessionId, question);
            conversationMemoryService.addAssistantMessage(sessionId, message);
            return message;
        }
    }

    private String buildInvoiceUploadMessage(InvoiceExportResponse response) {
        if (response == null || response.getResult() == null) {
            return "发票识别失败，请重新上传清晰的发票图片。";
        }
        InvoiceRecognitionResult result = response.getResult();
        if (!result.isSuccess()) {
            String errorMessage = result.getErrorMessage();
            return errorMessage == null || errorMessage.isBlank()
                    ? "发票识别失败，请重新上传清晰的发票图片。"
                    : "发票识别失败：" + errorMessage;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已识别发票并生成 Excel。\n");
        sb.append("核验结果：").append(InvoiceRecognitionService.resolveVerifyConclusion(result)).append("\n");
        if (result.getInvoice() != null) {
            appendIfPresent(sb, "发票号码", result.getInvoice().getInvoiceNo());
            appendIfPresent(sb, "开票日期", result.getInvoice().getInvoiceDate());
            appendIfPresent(sb, "价税合计", result.getInvoice().getInvoiceSum());
        }
        if (response.getExcelFileName() != null && response.getExcelPath() != null) {
            sb.append("\nfileName=").append(response.getExcelFileName());
            sb.append("\nfilePath=").append(response.getExcelPath());
            sb.append("\nsheetName=").append(INVOICE_SHEET_NAME);
        }
        return sb.toString().trim();
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append("：").append(value).append('\n');
    }

    private void writeEvent(Writer writer, DesktopChatStreamEvent event) throws Exception {
        writer.write(objectMapper.writeValueAsString(event));
        writer.write('\n');
        writer.flush();
    }

    private void writeDeltaEvent(Writer writer, String sessionId, String delta) throws Exception {
        aiMonitorService.markFirstResponseChunk();
        writeEvent(writer, new DesktopChatStreamEvent("delta", sessionId, null, delta, null, null));
    }

    private void writeEventSafely(Writer writer, DesktopChatStreamEvent event) {
        try {
            writeEvent(writer, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeDeltaEventSafely(Writer writer, String sessionId, String delta) {
        try {
            writeDeltaEvent(writer, sessionId, delta);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isClientAbortException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedIOException || current instanceof InterruptedException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("forcibly closed")
                        || normalized.contains("stream closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private DesktopSessionSummaryResponse toSummary(ChatSessionEntity session) {
        if (session == null) {
            return null;
        }
        ChatMessageEntity latest = chatMessageRepository.findTop1BySessionIdOrderByCreatedAtDesc(session.getSessionId());
        String preview = latest == null ? "" : latest.getContent();
        String title;
        if (preview == null || preview.isBlank()) {
            String suffix = session.getSessionId().length() > 8
                    ? session.getSessionId().substring(session.getSessionId().length() - 8)
                    : session.getSessionId();
            title = "会话 " + suffix;
        } else {
            title = preview.length() > 24 ? preview.substring(0, 24) + "..." : preview;
        }
        return new DesktopSessionSummaryResponse(
                session.getSessionId(),
                title,
                preview == null ? "" : preview,
                session.getLastActive(),
                session.getMessageCount(),
                session.getCurrentMode()
        );
    }

    private AuthenticatedUser currentUser(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthController.USER_ATTRIBUTE);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return user;
    }

    private void requireOwnedSession(String sessionId, AuthenticatedUser user) {
        ChatSessionEntity session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getUserId() == null || !session.getUserId().equals(user.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
    }
}
