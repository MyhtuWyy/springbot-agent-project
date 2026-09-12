package com.claw.service;

import com.claw.config.AliOcrProperties;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceVerifyOverrides;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvoiceTypeDetectionTest {

    @Test
    void electronicGeneralInvoiceWithoutCodeShouldBeTreatedAsDigitalGeneralInvoice() throws Exception {
        InvoiceRecognitionService service = new InvoiceRecognitionService(
                new AliOcrProperties(),
                new AliOcrInvoiceClient(new AliOcrProperties()),
                null
        );

        Map<String, String> rawKv = new LinkedHashMap<>();
        rawKv.put("发票名称", "电子发票（普通发票）");
        rawKv.put("发票号码", "26414000000099413341");
        rawKv.put("开票日期", "2026年07月30日");
        rawKv.put("金额", "97.03");
        rawKv.put("税额", "0.97");
        rawKv.put("价税合计", "98.00");

        Method method = InvoiceRecognitionService.class.getDeclaredMethod(
                "mapInvoiceFields",
                Map.class,
                InvoiceVerifyOverrides.class
        );
        method.setAccessible(true);
        InvoiceFieldData fieldData = (InvoiceFieldData) method.invoke(service, rawKv, new InvoiceVerifyOverrides());

        assertEquals("32", fieldData.getInvoiceTypeCode());
        assertNull(fieldData.getInvoiceCode());
        assertNull(fieldData.getVerifyCode());
    }
}
