package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.ConversationMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmotionSupportTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(EmotionSupportTool.class);

    private final ConversationMemoryService memoryService;

    public EmotionSupportTool(ConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "emotional_support";
    }

    @Override
    public String description() {
        return "情感树洞与 ABC 情绪拆解工具。mode=listen 表示倾听陪伴，mode=abc 表示按 ABC 模型逐步分析。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("sessionId", property("string", "当前会话 sessionId。"));

        JSONObject mode = property("string", "listen=倾听模式，abc=ABC 分析模式。");
        mode.put("enum", new JSONArray() {{
            add("listen");
            add("abc");
        }});
        properties.put("mode", mode);
        properties.put("confession", property("string", "当前用户输入内容。"));

        parameters.put("properties", properties);
        parameters.put("required", new JSONArray().fluentAdd("sessionId").fluentAdd("mode").fluentAdd("confession"));
        return parameters;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            if (args == null) {
                return "情感工具参数解析失败。";
            }

            String sessionId = trimToNull(args.getString("sessionId"));
            String mode = trimToNull(args.getString("mode"));
            String confession = trimToNull(args.getString("confession"));
            if (sessionId == null || mode == null || confession == null) {
                return "情感工具缺少必要参数。";
            }

            log.info("emotion tool called: sessionId={}, mode={}, confessionLength={}", sessionId, mode, confession.length());
            return switch (mode.toLowerCase()) {
                case "listen" -> handleListen(sessionId, confession);
                case "abc" -> handleAbc(sessionId, confession);
                default -> "不支持的情感工具模式。";
            };
        } catch (Exception e) {
            log.error("emotion tool error", e);
            return "情感工具暂时不可用：" + e.getMessage();
        }
    }

    private String handleListen(String sessionId, String confession) {
        memoryService.addEmotionConfession(sessionId, confession);
        if (memoryService.getEmotionPhase(sessionId) != ConversationMemoryService.EmotionPhase.LISTENING) {
            memoryService.resetEmotionState(sessionId);
        }
        return "我在，继续说也可以；如果你想做 ABC 情绪拆解，也可以直接告诉我。";
    }

    private String handleAbc(String sessionId, String confession) {
        ConversationMemoryService.EmotionPhase currentPhase = memoryService.getEmotionPhase(sessionId);
        switch (currentPhase) {
            case LISTENING -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionPhase(sessionId, ConversationMemoryService.EmotionPhase.ABC_STARTED);
                return "我们开始 ABC。先说 A：发生了什么事情？";
            }
            case ABC_STARTED -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionAbc(sessionId, "A", confession);
                return "收到。接着说 B：你当时脑中最强烈的想法或信念是什么？";
            }
            case A_DONE -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionAbc(sessionId, "B", confession);
                return "明白了。再说 C：这让你产生了什么情绪和感受？";
            }
            case B_DONE -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionAbc(sessionId, "C", confession);
                return "好。接着说 D：你现在如何反驳、松动或重新看待刚才那个信念？";
            }
            case C_DONE -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionAbc(sessionId, "D", confession);
                return "最后说 E：经过前面的整理，你现在更想建立什么新的想法或行动方向？";
            }
            case D_DONE -> {
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionAbc(sessionId, "E", confession);
                return generateAbcSummary(sessionId);
            }
            case E_COMPLETED -> {
                memoryService.resetEmotionState(sessionId);
                memoryService.addEmotionConfession(sessionId, confession);
                memoryService.updateEmotionPhase(sessionId, ConversationMemoryService.EmotionPhase.ABC_STARTED);
                return "上一轮 ABC 已结束，我们重新开始。先说 A：发生了什么事情？";
            }
            default -> {
                return "状态已重置，你可以重新说说现在最困扰你的事。";
            }
        }
    }

    private String generateAbcSummary(String sessionId) {
        Map<String, String> abcData = memoryService.getEmotionAbcData(sessionId);
        memoryService.resetEmotionState(sessionId);
        return """
                【ABC 梳理结果】
                A 事件：
                %s

                B 信念：
                %s

                C 情绪：
                %s

                D 反驳：
                %s

                E 新想法：
                %s

                这一轮 ABC 已完成。你如果愿意，也可以继续说接下来最想处理的部分。
                """.formatted(
                safe(abcData.get("eventA")),
                safe(abcData.get("beliefB")),
                safe(abcData.get("emotionC")),
                safe(abcData.get("disputeD")),
                safe(abcData.get("newBeliefE"))
        ).trim();
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }
}
