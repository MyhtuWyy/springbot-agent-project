package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.AmapService;
import com.claw.service.CityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RouteTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(RouteTool.class);

    private final AmapService amapService;
    private final CityResolver cityResolver;

    public RouteTool(AmapService amapService, CityResolver cityResolver) {
        this.amapService = amapService;
        this.cityResolver = cityResolver;
    }

    @Override
    public String name() {
        return "query_route";
    }

    @Override
    public String description() {
        return "查询起点到终点的驾车、公共交通、步行三种路线方案。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("origin", stringProperty("起点，例如杭州东站、酒店、景点"));
        properties.put("destination", stringProperty("终点，例如西湖、景点、地铁站"));
        properties.put("city", stringProperty("可选，城市名称，用于辅助解析"));
        schema.put("properties", properties);

        JSONArray required = new JSONArray();
        required.add("origin");
        required.add("destination");
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            if (args == null) {
                return "路线参数解析失败，请提供起点和终点。";
            }

            String origin = normalizePlace(args.getString("origin"));
            String destination = normalizePlace(args.getString("destination"));
            String city = normalizePlace(args.getString("city"));

            if (origin == null || destination == null) {
                return "请告诉我起点和终点，例如“从杭州东站到西湖怎么走”。";
            }

            if (city == null) {
                AmapService.PlaceContext originContext = amapService.resolvePlaceContext(origin);
                AmapService.PlaceContext destinationContext = amapService.resolvePlaceContext(destination);
                city = firstNonBlank(
                        originContext == null ? null : originContext.city(),
                        destinationContext == null ? null : destinationContext.city(),
                        cityResolver.resolve(origin),
                        cityResolver.resolve(destination)
                );
            }

            log.info("route query, origin={}, destination={}, city={}", origin, destination, city);
            return amapService.buildRecommendedRoutePlans(origin, destination, city);
        } catch (Exception e) {
            log.error("RouteTool execution failed", e);
            return "路线查询出错: " + e.getMessage();
        }
    }

    private JSONObject stringProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private String normalizePlace(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }

        String cleaned = trimmed
                .replaceAll("^[“”\"'`]+", "")
                .replaceAll("[“”\"'`]+$", "")
                .replaceAll("^(起点[:：]?|终点[:：]?|出发地[:：]?|目的地[:：]?)", "")
                .replaceAll("^[从由在向往去到赴前往抵达]+", "")
                .replaceAll("(怎么走|如何走|路线规划|路线|给我一个路线规划|给我路线规划|给我规划一下路线|给我规划路线|可以怎么走|怎么去|怎么)$", "")
                .replaceAll("(出发|启程)$", "")
                .replaceAll("(玩\\d+[天晚日]?|玩几天|待\\d+[天晚日]?|住\\d+[天晚日]?|旅游|旅行|游玩|攻略|计划|行程)$", "")
                .replaceAll("[，。！？；,.]+$", "")
                .trim();

        cleaned = cleaned.replaceAll("^(.*?)(?:的?(地铁站|高铁站|火车站|机场|酒店|景区|景点))$", "$1$2");
        cleaned = cleaned.replaceAll("^(.*?)(?:，|,).*$", "$1").trim();
        cleaned = cleaned.replaceAll("的$", "").trim();
        cleaned = stripTrailingCityWord(cleaned);
        if (isGenericCityOnly(cleaned)) {
            return null;
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private String stripTrailingCityWord(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 2) {
            return value;
        }
        if (value.endsWith("市") && !value.endsWith("站") && !value.endsWith("路") && !value.endsWith("桥")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private boolean isGenericCityOnly(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.length() <= 3 && !value.endsWith("站") && !value.endsWith("路") && !value.endsWith("桥")
                && !value.endsWith("园") && !value.endsWith("店") && !value.endsWith("湖");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }
}
