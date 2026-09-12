package com.claw.skills;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.dto.InvoiceExportResponse;
import com.claw.dto.InvoiceRecognitionRequest;
import com.claw.dto.InvoiceRecognitionResult;
import com.claw.service.InvoiceRecognitionService;
import com.claw.service.UserSessionContext;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillDefine;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

@Component
@SkillDefine(
        name = "invoice_skill",
        description = "Preview, recognize, verify and export invoice OCR results.",
        timeoutMs = 45000L
)
public class InvoiceSkill implements BaseSkill {
    private final InvoiceRecognitionService invoiceRecognitionService;

    public InvoiceSkill(InvoiceRecognitionService invoiceRecognitionService) {
        this.invoiceRecognitionService = invoiceRecognitionService;
    }

    @Override
    public String skillName() {
        return "invoice_skill";
    }

    @Override
    public String description() {
        return "Preview, recognize, verify and export invoice OCR results.";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("action", property("string", "preview, recognize_verify, export_excel"));
        properties.put("imageBase64", property("string", "Invoice image in base64."));
        properties.put("fileName", property("string", "Original file name."));
        properties.put("sessionId", property("string", "Optional session id for export context."));
        properties.put("ticketKeys", arrayProperty("string", "Optional OCR ticket keys."));
        properties.put("verifyEnabled", property("boolean", "Whether invoice verification should run."));

        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("action").fluentAdd("imageBase64").fluentAdd("fileName"));
        return schema;
    }

    @Override
    public SkillResult execute(SkillRequest request) {
        JSONObject args = request.arguments();
        String action = trim(args.getString("action"));
        String fileName = trim(args.getString("fileName"));
        byte[] imageBytes = decodeBase64(trim(args.getString("imageBase64")));
        InvoiceRecognitionRequest recognitionRequest = new InvoiceRecognitionRequest();
        if (args.containsKey("ticketKeys")) {
            recognitionRequest.setTicketKeys(args.getJSONArray("ticketKeys").toJavaList(String.class));
        }
        if (args.containsKey("verifyEnabled")) {
            recognitionRequest.setVerifyEnabled(args.getBoolean("verifyEnabled"));
        }

        Map<String, Function<JSONObject, SkillResult>> actionHandlers = Map.of(
                "preview", ignored -> preview(fileName, imageBytes),
                "recognize_verify", ignored -> recognize(fileName, imageBytes, recognitionRequest),
                "export_excel", ignored -> export(fileName, imageBytes, recognitionRequest, args.getString("sessionId"))
        );

        Function<JSONObject, SkillResult> handler = actionHandlers.get(action);
        if (handler == null) {
            return SkillResult.failure(skillName(), "unknown action", "unknown action: " + action, 0L, false);
        }
        return handler.apply(args);
    }

    private SkillResult preview(String fileName, byte[] imageBytes) {
        InvoiceRecognitionResult result = invoiceRecognitionService.previewRecognize(imageBytes, fileName);
        JSONObject data = (JSONObject) JSON.toJSON(result);
        return SkillResult.success(skillName(), "invoice preview done", data, 0L);
    }

    private SkillResult recognize(String fileName, byte[] imageBytes, InvoiceRecognitionRequest request) {
        InvoiceRecognitionResult result = invoiceRecognitionService.recognizeAndVerify(imageBytes, fileName, request);
        JSONObject data = (JSONObject) JSON.toJSON(result);
        return SkillResult.success(skillName(), "invoice recognize done", data, 0L);
    }

    private SkillResult export(String fileName, byte[] imageBytes, InvoiceRecognitionRequest request, String sessionId) {
        InvoiceExportResponse response = invoiceRecognitionService.recognizeAndExport(
                imageBytes,
                fileName,
                request,
                UserSessionContext.fromSessionId(sessionId)
        );
        JSONObject data = (JSONObject) JSON.toJSON(response);
        return SkillResult.success(skillName(), "invoice export done", data, 0L);
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private JSONObject arrayProperty(String itemType, String description) {
        JSONObject property = property("array", description);
        JSONObject items = new JSONObject();
        items.put("type", itemType);
        property.put("items", items);
        return property;
    }

    private byte[] decodeBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("imageBase64 is required");
        }
        String payload = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        return Base64.getDecoder().decode(payload);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
