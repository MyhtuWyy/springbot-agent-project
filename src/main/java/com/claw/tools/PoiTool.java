package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.AmapService;
import com.claw.service.CityResolver;
import com.claw.service.PoiResult;
import com.claw.service.PoiResultScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PoiTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(PoiTool.class);

    private final AmapService amapService;
    private final CityResolver cityResolver;
    private final PoiResultScorer scorer;

    public PoiTool(AmapService amapService, CityResolver cityResolver, PoiResultScorer scorer) {
        this.amapService = amapService;
        this.cityResolver = cityResolver;
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "search_poi";
    }

    @Override
    public String description() {
        return "查询景点和美食推荐，支持自由地点输入和附近搜索。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("city", stringProperty("可选，城市名或整句地点需求，例如洛阳、我在西湖附近想找美食。"));
        properties.put("location", stringProperty("可选，具体地点名，例如西湖、春熙路、龙门石窟。"));

        JSONObject type = stringProperty("查询类型：attractions=景点，food=美食，both=都查。");
        type.put("enum", new JSONArray() {{
            add("attractions");
            add("food");
            add("both");
        }});
        properties.put("type", type);
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            if (args == null) {
                return "参数解析失败，请提供有效的查询参数。";
            }

            String rawCity = trimToNull(args.getString("city"));
            String rawLocation = trimToNull(args.getString("location"));
            String type = normalizeType(args.getString("type"));

            ResolvedPoiQuery query = resolveQuery(rawCity, rawLocation);
            log.info("POI查询: city={}, location={}, type={}, rawCity={}, rawLocation={}",
                    query.city(), query.location(), type, rawCity, rawLocation);

            if (query.city() == null && query.location() == null) {
                return "未识别到有效地点，请直接输入城市、景点名或附近位置。";
            }

            if (query.location() != null) {
                return searchNearby(query.city(), query.location(), type);
            }
            return searchInCity(query.city(), type);
        } catch (Exception e) {
            log.error("POI 工具执行失败", e);
            return "景点美食查询出错: " + e.getMessage();
        }
    }

    private ResolvedPoiQuery resolveQuery(String rawCity, String rawLocation) {
        if (rawLocation != null) {
            AmapService.PlaceContext context = amapService.resolvePlaceContext(rawLocation);
            String city = context != null ? firstNonBlank(context.city(), cityResolver.resolve(rawCity)) : cityResolver.resolve(rawCity);
            String location = context != null ? context.displayName() : rawLocation;
            return new ResolvedPoiQuery(city, location);
        }

        if (rawCity != null) {
            AmapService.PlaceContext context = amapService.resolvePlaceContext(rawCity);
            if (context != null && context.displayName() != null && context.city() != null && !context.displayName().equals(context.city())) {
                return new ResolvedPoiQuery(context.city(), context.displayName());
            }
            String city = context != null ? firstNonBlank(context.city(), cityResolver.resolve(rawCity)) : cityResolver.resolve(rawCity);
            return new ResolvedPoiQuery(city, null);
        }
        return new ResolvedPoiQuery(null, null);
    }

    private String searchInCity(String city, String type) {
        return switch (type) {
            case "attractions" -> formatAttractions(city, scorer.scoreAttractions(amapService.searchPoiInCity(city, "attractions")));
            case "food" -> formatFoods(city, scorer.scoreFoods(amapService.searchPoiInCity(city, "food")));
            default -> formatAttractions(city, scorer.scoreAttractions(amapService.searchPoiInCity(city, "attractions")))
                    + "\n\n"
                    + formatFoods(city, scorer.scoreFoods(amapService.searchPoiInCity(city, "food")));
        };
    }

    private String searchNearby(String city, String location, String type) {
        String title = city == null ? location : city + " · " + location;
        return switch (type) {
            case "attractions" -> formatAttractions(title, scorer.scoreAttractions(amapService.searchPoiNearby(city, location, "attractions")));
            case "food" -> formatFoods(title, scorer.scoreFoods(amapService.searchPoiNearby(city, location, "food")));
            default -> formatAttractions(title, scorer.scoreAttractions(amapService.searchPoiNearby(city, location, "attractions")))
                    + "\n\n"
                    + formatFoods(title, scorer.scoreFoods(amapService.searchPoiNearby(city, location, "food")));
        };
    }

    private String formatAttractions(String title, List<PoiResult> results) {
        if (results == null || results.isEmpty()) {
            return "【" + title + " 景点推荐】\n暂无足够准确的景点结果，可换一个更具体的位置再试。";
        }

        StringBuilder sb = new StringBuilder("【").append(title).append(" 景点推荐】\n");
        for (int i = 0; i < results.size(); i++) {
            PoiResult poi = results.get(i);
            sb.append(i + 1).append(". ").append(poi.getName());
            appendMeta(sb, poi);
            sb.append("\n");
            appendAddress(sb, poi);
        }
        sb.append("以上结果已按知名度、评分、热度和名称完整度做过筛选。");
        return sb.toString().trim();
    }

    private String formatFoods(String title, List<PoiResult> results) {
        if (results == null || results.isEmpty()) {
            return "【" + title + " 美食推荐】\n暂无足够准确的美食结果，可换一个更具体的位置再试。";
        }

        StringBuilder sb = new StringBuilder("【").append(title).append(" 美食推荐】\n");
        for (int i = 0; i < results.size(); i++) {
            PoiResult poi = results.get(i);
            sb.append(i + 1).append(". ").append(poi.getName());
            appendMeta(sb, poi);
            sb.append("\n");
            appendAddress(sb, poi);
        }
        sb.append("以上结果已按招牌度、评分、热度和名称完整度做过筛选。");
        return sb.toString().trim();
    }

    private void appendMeta(StringBuilder sb, PoiResult poi) {
        if (poi.hasRating()) {
            sb.append(" 评分").append(String.format(Locale.ROOT, "%.1f", poi.getRating()));
        }
        if (poi.isNearBy()) {
            sb.append(" · ").append(formatDistance(poi.getDistanceMeters()));
        }
        if (!poi.getTag().isBlank()) {
            sb.append(" · ").append(poi.getTag());
        }
    }

    private void appendAddress(StringBuilder sb, PoiResult poi) {
        if (!poi.getAddress().isBlank()) {
            sb.append("   地址：").append(poi.getAddress()).append("\n");
        }
        if (!poi.getTel().isBlank()) {
            sb.append("   电话：").append(poi.getTel()).append("\n");
        }
    }

    private String formatDistance(int meters) {
        if (meters < 0) {
            return "距离未知";
        }
        if (meters >= 1000) {
            return String.format(Locale.ROOT, "%.1fkm", meters / 1000.0);
        }
        return meters + "m";
    }

    private JSONObject stringProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "string");
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record ResolvedPoiQuery(String city, String location) {
    }
}
