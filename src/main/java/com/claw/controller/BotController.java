package com.claw.controller;

import com.claw.dto.ChatRequest;
import com.claw.dto.ChatResponse;
import com.claw.dto.SessionStatusResponse;
import com.claw.dto.SystemStatusResponse;
import com.claw.service.BotFacadeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class BotController {
    private final BotFacadeService botFacadeService;

    public BotController(BotFacadeService botFacadeService) {
        this.botFacadeService = botFacadeService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String reply = botFacadeService.chat(request.sessionId(), request.message());
        return new ChatResponse(request.sessionId(), request.message(), reply);
    }

    @GetMapping("/weather")
    public String weather(@RequestParam @NotBlank String city) {
        return botFacadeService.weather(city);
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionStatusResponse sessionStatus(@PathVariable String sessionId) {
        return new SessionStatusResponse(
                sessionId,
                botFacadeService.hasMemory(sessionId),
                botFacadeService.memorySize(sessionId)
        );
    }

    @DeleteMapping("/sessions/{sessionId}/memory")
    public SessionStatusResponse clearMemory(@PathVariable String sessionId) {
        botFacadeService.clearMemory(sessionId);
        return new SessionStatusResponse(sessionId, false, 0);
    }

    @GetMapping("/system/status")
    public SystemStatusResponse systemStatus() {
        return new SystemStatusResponse(
                botFacadeService.hasDashScopeKey(),
                botFacadeService.envSource(),
                botFacadeService.ttsModel(),
                botFacadeService.ttsVoiceId(),
                botFacadeService.isWeChatRunning()
        );
    }
}
