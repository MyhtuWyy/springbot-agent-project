# 306-token 微信 AI 机器人

基于 **Spring Boot 3 + Java 21 + 阿里云 DashScope** 的多工具 AI 助手项目，支持微信机器人、桌面端聊天、Function Calling 工具调用、文件解析、简历问答、岗位匹配、发票识别、语音识别/合成、定时任务和 MCP 扩展工具。

> 说明：仓库不提交真实 `application.yml` 和密钥。拉取项目后，需要按本文档创建本地配置文件或配置环境变量后再运行。

## 功能概览

| 能力 | 说明 |
|---|---|
| AI 对话 | 接入 DashScope 兼容 OpenAI Chat Completions 接口，支持工具调用和多轮上下文 |
| 微信机器人 | 支持微信登录、收发文本、图片、文件、语音等消息 |
| 桌面端 | `desktop-app` 提供 React + Vite + Tauri 桌面聊天界面 |
| 天气/新闻/物流 | 天气使用 wttr.in，新闻和物流使用天聚数行 |
| 地图和出行 | 高德地图 POI、路线规划、旅行攻略，高铁票查询使用聚合数据接口 |
| 文件解析 | 支持 TXT、Markdown、CSV、JSON、XML、YAML、PDF、Word |
| Excel 工具 | 支持 Excel 查询、字段汇总、结果导出 |
| 简历知识库 | 简历上传、分块、向量检索、简历问答 |
| 岗位匹配 | 根据岗位链接和简历内容生成匹配分析 |
| 发票识别 | 阿里云 OCR 发票识别、校验、台账行生成、Excel 导出 |
| 语音能力 | DashScope ASR/TTS，支持语音转写和语音回复 |
| 定时任务 | 可创建提醒任务，并在任务中接入新闻、天气、物流等工具 |
| MCP 扩展 | 支持用户配置 MCP Server，并动态加载外部工具 |

## 技术栈

| 模块 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.3、Spring Web、Spring Data JPA |
| 数据库 | MySQL |
| AI 模型 | 阿里云 DashScope / 通义千问 |
| 向量检索 | Qdrant |
| 文档解析 | Apache POI、PDFBox |
| HTTP 客户端 | OkHttp、Fastjson2 |
| 微信接入 | wechat-ilink-sdk |
| 桌面端 | React 19、Vite、Tauri 2 |

## 项目结构

```text
306-token
├─ src/main/java/com/claw
│  ├─ controller/       # REST API：认证、桌面端、简历、发票、MCP、模型配置等
│  ├─ service/          # 核心业务：AI 编排、工具路由、文件解析、微信、OCR、简历等
│  ├─ tools/            # Function Calling 工具定义和注册
│  ├─ config/           # CORS、MCP、Excel MCP、OCR、调度等配置
│  ├─ entity/           # JPA 实体
│  ├─ repository/       # 数据库访问层
│  ├─ dto/              # 请求/响应 DTO
│  ├─ mcp/              # MCP Client、进程管理和资源限制
│  └─ util/             # 配置、HTTP 等通用工具
├─ src/skills/com/claw/skills
│  ├─ FileParseSkill.java       # 文件解析技能
│  ├─ InvoiceSkill.java         # 发票 OCR 技能
│  ├─ ResumeRagSkill.java       # 简历 RAG 技能
│  ├─ ScheduledTaskSkill.java   # 定时任务技能
│  └─ SpeechSkill.java          # 语音技能
├─ src/main/resources
│  ├─ application.yml           # 本地配置文件，仓库中建议不提交真实值
│  └─ logback.xml               # 日志配置
├─ desktop-app                  # React + Vite + Tauri 桌面端
├─ docs                         # 项目文档
├─ scripts                      # 辅助脚本
├─ pom.xml                      # Maven 后端工程
└─ .env.example                 # 环境变量示例
```

## 已有工具

### Function Calling 工具

