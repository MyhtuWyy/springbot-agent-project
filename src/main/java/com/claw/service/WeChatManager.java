package com.claw.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class WeChatManager {
    private static final Logger log = LoggerFactory.getLogger(WeChatManager.class);
    private static final long EXPORT_DEDUP_TTL_MS = 10 * 60 * 1000L;

    private final ObjectProvider<WeChatService> weChatServiceProvider;
    private final ConcurrentLinkedQueue<PendingExcelExport> pendingExcelExports = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Long> processedExportIds = new ConcurrentHashMap<>();
    private volatile WeChatService currentService;

    public WeChatManager(ObjectProvider<WeChatService> weChatServiceProvider) {
        this.weChatServiceProvider = weChatServiceProvider;
    }

    public synchronized boolean isRunning() {
        return currentService != null && currentService.isConnected();
    }

    public synchronized void startAsync() {
        if (isRunning()) {
            return;
        }
        currentService = weChatServiceProvider.getObject();
        Thread worker = new Thread(() -> {
            currentService.start();
            flushPendingExcelExports();
        }, "wechat-bot-runner");
        worker.setDaemon(false);
        worker.start();
    }

    public synchronized boolean waitUntilRunning(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                flushPendingExcelExports();
                return true;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isRunning()) {
            flushPendingExcelExports();
        }
        return isRunning();
    }

    public synchronized void stop() {
        if (currentService == null) {
            return;
        }
        currentService.stop();
        currentService = null;
    }

    public synchronized void sendMessage(String userId, String text) throws Exception {
        log.info("wechat send chain start: userId={}, textLength={}", userId, text == null ? 0 : text.length());
        try {
            requireRunning().sendMessage(userId, text);
            log.info("wechat send chain success: userId={}", userId);
        } catch (Exception e) {
            log.error("wechat send chain failed: userId={}", userId, e);
            throw e;
        }
    }

    public synchronized void sendImage(String userId, String imagePath, String caption) throws Exception {
        requireRunning().sendImage(userId, imagePath, caption);
    }

    public synchronized void sendVoice(String userId, String voicePath, Integer playtimeSec, Integer sampleRate) throws Exception {
        requireRunning().sendVoice(userId, voicePath, playtimeSec, sampleRate);
    }

    @EventListener
    public void onExcelExportReady(ExcelExportReadyEvent event) {
        if (event == null || event.userId() == null || event.filePath() == null) {
            return;
        }
        cleanupProcessedExportIds();
        PendingExcelExport export = new PendingExcelExport(
                resolveExportId(event),
                event.userId(),
                event.filePath(),
                firstNonBlank(event.fileName(), event.filePath().getFileName().toString()),
                firstNonBlank(event.caption(), "\u53d1\u7968Excel\u9644\u4ef6"),
                buildExcelExportText(event.messageText(), event.filePath(), event.fileName())
        );
        if (!isRunning()) {
            pendingExcelExports.add(export);
            log.info("Excel export queued in manager until WeChat is running. exportId={}, userId={}, filePath={}",
                    export.exportId(), export.userId(), export.filePath());
            return;
        }
        sendPendingExcelExport(export);
    }

    private synchronized void flushPendingExcelExports() {
        if (!isRunning()) {
            return;
        }
        cleanupProcessedExportIds();
        PendingExcelExport export;
        while ((export = pendingExcelExports.poll()) != null) {
            sendPendingExcelExport(export);
        }
    }

    private synchronized void sendPendingExcelExport(PendingExcelExport export) {
        try {
            if (export == null || export.filePath() == null || !Files.exists(export.filePath())) {
                log.warn("Skip excel export attachment because file does not exist. exportId={}, userId={}, filePath={}",
                        export == null ? null : export.exportId(),
                        export == null ? null : export.userId(),
                        export == null ? null : export.filePath());
                return;
            }
            if (!markExportIfFirst(export.exportId())) {
                log.info("Skip duplicate excel export push in manager. exportId={}, userId={}",
                        export.exportId(), export.userId());
                return;
            }
            requireRunning().sendFileAttachment(export.userId(), export.filePath(), export.fileName(), export.caption());
            requireRunning().sendMessage(export.userId(), export.messageText());
            log.info("Excel export attachment sent by manager. exportId={}, userId={}, fileName={}, filePath={}",
                    export.exportId(), export.userId(), export.fileName(), export.filePath().toAbsolutePath());
        } catch (Exception e) {
            if (export != null) {
                processedExportIds.remove(export.exportId());
            }
            log.error("Excel export attachment send failed in manager. exportId={}, userId={}, filePath={}",
                    export == null ? null : export.exportId(),
                    export == null ? null : export.userId(),
                    export == null ? null : export.filePath(),
                    e);
            if (export != null) {
                pendingExcelExports.add(export);
            }
        }
    }

    private boolean markExportIfFirst(String exportId) {
        if (exportId == null || exportId.isBlank()) {
            return true;
        }
        return processedExportIds.putIfAbsent(exportId, System.currentTimeMillis()) == null;
    }

    private void cleanupProcessedExportIds() {
        long now = System.currentTimeMillis();
        processedExportIds.entrySet().removeIf(entry -> now - entry.getValue() > EXPORT_DEDUP_TTL_MS);
    }

    private String resolveExportId(ExcelExportReadyEvent event) {
        if (event.exportId() != null && !event.exportId().isBlank()) {
            return event.exportId();
        }
        String filePart = event.filePath() == null ? "" : event.filePath().toAbsolutePath().toString();
        return event.userId() + "|" + filePart;
    }

    private WeChatService requireRunning() {
        if (currentService == null || !currentService.isConnected()) {
            throw new IllegalStateException("WeChat bot is not running");
        }
        return currentService;
    }

    private String buildExcelExportText(String messageText, Path filePath, String fileName) {
        if (messageText != null && !messageText.isBlank()) {
            return messageText.trim();
        }
        String resolvedName = firstNonBlank(fileName, filePath == null ? null : filePath.getFileName().toString(), "");
        String resolvedPath = filePath == null ? "" : filePath.toAbsolutePath().toString();
        return "\u672c\u6b21\u53d1\u7968\u8bc6\u522bExcel\u6587\u4ef6\u5df2\u751f\u6210\uff1a\n"
                + "\u6587\u4ef6\u540d\uff1a" + resolvedName + "\n"
                + "\u5de5\u4f5c\u8868\uff1ainvoice_ledger\n"
                + "\u672c\u5730\u8def\u5f84\uff1a" + resolvedPath;
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

    private record PendingExcelExport(String exportId,
                                      String userId,
                                      Path filePath,
                                      String fileName,
                                      String caption,
                                      String messageText) {
    }
}
