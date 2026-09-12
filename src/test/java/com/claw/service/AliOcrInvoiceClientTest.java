package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import com.claw.config.AliOcrProperties;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceVerifyOverrides;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliOcrInvoiceClientTest {

    @Test
    void digitalInvoiceShouldNotRequireInvoiceCode() {
        AliOcrInvoiceClient client = new AliOcrInvoiceClient(new AliOcrProperties());
        InvoiceFieldData fieldData = new InvoiceFieldData();
        fieldData.setInvoiceTypeCode("32");
        fieldData.setInvoiceNo("26414000000099413341");
        fieldData.setInvoiceDate("20260730");
        fieldData.setInvoiceSum("98.00");

        List<String> missing = client.findMissingVerifyFields(fieldData, new InvoiceVerifyOverrides());

        assertTrue(missing.isEmpty());
    }

    @Test
    void paperGeneralInvoiceShouldRequireVerifyCodeInsteadOfInvoiceSum() {
        AliOcrInvoiceClient client = new AliOcrInvoiceClient(new AliOcrProperties());
        InvoiceFieldData fieldData = new InvoiceFieldData();
        fieldData.setInvoiceTypeCode("04");
        fieldData.setInvoiceCode("123456789012");
        fieldData.setInvoiceNo("12345678");
        fieldData.setInvoiceDate("20260803");

        List<String> missing = client.findMissingVerifyFields(fieldData, new InvoiceVerifyOverrides());

        assertEquals(List.of("verifyCode"), missing);
    }

    @Test
    void nestedVerifyEnvelopeShouldBeParsed() throws Exception {
        AliOcrInvoiceClient client = new AliOcrInvoiceClient(new AliOcrProperties());
        Method method = AliOcrInvoiceClient.class.getDeclaredMethod("toBodyJson", Object.class);
        method.setAccessible(true);

        JSONObject parsed = (JSONObject) method.invoke(client, new FakeResponse(new FakeBody(
                "{\"code\":\"001\",\"msg\":\"success\",\"data\":{\"passed\":true}}",
                "request-1"
        )));

        assertEquals("001", parsed.getString("code"));
        assertEquals("request-1", parsed.getString("requestId"));
        assertTrue(parsed.getJSONObject("data").getBooleanValue("passed"));
    }

    @Test
    void nonSuccessBusinessCodeShouldNotPassVerification() throws Exception {
        AliOcrInvoiceClient client = new AliOcrInvoiceClient(new AliOcrProperties());
        Method method = AliOcrInvoiceClient.class.getDeclaredMethod("isVerifyPassed", JSONObject.class, String.class);
        method.setAccessible(true);

        JSONObject body = new JSONObject();
        body.put("code", "006");
        body.put("msg", "发票不存在");

        boolean passed = (boolean) method.invoke(client, body, "006");

        assertFalse(passed);
    }

    public static class FakeResponse {
        private final FakeBody body;

        public FakeResponse(FakeBody body) {
            this.body = body;
        }

        public FakeBody getBody() {
            return body;
        }
    }

    public static class FakeBody {
        private final String data;
        private final String requestId;

        public FakeBody(String data, String requestId) {
            this.data = data;
            this.requestId = requestId;
        }

        public String getData() {
            return data;
        }

        public String getRequestId() {
            return requestId;
        }
    }
}
