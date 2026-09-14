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

    @Test
    void enforcesFactualTicketSectionInTravelSummary() throws Exception {
        BailianService service = new BailianService(Mockito.mock(ToolRegistry.class));
        Method method = BailianService.class.getDeclaredMethod(
                "enforceTicketSection", String.class, String.class
        );
        method.setAccessible(true);

        String reply = """
                1. 天气情况
                晴天

                2. 合适的高铁票
                G802，约4小时

                3. 攻略规划
                第一天去景点
                """;
        String factual = """
                ### 🚄 高铁票

                **洛阳龙门 → 北京西** · 2026-09-14

                | 车次 | 出发 | 到达 |
                |---|---:|---:|
                | G358 | 11:21 | 14:21 |
                """;

        String result = (String) method.invoke(service, reply, factual);

        assertEquals(true, result.contains("G358"));
        assertEquals(false, result.contains("G802"));
        assertEquals(true, result.contains("3. 攻略规划"));
    }
}
