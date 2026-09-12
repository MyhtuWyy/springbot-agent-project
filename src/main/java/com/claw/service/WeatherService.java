package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class WeatherService {
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String WTTR_URL = "https://wttr.in/%s?format=j1&lang=zh";
    private static final String OPEN_METEO_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current_weather=true&timezone=auto";

    private final CityResolver cityResolver;
    private final AmapService amapService;

    public WeatherService(CityResolver cityResolver, AmapService amapService) {
        this.cityResolver = cityResolver;
        this.amapService = amapService;
    }

    public String getWeather(String cityOrPlace) {
        return getWeather(cityOrPlace, null);
    }

    public String getWeather(String cityOrPlace, String travelDate) {
        String city = cityResolver.resolve(cityOrPlace);
        if (city == null) {
            AmapService.PlaceContext context = amapService.resolvePlaceContext(cityOrPlace);
            city = context == null ? null : context.city();
        }
        if (city == null) {
            return "无法识别城市或地点，请给我更明确的位置。";
        }

        String targetDate = normalizeTargetDate(travelDate);
        String queryTime = LocalDateTime.now(ZONE).format(DATETIME_FMT);
        String wttr = fetchWttr(city, targetDate);
        if (wttr != null) {
            return "查询时间：" + queryTime + "\n" + wttr;
        }

        String openMeteo = fetchOpenMeteo(city);
        if (openMeteo != null) {
            return "查询时间：" + queryTime + "\n" + openMeteo;
        }

        log.warn("weather query failed, city={}", city);
        return city + " 天气暂时获取失败，请稍后重试。";
    }

    private String fetchWttr(String city, String targetDate) {
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            JSONObject json = JSON.parseObject(HttpUtil.doGet(String.format(WTTR_URL, encodedCity)));
            if (json == null) {
                return null;
            }

            if (targetDate == null || targetDate.isBlank()) {
                JSONArray currentConditions = json.getJSONArray("current_condition");
                if (currentConditions == null || currentConditions.isEmpty()) {
                    return null;
                }
                return formatCurrentWeather(city, currentConditions.getJSONObject(0), null);
            }

            JSONArray weather = json.getJSONArray("weather");
            if (weather == null || weather.isEmpty()) {
                return null;
            }

            for (int i = 0; i < weather.size(); i++) {
                JSONObject daily = weather.getJSONObject(i);
                if (daily == null) {
                    continue;
                }
                if (targetDate.equals(daily.getString("date"))) {
                    return formatDailyForecast(city, daily, targetDate);
                }
            }

            return formatDailyForecast(city, weather.getJSONObject(0), targetDate);
        } catch (Exception e) {
            log.info("wttr weather fetch failed, fallback to alternate provider, city={}", city, e);
            return null;
        }
    }

    private String fetchOpenMeteo(String city) {
        try {
            String location = amapService.geocode(city, city);
            if (location == null || location.isBlank() || !location.contains(",")) {
                return null;
            }
            String[] parts = location.split(",");
            if (parts.length < 2) {
                return null;
            }

            double latitude = Double.parseDouble(parts[1]);
            double longitude = Double.parseDouble(parts[0]);
            JSONObject json = JSON.parseObject(HttpUtil.doGet(String.format(Locale.ROOT, OPEN_METEO_URL, latitude, longitude)));
            if (json == null) {
                return null;
            }
            JSONObject current = json.getJSONObject("current_weather");
            return current == null ? null : formatOpenMeteo(city, current);
        } catch (Exception e) {
            log.warn("Open-Meteo weather fetch failed, city={}", city, e);
            return null;
        }
    }

    private String formatCurrentWeather(String city, JSONObject current, String targetDate) {
        String temp = current.getString("temp_C");
        String feelsLike = current.getString("FeelsLikeC");
        String humidity = current.getString("humidity");
        String windSpeed = current.getString("windspeedKmph");
        String windDir = current.getString("winddir16Point");
        String desc = extractDesc(current.getJSONArray("lang_zh"), current.getJSONArray("weatherDesc"));
        String label = targetDate == null ? "实时天气" : targetDate + " 天气";
        return city + " " + label + "：" + desc
                + "，温度 " + temp + "℃"
                + "，体感 " + feelsLike + "℃"
                + "，湿度 " + humidity + "%"
                + "，风速 " + windSpeed + "km/h"
                + "，风向 " + windDir;
    }

    private String formatDailyForecast(String city, JSONObject daily, String targetDate) {
        JSONArray hourly = daily.getJSONArray("hourly");
        JSONObject sample = null;
        if (hourly != null && !hourly.isEmpty()) {
            sample = chooseForecastSample(hourly, targetDate);
        }
        if (sample == null) {
            sample = daily;
        }

        String temp = firstNonBlank(sample.getString("tempC"), daily.getString("maxtempC"), daily.getString("avgtempC"), "?");
        String feelsLike = firstNonBlank(sample.getString("FeelsLikeC"), sample.getString("HeatIndexC"), temp);
        String humidity = firstNonBlank(sample.getString("humidity"), "?" );
        String windSpeed = firstNonBlank(sample.getString("windspeedKmph"), "?" );
        String windDir = firstNonBlank(sample.getString("winddir16Point"), "?" );
        String desc = extractDesc(sample.getJSONArray("lang_zh"), sample.getJSONArray("weatherDesc"));
        return city + " " + targetDate + " 天气：" + desc
                + "，温度 " + temp + "℃"
                + "，体感 " + feelsLike + "℃"
                + "，湿度 " + humidity + "%"
                + "，风速 " + windSpeed + "km/h"
                + "，风向 " + windDir;
    }

    private JSONObject chooseForecastSample(JSONArray hourly, String targetDate) {
        int hour = 9;
        if (targetDate != null) {
            hour = 9;
        }
        String expected = String.format(Locale.ROOT, "%04d", hour * 100);
        for (int i = 0; i < hourly.size(); i++) {
            JSONObject item = hourly.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String time = item.getString("time");
            if (expected.equals(time)) {
                return item;
            }
        }
        return hourly.getJSONObject(0);
    }

    private String formatOpenMeteo(String city, JSONObject current) {
        double temp = current.getDoubleValue("temperature");
        double windSpeed = current.getDoubleValue("windspeed");
        int code = current.getIntValue("weathercode");
        return city + " 实时天气：" + mapWeatherCode(code)
                + "，温度 " + String.format(Locale.ROOT, "%.1f", temp) + "℃"
                + "，风速 " + String.format(Locale.ROOT, "%.1f", windSpeed) + "km/h";
    }

    private String mapWeatherCode(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "大部晴朗";
            case 3 -> "多云";
            case 45, 48 -> "有雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 63, 65, 66, 67 -> "有雨";
            case 71, 73, 75, 77 -> "有雪";
            case 80, 81, 82 -> "阵雨";
            case 95, 96, 99 -> "雷暴";
            default -> "天气未知";
        };
    }

    private String extractDesc(JSONArray zh, JSONArray desc) {
        String value = extractFirstValue(zh);
        if (value != null) {
            return value;
        }
        value = extractFirstValue(desc);
        return value == null ? "天气未知" : value;
    }

    private String extractFirstValue(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        JSONObject first = arr.getJSONObject(0);
        return first == null ? null : first.getString("value");
    }

    private String normalizeTargetDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        LocalDate today = LocalDate.now(ZONE);
        if ("今天".equals(value)) return today.format(DATE_FMT);
        if ("明天".equals(value)) return today.plusDays(1).format(DATE_FMT);
        if ("后天".equals(value)) return today.plusDays(2).format(DATE_FMT);
        return value;
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
