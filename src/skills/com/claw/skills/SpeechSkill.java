package com.claw.skills;

import com.alibaba.fastjson2.JSONObject;
import com.claw.service.SpeechService;
import com.claw.service.DesktopVoiceControlService;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillDefine;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

@Component
@SkillDefine(
        name = "speech_skill",
        description = "Handle speech synthesis and transcription.",
        timeoutMs = 180000L
)
public class SpeechSkill implements BaseSkill {
    private final SpeechService speechService;
    private final DesktopVoiceControlService desktopVoiceControlService;

    public SpeechSkill(SpeechService speechService, DesktopVoiceControlService desktopVoiceControlService) {
        this.speechService = speechService;
        this.desktopVoiceControlService = desktopVoiceControlService;
    }

    @Override
    public String skillName() {
        return "speech_skill";
    }

    @Override
    public String description() {
        return "Handle speech synthesis and transcription.";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("action", property("string", "synthesize, transcribe"));
        properties.put("text", property("string", "Text used for speech synthesis."));
        properties.put("voiceId", property("string", "Optional TTS voice id."));
        properties.put("audioBase64", property("string", "Audio payload in base64 for transcription."));
        properties.put("fileName", property("string", "Optional audio file name."));
        properties.put("mimeType", property("string", "Optional audio mime type."));
        properties.put("sampleRate", property("integer", "Optional audio sample rate."));

        schema.put("properties", properties);
        schema.put("required", new com.alibaba.fastjson2.JSONArray().fluentAdd("action"));
        return schema;
    }

    @Override
    public SkillResult execute(SkillRequest request) {
        JSONObject args = request.arguments();
        String action = trim(args.getString("action"));
        Map<String, Function<JSONObject, SkillResult>> actionHandlers = Map.of(
                "synthesize", this::synthesize,
                "transcribe", this::transcribe
        );
        Function<JSONObject, SkillResult> handler = actionHandlers.get(action);
        if (handler == null) {
            return SkillResult.failure(skillName(), "unknown action", "unknown action: " + action, 0L, false);
        }
        return handler.apply(args);
    }

    private SkillResult synthesize(JSONObject args) {
        String text = trim(args.getString("text"));
        if (text == null || text.isBlank()) {
            return SkillResult.failure(skillName(), "text is required", "text is required", 0L, false);
        }

        String sessionId = trim(args.getString("sessionId"));
        String voiceId = trim(args.getString("voiceId"));
        if (voiceId == null || voiceId.isBlank()) {
            voiceId = desktopVoiceControlService.resolveVoiceId(sessionId);
        }

        SpeechService.SpeechSynthesisResult result = speechService.textToSpeech(text, voiceId);
        if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
            SpeechService.TtsFailure failure = speechService.getLastTtsFailure();
            String message = failure == null ? "tts failed" : failure.reason();
            return SkillResult.failure(skillName(), message, message, 0L, false);
        }

        JSONObject data = new JSONObject();
        data.put("audioBase64", Base64.getEncoder().encodeToString(result.audioBytes()));
        data.put("format", result.metadata().format());
        data.put("sampleRate", result.metadata().sampleRate());
        data.put("durationMs", result.metadata().durationMs());
        data.put("bitsPerSample", result.metadata().bitsPerSample());
        data.put("channels", result.metadata().channels());
        data.put("encodeType", result.metadata().encodeType());
        return SkillResult.success(skillName(), "speech synthesize done", data, 0L);
    }

    private SkillResult transcribe(JSONObject args) {
        String audioBase64 = trim(args.getString("audioBase64"));
        if (audioBase64 == null || audioBase64.isBlank()) {
            return SkillResult.failure(skillName(), "audioBase64 is required", "audioBase64 is required", 0L, false);
        }

        String payload = audioBase64.contains(",") ? audioBase64.substring(audioBase64.indexOf(',') + 1) : audioBase64;
        byte[] audioBytes = Base64.getDecoder().decode(payload);
        String text = speechService.speechToText(
                audioBytes,
                trim(args.getString("fileName")),
                trim(args.getString("mimeType")),
                args.getInteger("sampleRate")
        );
        if (text == null || text.isBlank()) {
            return SkillResult.failure(skillName(), "transcribe failed", "no transcript", 0L, false);
        }

        JSONObject data = new JSONObject();
        data.put("text", text);
        return SkillResult.success(skillName(), "speech transcribe done", data, 0L);
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
