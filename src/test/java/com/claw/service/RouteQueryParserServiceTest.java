package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RouteQueryParserServiceTest {

    private final RouteQueryParserService parser = new RouteQueryParserService(new CityResolver());

    @Test
    void shouldParseRouteWithoutExplicitFromPrefix() {
        JSONObject args = parser.parse("上海虹桥到外滩怎么走");

        assertNotNull(args);
        assertEquals("上海虹桥", args.getString("origin"));
        assertEquals("外滩", args.getString("destination"));
        assertEquals("上海", args.getString("city"));
    }

    @Test
    void shouldStripRouteSuffixFromDestination() {
        JSONObject args = parser.parse("从上海虹桥到外滩怎么走");

        assertNotNull(args);
        assertEquals("上海虹桥", args.getString("origin"));
        assertEquals("外滩", args.getString("destination"));
        assertEquals("上海", args.getString("city"));
    }

    @Test
    void shouldStripQuoteCharactersFromParsedPlaces() {
        JSONObject args = parser.parse("从杭州东站到西湖怎么走”");

        assertNotNull(args);
        assertEquals("杭州东站", args.getString("origin"));
        assertEquals("西湖", args.getString("destination"));
        assertEquals("杭州", args.getString("city"));
    }
}
