package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.claw.config.AliOcrProperties;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceVerifyOverrides;
import com.claw.dto.InvoiceVerifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AliOcrInvoiceClient {
    private static final Logger log = LoggerFactory.getLogger(AliOcrInvoiceClient.class);
    private static final String HANGZHOU_ENDPOINT = "ocr-api.cn-hangzhou.aliyuncs.com";
    private static final String SDK_CLIENT_CLASS = "com.aliyun.ocr_api20210707.Client";
    private static final String SDK_REQUEST_PACKAGE = "com.aliyun.ocr_api20210707.models.";
    private static final String CONFIG_CLASS = "com.aliyun.teaopenapi.models.Config";
    private static final String RUNTIME_OPTIONS_CLASS = "com.aliyun.teautil.models.RuntimeOptions";
    private static final Set<String> VERIFY_SUCCESS_CODES = Set.of("001", "000000");
    private static final Set<String> VERIFY_FAILED_CODES = Set.of("006", "009", "1005");
    private static final String VERIFY_PASS = "\u771f\u5b9e\u6709\u6548\u53d1\u7968";
    private static final String VERIFY_FAIL = "\u4fe1\u606f\u5f02\u5e38\uff0c\u7591\u4f3c\u865a\u5047\u53d1\u7968";
    private static final String VERIFY_INSUFFICIENT = "\u5173\u952e\u5b57\u6bb5\u4e0d\u8db3\u65e0\u6cd5\u6838\u9a8c";
    private static final AtomicBoolean SET_KEYS_UNSUPPORTED_LOGGED = new AtomicBoolean(false);

    private final AliOcrProperties properties;

    public AliOcrInvoiceClient(AliOcrProperties properties) {
        this.properties = properties;
    }

    public OcrResult recognizeTicket(byte[] imageBytes, List<String> keys) {
        ensureConfigured();
        try {
            Object request = newRequest("RecognizeInvoiceRequest", "RecognizeVATInvoiceRequest", "RecognizeGeneralStructureRequest");
            invokeCompatibleSetter(request, "setBody", new ByteArrayInputStream(imageBytes));
            boolean keysApplied = false;
            if (keys != null && !keys.isEmpty()) {
                keysApplied = invokeCompatibleSetterIfExists(request, "setKeys", keys);
            }
            if (keys != null && !keys.isEmpty() && !keysApplied) {
                logSetKeysFallback(request.getClass(), keys);
            }
            Object response = invokeClient(
                    request,
                    List.of("recognizeInvoiceWithOptions", "recognizeVATInvoiceWithOptions", "recognizeGeneralStructureWithOptions"),
                    List.of("recognizeInvoice", "recognizeVATInvoice", "recognizeGeneralStructure")
            );
            JSONObject body = toBodyJson(response);
            JSONObject data = body.getJSONObject("data");
            if (body.containsKey("code") && !isBlank(body.getString("code")) && !"200".equals(body.getString("code"))) {
                throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.API_ERROR, stringValue(body, "message"));
            }
            Map<String, String> kvData = extractKvData(data);
            log.info("Ali OCR recognize success: requestId={}, extractedKeys={}", body.getString("requestId"), kvData.keySet());
            return new OcrResult(body.getString("requestId"), body.toJSONString(), kvData);
        } catch (AliOcrInvoiceException e) {
            throw e;
        } catch (Exception e) {
            throw mapSdkException("OCR invoice recognize failed", e);
        }
    }

    public InvoiceVerifyResult verifyInvoice(InvoiceFieldData fieldData, InvoiceVerifyOverrides overrides) {
        ensureConfigured();
        InvoiceFieldData merged = mergeOverrides(fieldData, overrides);
        VerifyPayload payload = buildVerifyPayload(merged);
        List<String> missing = payload.missingFields();
        if (!missing.isEmpty()) {
            return buildInsufficientVerifyResult(missing, payload);
        }

        try {
            Object request = newRequest("VerifyVATInvoiceRequest");
            setIfPresent(request, "setInvoiceCode", payload.invoiceCode());
            setIfPresent(request, "setInvoiceNo", payload.invoiceNo());
            setIfPresent(request, "setInvoiceDate", payload.invoiceDate());
            setIfPresent(request, "setInvoiceSum", payload.invoiceSum());
            if (payload.invoiceKind() != null) {
                invokeIfExists(request, "setInvoiceKind", Integer.class, payload.invoiceKind());
            }
            if (!isBlank(payload.verifyCode())) {
                setIfPresent(request, "setVerifyCode", payload.verifyCode());
            }

            Object response = invokeClient(request, List.of("verifyVATInvoiceWithOptions"), List.of("verifyVATInvoice"));
            JSONObject body = toBodyJson(response);
            String code = firstNonBlank(stringValue(body, "code"), stringValue(body, "Code"));
            String msg = firstNonBlank(stringValue(body, "msg"), stringValue(body, "message"));

            InvoiceVerifyResult result = new InvoiceVerifyResult();
            result.setAttempted(true);
            result.setRequestId(body.getString("requestId"));
            result.setRawResponse(body.toJSONString());
            Map<String, String> details = flattenVerifyBody(body);
            details.putIfAbsent("request.invoiceCode", safe(payload.invoiceCode()));
            details.putIfAbsent("request.invoiceNo", safe(payload.invoiceNo()));
            details.putIfAbsent("request.invoiceDate", safe(payload.invoiceDate()));
            details.putIfAbsent("request.invoiceSum", safe(payload.invoiceSum()));
            details.putIfAbsent("request.verifyCode", safe(payload.verifyCode()));
            details.putIfAbsent("request.invoiceTypeCode", safe(payload.invoiceTypeCode()));
            details.putIfAbsent("request.invoiceKind", payload.invoiceKind() == null ? "" : String.valueOf(payload.invoiceKind()));
            result.setVerifyDetails(details);

            boolean passed = isVerifyPassed(body, code);
            result.setPassed(passed);
            result.setStatus(resolveVerifyStatus(code, passed));
            result.setMessage(resolveVerifyMessage(code, msg, passed));
            return result;
        } catch (AliOcrInvoiceException e) {
            throw e;
        } catch (Exception e) {
            throw mapSdkException("OCR invoice verify failed", e);
        }
    }

    public List<String> findMissingVerifyFields(InvoiceFieldData fieldData, InvoiceVerifyOverrides overrides) {
        InvoiceFieldData merged = mergeOverrides(fieldData, overrides);
        return buildVerifyPayload(merged).missingFields();
    }

    private InvoiceVerifyResult buildInsufficientVerifyResult(List<String> missing, VerifyPayload payload) {
        InvoiceVerifyResult result = new InvoiceVerifyResult();
        result.setAttempted(false);
        result.setPassed(false);
        result.setStatus("INSUFFICIENT");
        result.setMessage(VERIFY_INSUFFICIENT);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("missingFields", String.join(",", missing));
        details.put("invoiceCode", safe(payload.invoiceCode()));
        details.put("invoiceNo", safe(payload.invoiceNo()));
        details.put("invoiceDate", safe(payload.invoiceDate()));
        details.put("invoiceSum", safe(payload.invoiceSum()));
        details.put("verifyCode", safe(payload.verifyCode()));
        details.put("invoiceTypeCode", safe(payload.invoiceTypeCode()));
        details.put("invoiceKind", payload.invoiceKind() == null ? "" : String.valueOf(payload.invoiceKind()));
        result.setVerifyDetails(details);
        return result;
    }

    private boolean isPaperInvoice(InvoiceFieldData fieldData) {
        if (fieldData == null) {
            return true;
        }
        String title = normalize(fieldData.getInvoiceTitle());
        if (containsElectronicInvoiceHint(title)) {
            return false;
        }
        Map<String, String> extractedFields = fieldData.getExtractedFields();
        if (extractedFields == null || extractedFields.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : extractedFields.entrySet()) {
            String key = normalize(entry.getKey());
            String value = normalize(entry.getValue());
            if ((key.contains("\u53d1\u7968\u7c7b\u578b") || key.contains("\u53d1\u7968\u540d\u79f0") || key.contains("\u7968\u636e\u540d\u79f0"))
                    && containsElectronicInvoiceHint(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsElectronicInvoiceHint(String value) {
        return value.contains("\u7535\u5b50\u53d1\u7968")
                || value.contains("\u6570\u7535")
                || value.contains("\u5168\u7535");
    }

    private void ensureConfigured() {
        if (!properties.isEnabled()) {
            throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.CONFIG_ERROR, "\u963f\u91cc\u4e91OCR\u529f\u80fd\u672a\u542f\u7528\uff0c\u8bf7\u68c0\u67e5 aliyun.ocr.enabled");
        }
        if (isBlank(properties.getAccessKeyId()) || isBlank(properties.getAccessKeySecret())) {
            throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.CONFIG_ERROR, "\u963f\u91cc\u4e91OCR AccessKey \u672a\u914d\u7f6e\u5b8c\u6574");
        }
    }

    private Object buildClient() throws Exception {
        Class<?> configClass = Class.forName(CONFIG_CLASS);
        Object config = configClass.getDeclaredConstructor().newInstance();
        invokeSetter(config, "setAccessKeyId", String.class, properties.getAccessKeyId());
        invokeSetter(config, "setAccessKeySecret", String.class, properties.getAccessKeySecret());
        invokeSetter(config, "setEndpoint", String.class, isBlank(properties.getEndpoint()) ? HANGZHOU_ENDPOINT : properties.getEndpoint());
        Class<?> clientClass = Class.forName(SDK_CLIENT_CLASS);
        return clientClass.getDeclaredConstructor(configClass).newInstance(config);
    }

    private Object buildRuntimeOptions() throws Exception {
        Class<?> runtimeClass = Class.forName(RUNTIME_OPTIONS_CLASS);
        Object runtime = runtimeClass.getDeclaredConstructor().newInstance();
        invokeIfExists(runtime, "setConnectTimeout", Integer.class, properties.getConnectTimeoutMillis());
        invokeIfExists(runtime, "setReadTimeout", Integer.class, properties.getReadTimeoutMillis());
        invokeIfExists(runtime, "setSocketTimeout", Integer.class, properties.getSocketTimeoutMillis());
        return runtime;
    }

    private Object newRequest(String... classNames) throws Exception {
        for (String className : classNames) {
            try {
                Class<?> requestClass = Class.forName(SDK_REQUEST_PACKAGE + className);
                return requestClass.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.CONFIG_ERROR, "\u672a\u627e\u5230OCR SDK\u5bf9\u5e94\u7684\u8bf7\u6c42\u6a21\u578b");
    }

    private Object invokeClient(Object request, List<String> methodsWithOptions, List<String> plainMethods) throws Exception {
        Object client = buildClient();
        Class<?> clientClass = client.getClass();
        Object runtime = buildRuntimeOptions();
        for (String methodName : methodsWithOptions) {
            Method method = findMethod(clientClass, methodName, request.getClass(), runtime.getClass());
            if (method != null) {
                return method.invoke(client, request, runtime);
            }
        }
        for (String methodName : plainMethods) {
            Method method = findMethod(clientClass, methodName, request.getClass());
            if (method != null) {
                return method.invoke(client, request);
            }
        }
        throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.CONFIG_ERROR, "\u672a\u627e\u5230OCR SDK\u5bf9\u5e94\u7684\u8c03\u7528\u65b9\u6cd5");
    }

    private JSONObject toBodyJson(Object response) throws Exception {
        Object body = invokeGetter(response, "getBody");
        if (body == null) {
            return new JSONObject();
        }
        JSONObject root = JSON.parseObject(JSON.toJSONString(body));
        Object dataValue = root.get("data");
        if (dataValue instanceof String dataString && looksLikeJson(dataString)) {
            JSONObject parsedData = JSON.parseObject(dataString);
            if ((root.get("code") == null && root.get("message") == null) && (parsedData.containsKey("code") || parsedData.containsKey("msg"))) {
                if (root.containsKey("requestId") && !parsedData.containsKey("requestId")) {
                    parsedData.put("requestId", root.get("requestId"));
                }
                return parsedData;
            }
            root.put("data", parsedData);
        }
        return root;
    }

    private Map<String, String> extractKvData(JSONObject data) {
        Map<String, String> flatData = new LinkedHashMap<>();
        Map<String, String> kvMap = new LinkedHashMap<>();
        if (data == null) {
            return kvMap;
        }

        flattenData("", data, flatData);
        Map<String, String> qrFields = parseInvoiceQrFields(firstNonBlank(flatData.get("codes[0].data"), flatData.get("codes[1].data")));
        copyPreferredQrField(kvMap, qrFields, "\u53d1\u7968\u4ee3\u7801");
        copyPreferredQrField(kvMap, qrFields, "\u53d1\u7968\u53f7\u7801");
        copyPreferredQrField(kvMap, qrFields, "\u5f00\u7968\u65e5\u671f");
        copyPreferredQrField(kvMap, qrFields, "\u6821\u9a8c\u7801");

        collectPreferredField(kvMap, flatData, "\u53d1\u7968\u4ee3\u7801", "data.invoiceCode", "invoiceCode");
        collectPreferredField(kvMap, flatData, "\u53d1\u7968\u53f7\u7801", "data.invoiceNumber", "data.invoiceNo", "invoiceNumber", "invoiceNo");
        collectPreferredField(kvMap, flatData, "\u5f00\u7968\u65e5\u671f", "data.invoiceDate", "invoiceDate", "billingDate");
        collectPreferredField(kvMap, flatData, "\u91d1\u989d", "data.invoiceAmountPreTax", "invoiceAmountPreTax", "amountWithoutTax");
        collectPreferredField(kvMap, flatData, "\u4ef7\u7a0e\u5408\u8ba1", "data.totalAmount", "totalAmount", "invoiceSum");
        collectPreferredField(kvMap, flatData, "\u7a0e\u989d", "data.invoiceTax", "invoiceTax", "taxAmount");
        collectPreferredField(kvMap, flatData, "\u9500\u552e\u65b9\u540d\u79f0", "data.sellerName", "sellerName", "salesName");
        collectPreferredField(kvMap, flatData, "\u8d2d\u4e70\u65b9\u540d\u79f0", "data.purchaserName", "purchaserName", "buyerName");
        collectPreferredField(kvMap, flatData, "\u6821\u9a8c\u7801", "verifyCode", "checkCode");
        collectPreferredField(kvMap, flatData, "\u53d1\u7968\u540d\u79f0", "data.title", "data.invoiceType", "title", "invoiceType");
        copyPreferredQrField(kvMap, qrFields, "\u4ef7\u7a0e\u5408\u8ba1");

        for (Map.Entry<String, String> entry : flatData.entrySet()) {
            if (!isBlank(entry.getValue())) {
                kvMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return kvMap;
    }

    private Map<String, String> flattenVerifyBody(JSONObject body) {
        Map<String, String> details = new LinkedHashMap<>();
        if (body == null) {
            return details;
        }
        flattenData("", body, details);
        return details;
    }

    private void flattenData(String prefix, Object source, Map<String, String> output) {
        if (source instanceof JSONObject jsonObject) {
            for (String key : jsonObject.keySet()) {
                String childKey = prefix.isBlank() ? key : prefix + "." + key;
                flattenData(childKey, jsonObject.get(key), output);
            }
            return;
        }
        if (source instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                flattenData(prefix + "[" + index + "]", item, output);
                index++;
            }
            return;
        }
        output.put(prefix, source == null ? "" : String.valueOf(source));
    }

    private boolean isVerifyPassed(JSONObject body, String code) {
        if (VERIFY_SUCCESS_CODES.contains(code)) {
            return true;
        }
        if (VERIFY_FAILED_CODES.contains(code)) {
            return false;
        }
        JSONObject data = body.getJSONObject("data");
        if (data == null) {
            return false;
        }
        String verifyResult = normalize(stringValue(data, "verifyResult"));
        String passed = normalize(stringValue(data, "passed"));
        String status = normalize(stringValue(data, "status"));
        String msg = normalize(firstNonBlank(stringValue(body, "msg"), stringValue(data, "message")));
        return "true".equals(passed)
                || verifyResult.contains("match")
                || verifyResult.contains("success")
                || verifyResult.contains("\u4e00\u81f4")
                || status.contains("success")
                || msg.contains("\u4e00\u81f4");
    }

    private String resolveVerifyStatus(String code, boolean passed) {
        if (passed) {
            return "PASSED";
        }
        if (VERIFY_FAILED_CODES.contains(code)) {
            return "FAILED";
        }
        if (isBlank(code)) {
            return "VERIFY_UNKNOWN";
        }
        return "VERIFY_REJECTED";
    }

    private String resolveVerifyMessage(String code, String apiMessage, boolean passed) {
        if (passed) {
            return VERIFY_PASS;
        }
        if (!isBlank(apiMessage)) {
            return apiMessage;
        }
        if (VERIFY_FAILED_CODES.contains(code)) {
            return VERIFY_FAIL;
        }
        return VERIFY_INSUFFICIENT;
    }

    private void collectPreferredField(Map<String, String> target, Map<String, String> flatData, String fieldName, String... aliases) {
        if (target.containsKey(fieldName) && !isBlank(target.get(fieldName))) {
            return;
        }
        String value = findFirstMatchingValue(flatData, aliases);
        if (!isBlank(value)) {
            target.put(fieldName, value);
        }
    }

    private String findFirstMatchingValue(Map<String, String> flatData, String... aliases) {
        if (flatData == null || flatData.isEmpty()) {
            return null;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizePathToken(alias);
            for (Map.Entry<String, String> entry : flatData.entrySet()) {
                if (isBlank(entry.getValue())) {
                    continue;
                }
                String normalizedKey = normalizePathToken(entry.getKey());
                if (normalizedKey.equals(normalizedAlias) || normalizedKey.endsWith(normalizedAlias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private Map<String, String> parseInvoiceQrFields(String qrData) {
        Map<String, String> result = new LinkedHashMap<>();
        if (isBlank(qrData)) {
            return result;
        }
        List<String> tokens = new ArrayList<>();
        for (String part : qrData.split(",")) {
            String token = part == null ? "" : part.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.size() >= 6 && isInvoiceCodeToken(getToken(tokens, 2)) && isInvoiceDateToken(getToken(tokens, 5))) {
            result.put("\u53d1\u7968\u4ee3\u7801", getToken(tokens, 2));
            result.putIfAbsent("\u53d1\u7968\u53f7\u7801", getToken(tokens, 3));
            result.putIfAbsent("\u5f00\u7968\u65e5\u671f", getToken(tokens, 5));
            if (tokens.size() > 6 && isLikelyInvoiceSumToken(getToken(tokens, 6))) {
                result.put("\u4ef7\u7a0e\u5408\u8ba1", getToken(tokens, 6));
            }
            if (tokens.size() > 7 && !isBlank(getToken(tokens, 7))) {
                result.put("\u6821\u9a8c\u7801", getToken(tokens, 7));
            }
        }

        for (String token : tokens) {
            if (!result.containsKey("\u53d1\u7968\u4ee3\u7801") && isInvoiceCodeToken(token)) {
                result.put("\u53d1\u7968\u4ee3\u7801", token);
                continue;
            }
            if (!result.containsKey("\u5f00\u7968\u65e5\u671f") && isInvoiceDateToken(token)) {
                result.put("\u5f00\u7968\u65e5\u671f", token);
                continue;
            }
            if (!result.containsKey("\u4ef7\u7a0e\u5408\u8ba1") && isLikelyInvoiceSumToken(token)) {
                result.put("\u4ef7\u7a0e\u5408\u8ba1", token);
                continue;
            }
            if (!result.containsKey("\u53d1\u7968\u53f7\u7801") && isInvoiceNoToken(token)) {
                result.put("\u53d1\u7968\u53f7\u7801", token);
                continue;
            }
            if (!result.containsKey("\u6821\u9a8c\u7801") && isVerifyCodeToken(token)) {
                result.put("\u6821\u9a8c\u7801", token);
            }
        }
        if (!result.isEmpty()) {
            log.info("Invoice QR fields parsed: {}", result);
        }
        return result;
    }

    private String getToken(List<String> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    private boolean isInvoiceCodeToken(String token) {
        return token != null && token.matches("\\d{10,12}");
    }

    private boolean isInvoiceNoToken(String token) {
        return token != null && token.matches("\\d{8,20}");
    }

    private boolean isInvoiceDateToken(String token) {
        return token != null && token.matches("20\\d{6}");
    }

    private boolean isAmountToken(String token) {
        return token != null && token.matches("\\d+(\\.\\d{1,2})?");
    }

    private boolean isLikelyInvoiceSumToken(String token) {
        if (!isAmountToken(token)) {
            return false;
        }
        if (token == null) {
            return false;
        }
        if (token.contains(".")) {
            return true;
        }
        try {
            return Double.parseDouble(token) >= 10D;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isVerifyCodeToken(String token) {
        return token != null && token.matches("[0-9A-Za-z]{6,20}");
    }

    private void copyPreferredQrField(Map<String, String> target, Map<String, String> qrFields, String fieldName) {
        if (target.containsKey(fieldName) && !isBlank(target.get(fieldName))) {
            return;
        }
        if (qrFields == null) {
            return;
        }
        String value = qrFields.get(fieldName);
        if (!isBlank(value)) {
            target.put(fieldName, value);
        }
    }

    private InvoiceFieldData mergeOverrides(InvoiceFieldData fieldData, InvoiceVerifyOverrides overrides) {
        InvoiceFieldData source = fieldData == null ? new InvoiceFieldData() : fieldData;
        InvoiceVerifyOverrides safeOverrides = overrides == null ? new InvoiceVerifyOverrides() : overrides;
        InvoiceFieldData merged = new InvoiceFieldData();
        merged.setExtractedFields(source.getExtractedFields());
        merged.setInvoiceTitle(source.getInvoiceTitle());
        merged.setInvoiceTypeCode(source.getInvoiceTypeCode());
        merged.setInvoiceKind(source.getInvoiceKind());
        merged.setBuyerName(source.getBuyerName());
        merged.setSellerName(source.getSellerName());
        merged.setAmountWithoutTax(source.getAmountWithoutTax());
        merged.setTaxAmount(source.getTaxAmount());
        merged.setInvoiceCode(firstNonBlank(safeOverrides.getInvoiceCode(), source.getInvoiceCode()));
        merged.setInvoiceNo(firstNonBlank(safeOverrides.getInvoiceNo(), source.getInvoiceNo()));
        merged.setInvoiceDate(firstNonBlank(safeOverrides.getInvoiceDate(), source.getInvoiceDate()));
        merged.setInvoiceSum(firstNonBlank(safeOverrides.getInvoiceSum(), source.getInvoiceSum()));
        merged.setVerifyCode(normalizeVerifyCode(firstNonBlank(safeOverrides.getVerifyCode(), source.getVerifyCode())));
        return merged;
    }

    private VerifyPayload buildVerifyPayload(InvoiceFieldData merged) {
        String invoiceTypeCode = normalizeInvoiceTypeCode(merged == null ? null : merged.getInvoiceTypeCode());
        Integer invoiceKind = merged == null ? null : merged.getInvoiceKind();
        String invoiceCode = normalizePlainText(merged == null ? null : merged.getInvoiceCode());
        String invoiceNo = normalizePlainText(merged == null ? null : merged.getInvoiceNo());
        String invoiceDate = normalizeInvoiceDate(merged == null ? null : merged.getInvoiceDate());
        String verifyCode = normalizeVerifyCode(merged == null ? null : merged.getVerifyCode());
        String invoiceSum = normalizeVerifyAmount(merged, invoiceTypeCode, invoiceKind);

        boolean requiresInvoiceCode = requiresInvoiceCode(invoiceTypeCode, invoiceKind, merged);
        boolean requiresInvoiceSum = requiresInvoiceSum(invoiceTypeCode, invoiceKind);
        boolean requiresVerifyCode = requiresVerifyCode(invoiceTypeCode, invoiceKind);

        List<String> missing = new ArrayList<>();
        if (requiresInvoiceCode && isBlank(invoiceCode)) {
            missing.add("invoiceCode");
        }
        if (isBlank(invoiceNo)) {
            missing.add("invoiceNo");
        }
        if (isBlank(invoiceDate)) {
            missing.add("invoiceDate");
        }
        if (requiresInvoiceSum && isBlank(invoiceSum)) {
            missing.add("invoiceSum");
        }
        if (requiresVerifyCode && isBlank(verifyCode)) {
            missing.add("verifyCode");
        }
        if (!requiresInvoiceSum && !requiresVerifyCode && isBlank(invoiceSum) && isBlank(verifyCode)) {
            missing.add("invoiceSumOrVerifyCode");
        }

        return new VerifyPayload(invoiceCode, invoiceNo, invoiceDate, invoiceSum, verifyCode, invoiceTypeCode, invoiceKind, missing);
    }

    private boolean requiresInvoiceCode(String invoiceTypeCode, Integer invoiceKind, InvoiceFieldData fieldData) {
        if (invoiceKind != null && invoiceKind == 1) {
            return true;
        }
        if (invoiceTypeCode == null) {
            return isPaperInvoice(fieldData);
        }
        return switch (invoiceTypeCode) {
            case "31", "32", "51", "61", "83", "84" -> false;
            default -> true;
        };
    }

    private boolean requiresInvoiceSum(String invoiceTypeCode, Integer invoiceKind) {
        if (invoiceKind != null && invoiceKind == 1) {
            return true;
        }
        if (invoiceTypeCode == null) {
            return false;
        }
        return Set.of("01", "03", "15", "20", "31", "32", "51", "61", "83", "84", "85", "86").contains(invoiceTypeCode);
    }

    private boolean requiresVerifyCode(String invoiceTypeCode, Integer invoiceKind) {
        if (invoiceKind != null && invoiceKind == 1) {
            return true;
        }
        if (invoiceTypeCode == null) {
            return false;
        }
        return Set.of("04", "10", "11", "14", "86").contains(invoiceTypeCode);
    }

    private String normalizeVerifyAmount(InvoiceFieldData merged, String invoiceTypeCode, Integer invoiceKind) {
        String totalAmount = normalizeAmount(merged == null ? null : merged.getInvoiceSum());
        String amountWithoutTax = normalizeAmount(merged == null ? null : merged.getAmountWithoutTax());
        if (invoiceKind != null && invoiceKind == 1) {
            return firstNonBlank(amountWithoutTax, totalAmount);
        }
        if (invoiceTypeCode == null) {
            return firstNonBlank(amountWithoutTax, totalAmount);
        }
        if (Set.of("04", "10", "11", "14").contains(invoiceTypeCode)) {
            return null;
        }
        if (Set.of("31", "32", "51", "61", "83", "84").contains(invoiceTypeCode)) {
            return firstNonBlank(totalAmount, amountWithoutTax);
        }
        return firstNonBlank(amountWithoutTax, totalAmount);
    }

    private String normalizeAmount(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value
                .replace("\u00a5", "")
                .replace("\uffe5", "")
                .replace("\u5143", "")
                .replace(",", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalized).stripTrailingZeros().scale() < 0
                    ? new BigDecimal(normalized).setScale(0).toPlainString()
                    : new BigDecimal(normalized).setScale(Math.min(2, Math.max(0, new BigDecimal(normalized).scale()))).toPlainString();
        } catch (NumberFormatException e) {
            return normalized;
        }
    }

    private String normalizeInvoiceDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() == 8 ? digits : value.trim();
    }

    private String normalizeVerifyCode(String value) {
        if (isBlank(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9A-Za-z]", "");
        if (digits.isBlank()) {
            return null;
        }
        return digits.length() > 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private String normalizePlainText(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String normalizeInvoiceTypeCode(String value) {
        if (isBlank(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 2) {
            return null;
        }
        return digits.substring(0, 2);
    }

    private boolean looksLikeJson(String value) {
        if (isBlank(value)) {
            return false;
        }
        String trimmed = value.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private void setIfPresent(Object target, String methodName, String value) throws Exception {
        if (!isBlank(value)) {
            invokeCompatibleSetter(target, methodName, value);
        }
    }

    private void invokeSetter(Object target, String methodName, Class<?> parameterType, Object value) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterType);
        method.invoke(target, value);
    }

    private void invokeIfExists(Object target, String methodName, Class<?> parameterType, Object value) throws Exception {
        Method method = findMethod(target.getClass(), methodName, parameterType);
        if (method != null) {
            method.invoke(target, value);
        }
    }

    private void invokeCompatibleSetter(Object target, String methodName, Object value) throws Exception {
        Method method = findCompatibleSetter(target.getClass(), methodName, value == null ? null : value.getClass());
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + methodName);
        }
        method.invoke(target, value);
    }

    private boolean invokeCompatibleSetterIfExists(Object target, String methodName, Object value) throws Exception {
        Method method = findCompatibleSetter(target.getClass(), methodName, value == null ? null : value.getClass());
        if (method == null) {
            return false;
        }
        method.invoke(target, value);
        return true;
    }

    private Method findCompatibleSetter(Class<?> type, String methodName, Class<?> valueClass) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (valueClass == null || parameterType.isAssignableFrom(valueClass)) {
                return method;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Object invokeGetter(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private void logSetKeysFallback(Class<?> requestClass, List<String> keys) {
        if (SET_KEYS_UNSUPPORTED_LOGGED.compareAndSet(false, true)) {
            log.info("OCR request class {} does not support setKeys, fallback with default request fields: {}", requestClass.getName(), keys);
        }
    }

    private AliOcrInvoiceException mapSdkException(String action, Exception e) {
        Throwable root = unwrap(e);
        String message = root == null || root.getMessage() == null ? action : root.getMessage();
        if (root instanceof ClassNotFoundException || root instanceof NoSuchMethodException) {
            return new AliOcrInvoiceException(AliOcrInvoiceErrorType.CONFIG_ERROR, message, e);
        }
        if (message.toLowerCase(Locale.ROOT).contains("timeout") || message.toLowerCase(Locale.ROOT).contains("connect")) {
            return new AliOcrInvoiceException(AliOcrInvoiceErrorType.NETWORK_ERROR, message, e);
        }
        return new AliOcrInvoiceException(AliOcrInvoiceErrorType.API_ERROR, message, e);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocationTargetException && invocationTargetException.getTargetException() != null) {
            current = invocationTargetException.getTargetException();
        }
        return current;
    }

    private String stringValue(JSONObject source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePathToken(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]", "").toLowerCase(Locale.ROOT);
    }

    private record VerifyPayload(
            String invoiceCode,
            String invoiceNo,
            String invoiceDate,
            String invoiceSum,
            String verifyCode,
            String invoiceTypeCode,
            Integer invoiceKind,
            List<String> missingFields
    ) {
    }

    public record OcrResult(String requestId, String rawResponse, Map<String, String> kvData) {
    }
}
