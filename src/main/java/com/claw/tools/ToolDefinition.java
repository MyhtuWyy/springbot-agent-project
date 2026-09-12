package com.claw.tools;

import com.alibaba.fastjson2.JSONObject;

/**
 * 工具定义接口。
 * 所有可被 AI 调用的工具都需要实现该接口。
 */
public interface ToolDefinition {

    /**
     * 工具名称，对应 Function Calling 的 function name。
     */
    String name();

    /**
     * 工具描述，用于让模型理解工具用途。
     */
    String description();

    /**
     * 工具参数 JSON Schema。
     */
    JSONObject parametersSchema();

    /**
     * 执行工具调用。
     *
     * @param arguments JSON 参数字符串
     * @return 工具执行结果
     */
    String execute(String arguments);
}
