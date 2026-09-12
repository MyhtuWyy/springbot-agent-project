package com.claw.service;

import com.claw.dto.InvoiceExportResponse;
import com.claw.dto.InvoiceRecognitionRequest;
import com.claw.dto.InvoiceRecognitionResult;
import com.claw.util.ConfigUtil;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Scope("prototype")
public class WeChatService {
    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);

    private static final long MESSAGE_AGGREGATION_WINDOW_MS = 1200L;
    private static final int MAX_BATCH_SEGMENTS = 15;
    private static final long IMAGE_INTENT_TTL_MS = 120_000L;
    private static final long INVOICE_TASK_TTL_MS = 10 * 60 * 1000L;
    private static final Pattern DIRECT_VOICE_ID_PATTERN = Pattern.compile("(?i)[a-z0-9][a-z0-9_-]{2,}");
    private static final Pattern EMBEDDED_VOICE_ID_PATTERN = Pattern.compile(
            "(?i)(?:voice(?:id)?|voice-set)\\s*[:\\uFF1A]?\\s*([a-z0-9_-]{3,})"
    );

    private final ILinkClient client;
    private final BailianService bailianService;
    private final SpeechService speechService;
    private final ConversationService conversationService;
    private final FileParseService fileParseService;
    private final ExcelMcpService excelMcpService;
    private final InvoiceRecognitionService invoiceRecognitionService;
    private final InvoiceImageIntentService invoiceImageIntentService;
    private final UserIdentityService userIdentityService;

    private final Map<String, String> allVoiceAliasMap = ConfigUtil.getTtsVoiceAliasMap();
    private final Map<String, String> verifiedVoiceAliasMap = ConfigUtil.getTtsVerifiedVoiceAliasMap();
    private final Map<String, LinkedHashMap<String, String>> voiceCategoryOptions = buildVoiceCategoryOptions();

    private final ConcurrentHashMap<String, String> userVoiceIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> userVoiceReplyEnabled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingVoiceSelection> pendingVoiceSelections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingVoiceFallback> pendingVoiceFallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> userExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingIncomingBatch> pendingIncomingBatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingIncomingFlushTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<PendingAttachment>> pendingAttachments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingImageIntent> pendingImageIntents = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingExcelExport> pendingExcelExports = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Long> invoiceTaskMarkers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> exportPushMarkers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wechat-user-cleanup");
        t.setDaemon(true);
        return t;
    });

    private volatile LoginContext loginContext;
    private volatile boolean running;

    public WeChatService(BailianService bailianService,
                         SpeechService speechService,
                         ConversationService conversationService,
                         FileParseService fileParseService,
                         ExcelMcpService excelMcpService,
                         InvoiceRecognitionService invoiceRecognitionService,
                         InvoiceImageIntentService invoiceImageIntentService,
                         UserIdentityService userIdentityService) {
        this.bailianService = bailianService;
        this.speechService = speechService;
        this.conversationService = conversationService;
        this.fileParseService = fileParseService;
        this.excelMcpService = excelMcpService;
        this.invoiceRecognitionService = invoiceRecognitionService;
        this.invoiceImageIntentService = invoiceImageIntentService;
        this.userIdentityService = userIdentityService;

        cleanupScheduler.scheduleAtFixedRate(this::cleanupExecutors, 5, 5, TimeUnit.MINUTES);

        client = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .writeTimeoutMs(35000)
                        .httpMaxRetries(3)
                        .retryBaseDelayMs(1000)
                        .retryMaxDelayMs(10000)
                        .heartbeatEnabled(true)
                        .heartbeatIntervalMs(30000)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        loginContext = context;
                        running = true;
                        log.info("WeChat login success. botId={}", context.getBotId());
                        flushPendingExcelExports();
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("WeChat login failure", throwable);
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        if (messages == null) {
                            return;
                        }
                        for (WeixinMessage message : messages) {
                            handleIncomingMessage(message);
                        }
                    }
                })
                .build();
    }

    private Map<String, LinkedHashMap<String, String>> buildVoiceCategoryOptions() {
        LinkedHashMap<String, LinkedHashMap<String, String>> categories = new LinkedHashMap<>();

        LinkedHashMap<String, String> male = new LinkedHashMap<>();
        for (String alias : ConfigUtil.getTtsMaleVoiceOptionAliases()) {
            putIfVerifiedAliasExists(male, alias);
        }
        categories.put("male", male);

        LinkedHashMap<String, String> female = new LinkedHashMap<>();
        for (String alias : ConfigUtil.getTtsFemaleVoiceOptionAliases()) {
            putIfVerifiedAliasExists(female, alias);
        }
        categories.put("female", female);

        return categories;
    }

    private void putIfVerifiedAliasExists(Map<String, String> target, String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        String voiceId = verifiedVoiceAliasMap.get(alias.toLowerCase());
        if (voiceId != null && !voiceId.isBlank()) {
            target.put(alias, voiceId);
        }
    }
    public void start() {
        try {
            running = false;
            String qrCode = client.executeLogin();
            System.out.println("\u8bf7\u4f7f\u7528\u5fae\u4fe1\u626b\u7801\u767b\u5f55\uff1a");
            System.out.println(qrCode);
            loginContext = client.getLoginFuture().get();
            running = true;
            log.info("WeChat login established. botId={}", loginContext.getBotId());
            flushPendingExcelExports();
        } catch (Exception e) {
            running = false;
            log.error("WeChat start failed", e);
        }
    }

    public void stop() {
        running = false;
        pendingIncomingFlushTasks.values().forEach(task -> task.cancel(false));
        pendingIncomingFlushTasks.clear();
        pendingIncomingBatches.clear();
        pendingAttachments.clear();
        pendingImageIntents.clear();
        userExecutors.values().forEach(ExecutorService::shutdown);
        userExecutors.clear();
        cleanupScheduler.shutdown();
        conversationService.shutdown();
        client.close();
    }

    public boolean isConnected() {
        return running && loginContext != null;
    }

    public void sendImage(String userId, String imagePath, String caption) throws Exception {
        byte[] imageBytes = Files.readAllBytes(Paths.get(imagePath));
        String fileName = Paths.get(imagePath).getFileName().toString();
        client.sendImage(userId, imageBytes, fileName, caption);
    }

    public void sendVoice(String userId, String voicePath, Integer playtimeSec, Integer sampleRate) throws Exception {
        byte[] voiceBytes = Files.readAllBytes(Paths.get(voicePath));
        String fileName = Paths.get(voicePath).getFileName().toString();
        client.sendVoice(userId, voiceBytes, fileName, playtimeSec, sampleRate);
    }

    public void sendMessage(String userId, String text) throws Exception {
        client.sendText(userId, sanitizeOutgoingText(text));
    }

    public void sendTextWithTyping(String userId, String text, long typingDurationMs) {
        try {
            client.sendTextWithTyping(userId, sanitizeOutgoingText(text), typingDurationMs);
        } catch (Exception e) {
            log.error("Send text with typing failed", e);
        }
    }

    public void onExcelExportReady(ExcelExportReadyEvent event) {
        if (event == null || event.userId() == null || event.filePath() == null) {
            return;
        }
        PendingExcelExport export = new PendingExcelExport(
                event.exportId(),
                event.userId(),
                event.filePath(),
                firstNonBlank(event.fileName(), event.filePath().getFileName().toString()),
                firstNonBlank(event.caption(), "\u0045\u0078\u0063\u0065\u006c\u9644\u4ef6"),
                buildExcelExportText(event.messageText(), event.filePath(), event.fileName())
        );
        if (!isConnected()) {
            pendingExcelExports.add(export);
            log.info("Queue excel export message until WeChat reconnects. userId={}, filePath={}", event.userId(), event.filePath());
            return;
        }
        sendPendingExcelExport(export);
    }

    private void flushPendingExcelExports() {
        PendingExcelExport export;
        while ((export = pendingExcelExports.poll()) != null) {
            sendPendingExcelExport(export);
        }
    }

    private void sendPendingExcelExport(PendingExcelExport export) {
        try {
            if (export == null || export.filePath() == null || !Files.exists(export.filePath())) {
                return;
            }
            cleanupTaskMarkers();
            if (export.exportId() != null && !export.exportId().isBlank()
                    && exportPushMarkers.putIfAbsent(export.exportId(), System.currentTimeMillis()) != null) {
                log.info("Skip duplicate excel push in WeChatService. exportId={}, userId={}",
                        export.exportId(), export.userId());
                return;
            }
            byte[] bytes = Files.readAllBytes(export.filePath());
            sendFileBytes(export.userId(), bytes, export.fileName(), export.caption());
            safeSendText(export.userId(), export.messageText());
            log.info("Excel attachment sent. userId={}, fileName={}, filePath={}",
                    export.userId(), export.fileName(), export.filePath().toAbsolutePath());
        } catch (Exception e) {
            if (export != null && export.exportId() != null) {
                exportPushMarkers.remove(export.exportId());
            }
            log.error("Send excel attachment failed. userId={}, filePath={}", export.userId(), export.filePath(), e);
            pendingExcelExports.add(export);
        }
    }

    private void cacheAttachments(String userId, List<IncomingSegment> segments) {
        if (userId == null || userId.isBlank() || segments == null || segments.isEmpty()) {
            return;
        }
        List<PendingAttachment> cached = new ArrayList<>();
        for (IncomingSegment segment : segments) {
            if (segment == null || segment.item() == null) {
                continue;
            }
            try {
                switch (segment.type()) {
                    case IMAGE -> cached.add(new PendingAttachment(
                            IncomingSegmentType.IMAGE,
                            client.downloadImageFromMessageItem(segment.item()),
                            buildSourceName(segment),
                            "image",
                            ""
                    ));
                    case FILE -> cached.add(new PendingAttachment(
                            IncomingSegmentType.FILE,
                            client.downloadFileFromMessageItem(segment.item()),
                            buildSourceName(segment),
                            "file",
                            ""
                    ));
                    case VOICE -> cached.add(new PendingAttachment(
                            IncomingSegmentType.VOICE,
                            client.downloadVoiceFromMessageItem(segment.item()),
                            buildSourceName(segment),
                            "voice",
                            ""
                    ));
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.warn("Cache attachment failed. userId={}, type={}", userId, segment.type(), e);
            }
        }
        if (!cached.isEmpty()) {
            pendingAttachments.put(userId, cached);
        }
    }

    private String buildAttachmentAckMessage(List<IncomingSegment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "\u5df2\u6536\u5230\u9644\u4ef6";
        }
        long imageCount = attachments.stream().filter(a -> a != null && a.type() == IncomingSegmentType.IMAGE).count();
        long fileCount = attachments.stream().filter(a -> a != null && a.type() == IncomingSegmentType.FILE).count();
        long voiceCount = attachments.stream().filter(a -> a != null && a.type() == IncomingSegmentType.VOICE).count();
        return "\u5df2\u6536\u5230\u9644\u4ef6\uff1a\u56fe\u7247" + imageCount + "\u4e2a\uff0c\u6587\u4ef6" + fileCount + "\u4e2a\uff0c\u8bed\u97f3" + voiceCount + "\u4e2a";
    }

    private String buildTextMessageWithCachedAttachments(String text, List<PendingAttachment> cached) {
        if (cached == null || cached.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder(text == null ? "" : text.trim());
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append("\u9644\u4ef6\uff1a");
        for (int i = 0; i < cached.size(); i++) {
            PendingAttachment attachment = cached.get(i);
            if (attachment == null) {
                continue;
            }
            if (i > 0) {
                sb.append("\uff0c");
            }
            sb.append(firstNonBlank(attachment.fileName(), attachment.fileType(), "\u9644\u4ef6"));
        }
        return sb.toString();
    }

    private void rememberImageIntent(String userId, String textHint) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        pendingImageIntents.put(userId, new PendingImageIntent(InvoiceImageIntentService.ImageKind.UNKNOWN, InvoiceImageIntentService.ImageAction.WAIT_USER_INPUT, textHint, System.currentTimeMillis()));
    }

    private PendingImageIntent consumePendingImageIntent(String userId) {
        PendingImageIntent intent = pendingImageIntents.remove(userId);
        return intent == null || System.currentTimeMillis() - intent.updatedAt() > IMAGE_INTENT_TTL_MS ? null : intent;
    }

    private String buildImagePrompt(String sessionId) {
        return "\u8bf7\u6839\u636e\u56fe\u7247\u5185\u5bb9\u56de\u7b54";
    }

    private String buildDecisionStatusText(String userId, UserControlDecision decision) {
        return "\u5f53\u524d\u8bed\u97f3\u56de\u590d\u72b6\u6001\u5df2\u66f4\u65b0";
    }

    private boolean mayBeImmediateControlMessage(String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return false;
        }
        return isMemoryClearCommand(normalized)
                || hasGenericVoiceSwitchIntent(normalized)
                || hasVoiceStyleIntent(normalized)
                || containsAny(normalized,
                "语音回复", "文字回复", "当前音色", "重置音色", "恢复默认音色",
                "切换音色", "换音色", "改音色", "当前语音回复", "是不是语音回复");
    }

    private void applyControlDecision(String userId, UserControlDecision decision) {
        if (userId == null || decision == null) {
            return;
        }
        if (decision.resetVoice()) {
            userVoiceIds.remove(userId);
        }
        if (decision.voiceId() != null && !decision.voiceId().isBlank()) {
            userVoiceIds.put(userId, decision.voiceId());
        }
        if (decision.sessionVoiceReply() != null) {
            userVoiceReplyEnabled.put(userId, decision.sessionVoiceReply());
        }
        if (decision.currentMessageVoiceReply() != null) {
            userVoiceReplyEnabled.put(userId, decision.currentMessageVoiceReply());
        }
    }

    private UserControlDecision interpretUserControl(String text) {
        String normalized = normalizeText(text);
        boolean resetVoice = normalized.contains("\u91cd\u7f6e\u97f3\u8272") || normalized.contains("\u6062\u590d\u9ed8\u8ba4\u97f3\u8272");
        boolean queryVoice = normalized.contains("\u67e5\u8be2\u97f3\u8272") || normalized.contains("\u5f53\u524d\u97f3\u8272");
        Boolean sessionVoiceReply = normalized.contains("\u5f00\u542f\u8bed\u97f3\u56de\u590d") ? Boolean.TRUE : normalized.contains("\u5173\u95ed\u8bed\u97f3\u56de\u590d") ? Boolean.FALSE : null;
        return new UserControlDecision(null, resetVoice, queryVoice, sessionVoiceReply, null, text, queryVoice, null);
    }

    private boolean handlePendingVoiceFallback(String userId, String normalized) {
        PendingVoiceFallback pending = pendingVoiceFallbacks.remove(userId);
        if (pending == null) {
            return false;
        }
        if (normalized.contains("\u662f")) {
            userVoiceIds.put(userId, pending.defaultVoiceId());
            safeSendText(userId, "\u5df2\u5207\u6362\u4e3a\u9ed8\u8ba4\u97f3\u8272");
            return true;
        }
        if (normalized.contains("\u5426")) {
            userVoiceIds.put(userId, pending.invalidVoiceId());
            safeSendText(userId, "\u5df2\u4fdd\u7559\u5f53\u524d\u97f3\u8272");
            return true;
        }
        pendingVoiceFallbacks.put(userId, pending);
        return false;
    }

    private boolean handlePendingVoiceSelection(String userId, String normalized, boolean fromVoice) {
        PendingVoiceSelection pending = pendingVoiceSelections.get(userId);
        if (pending == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : pending.options().entrySet()) {
            if (normalizeText(entry.getKey()).equals(normalized) || normalizeText(entry.getValue()).equals(normalized)) {
                userVoiceIds.put(userId, entry.getValue());
                pendingVoiceSelections.remove(userId);
                safeSendText(userId, "\u5df2\u5207\u6362\u5230" + entry.getKey());
                return true;
            }
        }
        return false;
    }

    private String buildResumeReply(ConversationMemoryService.ResumeSnapshot snapshot) {
        if (snapshot == null || !snapshot.found()) {
            return "\u672a\u627e\u5230\u53ef\u6062\u590d\u7684\u5386\u53f2\u8bb0\u5f55";
        }
        return "\u5df2\u6062\u590d\u5386\u53f2\u8bb0\u5f55\uff0c\u6d88\u606f\u6570\uff1a" + snapshot.loadedMessageCount();
    }

    private boolean isMemoryClearCommand(String normalized) {
        return normalized != null && containsAny(normalized, "\u6e05\u9664\u8bb0\u5fc6", "\u6e05\u7a7a\u8bb0\u5fc6", "\u6e05\u9664\u5386\u53f2", "\u6e05\u7a7a\u5386\u53f2");
    }

    private boolean isResumeCommand(String normalized) {
        return normalized != null && containsAny(normalized, "\u6062\u590d\u5386\u53f2", "\u6062\u590d\u4f1a\u8bdd", "\u7ee7\u7eed\u4e0a\u6b21", "\u7ee7\u7eed\u5bf9\u8bdd");
    }

    private void sendReplyByMode(String userId, String text, boolean preferVoice, boolean forceText) {
        safeSendText(userId, text);
    }

    private void safeSendText(String userId, String text) {
        String sanitized = sanitizeOutgoingText(text);
        if (userId == null || userId.isBlank() || sanitized.isBlank()) {
            return;
        }
        try {
            sendMessage(userId, sanitized);
        } catch (Exception e) {
            log.error("Send text message failed. userId={}", userId, e);
        }
    }

    public void sendFileAttachment(String userId, Path filePath, String fileName, String caption) throws Exception {
        if (filePath == null || !Files.exists(filePath)) {
            throw new IllegalArgumentException("filePath is missing");
        }
        byte[] bytes = Files.readAllBytes(filePath);
        sendFileBytes(userId, bytes, fileName, caption);
    }

    private boolean isVoiceReplyEnabled(String userId) {
        return Boolean.TRUE.equals(userVoiceReplyEnabled.get(userId));
    }

    private void sendFileBytes(String userId, byte[] fileBytes, String fileName, String caption) throws Exception {
        client.sendFile(userId, fileBytes, fileName, caption);
    }

    private void handleIncomingMessage(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) {
            return;
        }
        String fromUserId = msg.getFrom_user_id();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null && !item.getText_item().getText().isBlank()) {
                enqueueIncomingSegment(fromUserId, new IncomingSegment(IncomingSegmentType.TEXT, item.getText_item().getText(), item));
            }
            if (item.getImage_item() != null) {
                enqueueIncomingSegment(fromUserId, new IncomingSegment(IncomingSegmentType.IMAGE, null, item));
            }
            if (item.getFile_item() != null) {
                enqueueIncomingSegment(fromUserId, new IncomingSegment(IncomingSegmentType.FILE, null, item));
            }
            if (item.getVoice_item() != null) {
                enqueueIncomingSegment(fromUserId, new IncomingSegment(IncomingSegmentType.VOICE, null, item));
            }
        }
    }

    private void enqueueIncomingSegment(String userId, IncomingSegment segment) {
        if (userId == null || userId.isBlank() || segment == null) {
            return;
        }
        pendingIncomingBatches.compute(userId, (key, existing) -> {
            PendingIncomingBatch batch = existing != null ? existing : new PendingIncomingBatch();
            if (batch.size() < MAX_BATCH_SEGMENTS) {
                batch.addSegment(segment);
            } else {
                log.warn("Pending batch overflow ignored. userId={}, maxSegments={}", userId, MAX_BATCH_SEGMENTS);
            }
            return batch;
        });

        ScheduledFuture<?> previous = pendingIncomingFlushTasks.remove(userId);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> next = cleanupScheduler.schedule(
                () -> flushIncomingBatch(userId),
                MESSAGE_AGGREGATION_WINDOW_MS,
                TimeUnit.MILLISECONDS
        );
        pendingIncomingFlushTasks.put(userId, next);
    }

    private void flushIncomingBatch(String userId) {
        pendingIncomingFlushTasks.remove(userId);
        PendingIncomingBatch batch = pendingIncomingBatches.remove(userId);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<IncomingSegment> segments = batch.snapshot();
        getUserExecutor(userId).submit(() -> processIncomingBatch(userId, segments));
    }

    private void processIncomingBatch(String userId, List<IncomingSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        try {
            if (shouldProcessIndividually(segments)) {
                for (IncomingSegment segment : segments) {
                    processSingleSegment(userId, segment);
                }
                return;
            }

            List<String> textParts = new ArrayList<>();
            List<IncomingSegment> images = new ArrayList<>();
            List<IncomingSegment> files = new ArrayList<>();
            for (IncomingSegment segment : segments) {
                switch (segment.type()) {
                    case TEXT -> {
                        String normalized = normalizeText(segment.text());
                        if (!normalized.isBlank()) {
                            textParts.add(normalized);
                        }
                    }
                    case IMAGE -> images.add(segment);
                    case FILE -> files.add(segment);
                    case VOICE -> processSingleSegment(userId, segment);
                }
            }

            String mergedText = String.join("\n", textParts).trim();
            if (!images.isEmpty() && !mergedText.isBlank() && tryHandleImageIntentBatch(userId, mergedText, images)) {
                if (!files.isEmpty()) {
                    cacheAttachments(userId, files);
                }
                return;
            }

            if (!images.isEmpty() || !files.isEmpty()) {
                List<IncomingSegment> attachments = new ArrayList<>();
                attachments.addAll(images);
                attachments.addAll(files);
                cacheAttachments(userId, attachments);
                if (mergedText.isBlank()) {
                    safeSendText(userId, buildAttachmentAckMessage(attachments));
                    return;
                }
            }

            if (!mergedText.isBlank()) {
                handleTextMessage(userId, mergedText);
            } else if (!images.isEmpty() || !files.isEmpty()) {
                safeSendText(userId, buildAttachmentAckMessage(concat(images, files)));
            }
        } catch (Exception e) {
            log.error("Process incoming batch failed. userId={}, segmentCount={}", userId, segments.size(), e);
            safeSendText(userId, "\u6d88\u606f\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }
    }

    private boolean shouldProcessIndividually(List<IncomingSegment> segments) {
        for (IncomingSegment segment : segments) {
            if (segment.type() == IncomingSegmentType.VOICE) {
                return true;
            }
            if (segment.type() == IncomingSegmentType.TEXT && mayBeImmediateControlMessage(segment.text())) {
                return true;
            }
        }
        return false;
    }

    private void processSingleSegment(String userId, IncomingSegment segment) {
        if (segment == null) {
            return;
        }
        switch (segment.type()) {
            case TEXT -> handleTextMessage(userId, segment.text());
            case IMAGE -> {
                cacheAttachments(userId, List.of(segment));
                safeSendText(userId, "\u5df2\u6536\u5230\u56fe\u7247\uff0c\u8bf7\u7ee7\u7eed\u53d1\u9001\u8bf4\u660e\u6216\u76f4\u63a5\u63d0\u95ee\u3002");
            }
            case FILE -> {
                cacheAttachments(userId, List.of(segment));
                safeSendText(userId, buildAttachmentAckMessage(List.of(segment)));
            }
            case VOICE -> handleVoiceMessage(userId, segment.item());
        }
    }

    private void handleTextMessage(String fromUserId, String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return;
        }
        try {
            UserSessionContext context = resolveSessionContext(fromUserId);

            if (handlePendingVoiceFallback(fromUserId, normalized)) {
                return;
            }
            if (handlePendingVoiceSelection(fromUserId, normalized, false)) {
                return;
            }
            if (isMemoryClearCommand(normalized)) {
                conversationService.clearMemory(context);
                safeSendText(fromUserId, "\u5df2\u6e05\u9664\u5f53\u524d\u4f1a\u8bdd\u8bb0\u5fc6\u3002");
                return;
            }
            if (isResumeCommand(normalized)) {
                ConversationMemoryService.ResumeSnapshot snapshot = conversationService.forceLoadHistory(context);
                safeSendText(fromUserId, buildResumeReply(snapshot));
                return;
            }

            UserControlDecision decision = interpretUserControl(normalized);
            if (decision.pendingSelection() != null) {
                pendingVoiceSelections.put(fromUserId, decision.pendingSelection());
                safeSendText(fromUserId, decision.pendingSelection().prompt());
                return;
            }
            applyControlDecision(fromUserId, decision);
            if (decision.shouldReplyStatusOnly()) {
                safeSendText(fromUserId, buildDecisionStatusText(fromUserId, decision));
                return;
            }

            String userMessage = decision.cleanedText();
            rememberImageIntent(fromUserId, normalized);
            List<PendingAttachment> cached = pendingAttachments.remove(fromUserId);
            if (cached != null && !cached.isEmpty() && tryHandleCachedImageAttachments(fromUserId, cached, userMessage)) {
                return;
            }
            userMessage = buildTextMessageWithCachedAttachments(userMessage, cached);
            if (userMessage.isBlank()) {
                return;
            }

            String reply = conversationService.reply(context, userMessage);
            boolean preferVoice = decision.currentMessageVoiceReply() != null
                    ? decision.currentMessageVoiceReply()
                    : isVoiceReplyEnabled(fromUserId);
            sendReplyByMode(fromUserId, reply, preferVoice, false);
        } catch (Exception e) {
            log.error("Handle text message failed. userId={}", fromUserId, e);
            safeSendText(fromUserId, "\u6d88\u606f\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }
    }

    private void handleImageMessage(String fromUserId, MessageItem item) {
        try {
            UserSessionContext context = resolveSessionContext(fromUserId);
            byte[] imageData = client.downloadImageFromMessageItem(item);
            if (imageData == null || imageData.length == 0) {
                safeSendText(fromUserId, "\u56fe\u7247\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u3002");
                return;
            }

            PendingImageIntent pendingIntent = consumePendingImageIntent(fromUserId);
            String textHint = pendingIntent == null ? null : pendingIntent.textHint();
            InvoiceImageIntentService.ImageIntentDecision decision = invoiceImageIntentService.detect(
                    textHint,
                    imageData,
                    "wechat-image-" + System.currentTimeMillis() + ".jpg"
            );
            log.info("Image message detected. userId={}, kind={}, action={}, reason={}",
                    fromUserId, decision.kind(), decision.action(), decision.reason());

            if (decision.action() == InvoiceImageIntentService.ImageAction.RECOGNIZE
                    || decision.action() == InvoiceImageIntentService.ImageAction.EXPORT_EXCEL
                    || decision.kind() == InvoiceImageIntentService.ImageKind.INVOICE) {
                handleInvoiceImage(fromUserId, context, imageData, decision);
                return;
            }

            String imageSummary = bailianService.chatWithImage(imageData, buildImagePrompt(context.sessionId()));
            String reply = conversationService.replyToImage(context.sessionId(), normalizeText(imageSummary));
            safeSendText(fromUserId, reply);
        } catch (Exception e) {
            log.error("Handle image message failed. userId={}", fromUserId, e);
            safeSendText(fromUserId, "\u56fe\u7247\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }
    }

    private void handleFileMessage(String fromUserId, MessageItem item) {
        try {
            UserSessionContext context = resolveSessionContext(fromUserId);
            byte[] fileData = client.downloadFileFromMessageItem(item);
            if (fileData == null || fileData.length == 0) {
                safeSendText(fromUserId, "\u6587\u4ef6\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u3002");
                return;
            }

            FileItem fileItem = item.getFile_item();
            String fileName = fileItem != null ? normalizeText(fileItem.getFile_name()) : "";
            if (fileName.isBlank()) {
                fileName = "uploaded-file";
            }
            String sessionId = context.sessionId();
            String fileType = fileParseService.detectFileType(fileName, fileData);
            fileParseService.rememberSessionFileReference(sessionId, fileName);

            if (excelMcpService.supportsWorkbook(fileName)) {
                String stored = excelMcpService.storeWorkbook(fileName, fileData);
                fileParseService.rememberSessionFileReference(sessionId, stored);
                safeSendText(fromUserId, "\u5df2\u6536\u5230Excel\u6587\u4ef6\uff1a" + fileName);
                return;
            }

            if (FileParseService.isParseable(fileName)) {
                String preview = fileParseService.getFilePreview(fileName, fileData);
                fileParseService.cacheSessionFile(sessionId, fileName, fileData);
                String syntheticMessage = "\u6587\u4ef6\u540d\uff1a" + fileName + "\n\u6587\u4ef6\u7c7b\u578b\uff1a" + fileType + "\n\u6587\u4ef6\u5185\u5bb9\u9884\u89c8\uff1a\n" + preview;
                String reply = conversationService.reply(context, syntheticMessage);
                safeSendText(fromUserId, reply);
                return;
            }

            safeSendText(fromUserId, "\u5df2\u6536\u5230\u6587\u4ef6\uff1a" + fileName + "\uff0c\u6682\u4e0d\u652f\u6301\u89e3\u6790\u8be5\u7c7b\u578b\u3002");
        } catch (Exception e) {
            log.error("Handle file message failed. userId={}", fromUserId, e);
            safeSendText(fromUserId, "\u6587\u4ef6\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }
    }

    private void handleVoiceMessage(String fromUserId, MessageItem item) {
        try {
            UserSessionContext context = resolveSessionContext(fromUserId);
            byte[] voiceData = client.downloadVoiceFromMessageItem(item);
            if (voiceData == null || voiceData.length == 0) {
                safeSendText(fromUserId, "\u8bed\u97f3\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u53d1\u9001\u3002");
                return;
            }
            SpeechService.SpeechToTextResult speechResult = speechService.speechToTextWithSource(item, voiceData);
            String recognized = speechResult == null ? null : speechResult.text();
            if (recognized == null || recognized.isBlank()) {
                safeSendText(fromUserId, "\u672a\u8bc6\u522b\u5230\u6e05\u6670\u8bed\u97f3\u5185\u5bb9\uff0c\u8bf7\u91cd\u8bd5\u3002");
                return;
            }

            String normalized = normalizeText(recognized);
            if (handlePendingVoiceFallback(fromUserId, normalized)) {
                return;
            }
            if (handlePendingVoiceSelection(fromUserId, normalized, true)) {
                return;
            }
            if (isMemoryClearCommand(normalized)) {
                conversationService.clearMemory(context);
                sendReplyByMode(fromUserId, "\u5df2\u6e05\u9664\u5f53\u524d\u4f1a\u8bdd\u8bb0\u5fc6\u3002", true, false);
                return;
            }
            if (isResumeCommand(normalized)) {
                sendReplyByMode(fromUserId, buildResumeReply(conversationService.forceLoadHistory(context)), true, false);
                return;
            }

            UserControlDecision decision = interpretUserControl(normalized);
            if (decision.pendingSelection() != null) {
                pendingVoiceSelections.put(fromUserId, decision.pendingSelection());
                safeSendText(fromUserId, decision.pendingSelection().prompt());
                return;
            }
            applyControlDecision(fromUserId, decision);
            if (decision.shouldReplyStatusOnly()) {
                sendReplyByMode(fromUserId, buildDecisionStatusText(fromUserId, decision), true, false);
                return;
            }

            String reply = conversationService.reply(context, decision.cleanedText());
            sendReplyByMode(fromUserId, reply, true, false);
        } catch (Exception e) {
            log.error("Handle voice message failed. userId={}", fromUserId, e);
            safeSendText(fromUserId, "\u8bed\u97f3\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }
    }

    private boolean tryHandleImageIntentBatch(String userId, String textHint, List<IncomingSegment> imageSegments) {
        try {
            UserSessionContext context = resolveSessionContext(userId);
            for (IncomingSegment imageSegment : imageSegments) {
                byte[] imageBytes = client.downloadImageFromMessageItem(imageSegment.item());
                if (imageBytes == null || imageBytes.length == 0) {
                    continue;
                }
                InvoiceImageIntentService.ImageIntentDecision decision = invoiceImageIntentService.detect(
                        textHint,
                        imageBytes,
                        buildSourceName(imageSegment)
                );
                if (decision.action() == InvoiceImageIntentService.ImageAction.RECOGNIZE
                        || decision.action() == InvoiceImageIntentService.ImageAction.EXPORT_EXCEL
                        || decision.kind() == InvoiceImageIntentService.ImageKind.INVOICE) {
                    handleInvoiceImage(userId, context, imageBytes, decision);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Handle image intent batch failed. userId={}", userId, e);
            return false;
        }
    }

    private boolean tryHandleCachedImageAttachments(String userId, List<PendingAttachment> cached, String textHint) {
        try {
            UserSessionContext context = resolveSessionContext(userId);
            for (PendingAttachment attachment : cached) {
                if (attachment.type() != IncomingSegmentType.IMAGE || attachment.data() == null || attachment.data().length == 0) {
                    continue;
                }
                InvoiceImageIntentService.ImageIntentDecision decision = invoiceImageIntentService.detect(
                        textHint,
                        attachment.data(),
                        attachment.fileName()
                );
                if (decision.action() == InvoiceImageIntentService.ImageAction.RECOGNIZE
                        || decision.action() == InvoiceImageIntentService.ImageAction.EXPORT_EXCEL
                        || decision.kind() == InvoiceImageIntentService.ImageKind.INVOICE) {
                    handleInvoiceImage(userId, context, attachment.data(), decision);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Handle cached image attachment failed. userId={}", userId, e);
            return false;
        }
    }

    private void handleInvoiceImage(String fromUserId,
                                    UserSessionContext context,
                                    byte[] imageBytes,
                                    InvoiceImageIntentService.ImageIntentDecision decision) {
        String taskId = buildInvoiceTaskId(fromUserId, imageBytes);
        if (!markInvoiceTaskIfFirst(taskId)) {
            log.info("Skip duplicate invoice image handling. taskId={}, userId={}", taskId, fromUserId);
            return;
        }
        try {
            UserSessionContext effectiveContext = context != null ? context : resolveSessionContext(fromUserId);
            String sourceName = "invoice-" + System.currentTimeMillis() + ".jpg";
            log.info("Invoice image handling start: userId={}, action={}, sourceName={}, bytes={}",
                    fromUserId, decision == null ? null : decision.action(), sourceName, imageBytes == null ? 0 : imageBytes.length);

            InvoiceExportResponse response = invoiceRecognitionService.recognizeAndExport(
                    imageBytes,
                    sourceName,
                    new InvoiceRecognitionRequest(),
                    effectiveContext
            );

            if (response == null || response.getExcelPath() == null) {
                invoiceTaskMarkers.remove(taskId);
                safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
                return;
            }
            log.info("Invoice image handling finished. taskId={}, userId={}, excelPath={}, verifyResult={}",
                    taskId, fromUserId, response.getExcelPath(),
                    response.getResult() == null ? null : InvoiceRecognitionService.resolveVerifyConclusion(response.getResult()));
        } catch (AliOcrInvoiceException e) {
            invoiceTaskMarkers.remove(taskId);
            log.error("\u5904\u7406\u53d1\u7968\u56fe\u7247\u5931\u8d25\uff0cuserId={}, action={}, errorType={}", fromUserId, decision == null ? null : decision.action(), e.getErrorType(), e);
            safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
        } catch (Exception e) {
            invoiceTaskMarkers.remove(taskId);
            log.error("\u5904\u7406\u53d1\u7968\u56fe\u7247\u5931\u8d25\uff0cuserId={}, action={}", fromUserId, decision == null ? null : decision.action(), e);
            safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
        }
    }

    private void sendInvoiceRecognitionResult(String fromUserId, InvoiceRecognitionResult result) {
        if (fromUserId == null || result == null) {
            return;
        }
        safeSendText(fromUserId, buildInvoiceRecognitionText(result));
    }

    private String buildExcelExportText(ExcelExportReadyEvent event, Path filePath) {
        return buildExcelExportText(event == null ? null : event.messageText(), filePath, event == null ? null : event.fileName());
    }

    private String buildExcelExportText(String messageText, Path filePath, String fileName) {
        if (messageText != null && !messageText.isBlank()) {
            return messageText.trim();
        }
        String resolvedName = fileName == null || fileName.isBlank()
                ? (filePath == null ? "" : filePath.getFileName().toString())
                : fileName;
        String path = filePath == null ? "" : filePath.toAbsolutePath().toString();
        return "\u672c\u6b21\u53d1\u7968\u8bc6\u522bExcel\u6587\u4ef6\u5df2\u751f\u6210\uff1a\n"
                + "\u6587\u4ef6\u540d\uff1a" + resolvedName + "\n"
                + "\u5de5\u4f5c\u8868\uff1ainvoice_ledger\n"
                + "\u672c\u5730\u8def\u5f84\uff1a" + path;
    }

    private String buildInvoiceRecognitionText(InvoiceRecognitionResult result) {
        if (result == null || !result.isSuccess()) {
            return "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002";
        }
        return "\u3010\u53d1\u7968\u6838\u9a8c\u7ed3\u679c\u3011\uff1a" + InvoiceRecognitionService.resolveVerifyConclusion(result);
    }

    private String buildInvoiceExportText(InvoiceRecognitionResult result, Path filePath, String fileName) {
        String resolvedName = fileName == null || fileName.isBlank()
                ? (filePath == null ? "" : filePath.getFileName().toString())
                : fileName;
        String path = filePath == null ? "" : filePath.toAbsolutePath().toString();
        return "\u672c\u6b21\u53d1\u7968\u8bc6\u522bExcel\u6587\u4ef6\u5df2\u751f\u6210\uff1a\n"
                + "\u6587\u4ef6\u540d\uff1a" + resolvedName + "\n"
                + "\u5de5\u4f5c\u8868\uff1ainvoice_ledger\n"
                + "\u672c\u5730\u8def\u5f84\uff1a" + path + "\n"
                + "\u3010\u53d1\u7968\u6838\u9a8c\u7ed3\u679c\u3011\uff1a" + InvoiceRecognitionService.resolveVerifyConclusion(result);
    }

    private ExecutorService getUserExecutor(String userId) {
        return userExecutors.computeIfAbsent(userId, key -> new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "wechat-user-" + key);
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        ));
    }

    private void cleanupExecutors() {
        try {
            cleanupTaskMarkers();
            userExecutors.entrySet().removeIf(entry -> {
                ExecutorService executor = entry.getValue();
                if (executor instanceof ThreadPoolExecutor tpe
                        && tpe.getQueue().isEmpty()
                        && tpe.getActiveCount() == 0) {
                    cleanupUserBatchState(entry.getKey());
                    tpe.shutdown();
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.warn("Cleanup user executors failed", e);
        }
    }

    private void cleanupUserBatchState(String userId) {
        ScheduledFuture<?> flushTask = pendingIncomingFlushTasks.remove(userId);
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        pendingIncomingBatches.remove(userId);
        pendingAttachments.remove(userId);
        pendingImageIntents.remove(userId);
    }

    private String buildInvoiceTaskId(String userId, byte[] imageBytes) {
        int imageHash = imageBytes == null ? 0 : Arrays.hashCode(imageBytes);
        return firstNonBlank(userId, "") + "|invoice|" + Integer.toHexString(imageHash);
    }

    private boolean markInvoiceTaskIfFirst(String taskId) {
        cleanupTaskMarkers();
        return invoiceTaskMarkers.putIfAbsent(taskId, System.currentTimeMillis()) == null;
    }

    private void cleanupTaskMarkers() {
        long now = System.currentTimeMillis();
        invoiceTaskMarkers.entrySet().removeIf(entry -> now - entry.getValue() > INVOICE_TASK_TTL_MS);
        exportPushMarkers.entrySet().removeIf(entry -> now - entry.getValue() > INVOICE_TASK_TTL_MS);
    }

    private UserSessionContext resolveSessionContext(String channelUserId) {
        return userIdentityService.resolveWechatUser(channelUserId, "wechat");
    }

    private String buildSourceName(IncomingSegment segment) {
        if (segment == null || segment.item() == null || segment.item().getFile_item() == null) {
            return "image-" + System.currentTimeMillis() + ".jpg";
        }
        String fileName = normalizeText(segment.item().getFile_item().getFile_name());
        return fileName.isBlank() ? "image-" + System.currentTimeMillis() + ".jpg" : fileName;
    }

    private String sanitizeOutgoingText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)<p\\s*>", "")
                .replaceAll("(?i)<[^>]+>", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String normalizeAction(String value) {
        return value == null ? "unchanged" : value.trim().toLowerCase();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    private boolean hasVoiceStyleIntent(String text) {
        String normalized = normalizeText(text);
        return containsAny(normalized,
                "音色", "声音", "声线", "播报",
                "御姐", "温柔", "甜美", "少女", "少年",
                "成熟", "活泼", "低沉", "磁性", "沉稳");
    }

    private boolean hasGenericVoiceSwitchIntent(String text) {
        String normalized = normalizeText(text);
        return containsAny(normalized,
                "切换音色", "换音色", "改音色", "调整音色",
                "切换声音", "换声音", "改声音", "调整声音");
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String stripPhrases(String text, String... patterns) {
        String result = text == null ? "" : text;
        for (String pattern : patterns) {
            result = result.replace(pattern, " ");
        }
        return result
                .replace(",", " ")
                .replace(".", " ")
                .replace("!", " ")
                .replace("?", " ")
                .replace(";", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trimPunctuation(String text) {
        return (text == null ? "" : text)
                .replaceAll("^[,.;!?\\s]+", "")
                .replaceAll("[,.;!?\\s]+$", "")
                .trim();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<IncomingSegment> concat(List<IncomingSegment> first, List<IncomingSegment> second) {
        List<IncomingSegment> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private record UserControlDecision(String voiceId,
                                       boolean resetVoice,
                                       boolean queryVoice,
                                       Boolean sessionVoiceReply,
                                       Boolean currentMessageVoiceReply,
                                       String cleanedText,
                                       boolean queryOnly,
                                       PendingVoiceSelection pendingSelection) {
        private boolean shouldReplyStatusOnly() {
            return queryOnly;
        }
    }

    private record FallbackDirective(String voiceId,
                                     Boolean sessionVoiceReply,
                                     Boolean currentMessageVoiceReply,
                                     String cleanedText,
                                     boolean queryOnly,
                                     boolean queryVoice,
                                     boolean resetVoice,
                                     PendingVoiceSelection pendingSelection) {
        private boolean hasControlSignal() {
            return (voiceId != null && !voiceId.isBlank())
                    || sessionVoiceReply != null
                    || currentMessageVoiceReply != null
                    || queryOnly
                    || queryVoice
                    || resetVoice
                    || pendingSelection != null;
        }
    }

    private record PendingVoiceSelection(String category, LinkedHashMap<String, String> options, String prompt) {
        private String choiceNameByVoiceId(String voiceId) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getValue().equals(voiceId)) {
                    return entry.getKey();
                }
            }
            return voiceId;
        }
    }

    private record PendingVoiceFallback(String invalidVoiceId, String defaultVoiceId, String prompt) {
    }

    private record PendingExcelExport(String exportId, String userId, Path filePath, String fileName, String caption, String messageText) {
    }

    private record PendingImageIntent(InvoiceImageIntentService.ImageKind kind,
                                      InvoiceImageIntentService.ImageAction action,
                                      String textHint,
                                      long updatedAt) {
    }

    private record PendingAttachment(IncomingSegmentType type,
                                     byte[] data,
                                     String fileName,
                                     String fileType,
                                     String filePreview) {
    }

    private enum IncomingSegmentType {
        TEXT, IMAGE, FILE, VOICE
    }

    private record IncomingSegment(IncomingSegmentType type, String text, MessageItem item) {
    }

    private static class PendingIncomingBatch {
        private final List<IncomingSegment> segments = Collections.synchronizedList(new ArrayList<>());

        void addSegment(IncomingSegment segment) {
            if (segment != null) {
                segments.add(segment);
            }
        }

        boolean isEmpty() {
            return segments.isEmpty();
        }

        int size() {
            return segments.size();
        }

        List<IncomingSegment> snapshot() {
            synchronized (segments) {
                return new ArrayList<>(segments);
            }
        }
    }
}




