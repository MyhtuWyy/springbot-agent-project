package com.claw.util;

import com.claw.config.SpringContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.*;

/**
 * 统一配置读取工具类。
 * 从 Spring Boot 的 Environment（application.yml）读取配置，
 * 同时兼容系统环境变量作为覆盖来源。
 * 不再依赖 .env 文件和 dotenv-java 库。
 */
public class ConfigUtil {
    private static final Logger log = LoggerFactory.getLogger(ConfigUtil.class);

    // ===== DashScope 核心配置 =====

    public static String getDashscopeApiKey() {
        return getProperty("dashscope.api-key", "");
    }

    public static String getDashscopeModel() {
        return getProperty("dashscope.model", "qwen-turbo");
    }

    public static String getDashscopeBaseUrl() {
        return getProperty("dashscope.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    public static String getVisionModel() {
        return getProperty("dashscope.vl-model", "qwen-vl-plus");
    }

    // ===== DashScope ASR（语音识别）配置 =====

    public static String getAsrModel() {
        return getProperty("dashscope.asr.model", "qwen3-asr-flash");
    }

    public static String getAsrLanguage() {
        return getProperty("dashscope.asr.language", "zh");
    }

    // ===== DashScope TTS（语音合成）配置 =====

    public static String getTtsModel() {
        return getProperty("dashscope.tts.model", "cosyvoice-v3-flash");
    }

    public static String getTtsVoiceId() {
        return getProperty("dashscope.tts.voice-id", "longanhuan");
    }

    public static String getTtsFormat() {
        return getProperty("dashscope.tts.format", "wav");
    }

    public static int getTtsSampleRate() {
        return getIntProperty("dashscope.tts.sample-rate", 16000);
    }

    public static double getTtsSpeed() {
        return getDoubleProperty("dashscope.tts.speed", 1.0);
    }

    public static double getTtsVolume() {
        return getDoubleProperty("dashscope.tts.volume", 1.0);
    }

    public static int getTtsPitch() {
        return getIntProperty("dashscope.tts.pitch", 0);
    }

    public static int getTtsSingleReplyMaxChars() {
        return getIntProperty("dashscope.tts.single-reply-max-chars", 80);
    }

    public static int getTtsSegmentMaxChars() {
        return getIntProperty("dashscope.tts.segment-max-chars", 100);
    }

    public static int getTtsSegmentMinChars() {
        return getIntProperty("dashscope.tts.segment-min-chars", 60);
    }

    /**
     * 获取 TTS 语音别名映射（alias -> voiceId）。
     * 配置格式为分号分隔的 key=value 对，如 "少女音=longanhuan;温婉女声=longdaiyu_v3"
     */
    public static Map<String, String> getTtsVoiceAliases() {
        String raw = getProperty("dashscope.tts.voice-alias-map", "");
        return parseSemicolonMap(raw);
    }

    /**
     * 获取男性语音选项别名列表。
     * 配置格式为逗号分隔，如 "阳光男声,沉稳男声,洒脱男声,顽皮男声"
     */
    public static List<String> getTtsMaleVoiceOptionAliases() {
        String raw = getProperty("dashscope.tts.male-option-aliases", "");
        return parseCommaList(raw);
    }

    /**
     * 获取女性语音选项别名列表。
     * 配置格式为逗号分隔，如 "少女音,温婉女声,元气女声"
     */
    public static List<String> getTtsFemaleVoiceOptionAliases() {
        String raw = getProperty("dashscope.tts.female-option-aliases", "");
        return parseCommaList(raw);
    }

    /**
     * 获取已验证的语音别名映射（alias -> voiceId）。
     * 配置格式为分号分隔的 key=value 对。
     */
    public static Map<String, String> getTtsVerifiedVoiceAliases() {
        String raw = getProperty("dashscope.tts.verified-alias-map", "");
        return parseSemicolonMap(raw);
    }

    // ===== 兼容旧命名的方法（用于 WeChatService）=====

    public static Map<String, String> getTtsVoiceAliasMap() {
        return getTtsVoiceAliases();
    }

    public static Map<String, String> getTtsVerifiedVoiceAliasMap() {
        return getTtsVerifiedVoiceAliases();
    }

    // ===== Tianapi 配置 =====

    public static String getTianapiKey() {
        return getProperty("tianapi.key", "");
    }

    public static String getTianApiKey() {
        return getTianapiKey();
    }

    // ===== Amap 高德地图配置 =====

    public static String getAmapApiKey() {
        return getProperty("amap.key", "");
    }

    public static String getTrainTicketApiUrl() {
        return getPropertyWithFallback("travel.train-ticket.api-url", "TRAVEL_TRAIN_TICKET_API_URL", "");
    }

    public static String getTrainTicketApiKey() {
        return getPropertyWithFallback("travel.train-ticket.api-key", "TRAVEL_TRAIN_TICKET_API_KEY", "");
    }

    public static String getTrainTicketApiKeyHeader() {
        return getPropertyWithFallback("travel.train-ticket.api-key-header", "TRAVEL_TRAIN_TICKET_API_KEY_HEADER", "Authorization");
    }

    // ===== Qdrant 向量库配置 =====

    public static String getQdrantUrl() {
        return getProperty("qdrant.url", "http://localhost:6333");
    }

    public static String getQdrantApiKey() {
        return getProperty("qdrant.api-key", "");
    }

    public static String getQdrantCollection() {
        return getProperty("qdrant.collection", "resume_chunks");
    }

    public static int getQdrantVectorSize() {
        return getIntProperty("qdrant.vector-size", 128);
    }

    // ===== Conversation 配置 =====

    public static int getConversationMaxMessages() {
        return getIntProperty("conversation.max-messages", 20);
    }

    public static int getConversationExpireMinutes() {
        return getIntProperty("conversation.expire-minutes", 60);
    }

    public static int getConversationResumeWindowMinutes() {
        return getIntProperty("conversation.resume-window-minutes", 60);
    }

    // ===== Wechat 语音配置 =====

    public static boolean isWechatVoiceTranscriptEnabled() {
        return getBooleanProperty("wechat.voice.include-transcript", false);
    }

    public static int getWechatVoiceSegmentIntervalMs() {
        return getIntProperty("wechat.voice.segment-interval-ms", 250);
    }

    public static int getWechatVoiceDefaultBitsPerSample() {
        return getIntProperty("wechat.voice.default-bits-per-sample", 16);
    }

    public static int getWechatVoiceDefaultEncodeType() {
        return getIntProperty("wechat.voice.default-encode-type", 1);
    }

    public static int getWechatVoiceSilkEncodeType() {
        return getIntProperty("wechat.voice.silk-encode-type", 6);
    }

    // ===== 配置源标识 =====

    public static String getEnvSource() {
        return "application.yml";
    }

    public static String getDotenvSource() {
        return "application.yml";
    }

    // ===== 系统状态辅助方法 =====

    public static boolean isDashScopeConfigured() {
        return !getDashscopeApiKey().isEmpty();
    }

    // ===== Bailian 兼容方法 =====

    public static String getBailianKey() {
        return getDashscopeApiKey();
    }

    public static String getBailianModel() {
        return getDashscopeModel();
    }

    // ===== 通用配置读取方法 =====

    /**
     * 从 Spring Environment 读取配置值。
     * 优先使用 application.yml 中的值；同时兼容系统环境变量覆盖（使用点分 key 的上划线形式）。
     * 若 Spring 上下文尚未初始化，返回默认值。
     */
    private static String getProperty(String key, String defaultValue) {
        Environment env = SpringContextHolder.getEnvironment();
        if (env != null) {
            String value = env.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return defaultValue;
    }

    private static String getPropertyWithFallback(String key, String envKey, String defaultValue) {
        String value = getProperty(key, null);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String sys = System.getProperty(envKey);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return defaultValue;
    }

    private static int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("解析整数配置失败，key={}，value={}，使用默认值={}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static double getDoubleProperty(String key, double defaultValue) {
        String value = getProperty(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("解析浮点配置失败，key={}，value={}，使用默认值={}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 解析分号分隔的 key=value 映射，如 "少女音=longanhuan;温婉女声=longdaiyu_v3"
     */
    private static Map<String, String> parseSemicolonMap(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0 && eq < trimmed.length() - 1) {
                String alias = trimmed.substring(0, eq).trim().toLowerCase();
                String voiceId = trimmed.substring(eq + 1).trim();
                if (!alias.isEmpty() && !voiceId.isEmpty()) {
                    map.put(alias, voiceId);
                }
            }
        }
        return map;
    }

    /**
     * 解析逗号分隔的列表，如 "少女音,温婉女声,元气女声"
     */
    private static List<String> parseCommaList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }
}
