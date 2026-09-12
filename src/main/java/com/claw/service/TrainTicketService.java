package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import com.claw.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TrainTicketService {
    private static final Logger log = LoggerFactory.getLogger(TrainTicketService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DEFAULT_API_URL = "https://apis.juhe.cn/fapigw/train/query";
    private static final String PENDING_STATION_KEY = "pendingStationClarification";
    private static final String PENDING_WORKFLOW_KEY = "pendingTravelWorkflow";
    private static final String PENDING_ORIGIN_CANDIDATES_KEY = "pendingOriginCandidates";
    private static final String PENDING_DESTINATION_CANDIDATES_KEY = "pendingDestinationCandidates";

    @Value("${travel.train-ticket.api-key:}")
    private String injectedApiKey;

    @Value("${travel.train-ticket.api-url:}")
    private String injectedApiUrl;

    private final AmapService amapService;
    private final CityResolver cityResolver;
    private final ConversationMemoryService conversationMemoryService;

    public TrainTicketService(AmapService amapService,
                              CityResolver cityResolver,
                              ConversationMemoryService conversationMemoryService) {
        this.amapService = amapService;
        this.cityResolver = cityResolver;
        this.conversationMemoryService = conversationMemoryService;
    }

    public String query(String origin, String destination, String travelDate) {
        return query(null, origin, destination, travelDate, null, false);
    }

    public String query(String origin, String destination, String travelDate, String timePreference) {
        return query(null, origin, destination, travelDate, timePreference, false);
    }

    public String query(String sessionId,
                        String origin,
                        String destination,
                        String travelDate,
                        String timePreference,
                        boolean travelWorkflow) {
        StationSelection fromSelection = resolveStationSelection(origin);
        StationSelection toSelection = resolveStationSelection(destination);
        if (fromSelection.needsClarification() || toSelection.needsClarification()) {
            if (sessionId != null && !sessionId.isBlank()) {
                savePendingStationSelection(sessionId, fromSelection, toSelection, travelDate, timePreference, travelWorkflow);
            }
            return buildStationClarificationMessage(fromSelection, toSelection);
        }

        String from = fromSelection.stationName();
        String to = toSelection.stationName();
        String date = normalizeTravelDate(travelDate);
        String period = normalizeTimePreference(timePreference);

        if (from == null || to == null || date == null) {
            return buildMissingArgsMessage(from, to, date);
        }

        if (sessionId != null && !sessionId.isBlank()) {
            clearPendingStationSelection(sessionId, from, to);
        }

        String apiKey = firstNonBlank(trimToNull(injectedApiKey), ConfigUtil.getTrainTicketApiKey());
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("train ticket api key missing");
            return "Train ticket API key is missing. Configure travel.train-ticket.api-key.";
        }

        String apiUrl = firstNonBlank(trimToNull(injectedApiUrl), ConfigUtil.getTrainTicketApiUrl(), DEFAULT_API_URL);
        String requestUrl = buildRequestUrl(apiUrl, apiKey, from, to, date, period);

        try {
            log.info("train ticket api request, origin={}, destination={}, date={}, timePreference={}, url={}",
                    from, to, date, period, requestUrl);
            String body = HttpUtil.doGet(requestUrl);
            log.info("train ticket api response body, origin={}, destination={}, date={}, timePreference={}, body={}",
                    from, to, date, period, abbreviate(body, 800));

            String rendered = renderResponse(from, to, date, period, body);
            log.info("train ticket api rendered result, origin={}, destination={}, date={}, timePreference={}, result={}",
                    from, to, date, period, abbreviate(rendered, 800));
            return rendered;
        } catch (Exception e) {
            log.warn("train ticket api request failed, origin={}, destination={}, date={}", from, to, date, e);
            return "Train ticket query failed: " + e.getMessage();
        }
    }

    public String normalizeTravelDate(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }

        LocalDate today = LocalDate.now(ZONE);
        if ("今天".equals(value)) return today.format(DATE_FMT);
        if ("明天".equals(value)) return today.plusDays(1).format(DATE_FMT);
        if ("后天".equals(value)) return today.plusDays(2).format(DATE_FMT);

        try {
            return LocalDate.parse(value, DATE_FMT).format(DATE_FMT);
        } catch (Exception ignored) {
        }

        try {
            return LocalDate.parse(today.getYear() + "-" + value, DateTimeFormatter.ofPattern("yyyy-M-d")).format(DATE_FMT);
        } catch (Exception ignored) {
        }

        return value;
    }

    private StationSelection resolveStationSelection(String rawValue) {
        String cleaned = normalizeStationName(rawValue);
        if (cleaned == null) {
            return new StationSelection(null, null, List.of());
        }
        if (looksLikeSpecificStation(cleaned)) {
            return new StationSelection(cleaned, null, List.of());
        }

        String city = cityResolver.resolve(cleaned);
        if (city != null && isCityOnlyInput(cleaned, city)) {
            List<String> candidates = amapService.findTrainStationsByCity(city);
            if (candidates.size() == 1) {
                return new StationSelection(candidates.get(0), city, candidates);
            }
            if (candidates.size() > 1) {
                return new StationSelection(null, city, candidates);
            }
        }
        return new StationSelection(cleaned, city, List.of());
    }

    private void savePendingStationSelection(String sessionId,
                                             StationSelection originSelection,
                                             StationSelection destinationSelection,
                                             String travelDate,
                                             String timePreference,
                                             boolean travelWorkflow) {
        JSONObject context = conversationMemoryService.getTravelContext(sessionId);
        if (context == null) {
            context = new JSONObject();
        }
        context.put(PENDING_STATION_KEY, true);
        context.put(PENDING_WORKFLOW_KEY, travelWorkflow);
        if (travelDate != null && !travelDate.isBlank()) {
            context.put("travelDate", travelDate);
            context.put("travel_date", travelDate);
        }
        if (timePreference != null && !timePreference.isBlank()) {
            context.put("timePreference", timePreference);
            context.put("time_preference", timePreference);
        }
        if (originSelection.city() != null) {
            context.put("origin", originSelection.city());
            context.put(PENDING_ORIGIN_CANDIDATES_KEY, new JSONArray(originSelection.candidates()));
        }
        if (destinationSelection.city() != null) {
            context.put("destination", destinationSelection.city());
            context.put("city", destinationSelection.city());
            context.put(PENDING_DESTINATION_CANDIDATES_KEY, new JSONArray(destinationSelection.candidates()));
        }
        conversationMemoryService.saveTravelContext(sessionId, context);
    }

    private void clearPendingStationSelection(String sessionId, String origin, String destination) {
        JSONObject context = conversationMemoryService.getTravelContext(sessionId);
        if (context == null) {
            context = new JSONObject();
        }
        context.remove(PENDING_STATION_KEY);
        context.remove(PENDING_WORKFLOW_KEY);
        context.remove(PENDING_ORIGIN_CANDIDATES_KEY);
        context.remove(PENDING_DESTINATION_CANDIDATES_KEY);
        if (origin != null) {
            context.put("origin", origin);
        }
        if (destination != null) {
            context.put("destination", destination);
        }
        conversationMemoryService.saveTravelContext(sessionId, context);
    }

    private String buildStationClarificationMessage(StationSelection originSelection, StationSelection destinationSelection) {
        StringBuilder sb = new StringBuilder("高铁票查询前，需要先确认具体出发站和到达站。");
        if (originSelection.needsClarification()) {
            sb.append("\n出发城市“").append(originSelection.city()).append("”可选车站：")
                    .append(String.join("、", originSelection.candidates())).append("。");
        }
        if (destinationSelection.needsClarification()) {
            sb.append("\n到达城市“").append(destinationSelection.city()).append("”可选车站：")
                    .append(String.join("、", destinationSelection.candidates())).append("。");
        }
        sb.append("\n请直接回复：从xxx出发，到xxx。");
        return sb.toString();
    }

    private String renderResponse(String origin, String destination, String travelDate, String timePreference, String body) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return "高铁票务接口返回格式异常。";
        }

        String responseCode = firstNonBlank(
                trimToNull(json.getString("error_code")),
                trimToNull(json.getString("code")),
                trimToNull(json.getString("resultcode"))
        );
        String reason = firstNonBlank(
                trimToNull(json.getString("reason")),
                trimToNull(json.getString("message")),
                trimToNull(json.getString("msg"))
        );
        log.info("train ticket parsed response, responseCode={}, reason={}", responseCode, reason);

        if (responseCode != null && !responseCode.isBlank() && !"0".equals(responseCode) && !"200".equals(responseCode)) {
            String message = firstNonBlank(reason, trimToNull(json.getString("error_msg")), trimToNull(json.getString("error_reason")));
            return "高铁票务接口返回错误：" + (message != null ? message : responseCode);
        }

        JSONArray trains = json.getJSONArray("result");
        if (trains == null || trains.isEmpty()) trains = json.getJSONArray("data");
        if (trains == null || trains.isEmpty()) trains = json.getJSONArray("trains");
        if ((trains == null || trains.isEmpty()) && json.getJSONObject("result") != null) {
            JSONObject resultObj = json.getJSONObject("result");
            trains = resultObj == null ? null : resultObj.getJSONArray("list");
            if (trains == null || trains.isEmpty()) {
                trains = resultObj == null ? null : resultObj.getJSONArray("data");
            }
        }

        if (trains == null || trains.isEmpty()) {
            String message = firstNonBlank(
                    reason,
                    trimToNull(json.getString("error_msg")),
                    trimToNull(json.getString("error_reason"))
            );
            if (message != null && !"success".equalsIgnoreCase(message)) {
                return "高铁票务接口返回：" + message;
            }
            return "高铁票务接口未返回车次列表。";
        }

        List<String> rows = new ArrayList<>();
        for (int i = 0; i < trains.size() && rows.size() < 3; i++) {
            JSONObject item = trains.getJSONObject(i);
            if (item == null) continue;

            String departTime = firstNonBlank(
                    item.getString("departure_time"),
                    item.getString("depart_time"),
                    item.getString("departTime"),
                    item.getString("start_time"),
                    "--:--"
            );
            if (!matchesTimePreference(departTime, timePreference)) continue;

            String trainNo = firstNonBlank(
                    item.getString("train_no"),
                    item.getString("trainNo"),
                    item.getString("train_code"),
                    item.getString("code"),
                    item.getString("station_train_code"),
                    "unknown"
            );
            String arriveTime = firstNonBlank(
                    item.getString("arrival_time"),
                    item.getString("arrive_time"),
                    item.getString("arriveTime"),
                    item.getString("end_time"),
                    "--:--"
            );
            String duration = firstNonBlank(
                    item.getString("run_time"),
                    item.getString("duration"),
                    item.getString("spend_time"),
                    "unknown"
            );
            String price = firstNonBlank(
                    item.getString("second_class_price"),
                    item.getString("secondClassPrice"),
                    item.getString("price"),
                    extractPriceSummary(item.getJSONArray("prices")),
                    item.getString("business_price"),
                    "unknown"
            );
            String booking = firstNonBlank(
                    item.getString("enable_booking"),
                    item.getString("bookable"),
                    item.getString("can_book")
            );

            String row = (rows.size() + 1) + ". " + trainNo + " " + departTime + "-" + arriveTime
                    + ", duration " + duration + ", price " + price;
            if (booking != null) {
                row += ", " + ("Y".equalsIgnoreCase(booking) ? "bookable" : "not bookable");
            }
            rows.add(row);
        }

        if (rows.isEmpty()) {
            return "高铁票务信息：" + origin + " -> " + destination + "，日期 " + travelDate
                    + (timePreference == null ? "" : ", period " + timePreference)
                    + "，未筛到符合条件的车次。";
        }

        return "高铁票务信息：" + origin + " -> " + destination + "，日期 " + travelDate
                + (timePreference == null ? "" : ", period " + timePreference)
                + " (top 3)\n" + String.join("\n", rows);
    }

    private String buildMissingArgsMessage(String origin, String destination, String travelDate) {
        List<String> missing = new ArrayList<>();
        if (origin == null) missing.add("origin");
        if (destination == null) missing.add("destination");
        if (travelDate == null) missing.add("travel date");
        return "Train ticket query missing: " + String.join(", ", missing) + ".";
    }

    private String normalizeTimePreference(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return null;
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.contains("上午") || normalized.contains("早上") || normalized.contains("morning")) return "上午";
        if (normalized.contains("下午") || normalized.contains("afternoon")) return "下午";
        if (normalized.contains("晚上") || normalized.contains("夜间") || normalized.contains("night")) return "晚上";
        return null;
    }

    private boolean matchesTimePreference(String departTime, String timePreference) {
        if (timePreference == null || timePreference.isBlank()) return true;
        int hour = parseHour(departTime);
        if (hour < 0) return true;
        return switch (timePreference) {
            case "上午" -> hour >= 6 && hour < 12;
            case "下午" -> hour >= 12 && hour < 18;
            case "晚上" -> hour >= 18 || hour < 6;
            default -> true;
        };
    }

    private int parseHour(String departTime) {
        if (departTime == null) return -1;
        try {
            String[] parts = departTime.split(":");
            return Integer.parseInt(parts[0].replaceAll("\\D", ""));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) return trimmed;
        }
        return null;
    }

    private String extractPriceSummary(JSONArray prices) {
        if (prices == null || prices.isEmpty()) return null;
        List<String> values = new ArrayList<>();
        int limit = Math.min(2, prices.size());
        for (int i = 0; i < limit; i++) {
            JSONObject item = prices.getJSONObject(i);
            if (item == null) continue;
            String seat = firstNonBlank(item.getString("seat_name"), item.getString("name"), item.getString("type"));
            String price = firstNonBlank(item.getString("price"), item.getString("amount"), item.getString("value"));
            if (seat != null && price != null) values.add(seat + " " + price);
        }
        return values.isEmpty() ? null : String.join(" / ", values);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String buildRequestUrl(String apiUrl, String apiKey, String from, String to, String date, String period) {
        StringBuilder sb = new StringBuilder(apiUrl);
        sb.append(apiUrl.contains("?") ? "&" : "?");
        sb.append("key=").append(urlEncode(apiKey));
        sb.append("&search_type=1");
        sb.append("&departure_station=").append(urlEncode(from));
        sb.append("&arrival_station=").append(urlEncode(to));
        sb.append("&date=").append(urlEncode(date));
        sb.append("&enable_booking=2");
        if (period != null) {
            sb.append("&departure_time_range=").append(urlEncode(period));
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeStationName(String value) {
        String cleaned = trimToNull(value);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceAll("(火车站|高铁站)$", "").trim();
        cleaned = cleaned.replaceAll("站$", "").trim();
        cleaned = cleaned.replaceAll("的$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private boolean looksLikeSpecificStation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("虹桥")
                || value.contains("机场")
                || value.endsWith("东")
                || value.endsWith("南")
                || value.endsWith("西")
                || value.endsWith("北");
    }

    private boolean isCityOnlyInput(String raw, String city) {
        if (raw == null || city == null) {
            return false;
        }
        return raw.equals(city) || raw.equals(city + "市");
    }

    private record StationSelection(String stationName, String city, List<String> candidates) {
        private boolean needsClarification() {
            return stationName == null && candidates != null && !candidates.isEmpty();
        }
    }
}
