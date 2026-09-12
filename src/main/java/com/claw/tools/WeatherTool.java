package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "查询指定城市或地点的天气，支持可选日期，例如今天、明天、后天或 yyyy-MM-dd。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONObject cityParam = new JSONObject();
        cityParam.put("type", "string");
        cityParam.put("description", "要查询天气的城市名或地点名，例如 杭州、西湖");
        properties.put("city", cityParam);

        JSONObject dateParam = new JSONObject();
        dateParam.put("type", "string");
        dateParam.put("description", "可选日期，例如 今天、明天、后天 或 2026-07-31");
        properties.put("date", dateParam);

        parameters.put("properties", properties);
        return parameters;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            String city = args != null ? trimToNull(args.getString("city")) : null;
            String date = args != null ? trimToNull(args.getString("date")) : null;
            if (city == null) {
                return "缺少城市或地点参数。";
            }
            return weatherService.getWeather(city, date);
        } catch (Exception e) {
            log.error("WeatherTool execute failed", e);
            return "天气查询出错：" + e.getMessage();
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
