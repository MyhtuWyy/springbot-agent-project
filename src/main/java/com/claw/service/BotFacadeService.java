package com.claw.service;

import com.claw.util.ConfigUtil;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class BotFacadeService {
    private final ConversationService conversationService;
    private final WeatherService weatherService;
    private final WeChatManager weChatManager;

    public BotFacadeService(ConversationService conversationService,
                            WeatherService weatherService,
                            WeChatManager weChatManager) {
        this.conversationService = conversationService;
        this.weatherService = weatherService;
        this.weChatManager = weChatManager;
    }

    public String chat(String sessionId, String message) {
        return conversationService.reply(sessionId, message);
    }

    public String chatStream(String sessionId, String message, Consumer<String> onDelta) {
        return conversationService.replyStream(sessionId, message, onDelta);
    }

    public String weather(String city) {
        return weatherService.getWeather(city);
    }

    public int memorySize(String sessionId) {
        return conversationService.getMemorySize(sessionId);
    }

    public boolean hasMemory(String sessionId) {
        return conversationService.hasMemory(sessionId);
    }

    public void clearMemory(String sessionId) {
        conversationService.clearMemory(sessionId);
    }

    public ConversationMemoryService.ResumeSnapshot forceLoadHistory(String sessionId) {
        return conversationService.forceLoadHistory(sessionId);
    }

    public boolean isWeChatRunning() {
        return weChatManager.isRunning();
    }

    public String envSource() {
        return ConfigUtil.getDotenvSource();
    }

    public boolean hasDashScopeKey() {
        return !ConfigUtil.getBailianKey().isBlank();
    }

    public String ttsModel() {
        return ConfigUtil.getTtsModel();
    }

    public String ttsVoiceId() {
        return ConfigUtil.getTtsVoiceId();
    }
}
