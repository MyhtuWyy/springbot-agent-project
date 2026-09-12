package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.ExcelMcpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExcelQueryTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(ExcelQueryTool.class);

    private final ExcelMcpService excelMcpService;

    public ExcelQueryTool(ExcelMcpService excelMcpService) {
        this.excelMcpService = excelMcpService;
    }

    @Override
    public String name() {
        return "excel_query_file";
    }

    @Override
    public String description() {
        return "读取指定 Excel 文件，可指定 sheet，并根据用户问题返回文本答案。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        properties.put("workbook", property("string", "Excel 文件名或路径，默认根目录为 D:/mcp-server-files/excel"));
        properties.put("sheetName", property("string", "可选，sheet 名称"));
        properties.put("question", property("string", "要回答的问题"));
        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("workbook").fluentAdd("question"));
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            return excelMcpService.queryWorkbook(
                    args.getString("workbook"),
                    args.getString("sheetName"),
                    args.getString("question")
            );
        } catch (Exception e) {
            log.error("excel query tool failed", e);
            return "Excel 查询失败: " + e.getMessage();
        }
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}