| 工具名 | 类 | 用途 |
|---|---|---|
| `get_weather` | `WeatherTool` | 查询城市或地点天气 |
| `search_news` | `NewsTool` | 查询新闻和热点资讯 |
| `query_logistics` | `LogisticsTool` | 查询快递物流或列出支持的快递公司 |
| `parse_file` | `FileParseTool` | 解析上传文件、读取分片内容 |
| `generate_copywriting` | `CopywritingTool` | 朋友圈、治愈短句、道歉、纪念日、签名等文案 |
| `search_poi` | `PoiTool` | 查询景点、美食、附近推荐 |
| `query_route` | `RouteTool` | 查询驾车、公交、步行等路线建议 |
| `plan_travel` | `TravelPlanTool` | 生成旅行攻略，组合天气、景点、美食等信息 |
| `query_train_tickets` | `TrainTicketTool` | 查询高铁/火车票；查不到车次时不虚构结果 |
| `emotional_support` | `EmotionSupportTool` | 倾听和 ABC 情绪拆解 |
| `resume_rag` | `ResumeRagTool` | 基于简历知识库问答 |
| `resume_list` | `ResumeListTool` | 查询已上传简历列表 |
| `job_match_url` | `JobMatchUrlTool` | 根据岗位链接进行简历匹配 |
| `excel_query_file` | `ExcelQueryTool` | 查询 Excel 表格内容 |
| `excel_summarize_columns` | `ExcelSummarizeTool` | 汇总 Excel 指定列 |
| `excel_export_result` | `ExcelExportTool` | 导出 Excel 查询/汇总结果 |

### Skill 工具

| Skill | 用途 |
|---|---|
| `FileParseSkill` | 文件解析和文件问答 |
| `InvoiceSkill` | 发票 OCR、校验、导出 |
| `ResumeRagSkill` | 简历上传、检索和问答 |
| `ScheduledTaskSkill` | 提醒、闹钟、定时推送 |
| `SpeechSkill` | ASR 语音识别和 TTS 语音合成 |

## 环境要求

- JDK 21
- Maven Wrapper：项目自带 `mvnw` / `mvnw.cmd`
- MySQL 8.x
- Node.js 20+ 和 npm
- 可选：Qdrant，用于简历向量检索
- 可选：Rust + Tauri CLI，用于桌面端打包

## 快速开始

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd 306-token
```

### 2. 准备数据库

创建 MySQL 数据库：

```sql
CREATE DATABASE wechat_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

本项目使用 JPA，开发环境可将 `spring.jpa.hibernate.ddl-auto` 设置为 `update` 自动建表。

### 3. 创建本地配置

如果仓库中没有 `src/main/resources/application.yml`，请自行创建。推荐写法是：敏感值从环境变量读取，本地只保留占位配置。

```yaml
server:
  port: 8080

spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/wechat_bot?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false

app:
  cli:
    enabled: true
  security:
    encryption-key: ${APP_CONFIG_ENCRYPTION_KEY}
  mcp:
    allowed-commands: ${APP_MCP_ALLOWED_COMMANDS:node,npx,python,python3,uvx}
    allowed-working-roots: ${APP_MCP_ALLOWED_WORKING_ROOTS:D:/mcp-server-files}

dashscope:
  api-key: ${DASHSCOPE_API_KEY}
  base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
  model: ${DASHSCOPE_MODEL:qwen-turbo}
  vl-model: ${DASHSCOPE_VL_MODEL:qwen-vl-plus}
  asr:
    model: ${DASHSCOPE_ASR_MODEL:qwen3-asr-flash}
    language: ${DASHSCOPE_ASR_LANGUAGE:zh}
  tts:
    model: ${DASHSCOPE_TTS_MODEL:cosyvoice-v3-flash}
    voice-id: ${DASHSCOPE_TTS_VOICE_ID:longanhuan}
    sample-rate: 16000
    format: wav
    speed: 1.0
    volume: 1.0
    pitch: 0

tianapi:
  key: ${TIANAPI_KEY:}

amap:
  key: ${AMAP_API_KEY:}

travel:
  train-ticket:
    api-key: ${TRAVEL_TRAIN_TICKET_API_KEY:}
    api-url: ${TRAVEL_TRAIN_TICKET_API_URL:https://apis.juhe.cn/fapigw/train/query}

qdrant:
  url: ${QDRANT_URL:http://localhost:6333}
  api-key: ${QDRANT_API_KEY:}
  collection: ${QDRANT_COLLECTION:resume_chunks}
  vector-size: ${QDRANT_VECTOR_SIZE:128}

conversation:
  max-messages: 20
  expire-minutes: 60
  resume-window-minutes: 60

excel:
  mcp:
    enabled: false
    file-root: ${EXCEL_MCP_FILE_ROOT:D:/mcp-server-files/excel}
    preview-row-limit: 10
    max-read-rows: 2000
    max-export-rows: 5000

aliyun:
  ocr:
    enabled: ${ALIYUN_OCR_ENABLED:false}
    access-key-id: ${ALIYUN_OCR_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OCR_ACCESS_KEY_SECRET:}
    endpoint: ${ALIYUN_OCR_ENDPOINT:ocr.cn-shanghai.aliyuncs.com}
    auto-verify: true
    max-file-size-mb: 10

wechat:
  voice:
    default-encode-type: 1
    silk-encode-type: 6
    default-bits-per-sample: 16
    include-transcript: false
    segment-interval-ms: 250
```

