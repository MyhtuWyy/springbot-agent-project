package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class CopywritingService {

    public String generate(String type,
                           String topic,
                           String city,
                           String destination,
                           String scene,
                           String style,
                           int count) {
        String normalizedType = normalizeType(type);
        String normalizedTopic = fallback(topic, "此刻");
        int size = Math.max(1, Math.min(10, count));

        List<String> copies = switch (normalizedType) {
            case "moments" -> generateMoments(normalizedTopic, city, destination, scene, style, size);
            case "healing" -> generateHealing(normalizedTopic, style, size);
            case "apology" -> generateApology(normalizedTopic, style, size);
            case "anniversary" -> generateAnniversary(normalizedTopic, style, size);
            case "signature" -> generateSignature(normalizedTopic, style, size);
            default -> List.of("暂不支持该类型文案：" + normalizedType);
        };

        return format(normalizedType, copies);
    }

    private List<String> generateMoments(String topic,
                                         String city,
                                         String destination,
                                         String scene,
                                         String style,
                                         int count) {
        String place = firstNonBlank(destination, city, topic, "这次出行");
        String mood = fallback(style, "轻松自然");
        String normalizedScene = normalizeScene(scene, topic);

        return switch (normalizedScene) {
            case "travel" -> generateTravelMoments(place, mood, count);
            case "food" -> buildFromTemplates(place, mood, count,
                    "在%1$s认真吃饭，就是今天最简单的快乐。好好生活，也要好好吃饭。🍜",
                    "%1$s这一口满足感，值得好好记录。胃被填满，心也跟着柔软起来。✨",
                    "今天的好心情，一半来自阳光，一半来自%1$s的美味。",
                    "今日关键词：逛吃逛吃，以及舍不得离开的%1$s。📍");
            case "photo" -> buildFromTemplates(place, mood, count,
                    "把%1$s拍进镜头里，也把今天明亮的心情留给自己。📷",
                    "%1$s很上镜，随手一拍，都是日子闪闪发光的样子。",
                    "镜头收下了%1$s，照片替我记住这一刻的风和快乐。✨",
                    "今天的照片不用修太多，因为%1$s本身就足够好看。");
            default -> buildFromTemplates(topic, mood, count,
                    "%1$s，值得被认真记录。普通的日子，也会因为用心感受而闪闪发光。✨",
                    "今天想分享的，不只是一张照片，还有%1$s带来的好心情。",
                    "给今天留一句话：关于%1$s，也关于认真热爱生活。",
                    "%1$s刚刚好，风刚刚好，这一刻也刚刚好。");
        };
    }

    private List<String> generateTravelMoments(String place, String style, int count) {
        if (place.contains("南京")) {
            return buildFromTemplates(place, style, count,
                    "南京的浪漫，藏在梧桐树影里，也藏在老城街巷的晚风里。慢慢走，慢慢看，把今天过成一张舍不得翻篇的明信片。📍南京",
                    "在南京，把脚步调成慢速。看光落在梧桐叶上，听风穿过老城街巷，原来旅行最好的纪念，是这一刻真的很开心。✨",
                    "金陵一梦，今日成真。没有匆忙赶路，只有梧桐、晚风，和被南京悄悄治愈的好心情。📍南京",
                    "南京适合慢慢逛：一半是老城故事，一半是人间烟火。今天不赶路，只负责感受风景和快乐。🍃");
        }
        return buildFromTemplates(place, style, count,
                "把脚步放慢，去听%1$s的风，也去看街巷里的光。旅行不一定要赶路，喜欢的瞬间本身就是答案。📍%1$s",
                "在%1$s，日子忽然有了电影感：路边的风景、傍晚的风，还有此刻刚刚好的心情。✨",
                "%1$s没有催我赶路，于是我把时间交给街巷，把快乐留在照片里。下一站，继续热爱。",
                "今日漫游%1$s：看喜欢的风景，吹自由的晚风，做一天不赶时间的人。🍃");
    }

    private List<String> generateHealing(String topic, String style, int count) {
        String mood = fallback(style, "温柔坚定");
        return buildFromTemplates(topic, mood, count,
                "因为%s而低落的时候，也别忘了把自己放回%s里。",
                "%s不是终点，它只是提醒你慢一点、稳一点，继续%s。",
                "和%s相处的过程中，允许自己先休息，再慢慢变得%s。",
                "愿你经历%s之后，依然能把日子过得%s。");
    }

    private List<String> generateApology(String topic, String style, int count) {
        String mood = fallback(style, "真诚克制");
        return buildFromTemplates(topic, mood, count,
                "关于%s，是我处理得不够好。对不起，也谢谢你愿意听我认真说完这句话。",
                "%s这件事让你不舒服了，我不找借口，只想郑重地说一声抱歉，并认真%s。",
                "因为%s让你失望，是我的问题。对不起，我会把后面的事做得更%s。",
                "回头看%s，我确实忽略了你的感受。抱歉，这是我应该更%s的地方。");
    }

    private List<String> generateAnniversary(String topic, String style, int count) {
        String mood = fallback(style, "有仪式感");
        return buildFromTemplates(topic, mood, count,
                "%s值得被认真纪念，也值得被反复庆祝成%s。",
                "把%s过成纪念日，本身就是一件很%s的事。",
                "从%s走到今天，想说的不只是开心，还有一路以来的%s。",
                "%s让普通的一天有了特别的意义，也让未来更值得%s。");
    }

    private List<String> generateSignature(String topic, String style, int count) {
        String mood = fallback(style, "简洁有辨识度");
        return buildFromTemplates(topic, mood, count,
                "把%s藏进日常，也把%s留给自己。",
                "%s，就是我理解的%s。",
                "关于%s，慢慢来，也要%s。",
                "不解释太多，%s已经足够%s。");
    }

    private List<String> buildFromTemplates(String left, String style, int count, String... templates) {
        List<String> results = new ArrayList<>();
        String safeLeft = fallback(left, "此刻");
        String normalizedStyle = fallback(style, "自然");
        int seed = Math.floorMod((safeLeft + "|" + normalizedStyle).hashCode(), templates.length);

        for (int i = 0; i < count; i++) {
            String template = templates[(seed + i) % templates.length];
            results.add(template.formatted(safeLeft, normalizedStyle));
        }
        return results;
    }

    private String format(String type, List<String> copies) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(displayTypeIcon(type)).append(" ").append(displayType(type));
        if (copies.size() == 1) {
            sb.append("\n\n> ").append(copies.getFirst().replace("\n", "\n> "));
        } else {
            for (int i = 0; i < copies.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(copies.get(i));
            }
        }
        return sb.toString().trim();
    }

    private String displayTypeIcon(String type) {
        return switch (type) {
            case "moments" -> "🌿";
            case "healing" -> "🌙";
            case "apology" -> "💬";
            case "anniversary" -> "✨";
            case "signature" -> "✍️";
            default -> "📝";
        };
    }

    private String displayType(String type) {
        return switch (type) {
            case "moments" -> "朋友圈文案";
            case "healing" -> "治愈短句";
            case "apology" -> "道歉文案";
            case "anniversary" -> "纪念日文案";
            case "signature" -> "个性签名";
            default -> "文案";
        };
    }

    private String normalizeType(String type) {
        if (isBlank(type)) {
            return "signature";
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "moments", "healing", "apology", "anniversary", "signature" -> normalized;
            default -> "signature";
        };
    }

    private String normalizeScene(String scene, String topic) {
        if (!isBlank(scene)) {
            return scene.trim().toLowerCase(Locale.ROOT);
        }
        if (isBlank(topic)) {
            return "generic";
        }
        if (topic.contains("旅游") || topic.contains("旅行") || topic.contains("打卡") || topic.contains("出游")) {
            return "travel";
        }
        if (topic.contains("美食") || topic.contains("好吃") || topic.contains("餐厅") || topic.contains("火锅")) {
            return "food";
        }
        if (topic.contains("拍照") || topic.contains("照片") || topic.contains("出片")) {
            return "photo";
        }
        return "generic";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String fallback(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
