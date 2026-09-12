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
}
