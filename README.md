# 306-token 微信 AI 机器人

基于 Spring Boot + 阿里云百炼（通义千问）的微信 AI 聊天机器人，支持 Function Calling 工具调用（天气查询、新闻搜索、物流跟踪、文件解析、文案生成、高德地图 POI 查询、行程规划、情绪树洞支持）、语音识别（ASR）、语音合成（TTS）、多轮对话记忆、图片描述等功能。

## 技术栈

- Java 21
- Spring Boot 3.x
- 阿里云 DashScope SDK（通义千问 qwen-turbo / qwen-vl-plus）
- 天聚数行 API（新闻查询、物流查询）
- 高德地图 API（POI 查询、地理编码、路线规划）
- wttr.in 天气 API
- 微信 Web 协议

---

## 配置项说明

所有配置均通过 Spring Boot 的 `application.yml` 统一管理，敏感配置必须通过环境变量注入（变量名示例见项目根目录 `.env.example`）。`APP_CONFIG_ENCRYPTION_KEY` 是必填项，用于加密用户模型配置中的 API Key；应用不会再使用内置 fallback 密钥。

### 基础服务配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `server.port` | HTTP 服务端口 | `8080` |
| `app.cli.enabled` | 是否启用命令行交互模式 | `true` |

### AI 模型配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `DASHSCOPE_API_KEY` | 阿里云百炼 API Key（必填） | — |
| `DASHSCOPE_MODEL` | 文本对话模型名称 | `qwen-turbo` |
| `DASHSCOPE_VL_MODEL` | 视觉理解模型名称 | `qwen-vl-plus` |

### 语音识别（ASR）配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `dashscope.asr.model` | 语音识别模型 | `qwen3-asr-flash` |
| `dashscope.asr.language` | 识别语言 | `zh` |

### 语音合成（TTS）配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `dashscope.tts.model` | TTS 模型名称 | `cosyvoice-v3-flash` |
| `dashscope.tts.voice-id` | 默认发音人 ID | `longanhuan` |
| `dashscope.tts.voice-alias-map` | 发音人别名映射（格式：`别名=voiceId;别名=voiceId`） | 内置多组默认映射 |
| `dashscope.tts.verified-alias-map` | 已验证可用的发音人别名映射 | — |
| `dashscope.tts.female-option-aliases` | 女声可选别名列表（逗号分隔） | `少女音,温婉女声,元气女声` |
| `dashscope.tts.male-option-aliases` | 男声可选别名列表（逗号分隔） | `阳光男声,沉稳男声,洒脱男声,顽皮男声` |
| `dashscope.tts.sample-rate` | 采样率 | `16000` |
| `dashscope.tts.format` | 音频格式 | `wav` |
| `dashscope.tts.speed` | 语速倍率 | `1.0` |
| `dashscope.tts.volume` | 音量倍率 | `1.0` |
| `dashscope.tts.pitch` | 音调偏移 | `0` |
| `dashscope.tts.single-reply-max-chars` | 单条语音回复最大字符数 | `80` |
| `dashscope.tts.segment-max-chars` | 语音分段最大字符数 | `100` |
| `dashscope.tts.segment-min-chars` | 语音分段最小字符数 | `60` |

### 微信语音配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `wechat.voice.default-encode-type` | 默认语音编码类型 | `1` |
| `wechat.voice.silk-encode-type` | SILK 格式编码类型 | `6` |
| `wechat.voice.default-bits-per-sample` | 默认采样位数 | `16` |
| `wechat.voice.include-transcript` | 是否在语音消息中包含文字转录 | `false` |
| `wechat.voice.segment-interval-ms` | 语音分段间隔（毫秒） | `250` |

### 对话记忆配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `conversation.max-messages` | 每个会话最大保留消息数 | `20` |
| `conversation.expire-minutes` | 会话过期时间（分钟） | `60` |

### 天聚数行 API 配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `TIANAPI_KEY` | 天聚数行 API Key（必填，用于新闻查询、物流查询等所有天聚数行接口） | — |

### 高德地图 API 配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `AMAP_API_KEY` | 高德地图 API Key（必填，用于 POI 查询、地理编码、路线规划） | — |

---

## Function Calling 工具使用说明

项目采用统一的工具注册机制，AI 可根据用户意图自动调用已注册的工具。

### 工具框架结构

