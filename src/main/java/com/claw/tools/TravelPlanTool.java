package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.AmapService;
import com.claw.service.CityResolver;
import com.claw.service.PoiResult;
import com.claw.service.PoiResultScorer;
import com.claw.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class TravelPlanTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(TravelPlanTool.class);

    private final WeatherService weatherService;
    private final AmapService amapService;
    private final CityResolver cityResolver;
    private final PoiResultScorer scorer;

    public TravelPlanTool(WeatherService weatherService,
                          AmapService amapService,
                          CityResolver cityResolver,
                          PoiResultScorer scorer) {
        this.weatherService = weatherService;
        this.amapService = amapService;
        this.cityResolver = cityResolver;
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "plan_travel";
    }

    @Override
    public String description() {
        return "生成城市或地点的旅游攻略，综合天气、景点和美食推荐，并支持传入出行日期与游玩天数。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("city", stringProperty("可选，城市名或整句需求，例如 洛阳旅游攻略"));
        properties.put("destination", stringProperty("可选，具体地点，例如 龙门石窟、西湖"));
        properties.put("travel_date", stringProperty("可选，出行日期，例如 2026-09-14、今天、明天、后天"));
        properties.put("trip_days", integerProperty("可选，游玩天数，例如 2、3。"));

        JSONObject travelType = stringProperty("攻略类型：attractions=景点，food=美食，both=都要");
        travelType.put("enum", new JSONArray() {{
            add("attractions");
            add("food");
            add("both");
        }});
        properties.put("travel_type", travelType);
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            if (args == null) {
                return "参数解析失败，请提供城市名或目的地。";
            }

            String rawCity = normalizeTravelInput(args.getString("city"));
            String rawDestination = normalizeTravelInput(args.getString("destination"));
            String travelType = normalizeType(args.getString("travel_type"));
            String travelDate = trimToNull(args.getString("travel_date"));
            Integer tripDays = args.getInteger("trip_days");

            ResolvedTravelQuery query = resolveTravelQuery(rawCity, rawDestination);
            if (query.city() == null) {
                return "没有识别到可用城市或地点，请直接给我城市名或景点名。";
            }

            log.info("travel plan: city={}, destination={}, travelType={}, travelDate={}, tripDays={}, rawCity={}, rawDestination={}",
                    query.city(), query.destination(), travelType, travelDate, tripDays, rawCity, rawDestination);

            String weather = weatherService.getWeather(query.city(), travelDate);
            String contextTitle = query.destination() == null ? query.city() : query.city() + " · " + query.destination();

            StringBuilder sb = new StringBuilder();
            sb.append("【").append(contextTitle).append(" 旅游攻略素材】\n");
            if (travelDate != null) {
                sb.append("出行日期：").append(travelDate).append("\n");
            }
            if (tripDays != null && tripDays > 0) {
                sb.append("游玩天数：").append(tripDays).append(" 天\n");
            }
            sb.append(weather).append("\n\n");

            if (!"food".equals(travelType)) {
                List<PoiResult> attractions = query.destination() == null
                        ? scorer.scoreAttractions(amapService.searchPoiInCity(query.city(), "attractions"))
                        : scorer.scoreAttractions(amapService.searchPoiNearby(query.city(), query.destination(), "attractions"));
                sb.append(renderPois("景点建议", attractions)).append("\n\n");
            }
            if (!"attractions".equals(travelType)) {
                List<PoiResult> foods = query.destination() == null
                        ? scorer.scoreFoods(amapService.searchPoiInCity(query.city(), "food"))
                        : scorer.scoreFoods(amapService.searchPoiNearby(query.city(), query.destination(), "food"));
                sb.append(renderPois("美食建议", foods)).append("\n\n");
            }

            sb.append("说明：以上是用于生成游玩规划的事实素材，请结合游玩天数安排每日行程。");
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("TravelPlanTool execute failed", e);
            return "旅游攻略生成失败: " + e.getMessage();
        }
    }

    private ResolvedTravelQuery resolveTravelQuery(String rawCity, String rawDestination) {
        AmapService.PlaceContext destinationContext = rawDestination == null ? null : amapService.resolvePlaceContext(rawDestination);
        AmapService.PlaceContext cityContext = rawCity == null ? null : amapService.resolvePlaceContext(rawCity);

        String city = firstNonBlank(
                destinationContext == null ? null : destinationContext.city(),
                cityContext == null ? null : cityContext.city(),
                cityResolver.resolve(rawCity),
                cityResolver.resolve(rawDestination)
        );

        String destination = firstNonBlank(
                destinationContext == null ? null : destinationContext.displayName(),
                cityResolver.extractFamousSpot(rawDestination),
                cityContext != null && cityContext.city() != null && !cityContext.city().equals(cityContext.displayName())
                        ? cityContext.displayName()
                        : null
        );

        if (destination != null && city != null && destination.equals(city)) {
            destination = null;
        }
        return new ResolvedTravelQuery(city, destination);
    }

    private String renderPois(String title, List<PoiResult> pois) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("】\n");
        if (pois == null || pois.isEmpty()) {
            sb.append("暂时没有足够准确的推荐结果。");
            return sb.toString();
        }
        for (int i = 0; i < pois.size(); i++) {
            PoiResult poi = pois.get(i);
            sb.append(i + 1).append(". ").append(poi.getName());
            if (poi.hasRating()) {
                sb.append(" 评分").append(String.format(Locale.ROOT, "%.1f", poi.getRating()));
            }
            if (!poi.getTag().isBlank()) {
                sb.append(" · ").append(poi.getTag());
            }
            sb.append("\n");
            if (!poi.getAddress().isBlank()) {
                sb.append("地址：").append(poi.getAddress()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private JSONObject stringProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private JSONObject integerProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "both";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "attractions" -> "attractions";
            case "food" -> "food";
            default -> "both";
        };
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeTravelInput(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String cleaned = trimmed
                .replaceAll("^(今天|明天|后天|上午|下午|晚上|早上)+", "")
                .replaceAll("^[从由在向往去到赴前往抵达]+", "")
                .replaceAll("(出发|启程)$", "")
                .replaceAll("(玩\\d+[天晚日]?|玩几天|待\\d+[天晚日]?|住\\d+[天晚日]?|旅游|旅行|游玩|攻略|计划|行程|玩|逛|游)$", "")
                .replaceAll("(高铁票|火车票|车票)$", "")
                .replaceAll("[,，。；、\\s]+$", "")
                .trim();
        cleaned = cleaned.replaceAll("的$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record ResolvedTravelQuery(String city, String destination) {
    }
}
