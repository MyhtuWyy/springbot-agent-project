package com.claw;

import com.claw.service.ConversationService;
import com.claw.service.SpeechService;
import com.claw.service.WeChatManager;
import com.claw.service.WeatherService;
import com.claw.util.ConfigUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Component
@ConditionalOnProperty(prefix = "app.cli", name = "enabled", havingValue = "true")
public class BotCliRunner implements CommandLineRunner {
    private static final String VERSION = "V2.2.0";
    private static final String TERMINAL_SESSION_ID = "terminal-default";
    private static final String TTS_VERIFY_TEXT = "This is a voice verification sample.";

    private final WeatherService weatherService;
    private final ConversationService conversationService;
    private final SpeechService speechService;
    private final WeChatManager weChatManager;
    private final Scanner scanner = new Scanner(System.in);

    public BotCliRunner(WeatherService weatherService,
                        ConversationService conversationService,
                        SpeechService speechService,
                        WeChatManager weChatManager) {
        this.weatherService = weatherService;
        this.conversationService = conversationService;
        this.speechService = speechService;
        this.weChatManager = weChatManager;
    }

    @Override
    public void run(String... args) {
        Path logPath = Paths.get("logs", "app.log").toAbsolutePath().normalize();
        System.out.println("Log file: " + logPath);
        System.out.println("Env source: " + ConfigUtil.getDotenvSource());
        System.out.println("TTS config: model=" + ConfigUtil.getTtsModel() + ", voiceId=" + ConfigUtil.getTtsVoiceId());
        System.out.println("Type help to list commands, exit to quit.");
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            handleCmd(input);
        }
    }

    @PreDestroy
    public void shutdown() {
        conversationService.shutdown();
        weChatManager.stop();
        scanner.close();
    }

    private void handleCmd(String input) {
        if (input.isEmpty()) {
            return;
        }

        String[] argsArr = input.split(" ");
        String cmd = argsArr[0];

        switch (cmd) {
            case "help" -> printHelp();
            case "version" -> System.out.println("Version: " + VERSION);
            case "status" -> showStatus();
            case "weather" -> handleWeather(argsArr);
            case "memory" -> handleMemoryStatus();
            case "memory-clear" -> handleMemoryClear();
            case "memory-size" -> handleMemorySize();
            case "tts-verify" -> handleTtsVerify(argsArr);
            case "tts-verify-batch" -> handleTtsVerifyBatch(argsArr);
            case "wechat" -> handleWechat();
            case "wechat-stop" -> handleWechatStop();
            case "wechat-send" -> handleWechatSend(argsArr);
            case "wechat-sendimage" -> handleWechatSendImage(argsArr);
            case "wechat-sendvoice" -> handleWechatSendVoice(argsArr);
            case "exit" -> handleExit();
            default -> handleChat(input);
        }
    }

    private void printHelp() {
        System.out.println("help");
        System.out.println("version");
        System.out.println("status");
        System.out.println("weather <city>");
        System.out.println("memory");
        System.out.println("memory-size");
        System.out.println("memory-clear");
        System.out.println("tts-verify <voiceId>");
        System.out.println("tts-verify-batch <id1,id2,...>");
        System.out.println("wechat");
        System.out.println("wechat-stop");
        System.out.println("wechat-send <userId> <message>");
        System.out.println("wechat-sendimage <userId> <imagePath> [caption]");
        System.out.println("wechat-sendvoice <userId> <voicePath> <playtimeSec> <sampleRate>");
        System.out.println("exit");
    }

    private void handleChat(String input) {
        System.out.println(conversationService.reply(TERMINAL_SESSION_ID, input));
    }

    private void handleMemoryStatus() {
        System.out.println(conversationService.hasMemory(TERMINAL_SESSION_ID)
                ? "Memory exists for current session."
                : "No memory for current session.");
    }

    private void handleMemorySize() {
        System.out.println("Memory size: " + conversationService.getMemorySize(TERMINAL_SESSION_ID));
    }

    private void handleMemoryClear() {
        conversationService.clearMemory(TERMINAL_SESSION_ID);
        System.out.println("Memory cleared.");
    }

    private void handleTtsVerify(String[] argsArr) {
        if (argsArr.length < 2) {
            System.out.println("Usage: tts-verify <voiceId>");
            return;
        }

        String voiceId = argsArr[1].trim();
        if (voiceId.isEmpty()) {
            System.out.println("voiceId must not be empty.");
            return;
        }

        verifySingleVoiceId(voiceId, true);
    }

    private void handleTtsVerifyBatch(String[] argsArr) {
        List<String> voiceIds = parseBatchVoiceIds(argsArr);
        if (voiceIds.isEmpty()) {
            System.out.println("Usage: tts-verify-batch <id1,id2,...>");
            return;
        }

        System.out.println("Batch verify: model=" + ConfigUtil.getTtsModel() + ", count=" + voiceIds.size());
        List<String> successIds = new ArrayList<>();
        List<String> failedResults = new ArrayList<>();

        for (String voiceId : voiceIds) {
            VerificationResult result = verifySingleVoiceId(voiceId, false);
            if (result.success()) {
                successIds.add(voiceId);
                System.out.println("[OK] " + voiceId);
            } else {
                failedResults.add(voiceId + " => " + result.reason());
                System.out.println("[FAIL] " + voiceId + " => " + result.reason());
            }
        }

        System.out.println("Success count: " + successIds.size());
        System.out.println("Fail count: " + failedResults.size());
        System.out.println("Available voiceIds: " + (successIds.isEmpty() ? "none" : String.join(", ", successIds)));
        for (String failedResult : failedResults) {
            System.out.println(failedResult);
        }
    }

    private VerificationResult verifySingleVoiceId(String voiceId, boolean verbose) {
        if (verbose) {
            System.out.println("Verify voice: model=" + ConfigUtil.getTtsModel() + ", voiceId=" + voiceId);
        }

        SpeechService.SpeechSynthesisResult result = speechService.textToSpeech(TTS_VERIFY_TEXT, voiceId);
        if (result != null && result.audioBytes() != null && result.audioBytes().length > 0) {
            if (verbose) {
                System.out.println("Verify success: format=" + result.metadata().format()
                        + ", sampleRate=" + result.metadata().sampleRate()
                        + ", bytes=" + result.audioBytes().length);
            }
            return new VerificationResult(true, "OK");
        }

        SpeechService.TtsFailure failure = speechService.getLastTtsFailure();
        if (failure != null) {
            if (verbose) {
                System.out.println("Verify failed: " + failure.reason());
            }
            return new VerificationResult(false, failure.reason());
        }

        if (verbose) {
            System.out.println("Verify failed: no valid audio output.");
        }
        return new VerificationResult(false, "No valid audio output");
    }

    private List<String> parseBatchVoiceIds(String[] argsArr) {
        List<String> voiceIds = new ArrayList<>();
        for (int i = 1; i < argsArr.length; i++) {
            String[] parts = argsArr[i].split("[,]+");
            for (String part : parts) {
                String voiceId = part.trim();
                if (!voiceId.isEmpty() && !voiceIds.contains(voiceId)) {
                    voiceIds.add(voiceId);
                }
            }
        }
        return voiceIds;
    }

    private void showStatus() {
        String bailianKey = ConfigUtil.getBailianKey();
        System.out.println("DashScope key: " + (bailianKey.isBlank() ? "missing" : "configured"));
        System.out.println("Memory size: " + conversationService.getMemorySize(TERMINAL_SESSION_ID));
    }

    private void handleWeather(String[] argsArr) {
        if (argsArr.length < 2) {
            System.out.println("Usage: weather <city>");
            return;
        }
        System.out.println(weatherService.getWeather(argsArr[1]));
    }

    private void handleWechat() {
        if (weChatManager.isRunning()) {
            System.out.println("微信机器人已在运行。");
            return;
        }
        System.out.println("正在启动微信机器人，请扫码登录...");
        weChatManager.startAsync();
        System.out.println("QR code will be printed here and also written to logs/app.log.");
        if (weChatManager.waitUntilRunning(180000L)) {
            System.out.println("微信机器人已登录并开始运行。");
        } else {
            System.out.println("微信机器人已启动，等待扫码登录完成。");
        }
    }

    private void handleWechatStop() {
        if (!weChatManager.isRunning()) {
            System.out.println("微信机器人未运行。");
            return;
        }
        weChatManager.stop();
        System.out.println("微信机器人已停止。");
    }

    private void handleWechatSend(String[] argsArr) {
        if (argsArr.length < 3) {
            System.out.println("用法: wechat-send <userId> <message>");
            return;
        }
        if (!weChatManager.isRunning()) {
            System.out.println("微信机器人未运行。");
            return;
        }

        String userId = argsArr[1];
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < argsArr.length; i++) {
            if (i > 2) {
                sb.append(" ");
            }
            sb.append(argsArr[i]);
        }

        try {
            weChatManager.sendMessage(userId, sb.toString());
            System.out.println("消息已发送: " + sb);
        } catch (Exception e) {
            System.out.println("发送失败: " + e.getMessage());
        }
    }

    private void handleWechatSendImage(String[] argsArr) {
        if (argsArr.length < 3) {
            System.out.println("用法: wechat-sendimage <userId> <imagePath> [caption]");
            return;
        }
        if (!weChatManager.isRunning()) {
            System.out.println("微信机器人未运行。");
            return;
        }

        String userId = argsArr[1];
        String imagePath = argsArr[2];
        StringBuilder captionBuilder = new StringBuilder();
        for (int i = 3; i < argsArr.length; i++) {
            if (i > 3) {
                captionBuilder.append(" ");
            }
            captionBuilder.append(argsArr[i]);
        }

        try {
            weChatManager.sendImage(userId, imagePath, captionBuilder.toString());
            System.out.println("图片已发送: " + imagePath);
        } catch (Exception e) {
            System.out.println("图片发送失败: " + e.getMessage());
        }
    }

    private void handleWechatSendVoice(String[] argsArr) {
        if (argsArr.length < 5) {
            System.out.println("用法: wechat-sendvoice <userId> <voicePath> <playtimeSec> <sampleRate>");
            return;
        }
        if (!weChatManager.isRunning()) {
            System.out.println("微信机器人未运行。");
            return;
        }

        String userId = argsArr[1];
        String voicePath = argsArr[2];
        try {
            Integer playtimeSec = Integer.parseInt(argsArr[3]);
            Integer sampleRate = Integer.parseInt(argsArr[4]);
            weChatManager.sendVoice(userId, voicePath, playtimeSec, sampleRate);
            System.out.println("语音已发送: " + voicePath);
        } catch (NumberFormatException e) {
            System.out.println("playtimeSec 和 sampleRate 必须是整数。");
        } catch (Exception e) {
            System.out.println("语音发送失败: " + e.getMessage());
        }
    }

    private void handleExit() {
        System.out.println("正在退出...");
        shutdown();
        System.exit(0);
    }

    private record VerificationResult(boolean success, String reason) {
    }
}
