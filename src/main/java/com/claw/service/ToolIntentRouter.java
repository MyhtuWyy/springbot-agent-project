package com.claw.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolIntentRouter {
    private static final Logger log = LoggerFactory.getLogger(ToolIntentRouter.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final List<String> WEATHER_KEYWORDS = List.of("天气", "气温", "温度", "下雨", "下雪");
    private static final List<String> NEWS_KEYWORDS = List.of("新闻", "资讯", "热点", "头条", "时事", "最新消息");
    private static final List<String> LOGISTICS_KEYWORDS = List.of("快递", "物流", "包裹", "单号", "寄件", "签收", "运单");
    private static final List<String> TRAVEL_KEYWORDS = List.of("旅游", "旅行", "攻略", "行程", "出行", "打卡", "出行计划", "旅游计划", "行程安排");
    private static final List<String> POI_KEYWORDS = List.of("景点", "美食", "餐厅", "小吃", "附近", "推荐", "好吃", "好玩");
    private static final List<String> ROUTE_KEYWORDS = List.of("路线", "怎么走", "导航", "公交", "地铁", "驾车", "步行", "自驾", "乘车", "通勤");
    private static final List<String> COPY_KEYWORDS = List.of("文案", "朋友圈", "配文", "短句", "签名", "祝福");
    private static final List<String> RESUME_KEYWORDS = List.of("简历", "履历", "项目经历", "工作经历");
    private static final List<String> RESUME_LIST_KEYWORDS = List.of("我的简历", "简历列表", "列出简历", "查看简历");
    private static final List<String> JOB_MATCH_KEYWORDS = List.of("岗位匹配", "匹配度", "匹配岗位", "岗位链接", "职位链接", "职位匹配", "jd匹配");
    private static final List<String> LOGISTICS_COMPANIES = List.of("顺丰", "中通", "圆通", "申通", "韵达", "京东", "ems", "极兔", "德邦", "菜鸟", "百世", "天天");

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,12})(?!\\d)");
    private static final Pattern TRACKING_NUMBER_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,30}");
    private static final Pattern PHONE_LAST4_PATTERN = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");
    private static final Pattern EXPLICIT_ROUTE_PATTERN = Pattern.compile("(?:从\\s*)?([^，。；,、]{2,30}?)\\s*(?:出发|启程)?\\s*[，,、\\s]*\\s*(?:到|去|前往|抵达)\\s*([^，。；,、]{2,30})");
    private static final Pattern SIMPLE_ROUTE_PATTERN = Pattern.compile("([^，。；,、\\s]{2,20}?)\\s*(?:到|去|前往)\\s*([^，。；,、\\s]{2,20})");

    public enum RouteMode {
        FORCE_TOOL,
        PREFER_AGENT
    }

    public record ToolRoute(
            String functionName,
            JSONObject arguments,
            boolean renderWithModel,
            RouteMode routeMode,
            List<String> candidateTools
    ) {
    }

    private record RouteCandidate(int score, Supplier<ToolRoute> supplier) {
    }

    private final CityResolver cityResolver;
    private final ConversationMemoryService conversationMemoryService;

    public ToolIntentRouter(CityResolver cityResolver, ConversationMemoryService conversationMemoryService) {
        this.cityResolver = cityResolver;
        this.conversationMemoryService = conversationMemoryService;
    }

    private boolean applyPendingStationConfirmation(String sessionId, String userMessage, JSONObject travelContext) {
        if (sessionId == null || sessionId.isBlank() || travelContext == null || travelContext.isEmpty()) {
            return false;
        }

        JSONArray originCandidates = travelContext.getJSONArray("pendingOriginCandidates");
        JSONArray destinationCandidates = travelContext.getJSONArray("pendingDestinationCandidates");
        String matchedOrigin = matchPendingStationCandidate(userMessage, originCandidates);
        String matchedDestination = matchPendingStationCandidate(userMessage, destinationCandidates);
        boolean updated = false;

        if (matchedOrigin != null) {
            travelContext.put("origin", matchedOrigin);
            travelContext.remove("pendingOriginCandidates");
            updated = true;
        }
        if (matchedDestination != null) {
            travelContext.put("destination", matchedDestination);
            String city = extractCityFromPlace(matchedDestination);
            if (city != null) {
                travelContext.put("city", city);
            }
            travelContext.remove("pendingDestinationCandidates");
            updated = true;
        }

        if (!updated) {
            return false;
        }

        boolean hasOriginPending = hasPendingCandidates(travelContext.getJSONArray("pendingOriginCandidates"));
        boolean hasDestinationPending = hasPendingCandidates(travelContext.getJSONArray("pendingDestinationCandidates"));
        if (!hasOriginPending && !hasDestinationPending) {
            travelContext.remove("pendingStationClarification");
            travelContext.remove("pendingTravelWorkflow");
        } else {
            travelContext.put("pendingStationClarification", true);
        }
        conversationMemoryService.saveTravelContext(sessionId, travelContext);
        return true;
    }

    private boolean hasPendingCandidates(JSONArray candidates) {
        return candidates != null && !candidates.isEmpty();
    }

    private String matchPendingStationCandidate(String userMessage, JSONArray candidates) {
        if (userMessage == null || userMessage.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String raw = userMessage.trim();
        String normalizedReply = normalizeTrainStationPlace(raw);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.getString(i);
            String normalizedCandidate = normalizeTrainStationPlace(candidate);
            if (candidate != null && !candidate.isBlank() && raw.contains(candidate)) {
                return candidate;
            }
            if (normalizedReply != null && normalizedCandidate != null
                    && (normalizedReply.equals(normalizedCandidate)
                    || normalizedReply.contains(normalizedCandidate)
                    || normalizedCandidate.contains(normalizedReply))) {
                return candidate;
            }
        }
        return null;
    }

    public ToolRoute match(String userMessage) {
        return match(null, userMessage);
    }

    public ToolRoute match(String sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        JSONObject travelContext = loadTravelContext(sessionId);
        if (travelContext.getBooleanValue("pendingStationClarification")) {
            boolean pendingTravelWorkflow = travelContext.getBooleanValue("pendingTravelWorkflow");
            String[] pendingPair = extractRoutePair(userMessage);
            if (pendingPair != null) {
                return pendingTravelWorkflow
                        ? buildTravelAgentRoute(sessionId, userMessage)
                        : buildTrainTicketRoute(sessionId, userMessage);
            }
            boolean updated = applyPendingStationConfirmation(sessionId, userMessage, travelContext);
            if (updated) {
                JSONObject refreshedContext = loadTravelContext(sessionId);
                if (!refreshedContext.getBooleanValue("pendingStationClarification")
                        && pendingTravelWorkflow) {
                    return buildTravelAgentRoute(sessionId, userMessage);
                }
            }
            return buildTrainTicketRoute(sessionId, userMessage);
        }
        if (travelContext.getBooleanValue("pendingTravelPlan")) {
            return buildTravelAgentRoute(sessionId, userMessage);
        }

        String normalized = normalize(userMessage);
        Long userId = extractUserId(sessionId);

        boolean route = containsAny(normalized, ROUTE_KEYWORDS) || looksLikeRouteQuery(normalized);
        boolean weather = containsAny(normalized, WEATHER_KEYWORDS);
        boolean news = containsAny(normalized, NEWS_KEYWORDS);
        boolean logistics = containsAny(normalized, LOGISTICS_KEYWORDS) || extractTrackingNumber(userMessage) != null;
        boolean travel = containsAny(normalized, TRAVEL_KEYWORDS);
        boolean poi = containsAny(normalized, POI_KEYWORDS);
        boolean travelPlanning = looksLikeTravelPlanning(normalized);
        boolean trainTicket = looksLikeTrainTicket(normalized);
        boolean copy = containsAny(normalized, COPY_KEYWORDS);
        boolean resume = containsAny(normalized, RESUME_KEYWORDS) || containsAny(normalized, RESUME_LIST_KEYWORDS);
        boolean jobMatch = looksLikeJobMatchRequest(userMessage, normalized);
        boolean jobPlatformUrl = isJobPlatformUrl(extractUrl(userMessage));

        List<RouteCandidate> candidates = new ArrayList<>();
        if (resume && userId != null) {
            addCandidate(candidates, 90, () -> buildResumeRoute(userId, userMessage, normalized));
        }
        if (jobMatch && userId != null) {
            addCandidate(candidates, jobPlatformUrl ? 115 : 95, () -> buildJobMatchRoute(userId, userMessage));
        }
        if (route) {
            addCandidate(candidates, 80, () -> buildRouteRoute(sessionId, userMessage));
        }
        if (weather) {
            addCandidate(candidates, 70, () -> buildWeatherRoute(sessionId, userMessage));
        }
        if (news) {
            addCandidate(candidates, 70, () -> buildNewsRoute(userMessage));
        }

        int logisticsScore = scoreLogisticsIntent(normalized, logistics, jobPlatformUrl);
        if (logisticsScore > 0) {
            addCandidate(candidates, logisticsScore, () -> buildLogisticsRoute(userMessage));
        }
        if (copy && (travel || poi)) {
            addCandidate(candidates, 68, () -> buildTravelCopyAgentRoute(userMessage));
        }
        if (trainTicket && !travelPlanning && !travel && !poi) {
            addCandidate(candidates, 78, () -> buildTrainTicketRoute(sessionId, userMessage));
        }
        if (poi && !travelPlanning && !trainTicket) {
            addCandidate(candidates, 62, () -> buildPoiRoute(sessionId, userMessage));
        }
        if (travelPlanning || travel || poi || trainTicket) {
            addCandidate(candidates, 55, () -> buildTravelAgentRoute(sessionId, userMessage));
        }
        if (copy) {
            addCandidate(candidates, 58, () -> buildCopyRoute(userMessage, normalized));
        }
        return pickBestCandidate(candidates);
    }

    public boolean isForcedToolQuery(String userMessage) {
        ToolRoute route = match(null, userMessage);
        return route != null && route.routeMode() == RouteMode.FORCE_TOOL;
    }

    private void addCandidate(List<RouteCandidate> candidates, int score, Supplier<ToolRoute> supplier) {
        if (candidates == null || supplier == null || score <= 0) {
            return;
        }
        candidates.add(new RouteCandidate(score, supplier));
    }

    private ToolRoute pickBestCandidate(List<RouteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        RouteCandidate best = null;
        for (RouteCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best == null ? null : best.supplier().get();
    }

    private int scoreLogisticsIntent(String normalized, boolean logisticsDetected, boolean jobPlatformUrl) {
        if (!logisticsDetected) {
            return 0;
        }
        boolean explicitKeyword = containsAny(normalized, LOGISTICS_KEYWORDS);
        if (jobPlatformUrl && !explicitKeyword) {
            return 0;
        }
        return explicitKeyword ? 85 : 50;
    }

    private ToolRoute buildResumeRoute(Long userId, String userMessage, String normalized) {
        JSONObject args = new JSONObject();
        args.put("userId", userId);
        if (containsAny(normalized, RESUME_LIST_KEYWORDS)) {
            return forceRoute("resume_list", args, true);
        }
        args.put("query", userMessage.trim());
        return forceRoute("resume_rag", args, true);
    }

    private ToolRoute buildWeatherRoute(String sessionId, String userMessage) {
        JSONObject args = new JSONObject();
        JSONObject context = loadTravelContext(sessionId);
        String city = firstNonBlank(
                extractCityFromMessage(userMessage),
                extractCityFromPlace(context.getString("destination")),
                normalizeCityName(context.getString("city"))
        );
        String travelDate = firstNonBlank(
                extractTravelDate(userMessage),
                context.getString("travelDate"),
                context.getString("travel_date")
        );
        args.put("city", city != null ? city : userMessage.trim());
        if (travelDate != null) {
            args.put("date", travelDate);
        }
        return forceRoute("get_weather", args, false);
    }

    private ToolRoute buildNewsRoute(String userMessage) {
        JSONObject args = new JSONObject();
        String keyword = extractFocusedNewsKeyword(userMessage);
        if (!keyword.isBlank()) args.put("keyword", keyword);
        return forceRoute("search_news", args, true);
    }

    private ToolRoute buildLogisticsRoute(String userMessage) {
        JSONObject args = new JSONObject();
        if (looksLikeListCompanies(userMessage)) {
            args.put("action", "list_companies");
            return forceRoute("query_logistics", args, false);
        }
        args.put("action", "query");
        String number = extractTrackingNumber(userMessage);
        String company = extractExplicitLogisticsCompany(userMessage);
        String phoneLast4 = extractPhoneLast4(userMessage);
        if (number != null) args.put("number", number);
        if (company != null) args.put("company", company);
        if (phoneLast4 != null) args.put("sender_phone_last4", phoneLast4);
        return forceRoute("query_logistics", args, true);
    }

    private ToolRoute buildJobMatchRoute(Long userId, String userMessage) {
        JSONObject args = new JSONObject();
        args.put("userId", userId);
        String jobUrl = extractUrl(userMessage);
        if (jobUrl != null) {
            args.put("jobUrl", jobUrl);
        }
        String platform = extractJobPlatform(userMessage, jobUrl);
        if (platform != null) {
            args.put("platform", platform);
        }
        return forceRoute("job_match_url", args, true);
    }

    private ToolRoute buildTravelAgentRoute(String sessionId, String userMessage) {
        JSONObject context = loadTravelContext(sessionId);
        JSONObject args = new JSONObject();
        copyNonLocationTravelContext(context, args);

        String[] pair = extractRoutePair(userMessage);
        boolean explicitRoute = pair != null;
        String origin = explicitRoute
                ? pair[0]
                : firstNonBlank(extractStandaloneOrigin(userMessage), normalizeTrainStationPlace(context.getString("origin")));
        String destination = explicitRoute
                ? pair[1]
                : firstNonBlank(extractStandaloneDestination(userMessage), normalizeTrainStationPlace(context.getString("destination")));
        String city = explicitRoute
                ? extractCityFromPlace(destination)
                : firstNonBlank(extractCityFromPlace(destination), extractCityFromMessage(userMessage), normalizeCityName(context.getString("city")));

        String travelDate = firstNonBlank(extractTravelDate(userMessage), args.getString("travelDate"), args.getString("travel_date"));
        Integer tripDays = firstNonBlankInteger(extractTripDays(userMessage), extractInteger(args, "tripDays"), extractInteger(args, "trip_days"));
        String transport = extractTransportPreference(userMessage);
        String timePreference = extractTimePreference(userMessage);

        if (origin != null) args.put("origin", origin);
        if (destination != null) args.put("destination", destination);
        if (city != null) args.put("city", city);
        if (travelDate != null) args.put("travelDate", travelDate);
        if (tripDays != null) {
            args.put("tripDays", tripDays);
            args.put("trip_days", tripDays);
        }
        if (transport != null) args.put("transport", transport);
        if (timePreference != null) args.put("timePreference", timePreference);
        args.put("needTickets", true);
        if (sessionId != null && !sessionId.isBlank()) args.put("session_id", sessionId);

        JSONObject mergedContext = new JSONObject();
        mergedContext.putAll(context);
        if (origin != null) mergedContext.put("origin", origin);
        if (destination != null) mergedContext.put("destination", destination);
        if (city != null) mergedContext.put("city", city);
        if (travelDate != null) {
            mergedContext.put("travelDate", travelDate);
            mergedContext.put("travel_date", travelDate);
        }
        if (tripDays != null) {
            mergedContext.put("tripDays", tripDays);
            mergedContext.put("trip_days", tripDays);
        }
        if (transport != null) {
            mergedContext.put("transport", transport);
        }
        if (timePreference != null) {
            mergedContext.put("timePreference", timePreference);
            mergedContext.put("time_preference", timePreference);
        }
        boolean pendingTravelPlan = origin == null || destination == null || travelDate == null || tripDays == null;
        mergedContext.put("pendingTravelPlan", pendingTravelPlan);
        mergeTravelContext(sessionId, mergedContext);
        log.info("travel route resolved, sessionId={}, message={}, explicitRoute={}, finalArgs={}",
                sessionId, userMessage, explicitRoute, args.toJSONString());

        String preferredTool = looksLikeTrainTicket(normalize(userMessage)) || isTicketRequested(userMessage)
                ? "query_train_tickets"
                : "plan_travel";
        return preferAgentRoute(preferredTool, args, false, List.of("plan_travel", "search_poi", "get_weather", "query_route", "query_train_tickets"));
    }

    private String extractStandaloneOrigin(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        Matcher matcher = Pattern.compile("从\\s*([^，。；,\\s]{2,20}?(?:市|县|区|镇|村|站|机场)?)\\s*(?:出发|过去|前往|到)").matcher(text);
        if (matcher.find()) {
            return normalizeTrainStationPlace(matcher.group(1));
        }
        return null;
    }

    private String extractStandaloneDestination(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        Matcher matcher = Pattern.compile("(?:到|去|前往|抵达)\\s*([^，。；,\\s]{2,24}?(?:市|县|区|镇|村|站|机场|景区|景点)?)(?:玩|旅游|旅行|游玩|逛逛|逛|攻略)?(?:[，。；,\\s]|$)").matcher(text);
        if (matcher.find()) {
            return normalizeTrainStationPlace(matcher.group(1));
        }
        return null;
    }

    private ToolRoute buildTrainTicketRoute(String sessionId, String userMessage) {
        JSONObject context = loadTravelContext(sessionId);
        JSONObject args = new JSONObject();
        copyNonLocationTravelContext(context, args);

        String[] pair = extractRoutePair(userMessage);
        String origin = pair != null ? pair[0] : normalizeTrainStationPlace(context.getString("origin"));
        String destination = pair != null ? pair[1] : normalizeTrainStationPlace(context.getString("destination"));
        if (origin == null) origin = normalizeTrainStationPlace(cityResolver.resolve(userMessage));
        if (destination == null) destination = normalizeTrainStationPlace(cityResolver.inferCityFromSpot(userMessage));

        String travelDate = firstNonBlank(extractTravelDate(userMessage), args.getString("travelDate"), args.getString("travel_date"));
        String timePreference = firstNonBlank(extractTimePreference(userMessage), args.getString("timePreference"), args.getString("time_preference"));

        args.remove("travelDate");
        args.remove("timePreference");
        if (origin != null) args.put("origin", origin);
        if (destination != null) args.put("destination", destination);
        if (travelDate != null) args.put("travel_date", travelDate);
        if (timePreference != null) args.put("time_preference", timePreference);
        if (sessionId != null && !sessionId.isBlank()) args.put("session_id", sessionId);

        JSONObject mergedContext = new JSONObject();
        mergedContext.putAll(context);
        if (origin != null) mergedContext.put("origin", origin);
        if (destination != null) mergedContext.put("destination", destination);
        if (travelDate != null) {
            mergedContext.put("travelDate", travelDate);
            mergedContext.put("travel_date", travelDate);
        }
        if (timePreference != null) {
            mergedContext.put("timePreference", timePreference);
            mergedContext.put("time_preference", timePreference);
        }
        mergeTravelContext(sessionId, mergedContext);

        log.info("train ticket route resolved, sessionId={}, message={}, contextOrigin={}, contextDestination={}, messagePair={}, finalArgs={}",
                sessionId,
                userMessage,
                context.getString("origin"),
                context.getString("destination"),
                pair == null ? null : pair[0] + "->" + pair[1],
                args.toJSONString());
        return forceRoute("query_train_tickets", args, true);
    }

    private ToolRoute buildPoiRoute(String sessionId, String userMessage) {
        JSONObject args = new JSONObject();
        JSONObject context = loadTravelContext(sessionId);
        args.put("type", detectPoiType(userMessage));
        String location = extractPoiLocation(userMessage, context);
        if (location != null) args.put("location", location);
        String city = firstNonBlank(extractCityFromMessage(userMessage), normalizeCityName(context.getString("city")));
        if (city != null) args.put("city", city);
        return forceRoute("search_poi", args, false);
    }

    private ToolRoute buildCopyRoute(String userMessage, String normalized) {
        return forceRoute("generate_copywriting", buildCopyArgs(userMessage, normalized), false);
    }

    private ToolRoute buildRouteRoute(String sessionId, String userMessage) {
        return forceRoute("query_route", buildRouteArgs(sessionId, userMessage), false);
    }

    private ToolRoute buildTravelCopyAgentRoute(String userMessage) {
        return preferAgentRoute("generate_copywriting", buildCopyArgs(userMessage, normalize(userMessage)), false, List.of("generate_copywriting", "plan_travel", "search_poi", "get_weather"));
    }

    private boolean looksLikeTravelPlanning(String normalized) {
        return containsAny(normalized, TRAVEL_KEYWORDS) || normalized.contains("想去");
    }

    private boolean looksLikeTrainTicket(String normalized) {
        return normalized.contains("高铁") || normalized.contains("火车票") || normalized.contains("车票")
                || normalized.contains("票价") || normalized.contains("订票") || normalized.contains("查票");
    }

    private boolean isTicketRequested(String userMessage) {
        return looksLikeTrainTicket(normalize(userMessage));
    }

    private boolean looksLikeRouteQuery(String normalized) {
        return normalized.contains("从") && normalized.contains("到")
                && (normalized.contains("怎么走") || normalized.contains("路线") || normalized.contains("导航")
                || normalized.contains("公交") || normalized.contains("地铁") || normalized.contains("驾车")
                || normalized.contains("步行") || normalized.contains("自驾"));
    }

    private String extractTransportPreference(String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.contains("高铁") || normalized.contains("火车")) return "train";
        if (normalized.contains("驾车") || normalized.contains("开车")) return "driving";
        if (normalized.contains("步行")) return "walking";
        if (normalized.contains("公交") || normalized.contains("地铁") || normalized.contains("公共交通")) return "transit";
        return null;
    }

    private String extractTimePreference(String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.contains("上午") || normalized.contains("早上")) return "上午";
        if (normalized.contains("下午")) return "下午";
        if (normalized.contains("晚上") || normalized.contains("夜间")) return "晚上";
        return null;
    }

    private JSONObject loadTravelContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return new JSONObject();
        JSONObject context = conversationMemoryService.getTravelContext(sessionId);
        return context == null ? new JSONObject() : context;
    }

    private void mergeTravelContext(String sessionId, JSONObject context) {
        if (sessionId == null || sessionId.isBlank() || context == null) return;
        conversationMemoryService.saveTravelContext(sessionId, context);
    }

    private void copyNonLocationTravelContext(JSONObject context, JSONObject args) {
        if (context == null || args == null) {
            return;
        }
        String travelDate = firstNonBlank(context.getString("travelDate"), context.getString("travel_date"));
        String timePreference = firstNonBlank(context.getString("timePreference"), context.getString("time_preference"));
        String transport = firstNonBlank(context.getString("transport"), context.getString("transport_mode"));
        Integer tripDays = firstNonBlankInteger(extractInteger(context, "tripDays"), extractInteger(context, "trip_days"));
        if (travelDate != null) {
            args.put("travelDate", travelDate);
            args.put("travel_date", travelDate);
        }
        if (tripDays != null) {
            args.put("tripDays", tripDays);
            args.put("trip_days", tripDays);
        }
        if (timePreference != null) {
            args.put("timePreference", timePreference);
            args.put("time_preference", timePreference);
        }
        if (transport != null) {
            args.put("transport", transport);
        }
    }

    private String extractRouteOrigin(String userMessage) {
        String[] pair = extractRoutePair(userMessage);
        return pair == null ? null : pair[0];
    }

    private String extractRouteDestination(String userMessage) {
        String[] pair = extractRoutePair(userMessage);
        return pair == null ? null : pair[1];
    }

    private String[] extractRoutePair(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.isBlank()) {
            return null;
        }

        Matcher explicitMatcher = EXPLICIT_ROUTE_PATTERN.matcher(text);
        if (explicitMatcher.find()) {
            String origin = normalizeTrainStationPlace(explicitMatcher.group(1));
            String destination = normalizeTrainStationPlace(explicitMatcher.group(2));
            if (origin != null && destination != null) {
                return new String[]{origin, destination};
            }
        }

        Matcher simpleMatcher = SIMPLE_ROUTE_PATTERN.matcher(text);
        if (simpleMatcher.find()) {
            String origin = normalizeTrainStationPlace(simpleMatcher.group(1));
            String destination = normalizeTrainStationPlace(simpleMatcher.group(2));
            if (origin != null && destination != null) {
                return new String[]{origin, destination};
            }
        }

        return null;
    }

    private String extractTravelDate(String userMessage) {
        String text = normalize(userMessage);
        LocalDate today = LocalDate.now(ZONE);
        if (text.contains("今天")) return today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (text.contains("明天")) return today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (text.contains("后天")) return today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);

        Matcher matcher = Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2})").matcher(text);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), DateTimeFormatter.ofPattern("yyyy-M-d")).format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer extractTripDays(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.isBlank()) {
            return null;
        }
        Matcher explicit = Pattern.compile("(\\d{1,2})\\s*[天日]").matcher(text);
        if (explicit.find()) {
            int value = Integer.parseInt(explicit.group(1));
            return value >= 1 && value <= 15 ? value : null;
        }
        if (text.contains("一日游")) return 1;
        if (text.contains("两日游") || text.contains("二日游")) return 2;
        if (text.contains("三日游")) return 3;
        if (text.contains("四日游")) return 4;
        if (text.contains("五日游")) return 5;
        return null;
    }

    private JSONObject buildCopyArgs(String userMessage, String normalized) {
        JSONObject args = new JSONObject();
        String city = extractCityFromMessage(userMessage);
        String destination = cityResolver.extractFamousSpot(userMessage);
        String type = detectCopyType(normalized);
        String topic = normalizeCopyTopic(cleanCopyTopic(userMessage), type, city, destination);
        if (topic.isBlank()) topic = destination != null ? destination : city != null ? city : userMessage.trim();

        args.put("type", type);
        args.put("count", detectCopyCount(userMessage));
        args.put("topic", topic);
        if (city != null) args.put("city", city);
        if (destination != null) args.put("destination", destination);

        String scene = inferCopyScene(normalized, city, destination);
        if (scene != null) args.put("scene", scene);
        String style = extractCopyStyle(userMessage);
        if (style != null) args.put("style", style);
        return args;
    }

    private JSONObject buildRouteArgs(String sessionId, String userMessage) {
        JSONObject args = new JSONObject();
        JSONObject travelContext = loadTravelContext(sessionId);
        String[] pair = extractRoutePair(userMessage);

        String origin = pair != null ? pair[0] : firstNonBlank(extractRouteOrigin(userMessage), travelContext.getString("origin"));
        String destination = pair != null ? pair[1] : firstNonBlank(extractRouteDestination(userMessage), travelContext.getString("destination"));
        String city = pair != null
                ? extractCityFromPlace(destination)
                : firstNonBlank(extractCityFromPlace(destination), extractCityFromMessage(userMessage), normalizeCityName(travelContext.getString("city")));

        origin = sanitizeRoutePlace(origin);
        destination = sanitizeRoutePlace(destination);
        if (origin != null) args.put("origin", origin);
        if (destination != null) args.put("destination", destination);
        if (city != null) args.put("city", city);

        log.info("route args resolved, sessionId={}, message={}, finalArgs={}", sessionId, userMessage, args.toJSONString());
        return args;
    }

    private ToolRoute forceRoute(String functionName, JSONObject args, boolean renderWithModel) {
        return new ToolRoute(functionName, args, renderWithModel, RouteMode.FORCE_TOOL, List.of(functionName));
    }

    private ToolRoute preferAgentRoute(String functionName, JSONObject args, boolean renderWithModel, List<String> candidates) {
        Set<String> names = new LinkedHashSet<>();
        names.add(functionName);
        names.addAll(candidates);
        return new ToolRoute(functionName, args, renderWithModel, RouteMode.PREFER_AGENT, new ArrayList<>(names));
    }

    private String extractNewsKeyword(String userMessage) {
        return normalize(userMessage)
                .replace("帮我", " ")
                .replace("给我", " ")
                .replace("我想看", " ")
                .replace("想看", " ")
                .replace("看一下", " ")
                .replace("看一看", " ")
                .replace("查一下", " ")
                .replace("查一查", " ")
                .replace("查一个", " ")
                .replace("关于", " ")
                .replace("有关", " ")
                .replace("一些", " ")
                .replace("今天", " ")
                .replace("今日", " ")
                .replace("当天", " ")
                .replace("最新", " ")
                .replace("最近", " ")
                .replace("新闻", " ")
                .replace("资讯", " ")
                .replace("消息", " ")
                .replace("头条", " ")
                .replaceAll("[，。！？；,.!?;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractFocusedNewsKeyword(String userMessage) {
        String keyword = normalize(userMessage)
                .replace("帮我", " ")
                .replace("给我", " ")
                .replace("发送", " ")
                .replace("推送", " ")
                .replace("播放", " ")
                .replace("查看", " ")
                .replace("看看", " ")
                .replace("想看", " ")
                .replace("想了解", " ")
                .replace("想知道", " ")
                .replace("今天的", " ")
                .replace("今天", " ")
                .replace("今日", " ")
                .replace("最新", " ")
                .replace("最近", " ")
                .replace("新闻", " ")
                .replace("资讯", " ")
                .replace("消息", " ")
                .replace("热点", " ")
                .replace("头条", " ")
                .replace("关于", " ")
                .replace("有关", " ")
                .replaceAll("[，。！？；,.!?;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return keyword.length() < 2 ? "" : keyword;
    }

    private boolean looksLikeListCompanies(String userMessage) {
        String n = normalize(userMessage);
        return n.contains("快递公司") || n.contains("支持哪些快递") || n.contains("有哪些快递");
    }

    private String extractExplicitLogisticsCompany(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        String normalized = normalize(userMessage);
        if (!normalized.contains("快递") && !normalized.contains("物流") && !normalized.contains("包裹")) {
            return null;
        }
        return extractLogisticsCompany(userMessage);
    }

    private String extractLogisticsCompany(String userMessage) {
        String normalized = normalize(userMessage);
        for (String company : LOGISTICS_COMPANIES) {
            if (normalized.contains(company.toLowerCase(Locale.ROOT))) return company;
        }
        return null;
    }

    private String extractTrackingNumber(String userMessage) {
        Matcher matcher = TRACKING_NUMBER_PATTERN.matcher(userMessage == null ? "" : userMessage);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() >= 8 && !candidate.matches("\\d{4}")) {
                if (best == null || candidate.length() > best.length()) best = candidate;
            }
        }
        return best;
    }

    private String extractPhoneLast4(String userMessage) {
        Matcher matcher = PHONE_LAST4_PATTERN.matcher(userMessage == null ? "" : userMessage);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String cleanCopyTopic(String userMessage) {
        return (userMessage == null ? "" : userMessage)
                .replaceAll("(?:请|麻烦)?(?:帮我|给我)?(?:生成|写出?|创作|制作)(?:一下|一份)?", " ")
                .replaceAll("(?:我想要|我想|想要|需要)(?:生成|写|创作|制作)?", " ")
                .replace("文案", " ")
                .replace("朋友圈", " ")
                .replace("配文", " ")
                .replace("短句", " ")
                .replace("签名", " ")
                .replace("帮我", " ")
                .replace("给我", " ")
                .replaceAll("\\d+[条个段篇]", " ")
                .replaceAll("[一二两三四五六七八九十]+[条个段篇]", " ")
                .replaceAll("[，。！？；,.!?;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeCopyTopic(String topic, String type, String city, String destination) {
        if (topic == null || topic.isBlank() || !"moments".equals(type)) {
            return topic == null ? "" : topic.trim();
        }

        String place = firstNonBlank(destination, city);
        if (place == null || !topic.contains(place)) {
            return topic.trim();
        }

        String remainder = topic.replace(place, " ")
                .replaceAll("^(?:我)?(?:正?在|来到|到了?|去)", " ")
                .replaceAll("(?:游玩|旅游|旅行|打卡|逛逛|闲逛|玩|逛|了)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (remainder.isBlank()) {
            return place + (destination != null ? "打卡" : "游玩");
        }
        return topic.trim();
    }

    private String detectCopyType(String normalized) {
        if (normalized.contains("朋友圈") || normalized.contains("配文")) return "moments";
        if (normalized.contains("治愈") || normalized.contains("短句")) return "healing";
        if (normalized.contains("道歉")) return "apology";
        if (normalized.contains("纪念日") || normalized.contains("周年")) return "anniversary";
        return "signature";
    }

    private int detectCopyCount(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)").matcher(text);
        if (matcher.find()) {
            int parsed = Integer.parseInt(matcher.group(1));
            if (parsed >= 1 && parsed <= 10) return parsed;
        }
        Matcher chineseMatcher = Pattern.compile("([一二两三四五六七八九十])\\s*[条个段篇]").matcher(text);
        if (chineseMatcher.find()) {
            return switch (chineseMatcher.group(1)) {
                case "一" -> 1;
                case "二", "两" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> 3;
            };
        }
        return 3;
    }

    private String inferCopyScene(String normalized, String city, String destination) {
        if (normalized.contains("美食") || normalized.contains("好吃") || normalized.contains("餐厅") || normalized.contains("火锅")) return "food";
        if (normalized.contains("拍照") || normalized.contains("照片") || normalized.contains("出片")) return "photo";
        if (destination != null || normalized.contains("旅游") || normalized.contains("旅行")
                || normalized.contains("游玩") || normalized.contains("打卡") || normalized.contains("逛")
                || (city != null && normalized.contains("玩"))) return "travel";
        return "generic";
    }

    private String extractCopyStyle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;
        List<String> styles = List.of("文艺", "高级感", "轻松", "自然", "幽默", "俏皮", "温柔", "治愈", "清新");
        StringBuilder style = new StringBuilder();
        for (String keyword : styles) {
            if (userMessage.contains(keyword)) {
                if (!style.isEmpty()) style.append("+");
                style.append(keyword);
            }
        }
        return style.isEmpty() ? null : style.toString();
    }

    private String extractPoiLocation(String userMessage, JSONObject context) {
        String famousSpot = cityResolver.extractFamousSpot(userMessage);
        if (famousSpot != null) {
            return famousSpot;
        }

        String explicitNearby = extractExplicitNearbyLocation(userMessage);
        if (explicitNearby != null) {
            return explicitNearby;
        }

        if (mentionsNearbyScope(userMessage)) {
            return normalizeTrainStationPlace(context == null ? null : context.getString("destination"));
        }

        return null;
    }

    private String extractExplicitNearbyLocation(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern.compile("([^，。；,\\s]{2,30}?)(?:附近|周边|旁边)").matcher(userMessage.trim());
        while (matcher.find()) {
            String candidate = normalizeTrainStationPlace(matcher.group(1));
            if (isValidPoiLocationCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean mentionsNearbyScope(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = normalize(userMessage);
        return normalized.contains("闄勮繎") || normalized.contains("鍛ㄨ竟") || normalized.contains("鏃佽竟");
    }

    private boolean isValidPoiLocationCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String cleaned = candidate.trim();
        if (cleaned.length() < 2 || cleaned.length() > 30) {
            return false;
        }

        String normalizedCity = normalizeCityName(cleaned);
        if (normalizedCity != null && (cleaned.equals(normalizedCity) || cleaned.equals(normalizedCity + "市"))) {
            return false;
        }

        String normalized = normalize(cleaned);
        return !(normalized.contains("鏌?") || normalized.contains("鏌ヨ") || normalized.contains("鎺ㄨ崘")
                || normalized.contains("鏅偣") || normalized.contains("缇庨")
                || normalized.contains("濂藉悆") || normalized.contains("濂界帺"));
    }

    private String sanitizeRoutePlace(String value) {
        if (value == null) return null;
        String cleaned = value.trim()
                .replaceAll("^(今天|明天|后天|本周末|周末|下周末|上午|下午|晚上|早上)+", "")
                .replaceAll("^[从由在向往去到赴前往抵达]+", "")
                .replaceAll("(怎么走|如何走|路线规划|路线|导航|乘车|坐车|打车|步行|自驾|公交|地铁|出行计划|行程规划|旅游攻略)$", "")
                .replaceAll("(出发|启程)$", "")
                .replaceAll("(玩\\d+[天晚日]?|玩几天|待\\d+[天晚日]?|住\\d+[天晚日]?|旅游|旅行|游玩|出差|攻略|计划|行程|玩|逛|游)$", "")
                .replaceAll("[,，。；、\\s]+$", "")
                .trim();
        cleaned = cleaned.replaceAll("的$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeTrainStationPlace(String value) {
        String cleaned = sanitizeRoutePlace(value);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceAll("(高铁票|火车票|车票|高铁|火车)$", "").trim();
        cleaned = cleaned.replaceAll("的$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String extractCityFromMessage(String message) {
        return firstNonBlank(extractRouteDestination(message) == null ? null : extractCityFromPlace(extractRouteDestination(message)),
                extractCityFromPlace(message));
    }

    private String extractCityFromPlace(String message) {
        String cleaned = normalizeTrainStationPlace(message);
        if (cleaned == null) {
            return null;
        }
        String inferred = cityResolver.inferCityFromSpot(cleaned);
        if (inferred != null) {
            return inferred;
        }
        return cityResolver.resolve(cleaned);
    }

    private String normalizeCityName(String value) {
        String cleaned = sanitizeRoutePlace(value);
        if (cleaned == null) {
            return null;
        }
        String resolved = cityResolver.resolve(cleaned);
        return resolved != null ? resolved : cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private Integer firstNonBlankInteger(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private Integer extractInteger(JSONObject source, String key) {
        if (source == null || key == null || key.isBlank()) {
            return null;
        }
        Integer value = source.getInteger(key);
        return value != null && value > 0 ? value : null;
    }

    private String detectPoiType(String message) {
        String n = normalize(message);
        boolean food = n.contains("美食") || n.contains("好吃") || n.contains("餐厅") || n.contains("小吃");
        boolean attractions = n.contains("景点") || n.contains("风景") || n.contains("好玩");
        if (food && !attractions) return "food";
        if (attractions && !food) return "attractions";
        return "both";
    }

    private Long extractUserId(String sessionId) {
        UserSessionContext context = UserSessionContext.fromSessionId(sessionId);
        if (context != null && context.userId() != null) {
            return context.userId();
        }
        if (sessionId == null || sessionId.isBlank()) return null;
        Matcher matcher = USER_ID_PATTERN.matcher(sessionId.trim());
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private String extractUrl(String userMessage) {
        Matcher matcher = URL_PATTERN.matcher(userMessage == null ? "" : userMessage);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean looksLikeJobMatchRequest(String userMessage, String normalized) {
        String jobUrl = extractUrl(userMessage);
        boolean explicitIntent = containsAny(normalized, JOB_MATCH_KEYWORDS)
                || (normalized.contains("岗位") && normalized.contains("匹配"))
                || (normalized.contains("职位") && normalized.contains("匹配"));
        boolean jobPlatformUrl = isJobPlatformUrl(jobUrl);
        return (explicitIntent && jobUrl != null) || (jobPlatformUrl && (explicitIntent || normalized.contains("简历")));
    }

    private boolean isJobPlatformUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("liepin.com")
                || lower.contains("zhipin.com")
                || lower.contains("bosszp.com")
                || lower.contains("lagou.com")
                || lower.contains("zhaopin.com")
                || lower.contains("51job.com");
    }

    private String extractJobPlatform(String userMessage, String jobUrl) {
        String explicit = extractPlatform(userMessage);
        if (explicit != null) {
            return explicit;
        }
        if (jobUrl == null || jobUrl.isBlank()) {
            return null;
        }
        String lower = jobUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("liepin")) return "liepin";
        if (lower.contains("zhipin") || lower.contains("boss")) return "boss";
        if (lower.contains("lagou")) return "lagou";
        if (lower.contains("zhaopin")) return "zhilian";
        if (lower.contains("51job")) return "51job";
        return null;
    }

    private String extractPlatform(String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.contains("boss")) return "boss";
        if (normalized.contains("猎聘")) return "liepin";
        if (normalized.contains("拉勾")) return "lagou";
        if (normalized.contains("智联")) return "zhilian";
        if (normalized.contains("51job") || normalized.contains("前程无忧")) return "51job";
        return null;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
