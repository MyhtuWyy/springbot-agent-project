package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.LogisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogisticsTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(LogisticsTool.class);

    private final LogisticsService logisticsService;

    public LogisticsTool(LogisticsService logisticsService) {
        this.logisticsService = logisticsService;
    }

    @Override
    public String name() {
        return "query_logistics";
    }

    @Override
    public String description() {
        return "查询快递物流信息，也支持列出常见物流公司编码。默认可只传单号自动识别，必要时再补充公司和手机号后四位。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("action", property("string", "query=查询物流，list_companies=列出常见物流公司编码。"));
        properties.put("number", property("string", "快递单号，例如 SF1234567890。"));
        properties.put("company", property("string", "可选。仅当用户明确给出物流公司时再传，例如顺丰、圆通、中通，或对应公司编码。"));
        properties.put("sender_phone_last4", property("string", "可选。寄件人或收件人手机号后四位，部分公司查询需要。"));
        parameters.put("properties", properties);
        return parameters;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            String action = args == null ? "query" : args.getString("action");
            if ("list_companies".equalsIgnoreCase(action)) {
                return logisticsService.listSupportedCompanies();
            }
            return logisticsService.queryLogistics(
                    args == null ? null : args.getString("number"),
                    args == null ? null : args.getString("company"),
                    args == null ? null : args.getString("sender_phone_last4")
            );
        } catch (Exception e) {
            log.error("物流工具执行异常", e);
            return "物流查询出错，" + e.getMessage();
        }
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}