### 4. 配置环境变量

可以参考根目录 `.env.example`。至少需要配置：

| 环境变量 | 必需 | 说明 |
|---|---:|---|
| `APP_CONFIG_ENCRYPTION_KEY` | 是 | 用户模型配置 API Key 的加密密钥，建议 32 位以上随机字符串 |
| `DASHSCOPE_API_KEY` | 是 | 阿里云 DashScope API Key |
| `SPRING_DATASOURCE_URL` | 是 | MySQL JDBC 地址 |
| `SPRING_DATASOURCE_USERNAME` | 是 | MySQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 是 | MySQL 密码 |
| `TIANAPI_KEY` | 否 | 新闻、物流查询需要 |
| `AMAP_API_KEY` | 否 | POI、路线、旅行规划需要 |
| `TRAVEL_TRAIN_TICKET_API_KEY` | 否 | 高铁票查询需要 |
| `ALIYUN_OCR_ACCESS_KEY_ID` | 否 | 发票 OCR 需要 |
| `ALIYUN_OCR_ACCESS_KEY_SECRET` | 否 | 发票 OCR 需要 |
| `QDRANT_URL` | 否 | 简历向量检索需要 |
| `QDRANT_API_KEY` | 否 | Qdrant 有鉴权时填写 |

Windows PowerShell 示例：

```powershell
$env:APP_CONFIG_ENCRYPTION_KEY="replace-with-random-secret"
$env:DASHSCOPE_API_KEY="sk-xxx"
$env:SPRING_DATASOURCE_USERNAME="root"
$env:SPRING_DATASOURCE_PASSWORD="your-password"
```

macOS / Linux 示例：

```bash
export APP_CONFIG_ENCRYPTION_KEY="replace-with-random-secret"
export DASHSCOPE_API_KEY="sk-xxx"
export SPRING_DATASOURCE_USERNAME="root"
export SPRING_DATASOURCE_PASSWORD="your-password"
```

## 运行后端

Windows：

```powershell
.\mvnw.cmd clean package -DskipTests
java -jar target/wechat-bot-1.0-SNAPSHOT.jar
```

macOS / Linux：

```bash
./mvnw clean package -DskipTests
java -jar target/wechat-bot-1.0-SNAPSHOT.jar
```

启动后默认监听：

```text
http://127.0.0.1:8080
```

可用接口示例：

| 接口 | 说明 |
|---|---|
| `GET /api/auth/capabilities` | 检查后端是否可用 |
| `POST /api/auth/register` | 桌面端注册 |
| `POST /api/auth/login` | 桌面端登录 |
| `POST /api/desktop/chat` | 桌面端普通聊天 |
| `POST /api/desktop/chat/stream` | 桌面端流式聊天 |
| `POST /api/desktop/files/upload` | 上传文件 |
| `GET /api/system/status` | 系统状态 |

