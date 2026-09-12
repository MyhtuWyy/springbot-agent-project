$path = "src/main/java/com/claw/service/WeChatService.java"
$content = Get-Content -Raw $path

$block1Pattern = '(?s)    public void start\(\) \{.*?^    private void cacheAttachments'
$block1Replacement = @"
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
        if (System.currentTimeMillis() >= 0) {
            log.info("Ignore excel export event on prototype WeChatService. userId={}, filePath={}",
                    event.userId(), event.filePath());
            return;
        }
        PendingExcelExport export = new PendingExcelExport(
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
            byte[] bytes = Files.readAllBytes(export.filePath());
            sendFileBytes(export.userId(), bytes, export.fileName(), export.caption());
            safeSendText(export.userId(), export.messageText());
            log.info("Excel attachment sent. userId={}, fileName={}, filePath={}",
                    export.userId(), export.fileName(), export.filePath().toAbsolutePath());
        } catch (Exception e) {
            log.error("Send excel attachment failed. userId={}, filePath={}", export.userId(), export.filePath(), e);
            pendingExcelExports.add(export);
        }
    }

    private void cacheAttachments
"@

$content = [regex]::Replace($content, $block1Pattern, $block1Replacement, [System.Text.RegularExpressions.RegexOptions]::Multiline)

$block2Pattern = '(?s)    private String buildResumeReply\(ConversationMemoryService\.ResumeSnapshot snapshot\) \{.*?^    private String buildSourceName'
$block2Replacement = @"
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
                safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
                return;
            }

            Path filePath = Paths.get(response.getExcelPath());
            sendPendingExcelExport(new PendingExcelExport(
                    fromUserId,
                    filePath,
                    firstNonBlank(response.getExcelFileName(), filePath.getFileName().toString()),
                    "\u53d1\u7968Excel\u9644\u4ef6",
                    buildInvoiceExportTextFixed(response.getResult(), filePath, response.getExcelFileName())
            ));
        } catch (AliOcrInvoiceException e) {
            log.error("\u5904\u7406\u53d1\u7968\u56fe\u7247\u5931\u8d25\uff0cuserId={}, action={}, errorType={}", fromUserId, decision == null ? null : decision.action(), e.getErrorType(), e);
            safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
        } catch (Exception e) {
            log.error("\u5904\u7406\u53d1\u7968\u56fe\u7247\u5931\u8d25\uff0cuserId={}, action={}", fromUserId, decision == null ? null : decision.action(), e);
            safeSendText(fromUserId, "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002");
        }
    }

    private void sendInvoiceRecognitionResult(String fromUserId, InvoiceRecognitionResult result) {
        if (fromUserId == null || result == null) {
            return;
        }
        safeSendText(fromUserId, buildInvoiceRecognitionTextFixed(result));
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
        return buildInvoiceExportTextFixed(result, filePath, fileName);
    }

    private String buildInvoiceRecognitionTextFixed(InvoiceRecognitionResult result) {
        if (result == null || !result.isSuccess()) {
            return "\u53d1\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6e05\u6670\u7684\u53d1\u7968\u56fe\u7247\u3002";
        }
        return "\u3010\u53d1\u7968\u6838\u9a8c\u7ed3\u679c\u3011\uff1a" + InvoiceRecognitionService.resolveVerifyConclusion(result);
    }

    private String buildInvoiceExportTextFixed(InvoiceRecognitionResult result, Path filePath, String fileName) {
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

    private UserSessionContext resolveSessionContext(String channelUserId) {
        return userIdentityService.resolveWechatUser(channelUserId, "wechat");
    }

    private String buildSourceName
"@

$content = [regex]::Replace($content, $block2Pattern, $block2Replacement, [System.Text.RegularExpressions.RegexOptions]::Multiline)

$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $path), $content, $encoding)
