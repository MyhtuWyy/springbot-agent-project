package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class SpeechService {
    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);
    private static final String ASR_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String LEGACY_TTS_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String COSYVOICE_TTS_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public enum SpeechTextSource {
        EMBEDDED_TEXT,
        DASHSCOPE_ASR
    }

    public record SpeechToTextResult(String text, SpeechTextSource source) {
    }

    public record SpeechAudioMetadata(String format, int sampleRate, int durationMs, int bitsPerSample,
                                      int channels, int encodeType, String transcriptText) {
    }

    public record SpeechSynthesisResult(byte[] audioBytes, SpeechAudioMetadata metadata) {
    }

    public record TtsFailure(String voiceId, String reason, boolean invalidVoice) {
    }

    public record TtsOptions(String voiceId) {
    }

    private static class NonRetryableTtsException extends Exception {
        NonRetryableTtsException(String message) {
            super(message);
        }
    }

    private final ThreadLocal<String> ttsVoiceOverride = new ThreadLocal<>();
    private final ThreadLocal<TtsFailure> lastTtsFailure = new ThreadLocal<>();

    public String speechToText(MessageItem item, byte[] voiceData) {
        SpeechToTextResult result = speechToTextWithSource(item, voiceData);
        return result != null ? result.text() : null;
    }

    public String speechToText(byte[] voiceData, String fileName, String mimeType, Integer sampleRate) {
        if (voiceData == null || voiceData.length == 0) {
            log.warn("speechToText called with empty audio payload");
            return null;
        }

        String resolvedMimeType = normalizeMimeType(mimeType);
        if (resolvedMimeType == null || resolvedMimeType.isBlank()) {
            String format = detectFormatByContent(voiceData);
            if (format == null || format.isBlank()) {
                format = detectFormatByFileName(fileName);
            }
            resolvedMimeType = mimeTypeFromFormat(format);
        }

        String recognizedText = transcribeWithDashScope(voiceData, resolvedMimeType, sampleRate, fileName);
        return recognizedText == null || recognizedText.isBlank() ? null : recognizedText.trim();
    }

    public SpeechSynthesisResult textToSpeech(String text, String voiceId) {
        String previous = ttsVoiceOverride.get();
        try {
            ttsVoiceOverride.set(voiceId);
            return textToSpeech(text);
        } finally {
            if (previous == null) {
                ttsVoiceOverride.remove();
            } else {
                ttsVoiceOverride.set(previous);
            }
        }
    }

    public SpeechToTextResult speechToTextWithSource(MessageItem item, byte[] voiceData) {
        if (item == null || item.getVoice_item() == null) {
            return null;
        }

        VoiceItem voiceItem = item.getVoice_item();
        String embeddedText = voiceItem.getText();
        if (embeddedText != null && !embeddedText.isBlank()) {
            log.info("语音消息携带转写文本，直接复用，playtime={}s", voiceItem.getPlaytime());
            return new SpeechToTextResult(embeddedText.trim(), SpeechTextSource.EMBEDDED_TEXT);
        }

        if (voiceData == null || voiceData.length == 0) {
            log.warn("语音消息没有可用转写文本，且下载到的音频数据为空");
            return null;
        }

        String recognizedText = transcribeWithDashScope(
                voiceData,
                detectMimeType(voiceItem, voiceData),
                voiceItem.getSample_rate(),
                null
        );
        if (recognizedText != null && !recognizedText.isBlank()) {
            return new SpeechToTextResult(recognizedText.trim(), SpeechTextSource.DASHSCOPE_ASR);
        }

        log.warn("语音消息没有可用转写文本，且外部 ASR 转写失败，size={} bytes", voiceData.length);
        return null;
    }

    public SpeechSynthesisResult textToSpeech(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        lastTtsFailure.remove();

        String apiKey = ConfigUtil.getBailianKey();
        if (apiKey.isBlank()) {
            log.warn("未配置 DashScope API Key，无法调用 TTS 服务");
            lastTtsFailure.set(new TtsFailure(resolveVoiceId(), "未配置 DashScope API Key", false));
            return null;
        }

        Exception lastException = null;
        String resolvedVoiceId = resolveVoiceId();
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                lastTtsFailure.set(new TtsFailure(resolvedVoiceId, "tts interrupted", false));
                log.warn("TTS synthesis aborted before request because thread was interrupted, attempt={}", attempt);
                break;
            }
            try {
                log.info("开始执行 TTS 合成，attempt={}, textLength={}, model={}, voiceId={}",
                        attempt, text.length(), ConfigUtil.getTtsModel(), resolvedVoiceId);
                SpeechSynthesisResult result = executeTextToSpeech(apiKey, text);
                if (result != null && result.audioBytes() != null && result.audioBytes().length > 0) {
                    lastTtsFailure.remove();
                    log.info("TTS 合成成功，attempt={}, bytes={}, durationMs={}, format={}, encodeType={}",
                            attempt,
                            result.audioBytes().length,
                            result.metadata().durationMs(),
                            result.metadata().format(),
                            result.metadata().encodeType());
                    return result;
                }
                log.warn("TTS 合成返回空结果，attempt={}", attempt);
            } catch (NonRetryableTtsException e) {
                log.error("TTS 合成失败，属于不可重试错误，attempt={}, reason={}", attempt, e.getMessage());
                lastException = e;
                lastTtsFailure.set(new TtsFailure(resolvedVoiceId, e.getMessage(), isInvalidVoiceError(e.getMessage())));
                break;
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                lastException = e;
                lastTtsFailure.set(new TtsFailure(resolvedVoiceId, "tts interrupted", false));
                log.warn("TTS synthesis interrupted, attempt={}", attempt);
                break;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    lastException = e;
                    lastTtsFailure.set(new TtsFailure(resolvedVoiceId, "tts interrupted", false));
                    log.warn("TTS synthesis interrupted during request, attempt={}", attempt);
                    break;
                }
                lastException = e;
                log.error("TTS 合成异常，attempt={}", attempt, e);
            }

            if (attempt < 3) {
                sleepQuietly(400L * attempt);
                if (Thread.currentThread().isInterrupted()) {
                    lastTtsFailure.set(new TtsFailure(resolvedVoiceId, "tts interrupted", false));
                    log.warn("TTS synthesis aborted during retry backoff, attempt={}", attempt);
                    break;
                }
            }
        }

        if (lastException != null) {
            if (lastTtsFailure.get() == null) {
                lastTtsFailure.set(new TtsFailure(resolvedVoiceId, lastException.getMessage(), false));
            }
            log.error("TTS 合成最终失败", lastException);
        } else {
            if (lastTtsFailure.get() == null) {
                lastTtsFailure.set(new TtsFailure(resolvedVoiceId, "未获取到有效音频结果", false));
            }
            log.error("TTS 合成最终失败，未获取到有效音频结果");
        }
        return null;
    }

    public SpeechAudioMetadata inspectAudioMetadata(byte[] audioBytes, String fileName, Integer fallbackSampleRate, String transcriptText) {
        String format = detectFormatByContent(audioBytes);
        if (format == null || format.isBlank()) {
            format = detectFormatByFileName(fileName);
        }
        if (format == null || format.isBlank()) {
            format = "wav";
        }

        int sampleRate = fallbackSampleRate != null && fallbackSampleRate > 0 ? fallbackSampleRate : ConfigUtil.getTtsSampleRate();
        int bitsPerSample = ConfigUtil.getWechatVoiceDefaultBitsPerSample();
        int channels = 1;

        String normalizedFormat = normalizeFormat(format);
        if ("wav".equals(normalizedFormat) && audioBytes != null && audioBytes.length >= 44) {
            int wavChannels = readLittleEndianShort(audioBytes, 22);
            int wavSampleRate = readLittleEndianInt(audioBytes, 24);
            int wavBitsPerSample = readLittleEndianShort(audioBytes, 34);
            if (wavChannels > 0) {
                channels = wavChannels;
            }
            if (wavSampleRate > 0) {
                sampleRate = wavSampleRate;
            }
            if (wavBitsPerSample > 0) {
                bitsPerSample = wavBitsPerSample;
            }
        }

        int durationMs = estimateDurationMs(audioBytes, sampleRate, normalizedFormat, bitsPerSample, channels);
        int encodeType = determineEncodeType(normalizedFormat);
        String finalTranscript = ConfigUtil.isWechatVoiceTranscriptEnabled() ? transcriptText : null;

        return new SpeechAudioMetadata(
                normalizedFormat,
                sampleRate,
                durationMs,
                bitsPerSample,
                channels,
                encodeType,
                finalTranscript
        );
    }

    public TtsFailure getLastTtsFailure() {
        return lastTtsFailure.get();
    }

    private String resolveVoiceId() {
        String override = ttsVoiceOverride.get();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return ConfigUtil.getTtsVoiceId();
    }

    private SpeechSynthesisResult executeTextToSpeech(String apiKey, String text) throws Exception {
        String model = ConfigUtil.getTtsModel();
        if (isCosyVoiceModel(model) || isQwenAudioTtsModel(model)) {
            return executeDashScopeSpeechSynthesizer(apiKey, text, model);
        }
        return executeLegacyMultimodalTts(apiKey, text, model);
    }

    private SpeechSynthesisResult executeDashScopeSpeechSynthesizer(String apiKey, String text, String model) throws Exception {
        JSONObject input = new JSONObject();
        input.put("text", text);

        JSONObject parameters = new JSONObject();
        parameters.put("voice", resolveVoiceId());
        parameters.put("format", ConfigUtil.getTtsFormat());
        parameters.put("sample_rate", ConfigUtil.getTtsSampleRate());
        parameters.put("volume", normalizeCosyVoiceVolume(ConfigUtil.getTtsVolume()));
        parameters.put("rate", ConfigUtil.getTtsSpeed());
        parameters.put("pitch", normalizeCosyVoicePitch(ConfigUtil.getTtsPitch()));

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("input", input);
        requestBody.put("parameters", parameters);

        Request request = new Request.Builder()
                .url(COSYVOICE_TTS_API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                .build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                if (isNonRetryableTtsError(response.code(), body)) {
                    throw new NonRetryableTtsException("status=" + response.code() + ", body=" + body);
                }
                log.error("DashScope SpeechSynthesizer 请求失败，status={}, body={}", response.code(), body);
                return null;
            }

            JSONObject json = JSON.parseObject(body);
            JSONObject output = json.getJSONObject("output");
            if (output == null) {
                log.warn("DashScope SpeechSynthesizer 响应缺少 output，body={}", body);
                return null;
            }

            JSONObject audio = output.getJSONObject("audio");
            if (audio == null) {
                log.warn("DashScope SpeechSynthesizer 响应缺少 audio，body={}", body);
                return null;
            }

            String audioUrl = audio.getString("url");
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("DashScope SpeechSynthesizer 未返回音频下载地址，body={}", body);
                return null;
            }

            byte[] audioBytes = downloadAudioBytes(audioUrl);
            if (audioBytes == null || audioBytes.length == 0) {
                log.warn("DashScope SpeechSynthesizer 音频下载结果为空，audioUrl={}", audioUrl);
                return null;
            }

            String responseFormat = audio.getString("format");
            if (responseFormat == null || responseFormat.isBlank()) {
                responseFormat = ConfigUtil.getTtsFormat();
            }
            int responseSampleRate = audio.getIntValue("sample_rate");
            if (responseSampleRate <= 0) {
                responseSampleRate = ConfigUtil.getTtsSampleRate();
            }

            SpeechAudioMetadata metadata = inspectAudioMetadata(audioBytes, "tts." + responseFormat, responseSampleRate, text);
            log.info("DashScope SpeechSynthesizer 合成成功，sampleRate={}, durationMs={}, format={}, bytes={}",
                    metadata.sampleRate(), metadata.durationMs(), metadata.format(), audioBytes.length);
            return new SpeechSynthesisResult(audioBytes, metadata);
        }
    }

    private SpeechSynthesisResult executeLegacyMultimodalTts(String apiKey, String text, String model) throws Exception {
        JSONObject voiceSetting = new JSONObject();
        voiceSetting.put("voice_id", resolveVoiceId());
        voiceSetting.put("speed", ConfigUtil.getTtsSpeed());
        voiceSetting.put("vol", ConfigUtil.getTtsVolume());
        voiceSetting.put("pitch", ConfigUtil.getTtsPitch());

        JSONObject audioSetting = new JSONObject();
        audioSetting.put("sample_rate", ConfigUtil.getTtsSampleRate());
        audioSetting.put("format", ConfigUtil.getTtsFormat());
        audioSetting.put("bitrate", 128000);
        audioSetting.put("channel", 1);

        JSONObject input = new JSONObject();
        input.put("text", text);
        input.put("voice_setting", voiceSetting);
        input.put("audio_setting", audioSetting);
        input.put("subtitle_enable", false);
        input.put("output_format", "hex");

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("input", input);

        Request request = new Request.Builder()
                .url(LEGACY_TTS_API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                .build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                if (isNonRetryableTtsError(response.code(), body)) {
                    throw new NonRetryableTtsException("status=" + response.code() + ", body=" + body);
                }
                log.error("DashScope 旧版 TTS 请求失败，status={}, body={}", response.code(), body);
                return null;
            }

            JSONObject json = JSON.parseObject(body);
            JSONObject output = json.getJSONObject("output");
            if (output == null) {
                log.warn("DashScope 旧版 TTS 响应缺少 output，body={}", body);
                return null;
            }

            JSONObject baseResp = output.getJSONObject("base_resp");
            if (baseResp != null && baseResp.getIntValue("status_code") != 0) {
                throw new NonRetryableTtsException("statusCode=" + baseResp.getIntValue("status_code")
                        + ", statusMsg=" + baseResp.getString("status_msg")
                        + ", traceId=" + baseResp.getString("trace_id"));
            }

            JSONObject data = output.getJSONObject("data");
            if (data == null) {
                log.warn("DashScope 旧版 TTS 响应缺少 data，body={}", body);
                return null;
            }

            String audioHex = data.getString("audio");
            if (audioHex == null || audioHex.isBlank()) {
                log.warn("DashScope 旧版 TTS 未返回有效音频数据，body={}", body);
                return null;
            }

            byte[] audioBytes = hexToBytes(audioHex);
            if (audioBytes.length == 0) {
                log.warn("DashScope 旧版 TTS 音频解码结果为空");
                return null;
            }

            JSONObject extraInfo = output.getJSONObject("extra_info");
            int responseSampleRate = extraInfo != null ? extraInfo.getIntValue("audio_sample_rate") : ConfigUtil.getTtsSampleRate();
            String responseFormat = extraInfo != null ? extraInfo.getString("audio_format") : ConfigUtil.getTtsFormat();
            SpeechAudioMetadata metadata = inspectAudioMetadata(audioBytes, "tts." + responseFormat, responseSampleRate, text);
            log.info("DashScope 旧版 TTS 合成成功，sampleRate={}, durationMs={}, format={}, bitsPerSample={}, encodeType={}",
                    metadata.sampleRate(), metadata.durationMs(), metadata.format(), metadata.bitsPerSample(), metadata.encodeType());
            return new SpeechSynthesisResult(audioBytes, metadata);
        }
    }

    private byte[] downloadAudioBytes(String audioUrl) throws Exception {
        Request request = new Request.Builder()
                .url(audioUrl)
                .get()
                .build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("下载音频失败，status=" + response.code() + ", url=" + audioUrl);
            }
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    private String transcribeWithDashScope(byte[] voiceData, String mimeType, Integer sampleRate, String fileName) {
        String apiKey = ConfigUtil.getBailianKey();
        if (apiKey.isBlank()) {
            log.warn("未配置 DashScope API Key，无法调用 ASR 服务");
            return null;
        }

        try {
            String resolvedMimeType = normalizeMimeType(mimeType);
            if (resolvedMimeType == null || resolvedMimeType.isBlank()) {
                resolvedMimeType = mimeTypeFromFormat(detectFormatByFileName(fileName));
            }
            if (resolvedMimeType == null || resolvedMimeType.isBlank()) {
                resolvedMimeType = mimeTypeFromFormat(detectFormatByContent(voiceData));
            }
            if (resolvedMimeType == null || resolvedMimeType.isBlank()) {
                resolvedMimeType = "audio/wav";
            }
            String dataUrl = "data:" + resolvedMimeType + ";base64," + Base64.getEncoder().encodeToString(voiceData);

            JSONObject inputAudio = new JSONObject();
            inputAudio.put("data", dataUrl);

            JSONObject audioContent = new JSONObject();
            audioContent.put("type", "input_audio");
            audioContent.put("input_audio", inputAudio);

            JSONArray contentArray = new JSONArray();
            contentArray.add(audioContent);

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", contentArray);

            JSONArray messages = new JSONArray();
            messages.add(userMessage);

            JSONObject asrOptions = new JSONObject();
            asrOptions.put("enable_itn", true);
            String language = ConfigUtil.getAsrLanguage();
            if (language != null && !language.isBlank()) {
                asrOptions.put("language", language.trim());
            }

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", ConfigUtil.getAsrModel());
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("asr_options", asrOptions);

            Request request = new Request.Builder()
                    .url(ASR_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("DashScope ASR 请求失败，status={}, body={}", response.code(), errorBody);
                    return null;
                }

                String body = response.body() != null ? response.body().string() : "";
                String recognizedText = extractAsrContent(body);
                if (recognizedText == null || recognizedText.isBlank()) {
                    log.warn("DashScope ASR 未返回有效文本，body={}", body);
                    return null;
                }

                log.info("DashScope ASR 转写成功，mimeType={}, sampleRate={}, fileName={}",
                        resolvedMimeType, sampleRate, fileName);
                return recognizedText.trim();
            }
        } catch (Exception e) {
            log.error("调用 DashScope ASR 异常", e);
            return null;
        }
    }

    private String extractAsrContent(String body) {
        JSONObject json = JSON.parseObject(body);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) {
            return null;
        }

        Object content = message.get("content");
        if (content instanceof String str) {
            return str;
        }

        if (content instanceof JSONArray array) {
            for (Object item : array) {
                if (item instanceof JSONObject obj) {
                    String extractedText = obj.getString("text");
                    if (extractedText != null && !extractedText.isBlank()) {
                        return extractedText;
                    }
                }
            }
        }
        return null;
    }

    private String detectMimeType(VoiceItem voiceItem, byte[] voiceData) {
        if (startsWith(voiceData, "RIFF") && containsAt(voiceData, 8, "WAVE")) {
            return "audio/wav";
        }
        if (startsWith(voiceData, "ID3")
                || (voiceData.length > 1 && (voiceData[0] & 0xFF) == 0xFF && (voiceData[1] & 0xE0) == 0xE0)) {
            return "audio/mpeg";
        }
        if (startsWith(voiceData, "OggS")) {
            return "audio/ogg";
        }
        if (startsWith(voiceData, "#!SILK_V3")) {
            return "audio/silk";
        }
        if (startsWith(voiceData, "fLaC")) {
            return "audio/flac";
        }

        Integer encodeType = voiceItem.getEncode_type();
        if (encodeType != null && encodeType == ConfigUtil.getWechatVoiceSilkEncodeType()) {
            return "audio/silk";
        }

        log.warn("无法精确识别语音 MIME 类型，encodeType={}，回退为 audio/wav", encodeType);
        return "audio/wav";
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("audio/") ? normalized : "audio/" + normalized;
    }

    private String mimeTypeFromFormat(String format) {
        String normalizedFormat = normalizeFormat(format);
        if (normalizedFormat.isBlank()) {
            return null;
        }
        return switch (normalizedFormat) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "silk" -> "audio/silk";
            default -> "audio/" + normalizedFormat;
        };
    }

    private int estimateDurationMs(byte[] audioBytes, int sampleRate, String format, int bitsPerSample, int channels) {
        if (audioBytes == null || audioBytes.length == 0) {
            return 1000;
        }

        String normalizedFormat = normalizeFormat(format);
        if ("wav".equals(normalizedFormat) && audioBytes.length >= 44) {
            int dataSize = findWavDataSize(audioBytes);
            if (channels > 0 && bitsPerSample > 0 && dataSize > 0 && sampleRate > 0) {
                int bytesPerSecond = sampleRate * channels * bitsPerSample / 8;
                if (bytesPerSecond > 0) {
                    return Math.max(1000, (int) Math.ceil(dataSize * 1000.0 / bytesPerSecond));
                }
            }
        }

        if (sampleRate > 0 && bitsPerSample > 0 && channels > 0) {
            int estimatedBytesPerSecond = sampleRate * channels * bitsPerSample / 8;
            if (estimatedBytesPerSecond > 0) {
                return Math.max(1000, (int) Math.ceil(audioBytes.length * 1000.0 / estimatedBytesPerSecond));
            }
        }

        return 1000;
    }

    private int determineEncodeType(String format) {
        String normalizedFormat = normalizeFormat(format);
        if ("silk".equals(normalizedFormat)) {
            return ConfigUtil.getWechatVoiceSilkEncodeType();
        }
        return ConfigUtil.getWechatVoiceDefaultEncodeType();
    }

    private String detectFormatByContent(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return null;
        }
        if (startsWith(audioBytes, "RIFF") && containsAt(audioBytes, 8, "WAVE")) {
            return "wav";
        }
        if (startsWith(audioBytes, "#!SILK_V3")) {
            return "silk";
        }
        if (startsWith(audioBytes, "OggS")) {
            return "ogg";
        }
        if (startsWith(audioBytes, "fLaC")) {
            return "flac";
        }
        if (startsWith(audioBytes, "ID3")
                || (audioBytes.length > 1 && (audioBytes[0] & 0xFF) == 0xFF && (audioBytes[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }
        return null;
    }

    private String detectFormatByFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        return normalizeFormat(fileName.substring(fileName.lastIndexOf('.') + 1));
    }

    private String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }

    private int readLittleEndianShort(byte[] bytes, int offset) {
        if (bytes.length < offset + 2) {
            return 0;
        }
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private int readLittleEndianInt(byte[] bytes, int offset) {
        if (bytes.length < offset + 4) {
            return 0;
        }
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private boolean startsWith(byte[] data, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data == null || data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAt(byte[] data, int offset, String text) {
        byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
        if (data == null || data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] hexToBytes(String hex) {
        String normalized = hex.replaceAll("\\s+", "");
        if ((normalized.length() & 1) == 1) {
            throw new IllegalArgumentException("hex length must be even");
        }

        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            int high = Character.digit(normalized.charAt(i), 16);
            int low = Character.digit(normalized.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex char");
            }
            bytes[i / 2] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    private int findWavDataSize(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 12) {
            return 0;
        }

        int offset = 12;
        while (offset + 8 <= audioBytes.length) {
            if (containsAt(audioBytes, offset, "data")) {
                int declaredSize = readLittleEndianInt(audioBytes, offset + 4);
                int actualRemaining = audioBytes.length - (offset + 8);
                if (actualRemaining <= 0) {
                    return 0;
                }
                if (declaredSize <= 0 || declaredSize > actualRemaining) {
                    return actualRemaining;
                }
                return declaredSize;
            }

            int chunkSize = readLittleEndianInt(audioBytes, offset + 4);
            if (chunkSize <= 0) {
                break;
            }

            offset += 8 + chunkSize;
            if ((chunkSize & 1) == 1) {
                offset++;
            }
        }

        return 0;
    }

    private boolean isNonRetryableTtsError(int statusCode, String errorBody) {
        if (statusCode < 400 || statusCode >= 500) {
            return false;
        }

        String normalizedBody = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
        return normalizedBody.contains("not activated")
                || normalizedBody.contains("invalidparameter")
                || normalizedBody.contains("invalid parameter")
                || normalizedBody.contains("model not found")
                || normalizedBody.contains("voice not found")
                || normalizedBody.contains("url error")
                || normalizedBody.contains("unauthorized")
                || normalizedBody.contains("forbidden");
    }

    private boolean isInvalidVoiceError(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return normalized.contains("voice not found")
                || normalized.contains("invalidparameter")
                || normalized.contains("invalid parameter")
                || normalized.contains("url error");
    }

    private boolean isCosyVoiceModel(String model) {
        String normalizedModel = normalizeFormat(model);
        return normalizedModel.startsWith("cosyvoice");
    }

    private boolean isQwenAudioTtsModel(String model) {
        String normalizedModel = normalizeFormat(model);
        return normalizedModel.startsWith("qwen")
                && (normalizedModel.contains("tts") || normalizedModel.contains("audio"));
    }

    private int normalizeCosyVoiceVolume(double rawVolume) {
        if (rawVolume <= 2.0D) {
            return Math.max(0, Math.min(100, (int) Math.round(rawVolume * 50)));
        }
        return Math.max(0, Math.min(100, (int) Math.round(rawVolume)));
    }

    private double normalizeCosyVoicePitch(int rawPitch) {
        double pitch = rawPitch;
        if (pitch <= 0) {
            return 1.0D;
        }
        return Math.max(0.5D, Math.min(2.0D, pitch));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
