package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import com.claw.util.HttpUtil;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AmapService {
    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final String PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final String PLACE_DETAIL_URL = "https://restapi.amap.com/v3/place/detail";
    private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";

    private static final int SEARCH_OFFSET = 10;
    private static final int MAX_TEXT_REQUESTS_PER_QUERY = 3;
    private static final int DETAIL_ENRICH_LIMIT = 3;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(15).toMillis();
    private static final long DETAIL_CACHE_TTL_MS = Duration.ofHours(6).toMillis();
    private static final long MIN_REQUEST_INTERVAL_MS = 220L;

    private static final List<String> GENERIC_GEO_LEVELS = List.of("国家", "省", "市", "区县", "乡镇", "村庄");

    private final Map<String, CacheEntry<String>> responseCache = new ConcurrentHashMap<>();
    private final Semaphore requestSemaphore = new Semaphore(1);
    private final AtomicLong lastRequestAt = new AtomicLong(0);

    public PlaceContext resolvePlaceContext(String rawText) {
        String query = sanitizeFreeformPlace(rawText);
        if (query == null) {
            return null;
        }

        String inferredCity = extractCityFromText(query);

        PlaceContext textContext = searchPlaceContextByText(query, inferredCity);
        GeoPoint geoPoint = geocodePoint(inferredCity, query);

        if (textContext != null && geoPoint != null && geoPoint.isGenericLevel()) {
            return textContext;
        }
        if (textContext != null && looksLikeSpecificPlace(textContext.displayName(), query)) {
            return textContext;
        }
        if (geoPoint != null && geoPoint.location() != null) {
            return new PlaceContext(
                    firstNonBlank(geoPoint.city(), inferredCity),
                    query,
                    firstNonBlank(geoPoint.name(), query),
                    geoPoint.location()
            );
        }
        return textContext;
    }

    public List<PoiResult> searchPoiInCity(String city, String type) {
        String normalizedCity = normalize(city);
        if (normalizedCity == null) {
            return List.of();
        }

        QueryPlan plan = QueryPlan.forType(type);
        LinkedHashMap<String, PoiResult> merged = new LinkedHashMap<>();
        int requestCount = 0;
        for (String keyword : plan.cityKeywords()) {
            if (requestCount >= MAX_TEXT_REQUESTS_PER_QUERY) {
                break;
            }
            mergePois(merged, searchText(normalizedCity, keyword, plan.typeCode(), 1));
            requestCount++;
            if (merged.size() < 6 && requestCount < MAX_TEXT_REQUESTS_PER_QUERY) {
                mergePois(merged, searchText(normalizedCity, keyword, plan.typeCode(), 2));
                requestCount++;
            }
            if (merged.size() >= 8) {
                break;
            }
        }
        List<PoiResult> results = reRankOriginalOrder(new ArrayList<>(merged.values()));
        enrichDetails(results);
        return results;
    }

    public List<PoiResult> searchPoiNearby(String city, String location, String type) {
        String normalizedLocation = sanitizeFreeformPlace(location);
        if (normalizedLocation == null) {
            return List.of();
        }

        PlaceContext context = resolvePlaceContext(firstNonBlank(location, city));
        String effectiveCity = firstNonBlank(city, context == null ? null : context.city());
        String effectiveLocation = context == null ? normalizedLocation : firstNonBlank(context.location(), geocode(effectiveCity, normalizedLocation));
        if (effectiveLocation == null) {
            return List.of();
        }

        QueryPlan plan = QueryPlan.forType(type);
        LinkedHashMap<String, PoiResult> merged = new LinkedHashMap<>();
        mergePois(merged, searchAround(effectiveLocation, plan.primaryKeyword(), plan.typeCode(), 5000, 1));
        if (merged.size() < 5) {
            mergePois(merged, searchAround(effectiveLocation, plan.secondaryKeyword(), plan.typeCode(), 10000, 1));
        }
        List<PoiResult> results = reRankOriginalOrder(new ArrayList<>(merged.values()));
        enrichDetails(results);
        return results;
    }

    public List<String> findTrainStationsByCity(String city) {
        String normalizedCity = normalize(city);
        if (normalizedCity == null) {
            return List.of();
        }

        LinkedHashMap<String, String> uniqueStations = new LinkedHashMap<>();
        collectTrainStations(uniqueStations, searchText(normalizedCity, "火车站", "150200", 1));
        collectTrainStations(uniqueStations, searchText(normalizedCity, "高铁站", "150200", 1));
        return new ArrayList<>(uniqueStations.values());
    }

    public String geocode(String cityHint, String address) {
        GeoPoint point = geocodePoint(cityHint, address);
        return point != null ? point.location() : null;
    }

    public String buildThreeRoutePlans(String origin, String destination, String cityHint) {
        GeoPoint originPoint = geocodePoint(cityHint, sanitizeRouteText(origin));
        GeoPoint destinationPoint = geocodePoint(cityHint, sanitizeRouteText(destination));
        if (originPoint == null || destinationPoint == null) {
            return "未能解析起点或终点，请提供更明确的地点名称。";
        }

        List<String> plans = new ArrayList<>();
        String driving = buildDrivingPlan(originPoint.location(), destinationPoint.location());
        if (driving != null) plans.add(driving);
        String transit = buildTransitPlan(originPoint, destinationPoint, cityHint);
        if (transit != null) plans.add(transit);
        String walking = buildWalkingPlan(originPoint.location(), destinationPoint.location());
        if (walking != null) plans.add(walking);

        if (plans.isEmpty()) {
            return "路线查询失败，请稍后再试。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【路线参考】\n");
        sb.append("起点：").append(origin).append("\n");
        sb.append("终点：").append(destination).append("\n\n");
        for (String plan : plans) {
            sb.append(plan).append("\n\n");
        }
        sb.append("你可以根据时间、换乘和费用自行选择。");
        return sb.toString().trim();
    }

    public String buildRecommendedRoutePlans(String origin, String destination, String cityHint) {
        GeoPoint originPoint = geocodePoint(cityHint, sanitizeRouteText(origin));
        GeoPoint destinationPoint = geocodePoint(cityHint, sanitizeRouteText(destination));
        if (originPoint == null || destinationPoint == null) {
            return "未能解析起点或终点，请提供更明确的地点名称。";
        }

        List<RouteCandidate> candidates = new ArrayList<>();
        candidates.addAll(collectDrivingCandidates(originPoint.location(), destinationPoint.location()));
        candidates.addAll(collectTransitCandidates(originPoint, destinationPoint, cityHint));
        RouteCandidate walking = collectWalkingCandidate(originPoint.location(), destinationPoint.location());
        if (walking != null) {
            candidates.add(walking);
        }

        if (candidates.isEmpty()) {
            return "路线查询失败，请稍后再试。";
        }

        List<RouteCandidate> recommendations = candidates.stream()
                .sorted(Comparator.comparingDouble(RouteCandidate::score)
                        .thenComparingInt(RouteCandidate::durationSeconds)
                        .thenComparingInt(RouteCandidate::transferCount))
                .limit(3)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("【路线推荐】\n");
        sb.append("起点：").append(origin).append("\n");
        sb.append("终点：").append(destination).append("\n\n");
        sb.append("已先筛选当前可用路线，再推荐最合适的 ").append(recommendations.size()).append(" 条方案：\n\n");
        for (int i = 0; i < recommendations.size(); i++) {
            sb.append(i + 1).append(". ").append(renderRouteCandidate(recommendations.get(i)));
            if (i < recommendations.size() - 1) {
                sb.append("\n\n");
            }
        }
        sb.append("\n\n你可以根据时间、换乘、步行距离和费用选择；如果你愿意，我还可以继续按“最快 / 最省钱 / 最少步行”再细分。");
        return sb.toString().trim();
    }

    private PlaceContext searchPlaceContextByText(String query, String cityHint) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(PLACE_TEXT_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("keywords", query)
                .addQueryParameter("offset", "5")
                .addQueryParameter("page", "1")
                .addQueryParameter("extensions", "all");
        if (cityHint != null && !cityHint.isBlank()) {
            builder.addQueryParameter("city", cityHint);
        }

        JSONObject json = getJson(builder.build().toString(), CACHE_TTL_MS);
        if (!isOk(json)) {
            return null;
        }

        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            return null;
        }

        JSONObject first = pois.getJSONObject(0);
        if (first == null) {
            return null;
        }

        String city = firstNonBlank(trimToNull(first.getString("cityname")), trimToNull(first.getString("adname")), cityHint);
        String name = trimToNull(first.getString("name"));
        String location = trimToNull(first.getString("location"));
        return new PlaceContext(city, query, firstNonBlank(name, query), location);
    }

    private GeoPoint geocodePoint(String cityHint, String address) {
        String normalizedAddress = normalize(address);
        if (normalizedAddress == null) {
            return null;
        }

        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(GEOCODE_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("address", normalizedAddress);
        String normalizedCityHint = normalize(cityHint);
        if (normalizedCityHint != null) {
            builder.addQueryParameter("city", normalizedCityHint);
        }

        JSONObject json = getJson(builder.build().toString(), CACHE_TTL_MS);
        if (!isOk(json)) {
            return null;
        }
        JSONArray geocodes = json.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return null;
        }
        JSONObject geocode = geocodes.getJSONObject(0);
        if (geocode == null) {
            return null;
        }
        return new GeoPoint(
                normalizedAddress,
                trimToNull(geocode.getString("location")),
                normalizeCityField(geocode.get("city")),
                trimToNull(geocode.getString("adcode")),
                trimToNull(geocode.getString("level"))
        );
    }

    private List<PoiResult> searchText(String city, String keyword, String typeCode, int page) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(PLACE_TEXT_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("keywords", keyword)
                .addQueryParameter("types", typeCode)
                .addQueryParameter("offset", String.valueOf(SEARCH_OFFSET))
                .addQueryParameter("page", String.valueOf(page))
                .addQueryParameter("extensions", "all")
                .build();
        return parsePoiList(getJson(url.toString(), CACHE_TTL_MS));
    }

    private List<PoiResult> searchAround(String location, String keyword, String typeCode, int radius, int page) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(PLACE_AROUND_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("location", location)
                .addQueryParameter("types", typeCode)
                .addQueryParameter("radius", String.valueOf(radius))
                .addQueryParameter("offset", String.valueOf(SEARCH_OFFSET))
                .addQueryParameter("page", String.valueOf(page))
                .addQueryParameter("extensions", "all")
                .addQueryParameter("sortrule", "distance");
        if (keyword != null && !keyword.isBlank()) {
            builder.addQueryParameter("keywords", keyword);
        }
        return parsePoiList(getJson(builder.build().toString(), CACHE_TTL_MS));
    }

    private void enrichDetails(List<PoiResult> pois) {
        if (pois == null || pois.isEmpty()) {
            return;
        }
        int enriched = 0;
        for (PoiResult poi : pois) {
            if (enriched >= DETAIL_ENRICH_LIMIT) break;
            if (poi == null || poi.getId().isBlank() || !needsDetail(poi)) continue;
            PoiResult detail = getPoiDetail(poi.getId());
            if (detail != null) {
                poi.mergeMissingFields(detail);
                poi.setDetailEnriched(true);
                enriched++;
            }
        }
    }

    private void collectTrainStations(Map<String, String> uniqueStations, List<PoiResult> pois) {
        if (uniqueStations == null || pois == null || pois.isEmpty()) {
            return;
        }
        for (PoiResult poi : pois) {
            if (poi == null || poi.getName().isBlank()) {
                continue;
            }
            String normalized = normalizeTrainStationName(poi.getName());
            if (normalized == null) {
                continue;
            }
            uniqueStations.putIfAbsent(normalized, normalized);
        }
    }

    private String normalizeTrainStationName(String value) {
        String cleaned = trimToNull(value);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceAll("(火车站|高铁站)$", "").trim();
        cleaned = cleaned.replaceAll("站$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private boolean needsDetail(PoiResult poi) {
        return !poi.hasRating() || poi.getTag().isBlank() || poi.getAddress().isBlank() || poi.getTel().isBlank();
    }

    private PoiResult getPoiDetail(String poiId) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(PLACE_DETAIL_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("id", poiId)
                .addQueryParameter("extensions", "all")
                .build();
        JSONObject json = getJson(url.toString(), DETAIL_CACHE_TTL_MS);
        if (!isOk(json)) return null;
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) return null;
        return parsePoi(pois.getJSONObject(0), 0);
    }

    private List<PoiResult> parsePoiList(JSONObject json) {
        if (!isOk(json)) return List.of();
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) return List.of();
        List<PoiResult> results = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            PoiResult poi = parsePoi(pois.getJSONObject(i), i);
            if (poi != null) results.add(poi);
        }
        return results;
    }

    private PoiResult parsePoi(JSONObject json, int originalRank) {
        if (json == null) return null;
        PoiResult poi = new PoiResult();
        poi.setId(defaultString(json.getString("id")));
        poi.setName(defaultString(json.getString("name")));
        poi.setType(defaultString(json.getString("type")));
        poi.setTypeCode(defaultString(json.getString("typecode")));
        poi.setAddress(defaultString(json.getString("address")));
        poi.setTel(defaultString(json.getString("tel")));
        poi.setLocation(defaultString(json.getString("location")));
        poi.setBusinessArea(defaultString(json.getString("business_area")));
        poi.setTag(defaultString(json.getString("tag")));
        poi.setOriginalRank(originalRank);
        poi.setDistanceMeters(parseInt(trimToNull(json.getString("distance"))));
        JSONObject bizExt = json.getJSONObject("biz_ext");
        if (bizExt != null) {
            poi.setRating(parseDouble(bizExt.getString("rating")));
            poi.setCost(parseDouble(bizExt.getString("cost")));
        }
        return poi.getName().isBlank() ? null : poi;
    }

    private String buildDrivingPlan(String origin, String destination) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(DRIVING_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("extensions", "base")
                .build();
        JSONObject json = getJson(url.toString(), CACHE_TTL_MS);
        if (!isOk(json)) return null;
        JSONObject route = json.getJSONObject("route");
        JSONArray paths = route != null ? route.getJSONArray("paths") : null;
        if (paths == null || paths.isEmpty()) return null;
        JSONObject path = paths.getJSONObject(0);
        StringBuilder sb = new StringBuilder("【驾车】");
        sb.append("约 ").append(formatDuration(path.getString("duration"))).append("，").append(formatDistance(path.getString("distance")));
        String trafficLights = path.getString("traffic_lights");
        if (trafficLights != null && !trafficLights.isBlank()) sb.append("，红绿灯约 ").append(trafficLights).append(" 个");
        String tolls = path.getString("tolls");
        if (tolls != null && !tolls.isBlank() && !"0".equals(tolls)) sb.append("，过路费约 ").append(tolls).append(" 元");
        String summary = summarizeDrivingSteps(path.getJSONArray("steps"));
        if (summary != null) sb.append("\n关键路线：").append(summary);
        return sb.toString();
    }

    private String buildWalkingPlan(String origin, String destination) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WALKING_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .build();
        JSONObject json = getJson(url.toString(), CACHE_TTL_MS);
        if (!isOk(json)) return null;
        JSONObject route = json.getJSONObject("route");
        JSONArray paths = route != null ? route.getJSONArray("paths") : null;
        if (paths == null || paths.isEmpty()) return null;
        JSONObject path = paths.getJSONObject(0);
        StringBuilder sb = new StringBuilder("【步行】约 ")
                .append(formatDuration(path.getString("duration")))
                .append("，")
                .append(formatDistance(path.getString("distance")));
        String summary = summarizeWalkingSteps(path.getJSONArray("steps"));
        if (summary != null) sb.append("\n步行指引：").append(summary);
        return sb.toString();
    }

    private String buildTransitPlan(GeoPoint origin, GeoPoint destination, String cityHint) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(TRANSIT_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin.location())
                .addQueryParameter("destination", destination.location())
                .addQueryParameter("extensions", "base")
                .addQueryParameter("strategy", "0");
        String city = normalize(cityHint);
        if (city == null) city = normalize(origin.city());
        if (city != null) {
            builder.addQueryParameter("city", city);
            builder.addQueryParameter("cityd", city);
        }
        JSONObject json = getJson(builder.build().toString(), CACHE_TTL_MS);
        if (!isOk(json)) return null;
        JSONObject route = json.getJSONObject("route");
        JSONArray transits = route != null ? route.getJSONArray("transits") : null;
        if (transits == null || transits.isEmpty()) return null;
        JSONObject transit = transits.getJSONObject(0);
        StringBuilder sb = new StringBuilder("【公交/地铁】");
        sb.append("约 ").append(formatDuration(transit.getString("duration"))).append("，").append(formatDistance(transit.getString("distance")));
        String cost = transit.getString("cost");
        if (cost != null && !cost.isBlank() && !"0".equals(cost)) sb.append("，票价约 ").append(cost).append(" 元");
        String summary = summarizeTransit(transit.getJSONArray("segments"));
        if (summary != null) sb.append("\n换乘建议：").append(summary);
        return sb.toString();
    }

    private List<RouteCandidate> collectDrivingCandidates(String origin, String destination) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(DRIVING_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("extensions", "base")
                .build();
        JSONObject json = getJson(url.toString(), CACHE_TTL_MS);
        if (!isOk(json)) {
            return List.of();
        }
        JSONObject route = json.getJSONObject("route");
        JSONArray paths = route != null ? route.getJSONArray("paths") : null;
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }

        List<RouteCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            JSONObject path = paths.getJSONObject(i);
            if (path == null) {
                continue;
            }
            int durationSeconds = parseInt(path.getString("duration"));
            int distanceMeters = parseInt(path.getString("distance"));
            int trafficLights = parseInt(path.getString("traffic_lights"));
            double tolls = parseDouble(path.getString("tolls"));
            candidates.add(new RouteCandidate(
                    "driving",
                    i == 0 ? "驾车推荐" : "驾车备选 " + (i + 1),
                    durationSeconds,
                    distanceMeters,
                    tolls,
                    0,
                    0,
                    trafficLights,
                    summarizeDrivingSteps(path.getJSONArray("steps")),
                    buildDrivingReason(durationSeconds, trafficLights, tolls, i),
                    scoreDriving(durationSeconds, distanceMeters, trafficLights, tolls, i)
            ));
        }
        return candidates;
    }

    private RouteCandidate collectWalkingCandidate(String origin, String destination) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WALKING_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .build();
        JSONObject json = getJson(url.toString(), CACHE_TTL_MS);
        if (!isOk(json)) {
            return null;
        }
        JSONObject route = json.getJSONObject("route");
        JSONArray paths = route != null ? route.getJSONArray("paths") : null;
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        JSONObject path = paths.getJSONObject(0);
        int durationSeconds = parseInt(path.getString("duration"));
        int distanceMeters = parseInt(path.getString("distance"));
        return new RouteCandidate(
                "walking",
                "步行方案",
                durationSeconds,
                distanceMeters,
                0D,
                0,
                distanceMeters,
                0,
                summarizeWalkingSteps(path.getJSONArray("steps")),
                buildWalkingReason(durationSeconds, distanceMeters),
                scoreWalking(durationSeconds, distanceMeters)
        );
    }

    private List<RouteCandidate> collectTransitCandidates(GeoPoint origin, GeoPoint destination, String cityHint) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(TRANSIT_URL)).newBuilder()
                .addQueryParameter("key", requireApiKey())
                .addQueryParameter("origin", origin.location())
                .addQueryParameter("destination", destination.location())
                .addQueryParameter("extensions", "base")
                .addQueryParameter("strategy", "0");
        String city = normalize(cityHint);
        if (city == null) {
            city = normalize(origin.city());
        }
        if (city != null) {
            builder.addQueryParameter("city", city);
            builder.addQueryParameter("cityd", city);
        }
        JSONObject json = getJson(builder.build().toString(), CACHE_TTL_MS);
        if (!isOk(json)) {
            return List.of();
        }
        JSONObject route = json.getJSONObject("route");
        JSONArray transits = route != null ? route.getJSONArray("transits") : null;
        if (transits == null || transits.isEmpty()) {
            return List.of();
        }

        List<RouteCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < transits.size(); i++) {
            JSONObject transit = transits.getJSONObject(i);
            if (transit == null) {
                continue;
            }
            int durationSeconds = parseInt(transit.getString("duration"));
            int distanceMeters = parseInt(transit.getString("distance"));
            double cost = parseDouble(transit.getString("cost"));
            JSONArray segments = transit.getJSONArray("segments");
            int transferCount = countTransitTransfers(segments);
            int walkingDistance = parseTransitWalkingDistance(segments);
            candidates.add(new RouteCandidate(
                    "transit",
                    i == 0 ? "公交/地铁推荐" : "公交/地铁备选 " + (i + 1),
                    durationSeconds,
                    distanceMeters,
                    cost,
                    transferCount,
                    walkingDistance,
                    0,
                    summarizeTransit(segments),
                    buildTransitReason(durationSeconds, cost, transferCount, walkingDistance, i),
                    scoreTransit(durationSeconds, distanceMeters, cost, transferCount, walkingDistance, i)
            ));
        }
        return candidates;
    }

    private String renderRouteCandidate(RouteCandidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(candidate.title()).append("】");
        sb.append("约 ").append(formatDurationSeconds(candidate.durationSeconds()));
        sb.append("，").append(formatDistanceMeters(candidate.distanceMeters()));
        if (candidate.cost() > 0D) {
            sb.append("，费用约 ").append(formatMoney(candidate.cost()));
        }
        if (candidate.transferCount() > 0) {
            sb.append("，换乘 ").append(candidate.transferCount()).append(" 次");
        }
        if (candidate.walkingDistanceMeters() > 0 && !"walking".equals(candidate.mode())) {
            sb.append("，步行约 ").append(formatDistanceMeters(candidate.walkingDistanceMeters()));
        }
        if (candidate.trafficLights() > 0) {
            sb.append("，红绿灯约 ").append(candidate.trafficLights()).append(" 个");
        }
        if (candidate.reason() != null && !candidate.reason().isBlank()) {
            sb.append("\n推荐理由：").append(candidate.reason());
        }
        if (candidate.summary() != null && !candidate.summary().isBlank()) {
            sb.append("\n路线摘要：").append(candidate.summary());
        }
        return sb.toString();
    }

    private double scoreDriving(int durationSeconds, int distanceMeters, int trafficLights, double tolls, int rank) {
        return normalizeDurationScore(durationSeconds)
                + Math.max(distanceMeters, 0) / 1200.0
                + Math.max(trafficLights, 0) * 1.8
                + Math.max(tolls, 0D) * 2.5
                + rank * 3;
    }

    private double scoreTransit(int durationSeconds, int distanceMeters, double cost, int transferCount, int walkingDistance, int rank) {
        return normalizeDurationScore(durationSeconds)
                + Math.max(distanceMeters, 0) / 1600.0
                + Math.max(transferCount, 0) * 12
                + Math.max(walkingDistance, 0) / 180.0
                + Math.max(cost, 0D) * 2
                + rank * 2;
    }

    private double scoreWalking(int durationSeconds, int distanceMeters) {
        double score = normalizeDurationScore(durationSeconds) + Math.max(distanceMeters, 0) / 150.0;
        if (durationSeconds > 5400) {
            score += 40;
        }
        return score;
    }

    private double normalizeDurationScore(int durationSeconds) {
        return durationSeconds <= 0 ? 9999 : durationSeconds / 60.0;
    }

    private String buildDrivingReason(int durationSeconds, int trafficLights, double tolls, int rank) {
        List<String> reasons = new ArrayList<>();
        if (rank == 0) {
            reasons.add("驾车候选里优先级更高");
        }
        if (durationSeconds > 0 && durationSeconds <= 2700) {
            reasons.add("整体耗时较短");
        }
        if (trafficLights >= 0 && trafficLights <= 12) {
            reasons.add("沿途红绿灯相对较少");
        }
        if (tolls == 0D) {
            reasons.add("基本不产生过路费");
        }
        return reasons.isEmpty() ? "适合希望更直接到达的情况" : String.join("，", reasons);
    }

    private String buildTransitReason(int durationSeconds, double cost, int transferCount, int walkingDistance, int rank) {
        List<String> reasons = new ArrayList<>();
        if (rank == 0) {
            reasons.add("公共交通候选里优先级最高");
        }
        if (durationSeconds > 0 && durationSeconds <= 3600) {
            reasons.add("通勤时间比较可控");
        }
        if (transferCount <= 1) {
            reasons.add("换乘次数较少");
        }
        if (walkingDistance > 0 && walkingDistance <= 800) {
            reasons.add("接驳步行压力较小");
        }
        if (cost > 0D && cost <= 5D) {
            reasons.add("费用相对较低");
        }
        return reasons.isEmpty() ? "适合兼顾费用和稳定性的出行" : String.join("，", reasons);
    }

    private String buildWalkingReason(int durationSeconds, int distanceMeters) {
        if (durationSeconds > 0 && durationSeconds <= 1800) {
            return "距离不远，步行即可到达";
        }
        if (distanceMeters > 0 && distanceMeters <= 2500) {
            return "路程较短，适合不想换乘时选择";
        }
        return "适合近距离机动前往";
    }

    private int parseTransitWalkingDistance(JSONArray segments) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < segments.size(); i++) {
            JSONObject segment = segments.getJSONObject(i);
            if (segment == null) {
                continue;
            }
            JSONObject walking = segment.getJSONObject("walking");
            if (walking == null) {
                continue;
            }
            int distance = parseInt(walking.getString("distance"));
            if (distance > 0) {
                total += distance;
            }
        }
        return total;
    }

    private int countTransitTransfers(JSONArray segments) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < segments.size(); i++) {
            JSONObject segment = segments.getJSONObject(i);
            if (segment == null) {
                continue;
            }
            JSONObject bus = segment.getJSONObject("bus");
            if (bus != null) {
                JSONArray buslines = bus.getJSONArray("buslines");
                if (buslines != null) {
                    lines += buslines.size();
                }
            }
            JSONObject railway = segment.getJSONObject("railway");
            if (railway != null) {
                lines++;
            }
        }
        return Math.max(0, lines - 1);
    }

    private String formatDistanceMeters(int meters) {
        return formatDistance(String.valueOf(meters));
    }

    private String formatDurationSeconds(int seconds) {
        return formatDuration(String.valueOf(seconds));
    }

    private String formatMoney(double amount) {
        if (Math.abs(amount - Math.rint(amount)) < 0.0001) {
            return ((int) Math.rint(amount)) + " 元";
        }
        return String.format(Locale.ROOT, "%.1f 元", amount);
    }

    private JSONObject getJson(String url, long ttlMs) {
        String cached = getCached(url);
        if (cached != null) return JSON.parseObject(cached);
        try {
            String body = throttledGet(url);
            putCache(url, body, ttlMs);
            return JSON.parseObject(body);
        } catch (Exception e) {
            log.warn("高德请求失败: {}", url, e);
            return null;
        }
    }

    private String throttledGet(String url) throws IOException, InterruptedException {
        requestSemaphore.acquire();
        try {
            long waitMs = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt.get());
            if (waitMs > 0) Thread.sleep(waitMs);
            String body = HttpUtil.doGet(url);
            lastRequestAt.set(System.currentTimeMillis());
            return body;
        } finally {
            requestSemaphore.release();
        }
    }

    private boolean isOk(JSONObject json) {
        return json != null && "1".equals(json.getString("status"));
    }

    private void mergePois(Map<String, PoiResult> merged, Collection<PoiResult> pois) {
        if (pois == null) return;
        for (PoiResult poi : pois) {
            if (poi == null || poi.getName().isBlank()) continue;
            String key = !poi.getId().isBlank() ? poi.getId() : poi.getName() + "|" + poi.getAddress();
            PoiResult existing = merged.get(key);
            if (existing == null) merged.put(key, poi);
            else {
                existing.mergeMissingFields(poi);
                existing.setOriginalRank(Math.min(existing.getOriginalRank(), poi.getOriginalRank()));
            }
        }
    }

    private List<PoiResult> reRankOriginalOrder(List<PoiResult> pois) {
        pois.sort(Comparator.comparingInt(PoiResult::getOriginalRank));
        for (int i = 0; i < pois.size(); i++) pois.get(i).setOriginalRank(i);
        return pois;
    }

    private String requireApiKey() {
        String key = ConfigUtil.getAmapApiKey();
        if (key == null || key.isBlank()) throw new IllegalStateException("amap.key 未配置");
        return key.trim();
    }

    private String getCached(String key) {
        CacheEntry<String> entry = responseCache.get(key);
        if (entry == null || entry.isExpired()) {
            responseCache.remove(key);
            return null;
        }
        return entry.value();
    }

    private void putCache(String key, String value, long ttlMs) {
        responseCache.put(key, CacheEntry.create(value, ttlMs));
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.trim();
    }

    private String sanitizeFreeformPlace(String text) {
        String cleaned = trimToNull(text);
        if (cleaned == null) return null;
        return cleaned
                .replace("给我", "")
                .replace("帮我", "")
                .replace("查一下", "")
                .replace("看一下", "")
                .replace("旅游攻略", "")
                .replace("旅游", "")
                .replace("旅行", "")
                .replace("路线", "")
                .replace("怎么走", "")
                .replace("景点推荐", "")
                .replace("美食推荐", "")
                .replace("附近", "")
                .replace("有哪些", "")
                .replace("有什么", "")
                .replace("推荐", "")
                .replace("一下", "")
                .replaceAll("[，。！？；、]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sanitizeRouteText(String text) {
        String cleaned = trimToNull(text);
        if (cleaned == null) return null;
        return cleaned
                .replace("给我规划一下路线", "")
                .replace("规划一下路线", "")
                .replace("怎么走", "")
                .replace("路线", "")
                .replace("导航", "")
                .trim();
    }

    private String extractCityFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String suffix : List.of("市", "省")) {
            int idx = text.indexOf(suffix);
            if (idx > 0 && idx <= 4) {
                return text.substring(0, idx);
            }
        }
        CityResolver resolver = new CityResolver();
        return resolver.resolve(text);
    }

    private boolean looksLikeSpecificPlace(String displayName, String query) {
        if (displayName == null || query == null) {
            return false;
        }
        if (displayName.length() >= 3 && !GENERIC_GEO_LEVELS.contains(displayName)) {
            return true;
        }
        return query.contains("站") || query.contains("寺") || query.contains("湖") || query.contains("园")
                || query.contains("馆") || query.contains("山") || query.contains("广场") || query.contains("古城");
    }

    private String normalizeCityField(Object cityField) {
        if (cityField == null) {
            return null;
        }
        if (cityField instanceof String str) {
            return trimToNull(str);
        }
        if (cityField instanceof JSONArray array && !array.isEmpty()) {
            return trimToNull(array.getString(0));
        }
        return trimToNull(String.valueOf(cityField));
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int parseInt(String value) {
        try {
            return value == null || value.isBlank() ? -1 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? 0D : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0D;
        }
    }

    private String formatDistance(String metersText) {
        int meters = parseInt(metersText);
        if (meters < 0) return "距离未知";
        if (meters >= 1000) return String.format(Locale.ROOT, "%.1f 公里", meters / 1000.0);
        return meters + " 米";
    }

    private String formatDuration(String secondsText) {
        int seconds = parseInt(secondsText);
        if (seconds <= 0) return "时间未知";
        int minutes = (int) Math.round(seconds / 60.0);
        if (minutes < 60) return minutes + " 分钟";
        int hours = minutes / 60;
        int remainMinutes = minutes % 60;
        return remainMinutes == 0 ? hours + " 小时" : hours + " 小时 " + remainMinutes + " 分钟";
    }

    private String summarizeDrivingSteps(JSONArray steps) {
        if (steps == null || steps.isEmpty()) return null;
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < steps.size() && actions.size() < 3; i++) {
            JSONObject step = steps.getJSONObject(i);
            String road = trimToNull(step.getString("road"));
            String instruction = trimToNull(step.getString("instruction"));
            if (road != null) actions.add("沿 " + road + " 行驶");
            else if (instruction != null) actions.add(shortenInstruction(instruction));
        }
        return actions.isEmpty() ? null : String.join("；", actions);
    }

    private String summarizeWalkingSteps(JSONArray steps) {
        if (steps == null || steps.isEmpty()) return null;
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < steps.size() && actions.size() < 3; i++) {
            String instruction = trimToNull(steps.getJSONObject(i).getString("instruction"));
            if (instruction != null) actions.add(shortenInstruction(instruction));
        }
        return actions.isEmpty() ? null : String.join("；", actions);
    }

    private String summarizeTransit(JSONArray segments) {
        if (segments == null || segments.isEmpty()) return null;
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < segments.size() && actions.size() < 4; i++) {
            JSONObject segment = segments.getJSONObject(i);
            if (segment == null) continue;
            JSONObject walking = segment.getJSONObject("walking");
            if (walking != null && actions.size() < 4) {
                int walkingDistance = parseInt(walking.getString("distance"));
                if (walkingDistance > 0) actions.add("步行 " + formatDistance(walking.getString("distance")));
            }
            JSONObject bus = segment.getJSONObject("bus");
            if (bus != null) {
                JSONArray buslines = bus.getJSONArray("buslines");
                if (buslines != null) {
                    for (int j = 0; j < buslines.size() && actions.size() < 4; j++) {
                        String name = trimToNull(buslines.getJSONObject(j).getString("name"));
                        if (name != null) actions.add("乘坐 " + simplifyLineName(name));
                    }
                }
            }
            JSONObject railway = segment.getJSONObject("railway");
            if (railway != null && actions.size() < 4) {
                String trip = firstNonBlank(railway.getString("trip"), railway.getString("name"));
                if (trip != null) actions.add("乘坐 " + trip);
            }
        }
        return actions.isEmpty() ? null : String.join("；", actions);
    }

    private String shortenInstruction(String instruction) {
        String cleaned = instruction == null ? null : instruction.replace("即可到达目的地", "到达目的地").trim();
        if (cleaned == null) return null;
        return cleaned.length() <= 28 ? cleaned : cleaned.substring(0, 28) + "...";
    }

    private String simplifyLineName(String name) {
        int bracket = name.indexOf('(');
        return bracket > 0 ? name.substring(0, bracket).trim() : name.trim();
    }

    private record RouteCandidate(
            String mode,
            String title,
            int durationSeconds,
            int distanceMeters,
            double cost,
            int transferCount,
            int walkingDistanceMeters,
            int trafficLights,
            String summary,
            String reason,
            double score
    ) {
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) return trimmed;
        }
        return null;
    }

    private record CacheEntry<T>(T value, long expireAt) {
        static <T> CacheEntry<T> create(T value, long ttlMs) {
            return new CacheEntry<>(value, System.currentTimeMillis() + ttlMs);
        }
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private record GeoPoint(String name, String location, String city, String adcode, String level) {
        boolean isGenericLevel() {
            return level != null && GENERIC_GEO_LEVELS.contains(level);
        }
    }

    public record PlaceContext(String city, String queryText, String displayName, String location) {
    }

    private record QueryPlan(String typeCode, List<String> cityKeywords, String primaryKeyword, String secondaryKeyword) {
        static QueryPlan forType(String type) {
            if ("food".equalsIgnoreCase(type)) {
                return new QueryPlan("050000", List.of("本地特色美食", "老字号餐厅"), "本地特色美食", "热门餐厅");
            }
            return new QueryPlan("110000", List.of("热门景点", "旅游景区"), "热门景点", "旅游景区");
        }
    }
}
