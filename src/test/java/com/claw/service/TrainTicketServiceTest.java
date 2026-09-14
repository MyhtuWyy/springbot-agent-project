package com.claw.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainTicketServiceTest {

    @Test
    void rendersTrainResultsAsCompactMarkdownTable() throws Exception {
        TrainTicketService service = new TrainTicketService(
                Mockito.mock(AmapService.class),
                new CityResolver(),
                Mockito.mock(ConversationMemoryService.class)
        );
        Method method = TrainTicketService.class.getDeclaredMethod(
                "renderResponse", String.class, String.class, String.class, String.class, String.class
        );
        method.setAccessible(true);

        String result = (String) method.invoke(
                service,
                "洛阳龙门",
                "北京西",
                "2026-09-14",
                null,
                """
                        {"reason":"success","result":[{"train_no":"G358","departure_time":"11:21","arrival_time":"14:21","duration":"03:00","enable_booking":"Y","prices":[{"seat_name":"二等座","price":438,"num":"有"},{"seat_name":"一等座","price":694,"num":"14"},{"seat_name":"商务座","price":1354,"num":"11"}]}]}
                        """
        );

        assertTrue(result.contains("### 🚄 高铁票"));
        assertTrue(result.contains("| G358 | 11:21 | 14:21 | 03:00 | 438元（有）"));
        assertTrue(result.contains("可订"));
    }

    @Test
    void reportsEmptyTrainResultsWithoutInventingTrainDetails() throws Exception {
        TrainTicketService service = new TrainTicketService(
                Mockito.mock(AmapService.class),
                new CityResolver(),
                Mockito.mock(ConversationMemoryService.class)
        );
        Method method = TrainTicketService.class.getDeclaredMethod(
                "renderResponse", String.class, String.class, String.class, String.class, String.class
        );
        method.setAccessible(true);

        String result = (String) method.invoke(
                service,
                "洛阳龙门",
                "北京西",
                "2026-09-14",
                null,
                "{\"reason\":\"success\",\"result\":[],\"error_code\":0}"
        );

        assertTrue(result.contains("没有查询到对应的车次，建议查询其他的高铁票。"));
        assertTrue(!result.contains("G802"));
    }
}
