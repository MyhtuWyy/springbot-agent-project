package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import com.claw.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private static final String NEWS_API_URL = "https://apis.tianapi.com/generalnews/index";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_LATEST_PAGES = 3;
    private static final int MAX_TOPIC_PAGES = 6;
    private static final int LATEST_RENDER_LIMIT = 8;
    private static final int TOPIC_RENDER_LIMIT = 8;
    private static final int TARGET_TOPIC_RESULTS = 12;
    private static final int CODE_SUCCESS = 200;
    private static final int CODE_EMPTY = 250;
    private static final int CODE_RATE_LIMIT = 130;

    private static final Map<String, List<String>> TOPIC_SYNONYMS = buildTopicSynonyms();

    public String searchNews(String keyword) {
        String apiKey = ConfigUtil.getTianApiKey();
        if (apiKey.isBlank()) {
            return "新闻 API 密钥未配置，请检查 application.yml 中的 tianapi.key。";
        }

        String cleanKeyword = sanitizeKeyword(keyword);
        try {
            if (cleanKeyword.isBlank()) {
                List<JSONObject> latestArticles = fetchLatestArticles(apiKey);
                if (latestArticles == null) {
                    return "新闻接口调用过于频繁，请稍后再试。";
                }
                return latestArticles.isEmpty()
                        ? "暂时没有查到最新新闻。"
                        : formatArticles("最新新闻", latestArticles, LATEST_RENDER_LIMIT);
            }

            List<JSONObject> topicArticles = searchTopicArticles(apiKey, cleanKeyword);
            if (topicArticles == null) {
                return "新闻接口调用过于频繁，请稍后再试。";
            }
            if (!topicArticles.isEmpty()) {
                return formatArticles(cleanKeyword + " 相关新闻", topicArticles, TOPIC_RENDER_LIMIT);
            }

            List<JSONObject> fallbackArticles = fetchLatestArticles(apiKey);
            if (fallbackArticles == null) {
                return "暂时没有查到和“" + cleanKeyword + "”相关的新闻，且新闻接口当前触发频控，请稍后再试。";
            }
            if (fallbackArticles.isEmpty()) {
                return "暂时没有查到和“" + cleanKeyword + "”相关的新闻。";
            }

            return "暂时没有查到和“" + cleanKeyword + "”直接相关的历史新闻。下面先给你最近的新闻作参考：\n"
                    + formatArticles("最新新闻", fallbackArticles, LATEST_RENDER_LIMIT);
        } catch (Exception e) {
            log.error("新闻查询异常, keyword={}", cleanKeyword, e);
            return "新闻接口请求出错：" + e.getMessage();
        }
    }

    private List<JSONObject> fetchLatestArticles(String apiKey) throws Exception {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_LATEST_PAGES; page++) {
            NewsPageResult result = requestNewsPage(apiKey, page, null);
            if (result.rateLimited()) {
                return null;
            }
            mergeArticles(merged, result.articles());
            if (merged.size() >= LATEST_RENDER_LIMIT) {
                break;
            }
            if (result.empty()) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<JSONObject> searchTopicArticles(String apiKey, String keyword) throws Exception {
        List<String> queryTerms = expandQueryTerms(keyword);
        LinkedHashMap<String, ScoredArticle> scored = new LinkedHashMap<>();

        for (String term : queryTerms) {
            for (int page = 1; page <= MAX_TOPIC_PAGES; page++) {
                NewsPageResult result = requestNewsPage(apiKey, page, term);
                if (result.rateLimited()) {
                    return null;
                }
                JSONArray pageArticles = result.articles();
                if (pageArticles == null || pageArticles.isEmpty()) {
                    if (page > 1 || result.empty()) {
                        break;
                    }
                    continue;
                }
                scoreArticles(scored, pageArticles, keyword, term);
                if (hasEnoughStrongMatches(scored)) {
                    return collectTopArticles(scored, TOPIC_RENDER_LIMIT);
                }
            }
        }

        if (scored.isEmpty()) {
            LinkedHashMap<String, JSONObject> fallbackPool = new LinkedHashMap<>();
            for (int page = 1; page <= MAX_TOPIC_PAGES; page++) {
                NewsPageResult result = requestNewsPage(apiKey, page, null);
                if (result.rateLimited()) {
                    return null;
                }
                mergeArticles(fallbackPool, result.articles());
                if (result.empty()) {
                    break;
                }
            }
            scoreArticles(scored, new JSONArray(new ArrayList<>(fallbackPool.values())), keyword, keyword);
        }

        return collectTopArticles(scored, TOPIC_RENDER_LIMIT);
    }

    private NewsPageResult requestNewsPage(String apiKey, int page, String keyword) throws Exception {
        StringBuilder url = new StringBuilder(NEWS_API_URL)
                .append("?key=").append(encode(apiKey))
                .append("&num=").append(PAGE_SIZE)
                .append("&page=").append(Math.max(1, page));
        if (keyword != null && !keyword.isBlank()) {
            url.append("&word=").append(encode(keyword));
        }

        JSONObject response = JSON.parseObject(HttpUtil.doGet(url.toString(), false));
        if (response == null) {
            return new NewsPageResult(-1, null);
        }

        int code = parseCode(response.getString("code"));
        if (code == CODE_RATE_LIMIT) {
            log.warn("新闻接口触发频控, keyword={}, page={}", keyword, page);
            return new NewsPageResult(code, null);
        }
        if (code != CODE_SUCCESS && code != CODE_EMPTY) {
            log.warn("新闻接口返回非成功状态, code={}, keyword={}, page={}", code, keyword, page);
            return new NewsPageResult(code, null);
        }
        return new NewsPageResult(code, extractArticles(response));
    }

    private JSONArray extractArticles(JSONObject response) {
        JSONObject result = response.getJSONObject("result");
        if (result == null) {
            return null;
        }
        JSONArray list = result.getJSONArray("list");
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return result.getJSONArray("newslist");
    }

    private void mergeArticles(Map<String, JSONObject> target, JSONArray source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }

        for (int i = 0; i < source.size(); i++) {
            JSONObject article = source.getJSONObject(i);
            if (article == null) {
                continue;
            }
            String dedupeKey = articleKey(article);
            if (!dedupeKey.isBlank()) {
                target.putIfAbsent(dedupeKey, article);
            }
        }
    }

    private void scoreArticles(Map<String, ScoredArticle> target, JSONArray articles, String keyword, String matchedTerm) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        String normalizedKeyword = normalize(keyword);
        String normalizedTerm = normalize(matchedTerm);
        for (int i = 0; i < articles.size(); i++) {
            JSONObject article = articles.getJSONObject(i);
            if (article == null) {
                continue;
            }

            int score = scoreArticle(article, normalizedKeyword, normalizedTerm);
            if (score <= 0) {
                continue;
            }

            String key = articleKey(article);
            ScoredArticle existing = target.get(key);
            if (existing == null || score > existing.score()) {
                target.put(key, new ScoredArticle(article, score));
            }
        }
    }

    private int scoreArticle(JSONObject article, String normalizedKeyword, String normalizedTerm) {
        String title = normalize(article.getString("title"));
        String desc = normalize(firstNonBlank(article.getString("description"), article.getString("digest")));
        String tags = normalize(firstNonBlank(article.getString("keywords"), article.getString("keyword")));
        String source = normalize(firstNonBlank(article.getString("src"), article.getString("source")));

        int score = 0;
        if (!normalizedKeyword.isBlank()) {
            score += weightedMatch(title, normalizedKeyword, 20, 8);
            score += weightedMatch(desc, normalizedKeyword, 8, 3);
            score += weightedMatch(tags, normalizedKeyword, 10, 4);
        }
        if (!normalizedTerm.isBlank() && !normalizedTerm.equals(normalizedKeyword)) {
            score += weightedMatch(title, normalizedTerm, 12, 4);
            score += weightedMatch(desc, normalizedTerm, 5, 2);
            score += weightedMatch(tags, normalizedTerm, 6, 2);
        }

        if (containsFinanceContext(title) || containsFinanceContext(desc) || containsFinanceContext(tags) || containsFinanceContext(source)) {
            score += 4;
        }
        return score;
    }

    private int weightedMatch(String text, String phrase, int exactScore, int tokenScore) {
        if (text == null || text.isBlank() || phrase == null || phrase.isBlank()) {
            return 0;
        }
        int score = text.contains(phrase) ? exactScore : 0;
        for (String token : tokenize(phrase)) {
            if (token.length() >= 2 && text.contains(token)) {
                score += tokenScore;
            }
        }
        return score;
    }

    private boolean containsFinanceContext(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("股票")
                || text.contains("股市")
                || text.contains("a股")
                || text.contains("港股")
                || text.contains("美股")
                || text.contains("证券")
                || text.contains("财经")
                || text.contains("金融")
                || text.contains("基金")
                || text.contains("上市");
    }

    private boolean hasEnoughStrongMatches(Map<String, ScoredArticle> scored) {
        if (scored.size() < TARGET_TOPIC_RESULTS) {
            return false;
        }
        long strongCount = scored.values().stream().filter(item -> item.score() >= 20).count();
        return strongCount >= 6;
    }

    private List<JSONObject> collectTopArticles(Map<String, ScoredArticle> scored, int limit) {
        return scored.values().stream()
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .limit(limit)
                .map(ScoredArticle::article)
                .toList();
    }

    private String formatArticles(String title, List<JSONObject> articles, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(title).append("]\n");

        int renderCount = Math.min(limit, articles.size());
        for (int i = 0; i < renderCount; i++) {
            JSONObject article = articles.get(i);
            String itemTitle = firstNonBlank(article.getString("title"), "无标题");
            String source = firstNonBlank(article.getString("src"), article.getString("source"), "未知来源");
            String time = firstNonBlank(article.getString("ctime"), article.getString("time"), "");
            String desc = firstNonBlank(article.getString("description"), article.getString("digest"), "");
            String link = firstNonBlank(article.getString("url"), article.getString("weburl"), "");

            sb.append(i + 1).append(". ").append(itemTitle).append("\n");
            if (!desc.isBlank()) {
                sb.append("   ").append(truncate(desc, 120)).append("\n");
            }
            sb.append("   来源: ").append(source);
            if (!time.isBlank()) {
                sb.append(" | ").append(time);
            }
            if (!link.isBlank()) {
                sb.append("\n   链接: ").append(link);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<String> expandQueryTerms(String keyword) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String clean = sanitizeKeyword(keyword);
        if (clean.isBlank()) {
            return List.of();
        }

        terms.add(clean);
        for (String token : tokenize(clean)) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }

        for (Map.Entry<String, List<String>> entry : TOPIC_SYNONYMS.entrySet()) {
            String normalizedKey = entry.getKey();
            if (normalize(clean).contains(normalizedKey) || terms.contains(normalizedKey)) {
                terms.addAll(entry.getValue());
            }
        }
        return new ArrayList<>(terms);
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = normalize(text)
                .replace('，', ' ')
                .replace('、', ' ')
                .replace('/', ' ')
                .replace('-', ' ');
        String[] parts = normalized.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (!token.isBlank() && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String sanitizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim()
                .replace("查询一下", " ")
                .replace("查询", " ")
                .replace("查一下", " ")
                .replace("查", " ")
                .replace("看一下", " ")
                .replace("看一看", " ")
                .replace("看下", " ")
                .replace("帮我", " ")
                .replace("给我", " ")
                .replace("一下", " ")
                .replace("今天的", " ")
                .replace("今天", " ")
                .replace("今日", " ")
                .replace("最新的", " ")
                .replace("最新", " ")
                .replace("新闻", " ")
                .replace("资讯", " ")
                .replace("消息", " ")
                .replace("头条", " ")
                .replace("热点", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String articleKey(JSONObject article) {
        String url = firstNonBlank(article.getString("url"), article.getString("weburl"));
        if (!url.isBlank()) {
            return url.trim();
        }
        return normalize(firstNonBlank(article.getString("title"), "")) + "|"
                + normalize(firstNonBlank(article.getString("ctime"), article.getString("time"), ""));
    }

    private int parseCode(String codeText) {
        try {
            return codeText == null ? -1 : Integer.parseInt(codeText.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, List<String>> buildTopicSynonyms() {
        Map<String, List<String>> synonyms = new LinkedHashMap<>();
        synonyms.put("股票", List.of("股票", "股市", "a股", "港股", "美股", "证券", "上市公司", "财经", "金融"));
        synonyms.put("股市", List.of("股票", "股市", "a股", "港股", "美股", "证券", "财经"));
        synonyms.put("财经", List.of("财经", "金融", "证券", "基金", "股票", "股市", "上市公司"));
        synonyms.put("基金", List.of("基金", "理财", "金融", "财经", "证券"));
        synonyms.put("ai", List.of("ai", "人工智能", "大模型", "算法", "算力", "芯片"));
        synonyms.put("人工智能", List.of("人工智能", "ai", "大模型", "算法", "算力", "芯片"));
        synonyms.put("汽车", List.of("汽车", "新能源车", "车企", "智能驾驶"));
        synonyms.put("楼市", List.of("楼市", "房地产", "房产", "地产"));
        synonyms.put("房地产", List.of("房地产", "楼市", "房产", "地产"));
        return synonyms;
    }

    private record ScoredArticle(JSONObject article, int score) {
    }

    private record NewsPageResult(int code, JSONArray articles) {
        private boolean empty() {
            return code == CODE_EMPTY || articles == null || articles.isEmpty();
        }

        private boolean rateLimited() {
            return code == CODE_RATE_LIMIT;
        }
    }
}
