package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.CopywritingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CopywritingTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(CopywritingTool.class);

    private final CopywritingService copywritingService;

    public CopywritingTool(CopywritingService copywritingService) {
        this.copywritingService = copywritingService;
    }

    @Override
    public String name() {
        return "generate_copywriting";
    }

    @Override
    public String description() {
        return "生成朋友圈文案、治愈短句、道歉文案、纪念日文案和个性签名。提取参数时不要把“帮我、生成、写一个”等请求指令放入主题。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();

        JSONObject typeParam = new JSONObject();
        typeParam.put("type", "string");
        typeParam.put("description", "文案类型：moments、healing、apology、anniversary、signature");
        typeParam.put("enum", new JSONArray() {{
            add("moments");
            add("healing");
            add("apology");
            add("anniversary");
            add("signature");
        }});
        properties.put("type", typeParam);

        JSONObject topicParam = new JSONObject();
        topicParam.put("type", "string");
        topicParam.put("description", "纯粹的内容主题或场景，例如杭州游玩、旅行打卡、生日聚会；不要包含“帮我、给我、生成、写一个、朋友圈文案”等请求指令。地点还应分别填写 city 或 destination。");
        properties.put("topic", topicParam);

        JSONObject cityParam = new JSONObject();
        cityParam.put("type", "string");
        cityParam.put("description", "可选，城市名，例如杭州、成都。");
        properties.put("city", cityParam);

        JSONObject destinationParam = new JSONObject();
        destinationParam.put("type", "string");
        destinationParam.put("description", "可选，具体地点名，例如西湖、春熙路。");
        properties.put("destination", destinationParam);

        JSONObject sceneParam = new JSONObject();
        sceneParam.put("type", "string");
        sceneParam.put("description", "可选，场景类型：travel、food、photo、generic");
        sceneParam.put("enum", new JSONArray() {{
            add("travel");
            add("food");
            add("photo");
            add("generic");
        }});
        properties.put("scene", sceneParam);

        JSONObject countParam = new JSONObject();
        countParam.put("type", "integer");
        countParam.put("description", "生成条数，默认 3，范围 1-10");
        properties.put("count", countParam);

        JSONObject styleParam = new JSONObject();
        styleParam.put("type", "string");
        styleParam.put("description", "文案风格偏好，例如文艺、轻松、高级感、俏皮。");
        properties.put("style", styleParam);

        parameters.put("properties", properties);

        JSONArray required = new JSONArray();
        required.add("type");
        required.add("topic");
        parameters.put("required", required);

        return parameters;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = JSON.parseObject(arguments);
            if (args == null) {
                return "参数解析失败，请提供有效的 JSON 参数。";
            }

            String type = trimToNull(args.getString("type"));
            String topic = trimToNull(args.getString("topic"));
            String city = trimToNull(args.getString("city"));
            String destination = trimToNull(args.getString("destination"));
            String scene = trimToNull(args.getString("scene"));
            String style = trimToNull(args.getString("style"));
            int count = Math.max(1, Math.min(10, args.getIntValue("count", 3)));

            if (type == null) {
                return "缺少 type 参数，请指定文案类型。";
            }
            if (topic == null) {
                return "缺少 topic 参数，请描述文案主题或场景。";
            }

            log.info("文案生成工具调用: type={}, topic={}, city={}, destination={}, scene={}, count={}, style={}",
                    type, topic, city, destination, scene, count, style);

            return copywritingService.generate(type, topic, city, destination, scene, style, count);
        } catch (Exception e) {
            log.error("文案生成工具执行异常", e);
            return "文案生成出错: " + e.getMessage();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
