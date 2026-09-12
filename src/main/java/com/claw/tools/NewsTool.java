package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.NewsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NewsTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(NewsTool.class);

    private final NewsService newsService;

    public NewsTool(NewsService newsService) {
        this.newsService = newsService;
    }

    @Override
    public String name() {
        return "search_news";
    }

    @Override
    public String description() {
        return "查询最新新闻或某个主题的相关新闻。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONObject keywordParam = new JSONObject();
        keywordParam.put("type", "string");
        keywordParam.put("description", "新闻搜索关键词。留空查最新新闻；填具体主题查相关新闻，例如科技、AI、杭州、体育。");
        properties.put("keyword", keywordParam);

        parameters.put("properties", properties);
        return parameters;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            return newsService.searchNews(args == null ? "" : args.getString("keyword"));
        } catch (Exception e) {
            log.error("新闻工具执行异常", e);
            return "新闻查询出错：" + e.getMessage();
        }
    }
}
