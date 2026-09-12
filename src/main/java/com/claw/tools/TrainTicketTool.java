package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.TrainTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TrainTicketTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(TrainTicketTool.class);

    private final TrainTicketService trainTicketService;

    public TrainTicketTool(TrainTicketService trainTicketService) {
        this.trainTicketService = trainTicketService;
    }

    @Override
    public String name() {
        return "query_train_tickets";
    }

    @Override
    public String description() {
        return "查询高铁票信息，需要出发地、目的地的标准站名或站点编码、出行日期，可选上午/下午/晚上时间偏好。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("origin", stringProperty("出发地，使用 12306 标准站名或站点编码，例如 北京南、杭州东"));
        properties.put("destination", stringProperty("目的地，使用 12306 标准站名或站点编码，例如 上海虹桥、西安北"));
        properties.put("travel_date", stringProperty("出行日期，优先使用 yyyy-MM-dd，也支持 今天、明天、后天"));
        properties.put("time_preference", stringProperty("可选，上午、下午或晚上"));
        schema.put("properties", properties);

        JSONArray required = new JSONArray();
        required.add("origin");
        required.add("destination");
        required.add("travel_date");
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            if (args == null) {
                return "高铁票查询参数解析失败。";
            }
            return trainTicketService.query(
                    args.getString("session_id"),
                    args.getString("origin"),
                    args.getString("destination"),
                    args.getString("travel_date"),
                    args.getString("time_preference"),
                    args.getBooleanValue("travel_workflow")
            );
        } catch (Exception e) {
            log.error("TrainTicketTool 执行失败", e);
            return "高铁票查询失败：" + e.getMessage();
        }
    }

    private JSONObject stringProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