```
com.claw.tools
├── ToolDefinition.java        # 工具定义接口（name/description/parametersSchema/execute）
├── ToolRegistry.java          # 工具注册中心，自动收集所有 ToolDefinition 实现
├── WeatherTool.java           # 天气查询工具
├── NewsTool.java              # 天聚数行新闻查询工具
├── LogisticsTool.java         # 天聚数行物流查询工具
├── FileParseTool.java         # 文件解析工具（TXT/PDF/Word 等）
├── CopywritingTool.java       # 文案生成工具箱（朋友圈文案/治愈短句/道歉文案/纪念日文案/小众签名）
├── AmapTool.java              # 高德地图 POI 查询工具（景点/美食）
├── TravelPlanTool.java        # 行程规划工具（天气+景点美食+路线）
└── EmotionSupportTool.java    # 情绪树洞倾听与 ABC 情绪拆解工具
```

- `ToolDefinition`：定义工具的元数据（名称、描述、参数 Schema）和执行逻辑
- `ToolRegistry`：通过 Spring 自动注入所有 `ToolDefinition` 实现，统一构建 tools JSON 并分发调用
- `BailianService`：通过 `ToolRegistry` 获取所有工具，无需手动维护工具列表

### 已注册工具

#### 天气查询工具（WeatherTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `get_weather` |
| 触发描述 | 查询指定城市的实时天气信息，当用户提到天气、气温、下雨、下雪、天气预报等关键词时调用 |
| 参数 | `city`（string，必填）：城市名称，如"北京"、"上海" |
| 数据来源 | [wttr.in](https://wttr.in) 开放天气接口 |
| 依赖服务 | `WeatherService` |

示例对话：
> 用户：杭州今天天气怎么样？
> AI 自动调用 `get_weather(city="杭州")` → 返回实时天气信息

#### 天聚数行新闻查询工具（NewsTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `search_news` |
| 触发描述 | 天聚数行新闻查询，搜索最新新闻和时事资讯。当用户提到新闻、时事、热点、头条、最新消息等关键词时调用 |
| 参数 | `keyword`（string，必填）：新闻搜索关键词，如"AI"、"奥运会"、"股市" |
| 数据来源 | [天聚数行](https://www.tianapi.com) 新闻 API |
| 依赖服务 | `NewsService` |
| 必需配置 | `application.yml` 中配置 `tianapi.key` |

示例对话：
> 用户：最近有什么科技新闻？
> AI 自动调用 `search_news(keyword="科技")` → 返回 5 条相关新闻摘要

#### 天聚数行物流查询工具（LogisticsTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `query_logistics` |
| 触发描述 | 查询快递物流信息，或列出支持的快递公司。当用户提供快递单号、询问快递/物流/包裹/运输进度/签收状态/物流轨迹时调用；当用户询问支持哪些快递公司时，action 设为 list_companies |
| 参数 | `action`（string）：`query`=查询物流，`list_companies`=列出支持的快递公司；`number`（string，action=query 时必填）：快递单号；`company`（string，可选但建议填写）：快递公司名称；`sender_phone_last4`（string，可选）：手机号后四位 |
| 支持快递公司 | 顺丰、中通、圆通、申通、韵达、京东、EMS、极兔、德邦、菜鸟、百世、天天、丰网 |
| 数据来源 | [天聚数行](https://www.tianapi.com) 快递查询 API |
| 依赖服务 | `LogisticsService` |
| 必需配置 | `application.yml` 中配置 `tianapi.key` |

**智能重试机制**：当用户未指定快递公司且查询失败时，系统会自动尝试多家候选快递公司（最多重试 3 家），特别针对纯数字单号（如 12 位、13 位、16 位）的号段重叠问题进行了优化。

**纯数字单号提示**：纯数字快递单号可能存在多家公司号段重叠的情况，如果查询失败，AI 会引导用户提供快递公司名称以提高准确率。

示例对话：
> 用户：帮我查一下顺丰快递 SF1234567890
> AI 自动调用 `query_logistics(action="query", number="SF1234567890", company="顺丰")` → 返回物流轨迹

> 用户：查一下 7512345678901234
> AI 自动调用 `query_logistics(action="query", number="7512345678901234")` → 若首次失败，自动尝试中通、圆通、韵达等候选公司

> 用户：你们支持查哪些快递公司？
> AI 自动调用 `query_logistics(action="list_companies")` → 返回支持的快递公司列表

#### 文件解析工具（FileParseTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `parse_file` |
| 触发描述 | 解析用户发送的文档文件内容。当用户发送了文件并希望查看内容、总结要点、提取信息、回答文件相关问题时调用 |
| 参数 | `action`（string，必填）：`info`=获取文件信息和预览，`full`=获取完整内容，`chunk`=获取指定分片；`file_name`（string，可选）：文件名；`chunk_index`（integer，可选）：分片索引 |
| 支持格式 | TXT、Markdown、CSV、JSON、XML、YAML、PDF、Word(.docx) |
| 依赖服务 | `FileParseService`（基于 Apache POI + PDFBox） |

**文件解析流程**：
1. 用户在微信发送文件 → WeChatService 下载并识别文件类型
2. 若文件类型可解析 → FileParseService 提取文本内容并缓存
3. 文件内容作为用户消息的一部分发送给 LLM
4. LLM 可直接基于内容回答，或通过 `parse_file` 工具获取更详细的分片内容

**大文件分片**：超过 4000 字符的文件内容会自动分片，AI 可通过 `action=chunk` 和 `chunk_index` 参数逐片获取。

示例对话：
> 用户：[发送文件 report.pdf] 总结这份报告的要点
> AI 自动解析 PDF 内容 → 基于内容生成总结

> 用户：[发送文件 data.csv] 这个表格里有多少条数据？
> AI 自动解析 CSV 内容 → 回答数据条数

#### 文案生成工具箱（CopywritingTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `generate_copywriting` |
| 触发描述 | 文案生成工具箱，支持多种文案创作场景。当用户提到朋友圈文案、治愈短句、道歉文案、纪念日文案、小众签名等需求时调用 |
| 参数 | `type`（string，必填）：文案类型，可选 `moments`=朋友圈文案、`healing`=治愈短句、`apology`=道歉文案、`anniversary`=纪念日文案、`signature`=小众签名；`topic`（string，必填）：主题或场景描述；`count`（integer，可选）：生成条数，默认 3 条；`style`（string，可选）：风格偏好 |
| 依赖服务 | 无外部依赖，由工具返回创作框架和风格指引，AI 在此基础上生成文案 |

示例对话：
> 用户：帮我写几条去三亚旅游的朋友圈文案
> AI 自动调用 `generate_copywriting(type="moments", topic="三亚旅游")` → 返回多条高质量文案

#### 高德地图 POI 查询工具（AmapTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `search_city_poi` |
| 触发描述 | 查询指定城市的热门景点和特色美食。当用户提到旅游、景点、好玩的地方、美食、好吃的、餐厅推荐、城市攻略等关键词时调用 |
| 参数 | `city`（string，必填）：城市名称；`type`（string，可选）：`attractions`=只查景点、`food`=只查美食、`both`=两者都查，默认 `both` |
| 数据来源 | [高德地图](https://lbs.amap.com) POI 搜索 API |
| 依赖服务 | `AmapService` |
| 必需配置 | `application.yml` 中配置 `amap.key` |

示例对话：
> 用户：成都有什么好玩的地方？
> AI 自动调用 `search_city_poi(city="成都")` → 返回景点和美食推荐

> 用户：杭州有什么好吃的？
> AI 自动调用 `search_city_poi(city="杭州", type="food")` → 返回美食推荐

#### 行程规划工具（TravelPlanTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `plan_travel` |
| 触发描述 | 城市旅行行程规划助手。综合查询城市天气、热门景点、特色美食，并支持路线规划。当用户提到旅行规划、行程安排、旅游攻略、出行计划、周末去哪玩等关键词时调用 |
| 参数 | `city`（string，必填）：目标城市；`travel_type`（string，可选）：`attractions`/`food`/`both`，默认 `both`；`origin`（string，可选）：出发地；`destination`（string，可选）：目的地；`transport_mode`（string，可选）：`driving`/`transit`/`walking`/`riding`，默认 `driving` |
| 数据来源 | [wttr.in](https://wttr.in) 天气 API + [高德地图](https://lbs.amap.com) POI/路线 API |
| 依赖服务 | `WeatherService`、`AmapService` |
| 必需配置 | `application.yml` 中配置 `amap.key` |

**功能特点**：综合天气查询 + 景点美食推荐 + 多模式路线规划（驾车/公交/步行/骑行），为用户提供一站式旅行参考信息。

示例对话：
> 用户：我想周末去杭州玩，从上海出发
> AI 自动调用 `plan_travel(city="杭州", origin="上海", transport_mode="driving")` → 返回天气+景点美食+路线规划

#### 情绪支持工具（EmotionSupportTool）

| 属性 | 值 |
|------|------|
| 工具名称 | `emotional_support` |
| 触发描述 | 提供情绪树洞倾听和 ABC 情绪拆解支持。当用户表现出情绪倾诉、心情低落、需要心理支持等需求时调用 |
| 参数 | `sessionId`（string，必填）：当前会话标识；`mode`（string，必填）：`listen`=纯倾听模式、`abc`=ABC 情绪拆解模式；`confession`（string，必填）：用户本次倾诉内容 |
| 依赖服务 | `EmotionMemoryService` |

**ABC 情绪拆解流程**：基于心理学 ABC 理论，分步骤引导用户完成情绪梳理：
1. **A（事件）**：引导用户描述引发情绪的具体事件
2. **B（信念）**：帮助用户识别当时的想法和信念
3. **C（情绪）**：引导用户描述情绪和身体感受
4. **D（反驳）**：引导用户尝试反驳不合理信念
5. **E（新信念）**：帮助用户形成更理性的新信念

示例对话：
> 用户：今天心情好差，感觉什么都不顺
> AI 自动调用 `emotional_support(sessionId="xxx", mode="listen", confession="今天心情好差...")` → 提供共情回应，并可引导进入 ABC 拆解

### 新增工具指南

新增一个 AI 可调用工具只需 3 步，**无需修改任何其他类的代码**：

1. **在 `com.claw.tools` 包下创建实现类**，实现 `ToolDefinition` 接口
2. **加上 `@Component` 注解**，让 Spring 自动扫描
3. **实现 4 个方法**：

```java
@Component
public class MyNewTool implements ToolDefinition {

    @Override
    public String name() {
        return "my_tool";  // snake_case 命名
    }

    @Override
    public String description() {
        return "工具描述，告诉 AI 什么时候调用此工具";
    }

    @Override
    public JSONObject parametersSchema() {
        // 返回 JSON Schema，定义工具参数
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONObject param = new JSONObject();
        param.put("type", "string");
        param.put("description", "参数描述");
        properties.put("param_name", param);

        parameters.put("properties", properties);
        parameters.put("required", new JSONArray().fluentAdd("param_name"));
        return parameters;
    }

    @Override
    public String execute(String arguments) {
        // 解析参数并执行业务逻辑
        JSONObject args = JSON.parseObject(arguments);
        return "执行结果";
    }
}
```

`ToolRegistry` 会在启动时自动发现并注册新工具，`BailianService` 会在 AI 对话中自动将其纳入 Function Calling 工具列表。

---

## 构建与运行

```bash
# 构建
mvnw clean package -DskipTests

# 运行（确保 application.yml 已配置）
java -jar target/wechat-bot-*.jar
```

当前关于上下文消息管理，有内存和数据库两种方式，分别对应 `application.yml` 中的 `context.memory` 和 `context.database` 配置。
流程：用户登录后可以  
1.开启新对话，清理内存和Mysql,回复已重置
2.继续上次，强制从Mysql加载，回复已恢复
3.正常发送消息的话，先判断内存有没有数据，有直接用，没有的话查看Mysql，最后活跃小于1小时，加载到内存，接着聊，最后活跃大于1小时或者没数据，就当新对话，重新聊。

定时任务接入了新闻，天气，物流查询三个工具，单独做另一个agent循环子链路，循环判断自然对话中需要调用什么工具。


现在问题，简历匹配之后他这条路就算走完了，不在就建立匹配这方面继续追问，再发消息也是一个新话题，还有直接查景点和美食，通过大模型回复，他回复的模式要优化一下，不能看起来一团糟


对于发送文件后，判断是文件解析，简历匹配还是Excel读取导出，做一个轻量会话状态机。先判断当前会话任务态，再决定是否继续，切换，确认。解决用户继续追问时，不跑偏；用户开启新话题时，能及时脱离文件上下文。当上传可解析文档后，默认不立刻强进，明显问内容时总结，上传Excel文件时，弱绑定，当说读取/导出时，进入；发岗位链接时进入岗位匹配。