## 运行桌面端

进入桌面端目录并安装依赖：

```bash
cd desktop-app
npm install
```

开发运行：

```bash
npm run dev
```

`desktop-app/scripts/dev.ps1` 会检查 `8080` 后端是否可用；如果后端未启动，会尝试先打包并启动 Spring Boot 后端。

只构建前端：

```bash
npm run build
```

Tauri 开发模式：

```bash
npm run tauri:dev
```

## 常用配置说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `8080` | 后端 HTTP 端口 |
| `app.cli.enabled` | `true` | 是否启用命令行交互 |
| `dashscope.model` | `qwen-turbo` | 文本对话模型 |
| `dashscope.vl-model` | `qwen-vl-plus` | 图片理解模型 |
| `dashscope.asr.model` | `qwen3-asr-flash` | 语音识别模型 |
| `dashscope.tts.model` | `cosyvoice-v3-flash` | 语音合成模型 |
| `conversation.max-messages` | `20` | 单会话最大上下文消息数 |
| `conversation.expire-minutes` | `60` | 内存会话过期时间 |
| `conversation.resume-window-minutes` | `60` | 可从数据库恢复上下文的窗口 |
| `qdrant.collection` | `resume_chunks` | 简历向量集合 |
| `excel.mcp.enabled` | `false` | 是否启用 Excel MCP 能力 |
| `aliyun.ocr.enabled` | `false` | 是否启用阿里云 OCR |

## 数据来源

| 能力 | 数据源 |
|---|---|
| 文本/视觉/语音模型 | [阿里云 DashScope](https://dashscope.aliyun.com/) |
| 天气 | [wttr.in](https://wttr.in) |
| 新闻、物流 | [天聚数行](https://www.tianapi.com) |
| POI、地理编码、路线 | [高德开放平台](https://lbs.amap.com) |
| 高铁/火车票 | [聚合数据](https://www.juhe.cn/) |
| 发票 OCR | 阿里云 OCR |
| 简历向量检索 | Qdrant |

## 开发说明

### 新增 Function Calling 工具

新增一个工具只需要：

1. 在 `src/main/java/com/claw/tools` 下创建类并实现 `ToolDefinition`
2. 添加 `@Component`
3. 实现 `name()`、`description()`、`parametersSchema()`、`execute()`

示例：

```java
@Component
public class MyTool implements ToolDefinition {
    @Override
    public String name() {
        return "my_tool";
    }

    @Override
    public String description() {
        return "说明 AI 什么时候应该调用这个工具";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", new JSONObject());
        schema.put("required", new JSONArray());
        return schema;
    }

    @Override
    public String execute(String arguments) {
        return "执行结果";
    }
}
```

`ToolRegistry` 会在启动时自动收集所有 `ToolDefinition` Bean，`BailianService` 会把它们纳入工具调用列表。

### 本地文件和敏感信息

- 不要提交真实 `application.yml`、`.env`、API Key、数据库密码。
- 推荐提交 `.env.example` 或 README 中的配置模板。
- `target/`、`logs/`、`desktop-app/node_modules/`、`desktop-app/dist/` 属于本地生成内容，通常不需要提交。

## 排查建议

| 问题 | 检查项 |
|---|---|
| 后端启动失败 | JDK 是否为 21、MySQL 是否启动、`application.yml` 是否存在 |
| AI 无回复或鉴权失败 | `DASHSCOPE_API_KEY` 是否正确，模型是否有权限 |
| 新闻/物流不可用 | `TIANAPI_KEY` 是否配置 |
| 地图/旅行规划不可用 | `AMAP_API_KEY` 是否配置 |
| 高铁票查不到 | `TRAVEL_TRAIN_TICKET_API_KEY` 是否配置，接口是否返回空结果 |
| 发票识别不可用 | `aliyun.ocr.enabled` 和阿里云 OCR 密钥是否配置 |
| 桌面端连不上后端 | 确认后端 `8080` 端口正常，或设置 `VITE_API_BASE_URL` |
