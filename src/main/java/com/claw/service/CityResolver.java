package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CityResolver {
    private static final List<String> COMMON_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "南京", "天津",
            "苏州", "西安", "长沙", "青岛", "厦门", "宁波", "福州", "昆明", "三亚", "桂林",
            "大连", "沈阳", "郑州", "洛阳", "无锡", "常州", "温州", "泉州", "佛山", "东莞", "珠海"
    );

    private static final Map<String, String> SPOT_TO_CITY = new LinkedHashMap<>();

    static {
        SPOT_TO_CITY.put("西湖", "杭州");
        SPOT_TO_CITY.put("灵隐寺", "杭州");
        SPOT_TO_CITY.put("千岛湖", "杭州");
        SPOT_TO_CITY.put("故宫", "北京");
        SPOT_TO_CITY.put("天安门", "北京");
        SPOT_TO_CITY.put("长城", "北京");
        SPOT_TO_CITY.put("外滩", "上海");
        SPOT_TO_CITY.put("东方明珠", "上海");
        SPOT_TO_CITY.put("迪士尼", "上海");
        SPOT_TO_CITY.put("春熙路", "成都");
        SPOT_TO_CITY.put("宽窄巷子", "成都");
        SPOT_TO_CITY.put("锦里", "成都");
        SPOT_TO_CITY.put("大雁塔", "西安");
        SPOT_TO_CITY.put("兵马俑", "西安");
        SPOT_TO_CITY.put("回民街", "西安");
        SPOT_TO_CITY.put("洪崖洞", "重庆");
        SPOT_TO_CITY.put("解放碑", "重庆");
        SPOT_TO_CITY.put("鼓浪屿", "厦门");
        SPOT_TO_CITY.put("中山陵", "南京");
        SPOT_TO_CITY.put("东湖", "武汉");
        SPOT_TO_CITY.put("黄鹤楼", "武汉");
        SPOT_TO_CITY.put("龙门石窟", "洛阳");
        SPOT_TO_CITY.put("白马寺", "洛阳");
        SPOT_TO_CITY.put("应天门", "洛阳");
        SPOT_TO_CITY.put("洛邑古城", "洛阳");
        SPOT_TO_CITY.put("老君山", "洛阳");
    }

    public String resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = normalizeText(raw);
        for (String city : COMMON_CITIES) {
            if (normalized.equals(city) || normalized.equals(city + "市")) {
                return city;
            }
        }
        for (String city : COMMON_CITIES) {
            if (normalized.contains(city)) {
                return city;
            }
        }
        return inferCityFromSpot(normalized);
    }

    public String inferCityFromSpot(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalizeText(text);
        for (Map.Entry<String, String> entry : SPOT_TO_CITY.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String extractFamousSpot(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalizeText(text);
        for (String spot : SPOT_TO_CITY.keySet()) {
            if (normalized.contains(spot)) {
                return spot;
            }
        }
        return null;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim()
                .replace("帮我", "")
                .replace("给我", "")
                .replace("查一下", "")
                .replace("看一下", "")
                .replace("推荐", "")
                .replace("附近", "")
                .replace("旅游攻略", "")
                .replace("旅游", "")
                .replace("旅行", "")
                .replace("路线", "")
                .replace("规划一下", "")
                .replace("怎么走", "")
                .replace("景点", "")
                .replace("美食", "")
                .replace("餐厅", "")
                .trim();
    }
}
