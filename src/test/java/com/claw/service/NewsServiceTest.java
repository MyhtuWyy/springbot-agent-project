package com.claw.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewsServiceTest {

    @Test
    void sanitizeKeywordRemovesNewsQueryFillers() throws Exception {
        NewsService service = new NewsService();
        Method method = NewsService.class.getDeclaredMethod("sanitizeKeyword", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "查询一下今天的新闻");
        assertEquals("", result);
    }

    @Test
    void sanitizeKeywordKeepsRealTopic() throws Exception {
        NewsService service = new NewsService();
        Method method = NewsService.class.getDeclaredMethod("sanitizeKeyword", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "查询一下今天的AI新闻");
        assertEquals("AI", result);
    }
}
