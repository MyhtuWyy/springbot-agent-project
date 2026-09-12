package com.claw.service;

import com.claw.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BailianServiceTest {

    @Test
    void formatsToolJsonToReadableText() throws Exception {
        BailianService service = new BailianService(Mockito.mock(ToolRegistry.class));
        Method method = BailianService.class.getDeclaredMethod("formatToolResultForDisplay", String.class);
        method.setAccessible(true);

        String json = """
                {"success":true,"skillName":"get_weather","message":"杭州 2026-08-04 天气：Clear","data":{"output":"ignored"},"durationMs":2700,"timeout":false}
                """;

        String result = (String) method.invoke(service, json);
        assertEquals("杭州 2026-08-04 天气：Clear", result);
    }

    @Test
    void fallsBackToPlainText() throws Exception {
        BailianService service = new BailianService(Mockito.mock(ToolRegistry.class));
        Method method = BailianService.class.getDeclaredMethod("formatToolResultForDisplay", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "plain text");
        assertEquals("plain text", result);
    }

    @Test
    void parseFileResultIsNotRewrittenByModel() throws Exception {
        BailianService service = new BailianService(Mockito.mock(ToolRegistry.class));
        Method method = BailianService.class.getDeclaredMethod(
                "renderForcedToolResult",
                String.class,
                String.class,
                String.class,
                boolean.class
        );
        method.setAccessible(true);

        String toolResult = """
                【文件问答】课程表.pdf
                问题：第一周有哪些课程
                原文相关内容：
                1. 第一周线下讲座
                2. 7.13 周一：上午李帅鸿、下午樊恒两场讲座
                """;

        String result = (String) method.invoke(service, "第一周有哪些课程", "parse_file", toolResult, true);
        assertEquals(toolResult.trim(), result);
    }

    @Test
    void parseFileStreamResultIsNotRewrittenByModel() throws Exception {
        BailianService service = new BailianService(Mockito.mock(ToolRegistry.class));
        Method method = BailianService.class.getDeclaredMethod(
                "streamForcedToolResult",
                String.class,
                String.class,
                String.class,
                java.util.function.Consumer.class
        );
        method.setAccessible(true);

        String toolResult = """
                【文件问答】课程表.pdf
                问题：第一周有哪些课程
                原文相关内容：
                1. 第一周线下讲座
                2. 7.13 周一：上午李帅鸿、下午樊恒两场讲座
                """;
        AtomicReference<String> delta = new AtomicReference<>();

        String result = (String) method.invoke(service, "第一周有哪些课程", "parse_file", toolResult, (java.util.function.Consumer<String>) delta::set);
        assertEquals(toolResult.trim(), result);
        assertEquals(toolResult.trim(), delta.get());
    }
}
