package com.claw.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopywritingServiceTest {

    @Test
    void shouldReturnUsableMomentsCopyWithoutInternalParameters() {
        CopywritingService service = new CopywritingService();

        String result = service.generate("moments", "南京游玩", "南京", null, "travel", null, 1);

        assertTrue(result.startsWith("### 🌿 朋友圈文案\n\n> "));
        assertTrue(result.contains("南京"));
        assertTrue(result.contains("梧桐") || result.contains("金陵") || result.contains("老城"));
        assertTrue(result.contains("📍") || result.contains("✨") || result.contains("🍃"));
        assertFalse(result.contains("类型：moments"));
        assertFalse(result.contains("主题："));
        assertFalse(result.contains("生成一个"));
        assertFalse(result.contains("轻松自然"));
    }

    @Test
    void shouldFormatMultipleCopiesAsOneContinuousMarkdownList() {
        CopywritingService service = new CopywritingService();

        String result = service.generate("moments", "杭州游玩", "杭州", null, "travel", null, 3);

        assertTrue(result.contains("\n1. "));
        assertTrue(result.contains("\n2. "));
        assertTrue(result.contains("\n3. "));
        assertFalse(result.contains("\n\n2. "));
    }
}
