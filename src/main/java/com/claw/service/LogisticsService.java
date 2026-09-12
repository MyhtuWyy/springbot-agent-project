package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.util.ConfigUtil;
import com.claw.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LogisticsService {
    private static final Logger log = LoggerFactory.getLogger(LogisticsService.class);
    private static final String QUERY_API_URL = "https://apis.tianapi.com/kuaidi/index";
    private static final String COMPANY_LIST_API_URL = "https://apis.tianapi.com/kuaidi/comcodelist";
    private static final int MAX_TRACE = 10;

    private static final Map<String, String> COMPANY_CODES = new LinkedHashMap<>();
    private static final Map<String, String> COMPANY_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> STATUS_MAP = Map.of(
            "1", "在途中",
            "2", "正在派件",
            "3", "已签收",
            "4", "派送失败",
            "5", "疑难件",
            "6", "退件签收"
    );

    static {
        COMPANY_CODES.put("顺丰", "shunfeng");
        COMPANY_CODES.put("顺丰快递", "shunfeng");
        COMPANY_CODES.put("顺丰速运", "shunfeng");
        COMPANY_CODES.put("sf", "shunfeng");
        COMPANY_CODES.put("中通", "zhongtong");
        COMPANY_CODES.put("中通快递", "zhongtong");
        COMPANY_CODES.put("圆通", "yuantong");
        COMPANY_CODES.put("圆通速递", "yuantong");
        COMPANY_CODES.put("申通", "shentong");
        COMPANY_CODES.put("申通快递", "shentong");
        COMPANY_CODES.put("韵达", "yunda");
        COMPANY_CODES.put("韵达快递", "yunda");
        COMPANY_CODES.put("京东", "jd");
        COMPANY_CODES.put("京东物流", "jd");
        COMPANY_CODES.put("ems", "ems");
        COMPANY_CODES.put("邮政", "ems");
        COMPANY_CODES.put("极兔", "jtexpress");
        COMPANY_CODES.put("德邦", "debangwuliu");
        COMPANY_CODES.put("菜鸟", "cainiao");
        COMPANY_CODES.put("百世", "huitongkuaidi");
        COMPANY_CODES.put("天天", "tiantian");
        COMPANY_CODES.put("丰网", "fengwang");

        COMPANY_NAMES.put("shunfeng", "顺丰速运");
        COMPANY_NAMES.put("zhongtong", "中通快递");
        COMPANY_NAMES.put("yuantong", "圆通速递");
        COMPANY_NAMES.put("shentong", "申通快递");
        COMPANY_NAMES.put("yunda", "韵达快递");
        COMPANY_NAMES.put("jd", "京东物流");
        COMPANY_NAMES.put("ems", "EMS");
        COMPANY_NAMES.put("jtexpress", "极兔速递");
        COMPANY_NAMES.put("debangwuliu", "德邦快递");
        COMPANY_NAMES.put("cainiao", "菜鸟速递");
        COMPANY_NAMES.put("huitongkuaidi", "百世快递");
        COMPANY_NAMES.put("tiantian", "天天快递");
        COMPANY_NAMES.put("fengwang", "丰网速运");
    }

    public String queryLogistics(String number, String company, String phoneLast4) {
        String apiKey = ConfigUtil.getTianApiKey();
        if (apiKey.isBlank()) {
            return "物流查询 API 密钥未配置，请检查 application.yml 中的 tianapi.key。";
        }

        String trackingNum = cleanTrackingNumber(number);
        if (trackingNum.isBlank()) {
            return "缺少快递单号，请提供要查询的单号。";
        }

        String explicitCompanyCode = resolveCompanyCode(company);
        String phone = cleanPhoneLast4(phoneLast4);

        try {
            JSONObject response = callQueryApi(apiKey, trackingNum, explicitCompanyCode, phone);
            if (response == null) {
                return notFoundMessage(trackingNum, company, phone);
            }

            int code = parseCode(response.getString("code"));
            if (code != 200) {
                String msg = fb(response.getString("msg"), "未知错误");
                log.warn("物流接口异常, code={}, msg={}, num={}, company={}", code, msg, trackingNum, explicitCompanyCode);
                return errorMessage(msg, trackingNum, company, phone);
            }

            JSONObject result = response.getJSONObject("result");
            if (result == null) {
                return notFoundMessage(trackingNum, company, phone);
            }

            JSONArray traces = extractTraces(result);
            return formatResult(result, traces, trackingNum, company);
        } catch (Exception e) {
            log.error("物流查询异常, num={}", trackingNum, e);
            return "物流接口请求出错，" + e.getMessage();
        }
    }

    public String listSupportedCompanies() {
        String apiKey = ConfigUtil.getTianApiKey();
        if (apiKey.isBlank()) {
            return "物流查询 API 密钥未配置，请检查 application.yml 中的 tianapi.key。";
        }

        try {
            JSONObject response = callCompanyListApi(apiKey);
            if (response == null) {
                return fallbackCompanyListMessage();
            }

            int code = parseCode(response.getString("code"));
            if (code != 200) {
                log.warn("物流公司列表接口异常, code={}, msg={}", code, response.getString("msg"));
                return fallbackCompanyListMessage();
            }

            JSONArray result = response.getJSONArray("result");
            if (result == null || result.isEmpty()) {
                return fallbackCompanyListMessage();
            }

            List<String> items = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                JSONObject item = result.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = fb(item.getString("name"), item.getString("kuaidiname"));
                String codeName = fb(item.getString("code"), item.getString("company"));
                if (name.isBlank() && codeName.isBlank()) {
                    continue;
                }
                items.add(name.isBlank() ? codeName : name + (codeName.isBlank() ? "" : "（" + codeName + "）"));
            }

            if (items.isEmpty()) {
                return fallbackCompanyListMessage();
            }

            StringBuilder sb = new StringBuilder("[常见物流公司编码]\n");
            int limit = Math.min(items.size(), 20);
            for (int i = 0; i < limit; i++) {
                sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
            }
            if (items.size() > limit) {
                sb.append("... 共 ").append(items.size()).append(" 项，仅展示前 ").append(limit).append(" 项。\n");
            }
            sb.append("\n说明: 查询物流时默认可只传单号自动识别；若接口提示需要公司编码或手机号后四位，再补充信息更稳。");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("查询物流公司列表失败", e);
            return fallbackCompanyListMessage();
        }
    }

    private JSONObject callQueryApi(String apiKey, String number, String companyCode, String phoneLast4) throws Exception {
        StringBuilder url = new StringBuilder(QUERY_API_URL)
                .append("?key=").append(encode(apiKey))
                .append("&number=").append(encode(number));
        if (!companyCode.isBlank()) {
            url.append("&company=").append(encode(companyCode));
        }
        if (!phoneLast4.isBlank()) {
            url.append("&senderphone=").append(encode(phoneLast4));
        }
        return JSON.parseObject(HttpUtil.doGet(url.toString()));
    }

    private JSONObject callCompanyListApi(String apiKey) throws Exception {
        String url = COMPANY_LIST_API_URL + "?key=" + encode(apiKey);
        return JSON.parseObject(HttpUtil.doGet(url));
    }

    private JSONArray extractTraces(JSONObject result) {
        for (String field : List.of("list", "data", "history", "records", "trace", "traces")) {
            JSONArray arr = result.getJSONArray(field);
            if (arr != null && !arr.isEmpty()) {
                return arr;
            }
        }
        return new JSONArray();
    }

    private String formatResult(JSONObject result, JSONArray traces, String trackingNum, String userCompany) {
        String company = resolveCompanyName(result, userCompany);
        String latestTime = fb(
                result.getString("updatetime"),
                result.getString("time"),
                traceField(traces, 0, "time"),
                traceField(traces, 0, "ftime"),
                traceField(traces, 0, "datetime")
        );
        String latestStatus = traces.isEmpty()
                ? fb(result.getString("content"), result.getString("context"), result.getString("statusdesc"), result.getString("message"))
                : buildTraceText(traces.getJSONObject(0));
        String status = resolveStatus(result, latestStatus);
        String courier = fb(result.getString("courier"), result.getString("deliveryman"));
        String courierPhone = fb(result.getString("courierPhone"), result.getString("deliverymanPhone"));
        String officialPhone = fb(result.getString("telephone"), result.getString("expPhone"), result.getString("tel"));
        String signStatus = normalizeSign(result.getString("issign"));
        String location = fb(result.getString("address"), result.getString("location"), result.getString("currentSite"));

        StringBuilder sb = new StringBuilder();
        sb.append("[物流查询结果]\n");
        sb.append("快递公司: ").append(company).append("\n");
        sb.append("单号: ").append(fb(result.getString("number"), result.getString("nu"), trackingNum)).append("\n");
        sb.append("物流状态: ").append(status).append("\n");
        if (!latestTime.isBlank()) {
            sb.append("更新时间: ").append(latestTime).append("\n");
        }
        if (!latestStatus.isBlank()) {
            sb.append("最新动态: ").append(latestStatus).append("\n");
        }
        if (!location.isBlank()) {
            sb.append("当前位置: ").append(location).append("\n");
        }
        if (!signStatus.isBlank()) {
            sb.append("签收情况: ").append(signStatus).append("\n");
        }
        if (!courier.isBlank()) {
            sb.append("快递员: ").append(courier).append("\n");
        }
        if (!courierPhone.isBlank()) {
            sb.append("快递员电话: ").append(courierPhone).append("\n");
        }
        if (!officialPhone.isBlank()) {
            sb.append("官方电话: ").append(officialPhone).append("\n");
        }

        if (!traces.isEmpty()) {
            sb.append("\n物流轨迹\n");
            int limit = Math.min(MAX_TRACE, traces.size());
            for (int i = 0; i < limit; i++) {
                JSONObject trace = traces.getJSONObject(i);
                String time = fb(trace.getString("time"), trace.getString("ftime"), trace.getString("datetime"));
                sb.append(i + 1).append(". ");
                if (!time.isBlank()) {
                    sb.append("[").append(time).append("] ");
                }
                sb.append(fb(buildTraceText(trace), "暂无详情")).append("\n");
            }
            if (traces.size() > MAX_TRACE) {
                sb.append("... 共 ").append(traces.size()).append(" 条，已展示最近 ").append(MAX_TRACE).append(" 条。");
            }
        }

        return sb.toString().trim();
    }

    private String buildTraceText(JSONObject trace) {
        if (trace == null) {
            return "";
        }
        String primary = fb(trace.getString("content"), trace.getString("status"), trace.getString("context"),
                trace.getString("remark"), trace.getString("desc"));
        String loc = fb(trace.getString("address"), trace.getString("location"), trace.getString("area"), trace.getString("site"));
        if (loc.isBlank()) {
            return primary;
        }
        if (primary.isBlank() || primary.contains(loc)) {
            return primary.isBlank() ? loc : primary;
        }
        return loc + " - " + primary;
    }

    private String resolveCompanyName(JSONObject result, String userCompany) {
        String apiName = fb(result.getString("kuaidiname"), result.getString("expName"), result.getString("name"));
        if (!apiName.isBlank()) {
            return COMPANY_NAMES.getOrDefault(apiName.toLowerCase(), apiName);
        }
        String code = fb(result.getString("company"), result.getString("type"), result.getString("expCode"));
        if (!code.isBlank()) {
            return COMPANY_NAMES.getOrDefault(code.toLowerCase(), code);
        }
        return (userCompany == null || userCompany.isBlank()) ? "接口未返回快递公司" : userCompany;
    }

    private String resolveStatus(JSONObject result, String latestStatus) {
        String raw = fb(result.getString("deliverystatus"), result.getString("status"));
        if (!raw.isBlank()) {
            String mapped = STATUS_MAP.get(raw.trim());
            if (mapped != null) {
                return mapped;
            }
        }
        return latestStatus.isBlank() ? "暂未获取到明确状态" : latestStatus;
    }

    private String normalizeSign(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw.trim()) {
            case "1", "true" -> "已签收";
            case "0", "false" -> "未签收";
            default -> raw.trim();
        };
    }

    private String cleanTrackingNumber(String number) {
        if (number == null) {
            return "";
        }
        return number.replaceAll("[^A-Za-z0-9]", "").trim();
    }

    private String resolveCompanyCode(String company) {
        if (company == null || company.isBlank()) {
            return "";
        }
        String key = company.trim().toLowerCase();
        return COMPANY_CODES.getOrDefault(key, key);
    }

    private String cleanPhoneLast4(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private String traceField(JSONArray traces, int idx, String key) {
        if (traces == null || traces.isEmpty() || idx >= traces.size()) {
            return "";
        }
        JSONObject obj = traces.getJSONObject(idx);
        return obj == null ? "" : fb(obj.getString(key));
    }

    private String notFoundMessage(String num, String company, String phoneLast4) {
        if (company == null || company.isBlank()) {
            return "暂未查到该单号的物流轨迹。\n单号: " + num
                    + "\n该接口默认会自动识别快递公司。"
                    + "\n如果仍查不到，建议补充物流公司名称或编码"
                    + (phoneLast4.isBlank() ? "，以及寄件/收件人手机号后四位后重试。" : "后再重试。");
        }
        return "暂未查到该单号的物流轨迹。\n单号: " + num + "\n快递公司: " + company + "\n请确认单号和快递公司是否正确。";
    }

    private String errorMessage(String msg, String num, String company, String phoneLast4) {
        if (msg.contains("手机")) {
            return "该快递公司查询需要寄件或收件人手机号后四位。\n单号: " + num
                    + (company != null && !company.isBlank() ? "\n快递公司: " + company : "");
        }
        if (msg.contains("公司") || msg.contains("编码")) {
            return "该单号查询需要补充物流公司编码。\n单号: " + num
                    + (phoneLast4.isBlank() ? "\n如仍失败，也可以同时补充手机号后四位。" : "");
        }
        if (msg.contains("单号") || msg.contains("不存在") || msg.contains("为空")) {
            return notFoundMessage(num, company, phoneLast4);
        }
        if (msg.contains("额度") || msg.contains("次数")) {
            return "物流查询接口额度不足，请检查天聚数行账户配额。";
        }
        return "物流查询失败，" + msg;
    }

    private String fallbackCompanyListMessage() {
        StringBuilder sb = new StringBuilder("[常见物流公司编码]\n");
        int i = 1;
        for (String name : List.of(
                "顺丰（shunfeng）",
                "中通（zhongtong）",
                "圆通（yuantong）",
                "申通（shentong）",
                "韵达（yunda）",
                "京东（jd）",
                "EMS（ems）",
                "极兔（jtexpress）",
                "德邦（debangwuliu）",
                "菜鸟（cainiao）",
                "百世/汇通（huitongkuaidi）",
                "天天（tiantian）"
        )) {
            sb.append(i++).append(". ").append(name).append("\n");
        }
        sb.append("\n说明: 这是本地保底示例，实际支持范围以天行接口返回为准。");
        return sb.toString().trim();
    }

    private int parseCode(String codeText) {
        try {
            return codeText == null ? -1 : Integer.parseInt(codeText.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String fb(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
