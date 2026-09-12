package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.ExcelMcpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExcelSummarizeTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(ExcelSummarizeTool.class);

    private final ExcelMcpService excelMcpService;

    public ExcelSummarizeTool(ExcelMcpService excelMcpService) {
        this.excelMcpService = excelMcpService;
    }

    @Override
    public String name() {
        return "excel_summarize_columns";
    }

    @Override
    public String description() {
        return "按列汇总 Excel 数据，支持分组列、指标列和汇总方式，并返回文本结果。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        properties.put("workbook", property("string", "Excel 文件名或路径"));
        properties.put("sheetName", property("string", "可选，sheet 名称"));
        properties.put("groupByColumns", arrayProperty("分组列列表，例如 [\"城市\", \"日期\"]"));
        properties.put("metricColumns", arrayProperty("指标列列表，例如 [\"销售额\", \"数量\"]"));
        properties.put("summaryType", property("string", "汇总方式，例如 sum、avg、count、max、min"));
        properties.put("question", property("string", "自然语言汇总要求，可与上面结构化字段组合使用"));
        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("workbook"));
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            return excelMcpService.summarizeWorkbook(
                    args.getString("workbook"),
                    args.getString("sheetName"),
                    toStringList(args.getJSONArray("groupByColumns")),
                    toStringList(args.getJSONArray("metricColumns")),
                    args.getString("summaryType"),
                    args.getString("question")
            );
        } catch (Exception e) {
            log.error("excel summarize tool failed", e);
            return "Excel 汇总失败: " + e.getMessage();
        }
    }

    private List<String> toStringList(JSONArray array) {
        return array == null ? List.of() : array.toJavaList(String.class);
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private JSONObject arrayProperty(String description) {
        JSONObject property = new JSONObject();
        property.put("type", "array");
        property.put("description", description);
        JSONObject items = new JSONObject();
        items.put("type", "string");
        property.put("items", items);
        return property;
    }
}
