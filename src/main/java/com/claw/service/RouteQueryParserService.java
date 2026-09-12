package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RouteQueryParserService {
    private static final Pattern FROM_TO_PATTERN = Pattern.compile("(?:从)?([^,，。；;“”\"'\\s]{2,30}?)(?:到|去|前往)([^,，。；;“”\"'\\s]{2,40})");
    private static final Pattern ORIGIN_HINT_PATTERN = Pattern.compile("(?:我现在在|我在|从|出发地是|起点是|起点在)([^,，。；;\\s]{2,30})");
    private static final Pattern DEST_HINT_PATTERN = Pattern.compile("(?:先到|去|到|前往|目的地是|终点是|终点在)([^,，。；;\\s]{2,30})");
    private static final Pattern ROUTE_SUFFIX_PATTERN = Pattern.compile("(怎么走|如何走|怎么去|怎么|路线规划|路线|导航|公交|地铁|打车|步行|骑行|乘车|坐车)+$");

    private final CityResolver cityResolver;

    public RouteQueryParserService(CityResolver cityResolver) {
        this.cityResolver = cityResolver;
    }

    public JSONObject parse(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        String text = userMessage.trim();
        String origin = null;
        String destination = null;

        Matcher pair = FROM_TO_PATTERN.matcher(text);
        if (pair.find()) {
            origin = sanitize(pair.group(1));
            destination = sanitize(pair.group(2));
        }

        if (origin == null) {
            origin = extractByPattern(ORIGIN_HINT_PATTERN, text);
        }
        if (destination == null) {
            destination = firstNonBlank(
                    extractByPattern(DEST_HINT_PATTERN, text),
                    cityResolver.extractFamousSpot(text)
            );
        }

        origin = sanitize(origin);
        destination = sanitize(destination);
        if (origin == null && destination == null) {
            return null;
        }

        JSONObject args = new JSONObject();
        if (origin != null) {
            args.put("origin", origin);
        }
        if (destination != null) {
            args.put("destination", destination);
        }
        String city = firstNonBlank(cityResolver.resolve(origin), cityResolver.resolve(destination), cityResolver.inferCityFromSpot(origin), cityResolver.inferCityFromSpot(destination));
        if (city != null) {
            args.put("city", city);
        }
        return args;
    }

    private String extractByPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return sanitize(matcher.group(1));
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim()
                .replaceAll("^[“”\"'`]+", "")
                .replaceAll("[“”\"'`]+$", "")
                .replaceAll("^(起点[:：]?|终点[:：]?|出发地[:：]?|目的地[:：]?)", "")
                .replaceAll("^[从在向往去到前往]+", "")
                .replaceAll("(例如|比如).*$", "")
                .replaceAll("[,，。；;]+$", "")
                .trim();
        cleaned = ROUTE_SUFFIX_PATTERN.matcher(cleaned).replaceAll("").trim();
        cleaned = cleaned.replaceAll("(去|走|一下|看看|吧|呢|啊|呀|哦|哈)$", "").trim();
        cleaned = cleaned.replaceAll("[“”\"'`]+$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
