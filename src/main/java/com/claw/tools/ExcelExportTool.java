package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.ExcelMcpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExcelExportTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(ExcelExportTool.class);

    private final ExcelMcpService excelMcpService;

    public ExcelExportTool(ExcelMcpService excelMcpService) {
        this.excelMcpService = excelMcpService;
    }

    @Override
    public String name() {
        return "excel_export_result";
    }

    @Override
    public String description() {
        return "基于本地 Excel/CSV 文件执行列筛选、按行过滤、排序后导出为新的结果文件。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("workbook", property("string", "源 Excel 文件名或路径。"));
        properties.put("sheetName", property("string", "可选，源 sheet 名称。"));
        properties.put("targetSheetName", property("string", "可选，导出结果 sheet 名称。"));
        properties.put("outputFile", property("string", "可选，导出文件名或路径。"));
        properties.put("instruction", property("string", "自然语言导出要求。"));
        properties.put("rowFilter", property("string", "可选，按行筛选条件，例如 销量>100。"));
        properties.put("sortColumn", property("string", "可选，排序列名。"));
        properties.put("sortOrder", property("string", "可选，排序方向：asc 或 desc。"));

        JSONObject selectedColumns = property("array", "可选，指定导出的列名列表。");
        selectedColumns.put("items", property("string", "列名"));
        properties.put("selectedColumns", selectedColumns);

        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("workbook"));
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            return excelMcpService.exportWorkbook(
                    args.getString("workbook"),
                    args.getString("sheetName"),
                    args.getString("targetSheetName"),
                    args.getString("outputFile"),
                    args.getJSONArray("selectedColumns") == null ? null : args.getJSONArray("selectedColumns").toJavaList(String.class),
                    args.getString("rowFilter"),
                    args.getString("sortColumn"),
                    args.getString("sortOrder"),
                    args.getString("instruction")
            );
        } catch (Exception e) {
            log.error("excel export tool failed", e);
            return "Excel导出失败：系统处理导出请求时发生异常，请稍后重试。";
        }
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}
