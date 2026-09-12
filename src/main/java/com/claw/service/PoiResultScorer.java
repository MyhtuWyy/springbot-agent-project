package com.claw.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class PoiResultScorer {
    private static final int TOP_N = 5;

    private static final List<String> ATTRACTION_STRONG = List.of(
            "景区", "公园", "博物馆", "古镇", "古城", "地标", "寺", "山", "湖", "湿地",
            "遗址", "纪念馆", "广场", "乐园", "度假区", "塔", "宫", "园林"
    );
    private static final List<String> ATTRACTION_WEAK = List.of(
            "谷", "峰", "河", "桥", "岛", "海滩", "街区", "步行街", "观景台", "城墙"
    );
    private static final List<String> ATTRACTION_NOISE = List.of(
            "停车场", "售票处", "游客中心", "服务区", "卫生间", "出入口", "办公区", "宿舍", "小区"
    );

    private static final List<String> FOOD_STRONG = List.of(
            "老字号", "本地特色", "招牌", "必吃", "网红", "热门", "必打卡", "非遗", "总店", "旗舰店", "名店"
    );
    private static final List<String> FOOD_WEAK = List.of(
            "小吃", "面馆", "火锅", "烧烤", "海鲜", "饭店", "餐厅", "酒楼", "私房菜", "本帮菜", "地方菜"
    );
    private static final List<String> FOOD_NOISE = List.of(
            "便利店", "超市", "食堂", "外卖", "配送", "自动售货", "批发", "菜市场"
    );

    public List<PoiResult> scoreAttractions(List<PoiResult> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .map(poi -> new ScoredPoi(poi, scoreAttraction(poi)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed()
                        .thenComparingInt(scored -> scored.poi().getOriginalRank()))
                .limit(TOP_N)
                .map(ScoredPoi::poi)
                .toList();
    }

    public List<PoiResult> scoreFoods(List<PoiResult> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .map(poi -> new ScoredPoi(poi, scoreFood(poi)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed()
                        .thenComparingInt(scored -> scored.poi().getOriginalRank()))
                .limit(TOP_N)
                .map(ScoredPoi::poi)
                .toList();
    }

    private double scoreAttraction(PoiResult poi) {
        String name = poi.getName();
        if (name.isBlank()) {
            return -1;
        }
        if (containsAny(name, ATTRACTION_NOISE)) {
            return -10;
        }

        double score = 0;
        score += keywordScore(name, ATTRACTION_STRONG, 12, ATTRACTION_WEAK, 6);
        score += keywordScore(poi.getType(), ATTRACTION_STRONG, 10, ATTRACTION_WEAK, 5);
        score += ratingScore(poi.getRating());
        score += rankScore(poi.getOriginalRank());
        score += infoScore(poi);
        score += distanceScore(poi);

        if (poi.getTypeCode().startsWith("11")) {
            score += 5;
        }
        if (!poi.getBusinessArea().isBlank()) {
            score += 1;
        }
        if (name.length() <= 2) {
            score -= 4;
        }
        return score;
    }

    private double scoreFood(PoiResult poi) {
        String name = poi.getName();
        if (name.isBlank()) {
            return -1;
        }
        if (containsAny(name, FOOD_NOISE) || containsAny(poi.getType(), FOOD_NOISE)) {
            return -10;
        }

        double score = 0;
        score += keywordScore(name, FOOD_STRONG, 12, FOOD_WEAK, 5);
        score += keywordScore(poi.getTag(), FOOD_STRONG, 8, FOOD_WEAK, 4);
        score += keywordScore(poi.getType(), FOOD_STRONG, 6, FOOD_WEAK, 4);
        score += ratingScore(poi.getRating());
        score += rankScore(poi.getOriginalRank());
        score += infoScore(poi);
        score += distanceScore(poi);

        if (poi.getTypeCode().startsWith("05")) {
            score += 5;
        }
        if (!poi.getTag().isBlank()) {
            score += 2;
        }
        if (poi.getCost() > 0) {
            score += 1;
        }
        if (name.length() <= 2) {
            score -= 4;
        }
        return score;
    }

    private double keywordScore(String text, List<String> strong, double strongScore, List<String> weak, double weakScore) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        if (containsAny(text, strong)) {
            return strongScore;
        }
        if (containsAny(text, weak)) {
            return weakScore;
        }
        return 0;
    }

    private double ratingScore(double rating) {
        if (rating >= 4.7) return 12;
        if (rating >= 4.5) return 10;
        if (rating >= 4.2) return 7;
        if (rating >= 4.0) return 5;
        if (rating >= 3.5) return 2;
        return 0;
    }

    private double rankScore(int rank) {
        if (rank <= 0) return 8;
        return Math.max(0, 8 - rank * 0.8);
    }

    private double infoScore(PoiResult poi) {
        double score = 0;
        if (!poi.getAddress().isBlank()) score += 2;
        if (!poi.getTel().isBlank()) score += 1;
        if (poi.isDetailEnriched()) score += 1;
        return score;
    }

    private double distanceScore(PoiResult poi) {
        if (!poi.isNearBy()) {
            return 0;
        }
        int distance = poi.getDistanceMeters();
        if (distance <= 1000) return 4;
        if (distance <= 3000) return 3;
        if (distance <= 5000) return 2;
        if (distance <= 10000) return 1;
        return 0;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record ScoredPoi(PoiResult poi, double score) {
    }
}
