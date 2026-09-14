package com.claw.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ToolIntentRouterTest {

    @Test
    void shouldRouteJobMatchUrlBeforeLogisticsForDesktopSession() {
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        Mockito.when(memoryService.getTravelContext(Mockito.anyString())).thenReturn(new com.alibaba.fastjson2.JSONObject());

        ToolIntentRouter router = new ToolIntentRouter(new CityResolver(), memoryService);
        ToolIntentRouter.ToolRoute route = router.match(
                "desktop:test-session",
                "https://www.liepin.com/job/1973932637.shtml 这个是岗位链接，查一下岗位匹配度"
        );

        assertNotNull(route);
        assertEquals("job_match_url", route.functionName());
        assertEquals("liepin", route.arguments().getString("platform"));
        assertNotNull(route.arguments().getLong("userId"));
        assertEquals("https://www.liepin.com/job/1973932637.shtml", route.arguments().getString("jobUrl"));
    }

    @Test
    void shouldExtractCleanMomentsArgumentsFromNaturalRequest() {
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        Mockito.when(memoryService.getTravelContext(Mockito.anyString())).thenReturn(new com.alibaba.fastjson2.JSONObject());

        ToolIntentRouter router = new ToolIntentRouter(new CityResolver(), memoryService);
        ToolIntentRouter.ToolRoute route = router.match("desktop:test-session", "我在南京玩，给我生成一个朋友圈文案");

        assertNotNull(route);
        assertEquals("generate_copywriting", route.functionName());
        assertEquals("moments", route.arguments().getString("type"));
        assertEquals("南京游玩", route.arguments().getString("topic"));
        assertEquals("南京", route.arguments().getString("city"));
        assertEquals("travel", route.arguments().getString("scene"));
        assertEquals(1, route.arguments().getIntValue("count"));
    }

    @Test
    void shouldParseChineseTripDaysAndCleanNaturalOrigin() {
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        Mockito.when(memoryService.getTravelContext(Mockito.anyString())).thenReturn(new com.alibaba.fastjson2.JSONObject());

        ToolIntentRouter router = new ToolIntentRouter(new CityResolver(), memoryService);
        ToolIntentRouter.ToolRoute route = router.match(
                "desktop:test-session",
                "我今天从洛阳出发，去西安旅游，玩三天"
        );

        assertNotNull(route);
        assertEquals("plan_travel", route.functionName());
        assertEquals("洛阳", route.arguments().getString("origin"));
        assertEquals("西安", route.arguments().getString("destination"));
        assertEquals(3, route.arguments().getIntValue("trip_days"));
    }

    @Test
    void shouldKeepFocusedTrainTicketReplyAsToolMarkdown() {
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        Mockito.when(memoryService.getTravelContext(Mockito.anyString())).thenReturn(new com.alibaba.fastjson2.JSONObject());

        ToolIntentRouter router = new ToolIntentRouter(new CityResolver(), memoryService);
        ToolIntentRouter.ToolRoute route = router.match(
                "desktop:test-session",
                "查询2026-09-14从洛阳龙门到北京西的高铁票"
        );

        assertNotNull(route);
        assertEquals("query_train_tickets", route.functionName());
        assertEquals(ToolIntentRouter.RouteMode.FORCE_TOOL, route.routeMode());
        assertEquals(false, route.renderWithModel());
        assertEquals("洛阳龙门", route.arguments().getString("origin"));
        assertEquals("北京西", route.arguments().getString("destination"));
    }
}
