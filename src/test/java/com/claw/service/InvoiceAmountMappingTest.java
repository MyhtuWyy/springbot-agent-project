package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import com.claw.config.AliOcrProperties;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceVerifyOverrides;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceAmountMappingTest {

    @Test
    void structuredTotalAmountShouldWinOverQrAmount() throws Exception {
        AliOcrInvoiceClient client = new AliOcrInvoiceClient(new AliOcrProperties());
        JSONObject data = new JSONObject();
        data.put("invoiceAmountPreTax", "97.03");
        data.put("invoiceTax", "0.97");
        data.put("totalAmount", "98.00");
        data.put("invoiceDate", "20260730");
        data.put("invoiceNo", "2641400000");
        data.put("invoiceCode", "123456789012");

        JSONObject qrCode = new JSONObject();
        qrCode.put("data", "01,10,123456789012,2641400000,97.03,20260730,32,ABCDEF");
        data.put("codes", List.of(qrCode));

        Method method = AliOcrInvoiceClient.class.getDeclaredMethod("extractKvData", JSONObject.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> kv = (Map<String, String>) method.invoke(client, data);

        assertEquals("98.00", kv.get("价税合计"));
    }

    @Test
    void inconsistentFallbackShouldUseAmountPlusTaxWhenStructuredTotalMissing() throws Exception {
        InvoiceRecognitionService service = new InvoiceRecognitionService(
                new AliOcrProperties(),
                new AliOcrInvoiceClient(new AliOcrProperties()),
                null
        );

        Map<String, String> rawKv = new LinkedHashMap<>();
        rawKv.put("金额", "97.03");
        rawKv.put("税额", "0.97");
        rawKv.put("价税合计", "32");

        Method method = InvoiceRecognitionService.class.getDeclaredMethod(
                "mapInvoiceFields",
                Map.class,
                InvoiceVerifyOverrides.class
        );
        method.setAccessible(true);
        InvoiceFieldData fieldData = (InvoiceFieldData) method.invoke(service, rawKv, new InvoiceVerifyOverrides());

        assertEquals("98.00", fieldData.getInvoiceSum());
    }

    @Test
    void manualOverrideShouldStillWin() throws Exception {
        InvoiceRecognitionService service = new InvoiceRecognitionService(
                new AliOcrProperties(),
                new AliOcrInvoiceClient(new AliOcrProperties()),
                null
        );

        Map<String, String> rawKv = new LinkedHashMap<>();
        rawKv.put("金额", "97.03");
        rawKv.put("税额", "0.97");
        rawKv.put("价税合计", "32");

        InvoiceVerifyOverrides overrides = new InvoiceVerifyOverrides();
        overrides.setInvoiceSum("105.00");

        Method method = InvoiceRecognitionService.class.getDeclaredMethod(
                "mapInvoiceFields",
                Map.class,
                InvoiceVerifyOverrides.class
        );
        method.setAccessible(true);
        InvoiceFieldData fieldData = (InvoiceFieldData) method.invoke(service, rawKv, overrides);

        assertEquals("105.00", fieldData.getInvoiceSum());
    }
}
