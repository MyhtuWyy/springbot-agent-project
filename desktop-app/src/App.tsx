import { invoke } from "@tauri-apps/api/core";
import {
  ChangeEvent,
  FormEvent,
  KeyboardEvent,
  memo,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type PointerEvent as ReactPointerEvent,
} from "react";
import "./App.css";

type Message = {
  id: string;
  role: "assistant" | "user";
  content: string;
  pending?: boolean;
  meta?: {
    exportInfo?: FileExportInfo;
  };
  attachment?: {
    kind: "image";
    fileName: string;
    previewUrl: string;
  };
};

type Overview = {
  appName: string;
  backendReady: boolean;
  hasDashScopeKey: boolean;
  weChatRunning: boolean;
  ttsModel: string;
  ttsVoiceId: string;
};

type SystemStatus = {
  dashScopeConfigured: boolean;
  envSource: string;
  ttsModel: string;
  ttsVoiceId: string;
  weChatRunning: boolean;
};

type SkillDescriptor = {
  name: string;
  available: boolean;
  enabled: boolean;
};

type SessionSummary = {
  sessionId: string;
  title: string;
  preview: string;
  lastActive: string;
  messageCount: number;
  currentMode: string;
};

type StreamEvent = {
  type: "status" | "delta" | "done" | "error";
  sessionId?: string;
  message?: string;
  delta?: string;
  reply?: string;
  error?: string;
};

type DesktopMessage = {
  role: "assistant" | "user";
  content: string;
};

type DesktopFileUploadResponse = {
  sessionId: string;
  fileName: string;
  fileType: string;
  parseable: boolean;
  preview: string;
  message: string;
};

type FileExportInfo = {
  fileName: string;
  filePath: string;
  sheetName: string;
};

type SkillResult = {
  success: boolean;
  skillName: string;
  message: string;
  data: Record<string, unknown>;
  error?: string | null;
  durationMs?: number;
  timeout?: boolean;
};

type MonitorRecentRequest = {
  requestId: string;
  sessionId: string;
  channel: string;
  requestType: string;
  routeType: string;
  routeTarget: string | null;
  model: string | null;
  stream: boolean;
  success: boolean;
  durationMs: number;
  firstTokenMs: number | null;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  toolCallCount: number;
  startedAt: string;
  errorMessage: string | null;
};

type MonitorSummary = {
  currentChatModel: string;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  averageDurationMs: number;
  averageFirstTokenMs: number | null;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  recentRequests: MonitorRecentRequest[];
};

type AuthUser = { id: number; username: string; displayName: string };
type AuthResponse = { token: string; expiresInSeconds: number; user: AuthUser };
type ModelProfile = { id: number; provider: string; baseUrl: string; model: string; maskedApiKey: string; temperature: number | null; maxTokens: number | null; defaultProfile: boolean };
type McpServer = { id: number; serverKey: string; displayName: string; command: string; args: string[]; workingDirectory: string; environmentKeys: string[]; enabled: boolean; timeoutMs: number; maxConcurrentRequests: number; maxMemoryMb: number; maxResponseBytes: number; maxCpuSeconds: number; maxCpuPercent: number };
type McpStatus = { state: "RUNNING" | "FAILED" | "STOPPED"; pid: number | null; startedAt: string | null; pendingRequests: number; stderr: string[]; error: string | null; resourceLimitMode: string | null };
type McpTool = { name: string; remoteName: string; description: string; enabled: boolean };
const EMPTY_MCP_FORM = { serverKey: "", displayName: "", command: "", args: "", workingDirectory: "", environment: "", timeoutMs: "10000", maxConcurrentRequests: "4", maxMemoryMb: "256", maxResponseBytes: "1048576", maxCpuSeconds: "300", maxCpuPercent: "50", enabled: false };

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.trim() || "http://127.0.0.1:8080";
const SESSION_STORAGE_KEY = "desktop-chat-session-id";
const AUTH_TOKEN_STORAGE_KEY = "desktop-auth-token";
const AI_AVATAR_STORAGE_KEY = "desktop-chat-ai-avatar";
const USER_AVATAR_STORAGE_KEY = "desktop-chat-user-avatar";
const MAX_VISIBLE_SESSIONS = 60;
const DEFAULT_VISIBLE_SESSION_COUNT = 5;
const RECENT_SESSION_DAYS = 30;
const DEFAULT_REQUEST_TIMEOUT_MS = 4000;
const STREAM_FLUSH_INTERVAL_MS = 80;
const TREND_CHART_WIDTH = 560;
const TREND_CHART_HEIGHT = 220;
const TREND_CHART_PADDING = 18;

function getStoredValue(key: string) {
  if (typeof window === "undefined") return "";
  return window.localStorage.getItem(key) || "";
}

function formatCount(value: number | null | undefined) {
  if (!value) return "0";
  return new Intl.NumberFormat("zh-CN").format(value);
}

function formatDuration(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return "--";
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

function formatRate(part: number, total: number) {
  if (!total) return "0%";
  return `${((part / total) * 100).toFixed(0)}%`;
}

function formatMonitorTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function formatRouteLabel(request: MonitorRecentRequest) {
  const routeBase = request.routeType || "unknown";
  return request.routeTarget ? `${routeBase} · ${request.routeTarget}` : routeBase;
}

function formatMonitorBucketLabel(value: number) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatPercent(value: number) {
  return `${Math.max(0, Math.min(100, value)).toFixed(0)}%`;
}

function formatTrendDelta(current: number, previous: number | null) {
  if (!Number.isFinite(current)) return "--";
  if (previous == null || !Number.isFinite(previous)) return "暂无对比";
  if (previous <= 0) return current > 0 ? "新增" : "持平";
  const delta = ((current - previous) / previous) * 100;
  if (Math.abs(delta) < 1) return "基本持平";
  return `${delta > 0 ? "+" : ""}${delta.toFixed(0)}%`;
}

function buildSparklinePath(values: number[], width: number, height: number, padding = 10) {
  if (!values.length) {
    return "";
  }
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const range = max - min || 1;
  const stepX = values.length === 1 ? 0 : (width - padding * 2) / (values.length - 1);

  return values
    .map((value, index) => {
      const x = padding + stepX * index;
      const y = height - padding - ((value - min) / range) * (height - padding * 2);
      return `${index === 0 ? "M" : "L"} ${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(" ");
}

function buildSparklineArea(path: string, width: number, height: number, padding = 10) {
  if (!path) {
    return "";
  }
  const lastPoint = `${width - padding} ${height - padding}`;
  const firstPoint = `${padding} ${height - padding}`;
  return `${path} L ${lastPoint} L ${firstPoint} Z`;
}

function buildEmptyMessage(sessionId: string): Message[] {
  return [
    {
      id: `empty-${sessionId}`,
      role: "assistant",
      content: "当前会话暂无消息，先发一条试试。",
    },
  ];
}

function mapHistoryToMessages(sessionId: string, history: DesktopMessage[]): Message[] {
  if (!history.length) return buildEmptyMessage(sessionId);
  return history.map((item, index) => ({
    id: `${sessionId}-${index}-${item.role}`,
    role: item.role,
    content: item.content,
  }));
}

function trimEmptyMessage(current: Message[]) {
  return current.some((item) => item.id.startsWith("empty-")) ? [] : [...current];
}

function normalizeSearch(value: string) {
  return value.trim().toLowerCase();
}

function sanitizeAssistantContent(content: string) {
  if (!content) return "";
  const cleaned = content
    .replace(/^.*(?:正在调用|调用`?parse_file`?|工具解析|工具调用).*$/gm, "")
    .replace(/(^\s*\d+\..+)\n\s*\n(?=\s*\d+\.)/gm, "$1\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  const legacyType = cleaned.match(/(?:^|\n)类型[：:]\s*(moments|healing|apology|anniversary|signature)\s*(?:\n|$)/i)?.[1]?.toLowerCase();
  if (!legacyType) return cleaned;

  const legacyTitles: Record<string, string> = {
    moments: "🌿 朋友圈文案",
    healing: "🌙 治愈短句",
    apology: "💬 道歉文案",
    anniversary: "✨ 纪念日文案",
    signature: "✍️ 个性签名",
  };
  const body = cleaned
    .replace(/^【文案生成】\s*/m, "")
    .replace(/^类型[：:].*$/m, "")
    .replace(/^主题[：:].*$/m, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  return `### ${legacyTitles[legacyType] || "📝 文案"}\n\n${body}`;
}

function stripMarkdownForSpeech(content: string) {
  return content
    .replace(/```[^\n]*\n?/g, "")
    .replace(/```/g, "")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^\s*>\s?/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/^\s*\d+\.\s+/gm, "")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*\n]+)\*/g, "$1")
    .replace(/~~([^~]+)~~/g, "$1")
    .replace(/\|/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function renderInlineMarkdown(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern =
    /(\[([^\]]+)\]\((https?:\/\/[^\s)]+)\))|(`([^`]+)`)|(\*\*([^*]+)\*\*)|(~~([^~]+)~~)|(\*([^*\n]+)\*)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }

    if (match[2] && match[3]) {
      nodes.push(
        <a
          key={`${keyPrefix}-link-${match.index}`}
          href={match[3]}
          target="_blank"
          rel="noreferrer"
        >
          {match[2]}
        </a>,
      );
    } else if (match[5]) {
      nodes.push(
        <code key={`${keyPrefix}-code-${match.index}`}>{match[5]}</code>,
      );
    } else if (match[7]) {
      nodes.push(
        <strong key={`${keyPrefix}-strong-${match.index}`}>{match[7]}</strong>,
      );
    } else if (match[9]) {
      nodes.push(
        <del key={`${keyPrefix}-del-${match.index}`}>{match[9]}</del>,
      );
    } else if (match[11]) {
      nodes.push(
        <em key={`${keyPrefix}-em-${match.index}`}>{match[11]}</em>,
      );
    }

    lastIndex = pattern.lastIndex;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }

  return nodes;
}

function renderMarkdownTextWithBreaks(text: string, keyPrefix: string): ReactNode[] {
  return text.split("\n").flatMap((line, index, array) => {
    const nodes = renderInlineMarkdown(line, `${keyPrefix}-line-${index}`);
    if (index === array.length - 1) {
      return nodes;
    }
    return [...nodes, <br key={`${keyPrefix}-br-${index}`} />];
  });
}

function parseMarkdownTableRow(line: string) {
  let normalized = line.trim();
  if (normalized.startsWith("|")) {
    normalized = normalized.slice(1);
  }
  if (normalized.endsWith("|")) {
    normalized = normalized.slice(0, -1);
  }
  return normalized.split("|").map((cell) => cell.trim());
}

function isMarkdownTableSeparator(line: string) {
  return /^[\s|:-]+$/.test(line) && line.includes("-");
}

function isMarkdownBlockBoundary(line: string) {
  const trimmed = line.trim();
  if (!trimmed) return true;
  return (
    trimmed.startsWith("```") ||
    /^#{1,6}\s+/.test(trimmed) ||
    /^>\s?/.test(trimmed) ||
    /^[-*+]\s+/.test(trimmed) ||
    /^\d+\.\s+/.test(trimmed) ||
    /^(-{3,}|\*{3,})$/.test(trimmed)
  );
}

function renderMarkdownHeading(level: number, content: ReactNode[], key: string) {
  if (level <= 1) return <h1 key={key}>{content}</h1>;
  if (level === 2) return <h2 key={key}>{content}</h2>;
  if (level === 3) return <h3 key={key}>{content}</h3>;
  if (level === 4) return <h4 key={key}>{content}</h4>;
  return <h5 key={key}>{content}</h5>;
}

function renderAssistantMarkdown(content: string) {
  if (!content) return null;
  const lines = content.replace(/\r\n?/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const rawLine = lines[index];
    const trimmed = rawLine.trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed.startsWith("```")) {
      const language = trimmed.slice(3).trim();
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        codeLines.push(lines[index]);
        index += 1;
      }
      if (index < lines.length && lines[index].trim().startsWith("```")) {
        index += 1;
      }
      blocks.push(
        <pre key={`code-${index}`} className="assistant-code-block">
          {language ? <span className="assistant-code-lang">{language}</span> : null}
          <code>{codeLines.join("\n")}</code>
        </pre>,
      );
      continue;
    }

    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      const level = headingMatch[1].length;
      blocks.push(
        renderMarkdownHeading(
          level,
          renderInlineMarkdown(headingMatch[2], `heading-${index}`),
          `heading-${index}`,
        ),
      );
      index += 1;
      continue;
    }

    if (/^(-{3,}|\*{3,})$/.test(trimmed)) {
      blocks.push(<hr key={`hr-${index}`} />);
      index += 1;
      continue;
    }

    if (trimmed.includes("|") && index + 1 < lines.length && isMarkdownTableSeparator(lines[index + 1].trim())) {
      const headers = parseMarkdownTableRow(trimmed);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && lines[index].trim() && lines[index].includes("|")) {
        rows.push(parseMarkdownTableRow(lines[index]));
        index += 1;
      }
      blocks.push(
        <div key={`table-${index}`} className="assistant-table-wrap">
          <table className="assistant-table">
            <thead>
              <tr>
                {headers.map((cell, cellIndex) => (
                  <th key={`table-head-${cellIndex}`}>
                    {renderInlineMarkdown(cell, `table-head-${index}-${cellIndex}`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, rowIndex) => (
                <tr key={`table-row-${rowIndex}`}>
                  {row.map((cell, cellIndex) => (
                    <td key={`table-cell-${rowIndex}-${cellIndex}`}>
                      {renderInlineMarkdown(cell, `table-cell-${index}-${rowIndex}-${cellIndex}`)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      continue;
    }

    if (/^>\s?/.test(trimmed)) {
      const quoteLines: string[] = [];
      while (index < lines.length && /^>\s?/.test(lines[index].trim())) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(
        <blockquote key={`quote-${index}`}>
          {renderMarkdownTextWithBreaks(quoteLines.join("\n"), `quote-${index}`)}
        </blockquote>,
      );
      continue;
    }

    if (/^[-*+]\s+/.test(trimmed)) {
      const items: string[] = [];
      while (index < lines.length && /^[-*+]\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^[-*+]\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ul key={`ul-${index}`}>
          {items.map((item, itemIndex) => (
            <li key={`ul-item-${itemIndex}`}>
              {renderMarkdownTextWithBreaks(item, `ul-${index}-${itemIndex}`)}
            </li>
          ))}
        </ul>,
      );
      continue;
    }

    if (/^\d+\.\s+/.test(trimmed)) {
      const items: string[] = [];
      while (index < lines.length && /^\d+\.\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+\.\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ol key={`ol-${index}`}>
          {items.map((item, itemIndex) => (
            <li key={`ol-item-${itemIndex}`}>
              {renderMarkdownTextWithBreaks(item, `ol-${index}-${itemIndex}`)}
            </li>
          ))}
        </ol>,
      );
      continue;
    }

    const paragraphLines: string[] = [];
    while (index < lines.length) {
      const currentLine = lines[index];
      if (!currentLine.trim() || isMarkdownBlockBoundary(currentLine)) {
        break;
      }
      if (
        currentLine.trim().includes("|") &&
        index + 1 < lines.length &&
        isMarkdownTableSeparator(lines[index + 1].trim())
      ) {
        break;
      }
      paragraphLines.push(currentLine);
      index += 1;
    }

    if (!paragraphLines.length) {
      paragraphLines.push(rawLine);
      index += 1;
    }

    blocks.push(
      <p key={`p-${index}`} className="assistant-markdown-paragraph">
        {renderMarkdownTextWithBreaks(paragraphLines.join("\n"), `p-${index}`)}
      </p>,
    );
  }

  return blocks;
}

function parseSessionTime(value: string) {
  const time = Date.parse(value);
  return Number.isNaN(time) ? 0 : time;
}

function isRecentSession(session: SessionSummary) {
  if (!session.messageCount || session.messageCount <= 0) return false;
  const lastActive = parseSessionTime(session.lastActive);
  if (!lastActive) return false;
  const maxAge = RECENT_SESSION_DAYS * 24 * 60 * 60 * 1000;
  return Date.now() - lastActive <= maxAge;
}

function matchesSession(session: SessionSummary, searchValue: string, cachedMessages?: Message[]) {
  if (!searchValue) return true;
  const haystacks = [
    session.sessionId,
    session.title,
    session.preview,
    session.currentMode,
    String(session.messageCount),
    ...(cachedMessages || []).map((item) => item.content),
  ];
  return haystacks.some((item) => item.toLowerCase().includes(searchValue));
}

function parseFileExportInfo(content: string): FileExportInfo | null {
  const fileName = content.match(/^fileName=(.+)$/m)?.[1]?.trim();
  const filePath = content.match(/^filePath=(.+)$/m)?.[1]?.trim();
  const sheetName = content.match(/^sheetName=(.+)$/m)?.[1]?.trim();
  if (!fileName || !filePath || !sheetName) return null;
  return { fileName, filePath, sheetName };
}

function sameMessages(left: Message[] | undefined, right: Message[]) {
  if (!left || left.length !== right.length) return false;
  return left.every((item, index) => {
    const other = right[index];
    return (
      item.id === other.id &&
      item.role === other.role &&
      item.content === other.content &&
      item.pending === other.pending &&
      item.meta?.exportInfo?.fileName === other.meta?.exportInfo?.fileName &&
      item.meta?.exportInfo?.filePath === other.meta?.exportInfo?.filePath &&
      item.meta?.exportInfo?.sheetName === other.meta?.exportInfo?.sheetName &&
      item.attachment?.kind === other.attachment?.kind &&
      item.attachment?.fileName === other.attachment?.fileName &&
      item.attachment?.previewUrl === other.attachment?.previewUrl
    );
  });
}

function isNearBottom(element: HTMLElement, threshold = 24) {
  const distanceToBottom = element.scrollHeight - element.scrollTop - element.clientHeight;
  return distanceToBottom <= threshold;
}

function readFileAsDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("read file failed"));
    reader.readAsDataURL(file);
  });
}

function readBlobAsDataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("read blob failed"));
    reader.readAsDataURL(blob);
  });
}

type MessageRowProps = {
  message: Message;
  aiAvatarSrc: string;
  userAvatarSrc: string;
  exportInfo: FileExportInfo | null;
  activeSessionId: string;
  savingMessageId: string;
  speakingMessageId: string;
  onSaveExportFile: (sessionId: string, messageId: string, exportInfo: FileExportInfo | null, target: "download" | "desktop") => Promise<void>;
  onSpeakText: (messageId: string, text: string, sessionId: string) => Promise<void>;
};

const ChatMessageRow = memo(function ChatMessageRow({
  message,
  aiAvatarSrc,
  userAvatarSrc,
  exportInfo,
  activeSessionId,
  savingMessageId,
  speakingMessageId,
  onSaveExportFile,
  onSpeakText,
}: MessageRowProps) {
  const assistantContent = message.role === "assistant" ? sanitizeAssistantContent(message.content) : "";
  const speechText = assistantContent ? stripMarkdownForSpeech(assistantContent) : "";

  return (
    <article className={`message-row ${message.role === "user" ? "user" : "assistant"}`}>
      {message.role === "assistant" ? (
        <div className="message-avatar">
          {aiAvatarSrc ? <img src={aiAvatarSrc} alt="AI 头像" /> : <span>AI</span>}
        </div>
      ) : null}

      <div className={`message-bubble ${message.role === "assistant" && speechText.trim() ? "has-speech-action" : ""}`}>
        {message.attachment?.kind === "image" ? (
          <div className="message-image-block">
            <img
              className="message-image-preview"
              src={message.attachment.previewUrl}
              alt={message.attachment.fileName}
            />
            <span className="message-image-name">{message.attachment.fileName}</span>
          </div>
        ) : null}
        {message.role === "assistant" ? (
          <div className="assistant-markdown">
            {assistantContent
              ? renderAssistantMarkdown(assistantContent)
              : <p className="assistant-markdown-paragraph">{message.pending ? "正在回复..." : ""}</p>}
          </div>
        ) : (
          <p>{message.content || (message.pending ? "正在回复..." : "")}</p>
        )}
        {exportInfo ? (
          <div className="message-actions">
            <button
              type="button"
              className="secondary-button"
              onClick={() => void onSaveExportFile(activeSessionId, message.id, exportInfo, "download")}
              disabled={savingMessageId === message.id}
            >
              保存到下载
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={() => void onSaveExportFile(activeSessionId, message.id, exportInfo, "desktop")}
              disabled={savingMessageId === message.id}
            >
              保存到桌面
            </button>
          </div>
        ) : null}
        {message.role === "assistant" && speechText.trim() ? (
          <div className="message-speak-action">
            <button
              type="button"
              className={`speak-button ${speakingMessageId === message.id ? "is-speaking" : ""}`}
              onClick={() => void onSpeakText(message.id, speechText, activeSessionId)}
              disabled={speakingMessageId === message.id}
              aria-label={speakingMessageId === message.id ? "朗读中" : "朗读"}
              title={speakingMessageId === message.id ? "朗读中" : "朗读"}
            >
              <span className="microphone-icon" aria-hidden="true" />
            </button>
          </div>
        ) : null}
      </div>

      {message.role === "user" ? (
        <div className="message-avatar">
          {userAvatarSrc ? <img src={userAvatarSrc} alt="用户头像" /> : <span>我</span>}
        </div>
      ) : null}
    </article>
  );
});

async function fetchJson<T>(url: string, init?: RequestInit, timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || "";
    const headers = new Headers(init?.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(url, { ...init, headers, signal: controller.signal });
    if (!response.ok) {
      handleUnauthorizedResponse(url, response.status);
      throw new Error(`request failed: ${response.status}`);
    }
    return (await response.json()) as T;
  } finally {
    window.clearTimeout(timer);
  }
}

function handleUnauthorizedResponse(url: string, status: number) {
  if (status === 401 && !url.includes("/api/auth/login") && !url.includes("/api/auth/register")) {
    window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
    window.dispatchEvent(new Event("desktop-auth-expired"));
  }
}

function App() {
  const [authUser, setAuthUser] = useState<AuthUser | null>(null);
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [authUsername, setAuthUsername] = useState("");
  const [authPassword, setAuthPassword] = useState("");
  const [authDisplayName, setAuthDisplayName] = useState("");
  const [authError, setAuthError] = useState("");
  const [authBusy, setAuthBusy] = useState(false);
  const [modelProfiles, setModelProfiles] = useState<ModelProfile[]>([]);
  const [modelForm, setModelForm] = useState({ provider: "openai-compatible", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen-plus", apiKey: "", temperature: "0.7", maxTokens: "2000" });
  const [modelSaving, setModelSaving] = useState(false);
  const [modelMessage, setModelMessage] = useState("");
  const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
  const [mcpForm, setMcpForm] = useState(EMPTY_MCP_FORM);
  const [editingMcpId, setEditingMcpId] = useState<number | null>(null);
  const [mcpBusy, setMcpBusy] = useState(false);
  const [mcpMessage, setMcpMessage] = useState("");
  const [mcpTesting, setMcpTesting] = useState<number | null>(null);
  const [mcpTestResults, setMcpTestResults] = useState<Record<number, string>>({});
  const [mcpStatuses, setMcpStatuses] = useState<Record<number, McpStatus>>({});
  const [mcpTools, setMcpTools] = useState<Record<number, McpTool[]>>({});
  const [overview, setOverview] = useState<Overview | null>(null);
  const [systemStatus, setSystemStatus] = useState<SystemStatus | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [skills, setSkills] = useState<SkillDescriptor[]>([]);
  const [skillUpdating, setSkillUpdating] = useState("");
  const [skillMessage, setSkillMessage] = useState("");
  const [monitorSummary, setMonitorSummary] = useState<MonitorSummary | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState(300);
  const [viewMode, setViewMode] = useState<"chat" | "monitor" | "settings">("chat");
  const [hoveredTrendIndex, setHoveredTrendIndex] = useState<number | null>(null);
  const [sessionListExpanded, setSessionListExpanded] = useState(false);
  const [activeSessionId, setActiveSessionId] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [messageCache, setMessageCache] = useState<Record<string, Message[]>>({});
  const [searchTerm, setSearchTerm] = useState("");
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [sessionActionError, setSessionActionError] = useState("");
  const [isUploading, setIsUploading] = useState(false);
  const [savingMessageId, setSavingMessageId] = useState("");
  const [isBooting, setIsBooting] = useState(true);
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  const [isAttachmentMenuOpen, setIsAttachmentMenuOpen] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [recordingError, setRecordingError] = useState("");
  const [speakingMessageId, setSpeakingMessageId] = useState("");
  const [sessionContextMenu, setSessionContextMenu] = useState<{ sessionId: string; x: number; y: number } | null>(null);
  const [aiAvatarSrc, setAiAvatarSrc] = useState(() => getStoredValue(AI_AVATAR_STORAGE_KEY));
  const [userAvatarSrc, setUserAvatarSrc] = useState(() => getStoredValue(USER_AVATAR_STORAGE_KEY));
  const chatBodyRef = useRef<HTMLElement | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const aiAvatarInputRef = useRef<HTMLInputElement | null>(null);
  const userAvatarInputRef = useRef<HTMLInputElement | null>(null);
  const attachmentMenuRef = useRef<HTMLDivElement | null>(null);
  const sessionMenuRef = useRef<HTMLDivElement | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordingStreamRef = useRef<MediaStream | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);
  const voiceAudioRef = useRef<HTMLAudioElement | null>(null);
  const resizeStateRef = useRef({ dragging: false, startX: 0, startWidth: 300 });
  const activeSessionIdRef = useRef("");
  const shouldAutoScrollRef = useRef(true);
  const lastLoadRequestRef = useRef(0);
  const objectUrlRef = useRef<string[]>([]);
  const isSendingRef = useRef(false);
  const isUploadingRef = useRef(false);
  const pendingStreamDeltaRef = useRef("");
  const streamFlushTimerRef = useRef<number | null>(null);

  const activeSession = useMemo(
    () => sessions.find((item) => item.sessionId === activeSessionId) || null,
    [sessions, activeSessionId],
  );

  const hasSessionSearch = Boolean(normalizeSearch(searchTerm));

  const visibleSessions = useMemo(() => {
    const filteredSessions = sessions.filter((session) =>
      matchesSession(session, normalizeSearch(searchTerm), messageCache[session.sessionId]),
    );
    const sortedSessions = [...filteredSessions].sort(
      (left, right) => parseSessionTime(right.lastActive) - parseSessionTime(left.lastActive),
    );
    if (hasSessionSearch) return sortedSessions.slice(0, MAX_VISIBLE_SESSIONS);
    const recentSessions = sortedSessions.filter((session) => isRecentSession(session));
    if (!activeSession) return recentSessions.slice(0, MAX_VISIBLE_SESSIONS);
    if (recentSessions.some((session) => session.sessionId === activeSession.sessionId)) {
      return recentSessions.slice(0, MAX_VISIBLE_SESSIONS);
    }
    return [activeSession, ...recentSessions].slice(0, MAX_VISIBLE_SESSIONS);
  }, [activeSession, hasSessionSearch, messageCache, searchTerm, sessions]);

  const displayedSessions = useMemo(() => {
    if (hasSessionSearch || sessionListExpanded || visibleSessions.length <= DEFAULT_VISIBLE_SESSION_COUNT) {
      return visibleSessions;
    }

    const leadingSessions = visibleSessions.slice(0, DEFAULT_VISIBLE_SESSION_COUNT);
    if (!activeSession) {
      return leadingSessions;
    }
    if (leadingSessions.some((session) => session.sessionId === activeSession.sessionId)) {
      return leadingSessions;
    }

    return [
      activeSession,
      ...leadingSessions.filter((session) => session.sessionId !== activeSession.sessionId),
    ].slice(0, DEFAULT_VISIBLE_SESSION_COUNT);
  }, [activeSession, hasSessionSearch, sessionListExpanded, visibleSessions]);

  const showSessionOverflowToggle =
    !hasSessionSearch &&
    visibleSessions.length > DEFAULT_VISIBLE_SESSION_COUNT;

  const showExpandedSessionList =
    hasSessionSearch || sessionListExpanded;

  const monitorCards = useMemo(() => {
    if (!monitorSummary) {
      return [];
    }
    return [
      { label: "请求数", value: formatCount(monitorSummary.totalRequests), hint: `成功率 ${formatRate(monitorSummary.successRequests, monitorSummary.totalRequests)}` },
      { label: "平均耗时", value: formatDuration(monitorSummary.averageDurationMs), hint: `首响 ${formatDuration(monitorSummary.averageFirstTokenMs)}` },
      { label: "总 Token", value: formatCount(monitorSummary.totalTokens), hint: `输入 ${formatCount(monitorSummary.totalPromptTokens)} · 输出 ${formatCount(monitorSummary.totalCompletionTokens)}` },
      { label: "失败请求", value: formatCount(monitorSummary.failedRequests), hint: `当前模型 ${monitorSummary.currentChatModel || "未知"}` },
    ];
  }, [monitorSummary]);

  const monitorTrend = useMemo(() => {
    if (!monitorSummary?.recentRequests?.length) {
      return [];
    }
    const bucketSizeMs = 10 * 60 * 1000;
    const buckets = new Map<number, { ts: number; count: number; tokens: number; duration: number }>();

    for (const request of monitorSummary.recentRequests) {
      const time = new Date(request.startedAt).getTime();
      if (Number.isNaN(time)) continue;
      const bucketTs = Math.floor(time / bucketSizeMs) * bucketSizeMs;
      const current = buckets.get(bucketTs) || { ts: bucketTs, count: 0, tokens: 0, duration: 0 };
      current.count += 1;
      current.tokens += request.totalTokens;
      current.duration += request.durationMs;
      buckets.set(bucketTs, current);
    }

    return [...buckets.values()]
      .sort((left, right) => left.ts - right.ts)
      .slice(-8)
      .map((bucket) => ({
        ...bucket,
        label: formatMonitorBucketLabel(bucket.ts),
        avgDuration: bucket.count ? Math.round(bucket.duration / bucket.count) : 0,
      }));
  }, [monitorSummary]);

  const monitorTrendMaxCount = useMemo(
    () => Math.max(...monitorTrend.map((item) => item.count), 1),
    [monitorTrend],
  );

  const monitorTrendMaxTokens = useMemo(
    () => Math.max(...monitorTrend.map((item) => item.tokens), 1),
    [monitorTrend],
  );

  const trendCountPath = useMemo(
    () => buildSparklinePath(monitorTrend.map((item) => item.count), TREND_CHART_WIDTH, TREND_CHART_HEIGHT, TREND_CHART_PADDING),
    [monitorTrend],
  );

  const trendTokenPath = useMemo(
    () => buildSparklinePath(monitorTrend.map((item) => item.tokens), TREND_CHART_WIDTH, TREND_CHART_HEIGHT, TREND_CHART_PADDING),
    [monitorTrend],
  );

  const trendCountArea = useMemo(
    () => buildSparklineArea(trendCountPath, TREND_CHART_WIDTH, TREND_CHART_HEIGHT, TREND_CHART_PADDING),
    [trendCountPath],
  );

  const trendTokenArea = useMemo(
    () => buildSparklineArea(trendTokenPath, TREND_CHART_WIDTH, TREND_CHART_HEIGHT, TREND_CHART_PADDING),
    [trendTokenPath],
  );

  const trendChartPoints = useMemo(() => {
    if (!monitorTrend.length) {
      return [];
    }
    const stepX =
      monitorTrend.length === 1
        ? 0
        : (TREND_CHART_WIDTH - TREND_CHART_PADDING * 2) / (monitorTrend.length - 1);

    return monitorTrend.map((item, index) => ({
      ...item,
      x: TREND_CHART_PADDING + stepX * index,
      countY: TREND_CHART_HEIGHT - TREND_CHART_PADDING - (item.count / monitorTrendMaxCount) * (TREND_CHART_HEIGHT - TREND_CHART_PADDING * 2),
      tokenY: TREND_CHART_HEIGHT - TREND_CHART_PADDING - (item.tokens / monitorTrendMaxTokens) * (TREND_CHART_HEIGHT - TREND_CHART_PADDING * 2),
      hitX:
        monitorTrend.length === 1
          ? TREND_CHART_PADDING
          : index === 0
            ? TREND_CHART_PADDING
            : TREND_CHART_PADDING + stepX * index - stepX / 2,
      hitWidth:
        monitorTrend.length === 1
          ? TREND_CHART_WIDTH - TREND_CHART_PADDING * 2
          : index === monitorTrend.length - 1
            ? TREND_CHART_WIDTH - TREND_CHART_PADDING - (TREND_CHART_PADDING + stepX * index - stepX / 2)
            : stepX,
    }));
  }, [monitorTrend, monitorTrendMaxCount, monitorTrendMaxTokens]);

  const trendAxisTicks = useMemo(
    () =>
      [1, 0.66, 0.33, 0].map((ratio) => ({
        y: TREND_CHART_HEIGHT - TREND_CHART_PADDING - ratio * (TREND_CHART_HEIGHT - TREND_CHART_PADDING * 2),
        countLabel: Math.round(monitorTrendMaxCount * ratio),
        tokenLabel: Math.round(monitorTrendMaxTokens * ratio),
      })),
    [monitorTrendMaxCount, monitorTrendMaxTokens],
  );

  const hoveredTrendPoint =
    hoveredTrendIndex == null || hoveredTrendIndex < 0 || hoveredTrendIndex >= trendChartPoints.length
      ? null
      : trendChartPoints[hoveredTrendIndex];

  const trendRangeLabel = useMemo(() => {
    if (!monitorTrend.length) return "暂无趋势数据";
    if (monitorTrend.length === 1) return monitorTrend[0].label;
    return `${monitorTrend[0].label} - ${monitorTrend[monitorTrend.length - 1].label}`;
  }, [monitorTrend]);

  const trendSnapshotCards = useMemo(() => {
    if (!monitorTrend.length) {
      return [];
    }
    const latest = monitorTrend[monitorTrend.length - 1];
    const previous = monitorTrend.length > 1 ? monitorTrend[monitorTrend.length - 2] : null;
    const peakCount = [...monitorTrend].sort((left, right) => right.count - left.count)[0];
    const peakTokens = [...monitorTrend].sort((left, right) => right.tokens - left.tokens)[0];

    return [
      {
        label: "最近时间片",
        value: latest.label,
        hint: `请求 ${latest.count} · ${formatCount(latest.tokens)} tok`,
      },
      {
        label: "请求峰值",
        value: `${peakCount.count} 次`,
        hint: peakCount.label,
      },
      {
        label: "Token 峰值",
        value: formatCount(peakTokens.tokens),
        hint: peakTokens.label,
      },
      {
        label: "相邻变化",
        value: formatTrendDelta(latest.count, previous?.count ?? null),
        hint: `Token ${formatTrendDelta(latest.tokens, previous?.tokens ?? null)}`,
      },
    ];
  }, [monitorTrend]);

  const modelStats = useMemo(() => {
    if (!monitorSummary?.recentRequests?.length) {
      return [];
    }
    const grouped = new Map<string, { model: string; count: number; success: number; tokens: number; duration: number }>();

    for (const request of monitorSummary.recentRequests) {
      const model = request.model || "未知模型";
      const current = grouped.get(model) || { model, count: 0, success: 0, tokens: 0, duration: 0 };
      current.count += 1;
      current.success += request.success ? 1 : 0;
      current.tokens += request.totalTokens;
      current.duration += request.durationMs;
      grouped.set(model, current);
    }

    return [...grouped.values()]
      .sort((left, right) => right.count - left.count)
      .slice(0, 6)
      .map((item) => ({
        ...item,
        successRate: formatRate(item.success, item.count),
        avgDuration: item.count ? Math.round(item.duration / item.count) : 0,
      }));
  }, [monitorSummary]);

  const modelStatsMaxCount = useMemo(
    () => Math.max(...modelStats.map((item) => item.count), 1),
    [modelStats],
  );

  const routeStats = useMemo(() => {
    if (!monitorSummary?.recentRequests?.length) {
      return [];
    }
    const grouped = new Map<string, { key: string; count: number; success: number; tools: number }>();

    for (const request of monitorSummary.recentRequests) {
      const key = formatRouteLabel(request);
      const current = grouped.get(key) || { key, count: 0, success: 0, tools: 0 };
      current.count += 1;
      current.success += request.success ? 1 : 0;
      current.tools += request.toolCallCount;
      grouped.set(key, current);
    }

    return [...grouped.values()]
      .sort((left, right) => right.count - left.count)
      .slice(0, 8)
      .map((item) => ({
        ...item,
        successRate: formatRate(item.success, item.count),
      }));
  }, [monitorSummary]);

  const routeStatsMaxCount = useMemo(
    () => Math.max(...routeStats.map((item) => item.count), 1),
    [routeStats],
  );

  useEffect(() => {
    const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
    if (!token) {
      setIsBooting(false);
      return;
    }
    void fetchJson<AuthUser>(`${API_BASE_URL}/api/auth/me`)
      .then((user) => { setAuthUser(user); void bootstrap(); })
      .catch(() => { window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY); setIsBooting(false); });
  }, []);

  useEffect(() => {
    function handleAuthExpired() {
      setAuthUser(null);
      setSessions([]);
      setMessages([]);
      setMessageCache({});
      setSessionActionError("");
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    }
    window.addEventListener("desktop-auth-expired", handleAuthExpired);
    return () => window.removeEventListener("desktop-auth-expired", handleAuthExpired);
  }, []);

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId;
  }, [activeSessionId]);

  useEffect(() => {
    return () => {
      objectUrlRef.current.forEach((url) => URL.revokeObjectURL(url));
      objectUrlRef.current = [];
      recordingStreamRef.current?.getTracks().forEach((track) => track.stop());
      recordingStreamRef.current = null;
      mediaRecorderRef.current = null;
      if (voiceAudioRef.current) {
        voiceAudioRef.current.pause();
        voiceAudioRef.current.src = "";
      }
      if (streamFlushTimerRef.current !== null) {
        window.clearTimeout(streamFlushTimerRef.current);
        streamFlushTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    window.localStorage.setItem(AI_AVATAR_STORAGE_KEY, aiAvatarSrc);
  }, [aiAvatarSrc]);

  useEffect(() => {
    window.localStorage.setItem(USER_AVATAR_STORAGE_KEY, userAvatarSrc);
  }, [userAvatarSrc]);

  useEffect(() => {
    isSendingRef.current = isSending;
  }, [isSending]);

  useEffect(() => {
    isUploadingRef.current = isUploading;
  }, [isUploading]);

  useEffect(() => {
    function clampWidth(width: number) {
      return Math.max(180, Math.min(330, width));
    }

    function handlePointerMove(event: PointerEvent) {
      if (!resizeStateRef.current.dragging) return;
      const delta = event.clientX - resizeStateRef.current.startX;
      setSidebarWidth(clampWidth(resizeStateRef.current.startWidth + delta));
    }

    function handlePointerUp() {
      resizeStateRef.current.dragging = false;
      document.body.classList.remove("is-resizing-sidebar");
    }

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
    window.addEventListener("pointercancel", handlePointerUp);
    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", handlePointerUp);
      window.removeEventListener("pointercancel", handlePointerUp);
    };
  }, []);

  useEffect(() => {
    function handlePointerDown(event: PointerEvent) {
      if (sessionMenuRef.current?.contains(event.target as Node)) return;
      setSessionContextMenu(null);
      if (attachmentMenuRef.current?.contains(event.target as Node)) return;
      if (isAttachmentMenuOpen) {
        setIsAttachmentMenuOpen(false);
      }
    }
    window.addEventListener("pointerdown", handlePointerDown);
    return () => window.removeEventListener("pointerdown", handlePointerDown);
  }, [isAttachmentMenuOpen]);

  useLayoutEffect(() => {
    if (!shouldAutoScrollRef.current) return;
    const container = chatBodyRef.current;
    if (!container) return;
    container.scrollTop = container.scrollHeight;
  }, [messages, activeSessionId]);

  useEffect(() => {
    if (!activeSessionId || isBooting) return;
    const timer = window.setInterval(() => {
      void loadMessages(activeSessionIdRef.current, { silent: true });
      void refreshSessions();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [activeSessionId, isBooting]);

  useEffect(() => {
    if (isBooting) return;
    const timer = window.setInterval(() => {
      void loadMonitorSummary();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [isBooting]);

  useEffect(() => {
    if (viewMode !== "settings" || !authUser || !mcpServers.length) return;
    let disposed = false;
    async function refreshStatuses() {
      if (document.visibilityState !== "visible") return;
      try { const statuses = await fetchJson<Record<number, McpStatus>>(`${API_BASE_URL}/api/settings/mcp-servers/statuses`); if (!disposed) setMcpStatuses(statuses); } catch { return; }
    }
    void refreshStatuses(); const timer = window.setInterval(() => void refreshStatuses(), 3000);
    return () => { disposed = true; window.clearInterval(timer); };
  }, [viewMode, authUser?.id, mcpServers]);

  function setSessionMessages(
    sessionId: string,
    nextMessages: Message[] | ((current: Message[]) => Message[]),
  ) {
    setMessageCache((current) => {
      const previous = current[sessionId] || buildEmptyMessage(sessionId);
      const resolved = typeof nextMessages === "function" ? nextMessages(previous) : nextMessages;
      const nextCache = { ...current, [sessionId]: resolved };
      if (sessionId === activeSessionIdRef.current) setMessages(resolved);
      return nextCache;
    });
  }

  function replaceSessionMessages(sessionId: string, nextMessages: Message[]) {
    setMessageCache((current) => ({ ...current, [sessionId]: nextMessages }));
    if (sessionId === activeSessionIdRef.current) setMessages(nextMessages);
  }

  function updateActiveSessionMessage(
    sessionId: string,
    messageId: string,
    updater: (message: Message) => Message,
    options?: { syncCache?: boolean },
  ) {
    const syncCache = options?.syncCache ?? false;

    setMessages((current) => {
      if (sessionId !== activeSessionIdRef.current) {
        return current;
      }
      let changed = false;
      const next = current.map((item) => {
        if (item.id !== messageId) {
          return item;
        }
        changed = true;
        return updater(item);
      });
      return changed ? next : current;
    });

    if (!syncCache) {
      return;
    }

    setMessageCache((current) => {
      const previous = current[sessionId] || buildEmptyMessage(sessionId);
      let changed = false;
      const nextMessages = previous.map((item) => {
        if (item.id !== messageId) {
          return item;
        }
        changed = true;
        return updater(item);
      });
      if (!changed) {
        return current;
      }
      return { ...current, [sessionId]: nextMessages };
    });
  }

  function activateSession(sessionId: string, nextMessages?: Message[]) {
    activeSessionIdRef.current = sessionId;
    setActiveSessionId(sessionId);
    setViewMode("chat");
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId);
    const cached = nextMessages ?? messageCache[sessionId];
    if (cached?.length) setMessages(cached);
    shouldAutoScrollRef.current = true;
  }

  async function bootstrap() {
    try {
      const [overviewResult, sessionsResult, monitorResult] = await Promise.allSettled([
        fetchJson<Overview>(`${API_BASE_URL}/api/desktop/overview`),
        fetchJson<SessionSummary[]>(`${API_BASE_URL}/api/desktop/sessions`),
        fetchJson<MonitorSummary>(`${API_BASE_URL}/api/desktop/monitor/summary`),
      ]);

      setOverview(overviewResult.status === "fulfilled" ? overviewResult.value : null);
      setMonitorSummary(monitorResult.status === "fulfilled" ? monitorResult.value : null);
      const sessionList = sessionsResult.status === "fulfilled" ? sessionsResult.value : [];
      void loadStartupMetadata();

      let resolvedActiveId = window.sessionStorage.getItem(SESSION_STORAGE_KEY) || "";
      if (!sessionList.length) {
        try {
          const created = await createSession();
          setSessions([created]);
          resolvedActiveId = created.sessionId;
        } catch {
          setSessions([]);
          setMessages([]);
          return;
        }
      } else {
        setSessions(sessionList);
        if (!resolvedActiveId || !sessionList.some((item) => item.sessionId === resolvedActiveId)) {
          resolvedActiveId = sessionList[0].sessionId;
        }
      }

      activateSession(resolvedActiveId);
      await loadMessages(resolvedActiveId);
    } finally {
      setIsBooting(false);
    }
  }

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAuthBusy(true);
    setAuthError("");
    try {
      const endpoint = authMode === "login" ? "login" : "register";
      const response = await fetchJson<AuthResponse>(`${API_BASE_URL}/api/auth/${endpoint}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: authUsername, password: authPassword, displayName: authDisplayName || undefined }),
      });
      window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, response.token);
      setAuthUser(response.user);
      setAuthPassword("");
      await bootstrap();
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      setAuthError(message.includes("404")
        ? "当前连接的后端版本过旧或地址错误，请重启桌面应用和后端"
        : message.includes("Failed to fetch") || message.includes("aborted")
          ? "无法连接后端服务，请确认后端已经启动"
          : message || "登录失败，请稍后重试");
    } finally {
      setAuthBusy(false);
    }
  }

  async function handleLogout() {
    try { await fetchJson(`${API_BASE_URL}/api/auth/logout`, { method: "POST" }); } catch { /* token is cleared below */ }
    window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    setAuthUser(null);
    setSessions([]);
    setMessages([]);
  }

  async function loadStartupMetadata() {
    const [statusResult, skillsResult] = await Promise.allSettled([
      fetchJson<SystemStatus>(`${API_BASE_URL}/api/system/status`),
      fetchJson<SkillDescriptor[]>(`${API_BASE_URL}/api/skills`),
    ]);

    if (statusResult.status === "fulfilled") {
      setSystemStatus(statusResult.value);
    }
    if (skillsResult.status === "fulfilled") {
      setSkills(skillsResult.value);
    }
  }

  async function loadModelProfiles() {
    try { setModelProfiles(await fetchJson<ModelProfile[]>(`${API_BASE_URL}/api/settings/models`)); } catch { setModelProfiles([]); }
  }

  async function loadMcpServers() {
    try {
      const servers = await fetchJson<McpServer[]>(`${API_BASE_URL}/api/settings/mcp-servers`); setMcpServers(servers);
      setMcpStatuses(await fetchJson<Record<number, McpStatus>>(`${API_BASE_URL}/api/settings/mcp-servers/statuses`));
    } catch { setMcpServers([]); }
  }

  async function saveMcpServer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMcpBusy(true); setMcpMessage("");
    const environment = Object.fromEntries(mcpForm.environment.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => { const index = line.indexOf("="); return index < 1 ? [line, ""] : [line.slice(0, index).trim(), line.slice(index + 1)]; }));
    try {
      const url = editingMcpId == null ? `${API_BASE_URL}/api/settings/mcp-servers` : `${API_BASE_URL}/api/settings/mcp-servers/${editingMcpId}`;
      const server = await fetchJson<McpServer>(url, { method: editingMcpId == null ? "POST" : "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ...mcpForm, args: mcpForm.args.split(/\r?\n/).map((value) => value.trim()).filter(Boolean), environment, timeoutMs: Number(mcpForm.timeoutMs), maxConcurrentRequests: Number(mcpForm.maxConcurrentRequests), maxMemoryMb: Number(mcpForm.maxMemoryMb), maxResponseBytes: Number(mcpForm.maxResponseBytes), maxCpuSeconds: Number(mcpForm.maxCpuSeconds), maxCpuPercent: Number(mcpForm.maxCpuPercent) }) });
      setMcpServers((current) => editingMcpId == null ? [server, ...current] : current.map((item) => item.id === server.id ? server : item)); setMcpForm(EMPTY_MCP_FORM); setEditingMcpId(null); setMcpMessage("MCP 服务已保存");
    } catch (error) { setMcpMessage(error instanceof Error ? error.message : "MCP 服务保存失败"); }
    finally { setMcpBusy(false); }
  }

  async function toggleMcpServer(server: McpServer, enabled: boolean) {
    setMcpBusy(true); setMcpMessage("");
    try { const updated = await fetchJson<McpServer>(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/enabled`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled }) }); setMcpServers((current) => current.map((item) => item.id === updated.id ? updated : item)); }
    catch (error) { setMcpMessage(error instanceof Error ? error.message : "MCP 状态更新失败"); }
    finally { setMcpBusy(false); }
  }

  async function testMcpServer(server: McpServer) {
    setMcpTesting(server.id); setMcpTestResults((current) => ({ ...current, [server.id]: "" }));
    try { const result = await fetchJson<{ success: boolean; toolCount: number; tools: string[] }>(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/test`, { method: "POST" }, 120000); setMcpTestResults((current) => ({ ...current, [server.id]: `连接正常，发现 ${result.toolCount} 个工具` })); await loadMcpTools(server); await loadMcpStatus(server); }
    catch { setMcpTestResults((current) => ({ ...current, [server.id]: "连接失败，请检查命令、参数和服务日志" })); }
    finally { setMcpTesting(null); }
  }

  function editMcpServer(server: McpServer) { setEditingMcpId(server.id); setMcpForm({ serverKey: server.serverKey, displayName: server.displayName, command: server.command, args: server.args.join("\n"), workingDirectory: server.workingDirectory, environment: "", timeoutMs: String(server.timeoutMs), maxConcurrentRequests: String(server.maxConcurrentRequests), maxMemoryMb: String(server.maxMemoryMb), maxResponseBytes: String(server.maxResponseBytes), maxCpuSeconds: String(server.maxCpuSeconds), maxCpuPercent: String(server.maxCpuPercent), enabled: server.enabled }); setMcpMessage("环境变量留空将保留现有密钥"); }
  async function deleteMcpServer(server: McpServer) { if (!window.confirm(`确认删除 MCP 服务“${server.displayName}”？`)) return; setMcpBusy(true); try { await fetchJson(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}`, { method: "DELETE" }); setMcpServers((current) => current.filter((item) => item.id !== server.id)); } finally { setMcpBusy(false); } }
  async function loadMcpStatus(server: McpServer) { const status = await fetchJson<McpStatus>(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/status`); setMcpStatuses((current) => ({ ...current, [server.id]: status })); }
  async function controlMcpServer(server: McpServer, action: "restart" | "stop") { setMcpBusy(true); try { const status = await fetchJson<McpStatus>(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/${action}`, { method: "POST" }, 120000); setMcpStatuses((current) => ({ ...current, [server.id]: status })); } catch { setMcpMessage(`${action === "restart" ? "重启" : "关闭"}失败`); } finally { setMcpBusy(false); } }
  async function loadMcpTools(server: McpServer) { const tools = await fetchJson<McpTool[]>(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/tools`, undefined, 120000); setMcpTools((current) => ({ ...current, [server.id]: tools })); }
  async function toggleMcpTool(server: McpServer, tool: McpTool, enabled: boolean) { await fetchJson(`${API_BASE_URL}/api/settings/mcp-servers/${server.id}/tools/enabled`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ toolName: tool.remoteName, enabled }) }); setMcpTools((current) => ({ ...current, [server.id]: (current[server.id] || []).map((item) => item.remoteName === tool.remoteName ? { ...item, enabled } : item) })); }

  async function setSkillEnabled(skillName: string, enabled: boolean) {
    setSkillUpdating(skillName); setSkillMessage("");
    try {
      await fetchJson(`${API_BASE_URL}/api/skills/${encodeURIComponent(skillName)}/enabled`, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled }),
      });
      setSkills((current) => current.map((skill) => skill.name === skillName ? { ...skill, enabled } : skill));
      setSkillMessage(`${skillName} 已${enabled ? "启用" : "停用"}`);
    } catch (error) {
      setSkillMessage(error instanceof Error ? error.message : "技能设置保存失败");
    } finally { setSkillUpdating(""); }
  }

  async function saveModelProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setModelSaving(true); setModelMessage("");
    try {
      const response = await fetchJson<ModelProfile>(`${API_BASE_URL}/api/settings/models`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ...modelForm, temperature: Number(modelForm.temperature), maxTokens: Number(modelForm.maxTokens), defaultProfile: true }) });
      setModelProfiles((current) => [response, ...current.filter((item) => item.id !== response.id)]);
      setModelForm((current) => ({ ...current, apiKey: "" })); setModelMessage("模型配置已保存");
    } catch (error) { setModelMessage(error instanceof Error ? error.message : "保存失败"); }
    finally { setModelSaving(false); }
  }

  async function refreshSessions() {
    try {
      setSessions(await fetchJson<SessionSummary[]>(`${API_BASE_URL}/api/desktop/sessions`));
    } catch {
      return;
    }
  }

  async function loadMonitorSummary() {
    try {
      setMonitorSummary(await fetchJson<MonitorSummary>(`${API_BASE_URL}/api/desktop/monitor/summary`));
    } catch {
      return;
    }
  }

  async function executeSkill<T = unknown>(skillName: string, payload: Record<string, unknown>) {
    const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || "";
    const response = await fetch(`${API_BASE_URL}/api/skills/${encodeURIComponent(skillName)}/execute`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(payload),
    });
    if (!response.ok) {
      handleUnauthorizedResponse(response.url || `${API_BASE_URL}/api/skills/${encodeURIComponent(skillName)}/execute`, response.status);
      throw new Error(`skill failed: ${response.status}`);
    }
    return (await response.json()) as SkillResult & { data: T };
  }

  function getMessageExportInfo(message: Message) {
    return message.meta?.exportInfo || (message.role === "assistant" ? parseFileExportInfo(message.content) : null);
  }

  async function sendChatMessage(currentSessionId: string, message: string) {
    shouldAutoScrollRef.current = true;
    pendingStreamDeltaRef.current = "";
    if (streamFlushTimerRef.current !== null) {
      window.clearTimeout(streamFlushTimerRef.current);
      streamFlushTimerRef.current = null;
    }
    const userMessage: Message = { id: `${Date.now()}-user`, role: "user", content: message };
    const assistantMessageId = `${Date.now()}-assistant`;

    setSessionMessages(currentSessionId, (current) => [
      ...trimEmptyMessage(current),
      userMessage,
      { id: assistantMessageId, role: "assistant", content: "", pending: true },
    ]);

    setIsSending(true);
    try {
      const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || "";
      const response = await fetch(`${API_BASE_URL}/api/desktop/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify({ sessionId: currentSessionId, message }),
      });

      if (!response.ok || !response.body) {
        handleUnauthorizedResponse(response.url || `${API_BASE_URL}/api/desktop/chat/stream`, response.status);
        throw new Error(`chat failed: ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";

      const flushPendingDelta = () => {
        const deltaText = pendingStreamDeltaRef.current;
        pendingStreamDeltaRef.current = "";
        streamFlushTimerRef.current = null;
        if (!deltaText) return;
        updateActiveSessionMessage(
          currentSessionId,
          assistantMessageId,
          (item) => ({ ...item, content: item.content + deltaText, pending: true }),
        );
      };

      const scheduleDeltaFlush = () => {
        if (streamFlushTimerRef.current !== null) return;
        streamFlushTimerRef.current = window.setTimeout(flushPendingDelta, STREAM_FLUSH_INTERVAL_MS);
      };

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          if (!line.trim()) continue;
          const eventData = JSON.parse(line) as StreamEvent;

          if (eventData.type === "delta") {
            pendingStreamDeltaRef.current += eventData.delta ?? "";
            scheduleDeltaFlush();
          }

          if (eventData.type === "done") {
            if (streamFlushTimerRef.current !== null) {
              window.clearTimeout(streamFlushTimerRef.current);
              flushPendingDelta();
            }
            updateActiveSessionMessage(
              currentSessionId,
              assistantMessageId,
              (item) => ({ ...item, content: sanitizeAssistantContent(eventData.reply || item.content), pending: false }),
              { syncCache: true },
            );
            void refreshSessions();
          }

          if (eventData.type === "error") {
            throw new Error(eventData.error || "unknown error");
          }
        }
      }
    } catch (error) {
      if (streamFlushTimerRef.current !== null) {
        window.clearTimeout(streamFlushTimerRef.current);
        streamFlushTimerRef.current = null;
      }
      pendingStreamDeltaRef.current = "";
      const errorText = error instanceof Error ? error.message : "桌面端请求失败";
      updateActiveSessionMessage(
        currentSessionId,
        assistantMessageId,
        () => ({ id: assistantMessageId, role: "assistant", content: `请求失败：${errorText}`, pending: false }),
        { syncCache: true },
      );
    } finally {
      setIsSending(false);
      void refreshSessions();
      void loadMonitorSummary();
    }
  }

  async function transcribeVoiceMessage(currentSessionId: string, blob: Blob, fileName: string) {
    const audioBase64 = await readBlobAsDataUrl(blob);
    const result = await executeSkill<Record<string, unknown>>("speech_skill", {
      action: "transcribe",
      audioBase64,
      fileName,
      mimeType: blob.type,
    });
    const text = typeof result.data.text === "string" ? result.data.text.trim() : "";
    if (!text) {
      throw new Error(result.error || "语音识别失败");
    }
    await sendChatMessage(currentSessionId, text);
  }

  async function speakText(messageId: string, text: string, sessionId: string) {
    if (!text.trim()) return;
    setSpeakingMessageId(messageId);
    try {
      const result = await executeSkill<Record<string, unknown>>("speech_skill", {
        action: "synthesize",
        text,
        sessionId,
      });
      const audioBase64 = typeof result.data.audioBase64 === "string" ? result.data.audioBase64 : "";
      const format = typeof result.data.format === "string" ? result.data.format : "wav";
      if (!audioBase64) throw new Error(result.error || "语音合成失败");
      const mimeType =
        format === "mp3" ? "audio/mpeg" : format === "ogg" ? "audio/ogg" : `audio/${format || "wav"}`;
      const audioUrl = audioBase64.startsWith("data:")
        ? audioBase64
        : `data:${mimeType};base64,${audioBase64}`;
      if (voiceAudioRef.current) {
        voiceAudioRef.current.pause();
        voiceAudioRef.current.src = audioUrl;
        await voiceAudioRef.current.play();
        return;
      }
      const audio = new Audio(audioUrl);
      voiceAudioRef.current = audio;
      await audio.play();
    } finally {
      setSpeakingMessageId("");
    }
  }

  async function toggleVoiceRecording() {
    if (isRecording) {
      mediaRecorderRef.current?.stop();
      return;
    }

    setRecordingError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setRecordingError("当前环境不支持语音录制");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      recordingStreamRef.current = stream;
      recordedChunksRef.current = [];

      const recorder = new MediaRecorder(stream);
      mediaRecorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          recordedChunksRef.current.push(event.data);
        }
      };
      recorder.onstop = async () => {
        setIsRecording(false);
        const chunks = [...recordedChunksRef.current];
        recordedChunksRef.current = [];
        recordingStreamRef.current?.getTracks().forEach((track) => track.stop());
        recordingStreamRef.current = null;
        mediaRecorderRef.current = null;
        if (!chunks.length || !activeSessionIdRef.current) return;
        const mimeType = chunks[0]?.type || "audio/webm";
        const blob = new Blob(chunks, { type: mimeType });
        const fileName = `voice-${Date.now()}.webm`;
        try {
          await transcribeVoiceMessage(activeSessionIdRef.current, blob, fileName);
        } catch (error) {
          const errorText = error instanceof Error ? error.message : "语音识别失败";
          setRecordingError(errorText);
        }
      };
      recorder.start();
      setIsRecording(true);
    } catch (error) {
      const errorText = error instanceof Error ? error.message : "无法启动录音";
      setRecordingError(errorText);
    }
  }

  async function createSession() {
    return fetchJson<SessionSummary>(`${API_BASE_URL}/api/desktop/sessions`, { method: "POST" });
  }

  async function loadMessages(sessionId: string, options?: { silent?: boolean }) {
    if (!sessionId) {
      setMessages([]);
      return;
    }

    const localMessages = messageCache[sessionId];
    const hasPendingLocalMessage = localMessages?.some((item) => item.pending);

    if (options?.silent && (isSendingRef.current || isUploadingRef.current)) {
      return;
    }

    if (options?.silent && sessionId === activeSessionIdRef.current) {
      const container = chatBodyRef.current;
      if (container && !isNearBottom(container)) {
        shouldAutoScrollRef.current = false;
        return;
      }
    }

    const cachedMessages = messageCache[sessionId];
    if (!options?.silent && cachedMessages?.length && sessionId === activeSessionIdRef.current) {
      setMessages(cachedMessages);
    }

    if (!options?.silent) {
      setIsLoadingMessages(true);
    }
    const requestId = ++lastLoadRequestRef.current;

    try {
      const history = await fetchJson<DesktopMessage[]>(
        `${API_BASE_URL}/api/desktop/sessions/${encodeURIComponent(sessionId)}/messages`,
      );
      if (requestId !== lastLoadRequestRef.current || sessionId !== activeSessionIdRef.current) return;

      const resolvedMessages = mapHistoryToMessages(sessionId, history);
      if (sameMessages(messageCache[sessionId], resolvedMessages)) {
        return;
      }

      if (hasPendingLocalMessage && resolvedMessages.length < (cachedMessages?.length || 0)) {
        return;
      }

      if (!history.length) {
        const session = sessions.find((item) => item.sessionId === sessionId);
        if (session?.messageCount && session.messageCount > 0) {
          await refreshSessions();
          try {
            const retryHistory = await fetchJson<DesktopMessage[]>(
              `${API_BASE_URL}/api/desktop/sessions/${encodeURIComponent(sessionId)}/messages`,
            );
            const retryMessages = mapHistoryToMessages(sessionId, retryHistory);
            if (requestId === lastLoadRequestRef.current && sessionId === activeSessionIdRef.current) {
              if (!sameMessages(messageCache[sessionId], retryMessages)) {
                replaceSessionMessages(sessionId, retryMessages);
              }
            }
          } catch {
            return;
          }
          return;
        }
      }

      replaceSessionMessages(sessionId, resolvedMessages);
    } catch {
      if (requestId === lastLoadRequestRef.current && sessionId === activeSessionIdRef.current && !cachedMessages?.length) {
        replaceSessionMessages(sessionId, buildEmptyMessage(sessionId));
      }
    } finally {
      if (requestId === lastLoadRequestRef.current && !options?.silent) setIsLoadingMessages(false);
    }
  }

  async function handleCreateSession() {
    setIsSending(true);
    setSessionActionError("");
    try {
      const created = await createSession();
      await refreshSessions();
      const emptyMessages = buildEmptyMessage(created.sessionId);
      replaceSessionMessages(created.sessionId, emptyMessages);
      activateSession(created.sessionId, emptyMessages);
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      setSessionActionError(message.includes("401")
        ? "登录状态已失效，正在返回登录页"
        : message.includes("404")
          ? "新建会话接口不存在，请确认运行的是新版后端"
          : message.includes("500")
            ? "后端创建会话失败，请查看 Spring Boot 日志"
            : "无法连接后端，请确认 8080 端口服务可访问");
    } finally {
      setIsSending(false);
    }
  }

  async function handleSelectSession(sessionId: string) {
    if (!sessionId) return;
    if (sessionId === activeSessionId) {
      if (viewMode !== "chat") {
        setViewMode("chat");
        const cachedMessages = messageCache[sessionId];
        if (cachedMessages?.length) {
          setMessages(cachedMessages);
        }
        shouldAutoScrollRef.current = true;
      }
      return;
    }
    activateSession(sessionId);
    await loadMessages(sessionId);
  }

  async function handleDeleteSession(sessionId: string) {
    if (!sessionId) return;
    setSessionContextMenu(null);

    try {
      const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || "";
      const response = await fetch(`${API_BASE_URL}/api/desktop/sessions/${encodeURIComponent(sessionId)}`, {
        method: "DELETE",
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      });
      if (!response.ok) {
        handleUnauthorizedResponse(response.url || `${API_BASE_URL}/api/desktop/sessions/${encodeURIComponent(sessionId)}`, response.status);
        throw new Error(`delete failed: ${response.status}`);
      }

      setMessageCache((current) => {
        const next = { ...current };
        delete next[sessionId];
        return next;
      });

      const nextSessionsResponse = await fetchJson<SessionSummary[]>(`${API_BASE_URL}/api/desktop/sessions`);
      const nextSessions = nextSessionsResponse;
      setSessions(nextSessions);

      if (sessionId !== activeSessionIdRef.current) return;

      const nextActiveSession = nextSessions[0];
      if (nextActiveSession) {
        activateSession(nextActiveSession.sessionId);
        await loadMessages(nextActiveSession.sessionId);
        return;
      }

      const created = await createSession();
      setSessions([created]);
      activateSession(created.sessionId);
      await loadMessages(created.sessionId);
    } catch (error) {
      console.error("delete session failed", error);
    }
  }

  function handleChatScroll() {
    const container = chatBodyRef.current;
    if (!container) return;
    shouldAutoScrollRef.current = isNearBottom(container);
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey) return;
    event.preventDefault();
    const form = event.currentTarget.form;
    if (!form) return;
    void form.requestSubmit();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = input.trim();
    if (!message || isSending || !activeSessionId) return;
    setInput("");
    await sendChatMessage(activeSessionId, message);
  }

  function handleAttachmentMenuToggle() {
    setIsAttachmentMenuOpen((current) => !current);
    setSessionContextMenu(null);
  }

  function openAttachmentPicker() {
    setIsAttachmentMenuOpen(false);
    setSessionContextMenu(null);
    fileInputRef.current?.click();
  }

  function handleSidebarResizeStart(event: ReactPointerEvent<HTMLDivElement>) {
    setSessionContextMenu(null);
    resizeStateRef.current = {
      dragging: true,
      startX: event.clientX,
      startWidth: sidebarWidth,
    };
    document.body.classList.add("is-resizing-sidebar");
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";

    if (!file || !activeSessionId) return;

    shouldAutoScrollRef.current = true;
    const currentSessionId = activeSessionId;
    const uploadingId = `${Date.now()}-uploading`;

    setSessionMessages(currentSessionId, (current) => [
      ...trimEmptyMessage(current),
      {
        id: `${Date.now()}-user-file`,
        role: "user",
        content: `[上传文件] ${file.name}`,
      },
      {
        id: uploadingId,
        role: "assistant",
        content: `正在上传文件：${file.name}`,
        pending: true,
      },
    ]);

    setIsUploading(true);

    try {
      const formData = new FormData();
      formData.append("sessionId", currentSessionId);
      formData.append("file", file);

      const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || "";
      const response = await fetch(`${API_BASE_URL}/api/desktop/files/upload`, {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
        body: formData,
      });
      if (!response.ok) {
        handleUnauthorizedResponse(response.url || `${API_BASE_URL}/api/desktop/files/upload`, response.status);
        throw new Error(`upload failed: ${response.status}`);
      }

      const result = await response.json() as DesktopFileUploadResponse;
      setSessionMessages(currentSessionId, (current) =>
        current.map((item) =>
          item.id === uploadingId
            ? { ...item, content: result.message || "已收到文件", pending: false }
            : item,
        ),
      );
      void refreshSessions();
    } catch (error) {
      const errorText = error instanceof Error ? error.message : "文件上传失败";
      setSessionMessages(currentSessionId, (current) =>
        current.map((item) =>
          item.id === uploadingId
            ? { ...item, content: `文件上传失败：${errorText}`, pending: false }
            : item,
        ),
      );
    } finally {
      setIsUploading(false);
    }
  }

  async function handleAvatarChange(event: ChangeEvent<HTMLInputElement>, kind: "ai" | "user") {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const avatarUrl = await readFileAsDataUrl(file);
    if (kind === "ai") {
      setAiAvatarSrc(avatarUrl);
      return;
    }
    setUserAvatarSrc(avatarUrl);
  }

  async function handleSaveExportFile(
    sessionId: string,
    messageId: string,
    exportInfo: FileExportInfo | null,
    target: "download" | "desktop",
  ) {
    if (!exportInfo) return;

    setSavingMessageId(messageId);
    try {
      const savedPath = (await invoke(
        target === "download" ? "save_download_file" : "save_document_file",
        {
          sourcePath: exportInfo.filePath,
          fileName: exportInfo.fileName,
        },
      )) as string;

      const targetLabel = target === "download" ? "下载目录" : "桌面";
      setSessionMessages(sessionId, (current) => [
        ...current,
        {
          id: `${Date.now()}-saved`,
          role: "assistant",
          content: `已保存到${targetLabel}：${savedPath}`,
        },
      ]);
    } catch (error) {
      const errorText = error instanceof Error ? error.message : "保存失败";
      setSessionMessages(sessionId, (current) => [
        ...current,
        {
          id: `${Date.now()}-saved-error`,
          role: "assistant",
          content: `保存失败：${errorText}`,
        },
      ]);
    } finally {
      setSavingMessageId("");
    }
  }

  if (!authUser) {
    return (
      <main className="app-shell">
        <section className="auth-panel">
          <div className="auth-card">
            <div className="auth-mark">AI</div>
            <h1>桌面助手</h1>
            <p>{authMode === "login" ? "登录后同步你的会话与能力配置" : "创建账号，开始使用桌面助手"}</p>
            <form onSubmit={handleAuthSubmit} className="auth-form">
              {authMode === "register" ? <input value={authDisplayName} onChange={(e) => setAuthDisplayName(e.target.value)} placeholder="显示名称（可选）" maxLength={128} /> : null}
              <input value={authUsername} onChange={(e) => setAuthUsername(e.target.value)} placeholder="用户名" autoComplete="username" required minLength={3} />
              <input value={authPassword} onChange={(e) => setAuthPassword(e.target.value)} placeholder="密码（至少 8 位）" type="password" autoComplete={authMode === "login" ? "current-password" : "new-password"} required minLength={8} />
              {authError ? <div className="auth-error">{authError}</div> : null}
              <button type="submit" className="auth-submit" disabled={authBusy}>{authBusy ? "处理中..." : authMode === "login" ? "登录" : "注册并登录"}</button>
            </form>
            <button type="button" className="auth-switch" onClick={() => { setAuthMode(authMode === "login" ? "register" : "login"); setAuthError(""); }}>
              {authMode === "login" ? "还没有账号？注册" : "已有账号？登录"}
            </button>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="app-shell">
      <section
        className="desktop-frame"
        style={{
          ["--sidebar-width" as never]: `${sidebarWidth}px`,
        }}
      >
        <aside className="session-panel">
          <div className="panel-head">
            <div className="sidebar-search">
              <input
                value={searchTerm}
                onChange={(event) => setSearchTerm(event.currentTarget.value)}
                placeholder="搜索全部历史对话"
              />
            </div>

            <div className="sidebar-avatar-card">
              <button
                type="button"
                className="avatar-upload"
                onClick={() => aiAvatarInputRef.current?.click()}
                aria-label="上传 AI 头像"
              >
                {aiAvatarSrc ? <img src={aiAvatarSrc} alt="AI 头像" /> : <span>AI</span>}
              </button>
              <div>
                <h1>桌面助手</h1>
                <p>{systemStatus ? "后端已连接" : "后端未连接"}</p>
                <p>
                  {systemStatus
                    ? `DashScope ${systemStatus.dashScopeConfigured ? "已连接" : "未连接"} · 微信 ${
                        systemStatus.weChatRunning ? "运行中" : "未运行"
                      }`
                    : ""}
                </p>
              </div>
            </div>
          </div>

          <div className="session-section">
            <div className="session-create-wrap">
              <button
                type="button"
                className="toolbar-button new-session-button"
                onClick={handleCreateSession}
                disabled={isSending || isUploading}
              >
                新建会话
              </button>
              {sessionActionError ? <div className="session-action-error">{sessionActionError}</div> : null}
            </div>

            <div className="session-list-head">
              <div className="session-list-label">历史会话</div>
            </div>

            <div className={showSessionOverflowToggle ? "session-list-shell has-overflow" : "session-list-shell"}>
              <div className={showExpandedSessionList ? "session-list expanded" : "session-list collapsed"}>
                {displayedSessions.map((session) => (
                  <div
                    key={session.sessionId}
                    className={`session-item ${session.sessionId === activeSessionId ? "active" : ""}`}
                    onClick={() => void handleSelectSession(session.sessionId)}
                    onContextMenu={(event) => {
                      event.preventDefault();
                      setIsAttachmentMenuOpen(false);
                      setSessionContextMenu({
                        sessionId: session.sessionId,
                        x: event.clientX,
                        y: event.clientY,
                      });
                    }}
                  >
                    <span className="session-item-icon" />
                    <span className="session-item-main">
                      <strong>{session.title}</strong>
                      <span>{session.preview || "暂无预览"}</span>
                    </span>
                    <em>{session.messageCount} 条</em>
                  </div>
                ))}
              </div>

              {showSessionOverflowToggle ? (
                <button
                  type="button"
                  className={sessionListExpanded ? "session-more-button expanded" : "session-more-button"}
                  onClick={() => setSessionListExpanded((current) => !current)}
                  aria-label={sessionListExpanded ? "收起更多会话" : "展开更多会话"}
                >
                  <span className="session-more-dots" aria-hidden="true">...</span>
                </button>
              ) : null}
            </div>
          </div>

          <div className="feature-panel">
            <div className="feature-panel-label">功能区</div>
            <button
              type="button"
              className={viewMode === "monitor" ? "feature-button active" : "feature-button"}
              onClick={() => setViewMode("monitor")}
            >
              <span className="feature-button-main">日志监控</span>
              <span className="feature-button-sub">查看请求数、耗时、Token 和趋势</span>
            </button>
            <button type="button" className={viewMode === "settings" ? "feature-button active" : "feature-button"} onClick={() => { setViewMode("settings"); void loadModelProfiles(); void loadMcpServers(); void loadStartupMetadata(); }}>
              <span className="feature-button-main">能力设置</span>
              <span className="feature-button-sub">配置模型、访问凭据与技能</span>
            </button>
          </div>

          <div className="panel-foot">
            <span>{overview?.appName || "桌面助手"}</span>
            <span>{systemStatus?.envSource || "env 未知"}</span>
            <span>{skills.length ? `skills ${skills.length}` : "skills 0"}</span>
            <button type="button" className="auth-switch" onClick={() => void handleLogout()}>退出登录</button>
          </div>

          {sessionContextMenu ? (
            <div
              ref={sessionMenuRef}
              className="session-context-menu"
              style={{ left: sessionContextMenu.x, top: sessionContextMenu.y }}
            >
              <button type="button" onClick={() => void handleDeleteSession(sessionContextMenu.sessionId)}>
                删除
              </button>
            </div>
          ) : null}
        </aside>

        <div
          className="sidebar-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整侧边栏宽度"
          onPointerDown={handleSidebarResizeStart}
        >
          <span />
        </div>

        <section className="chat-panel">
          {viewMode === "chat" ? (
            <div className="chat-stage">
              <header className="chat-header">
                <div>
                  <h2>{activeSession?.title || "当前会话"}</h2>
                  <p>{activeSessionId || "未选择会话"}</p>
                </div>
                <div className="header-meta">
                  <span>{overview?.ttsModel || "tts 未知"}</span>
                  <button
                    type="button"
                    className="header-avatar-button"
                    onClick={() => userAvatarInputRef.current?.click()}
                    aria-label="更换用户头像"
                  >
                    <span className="header-avatar-preview">
                      {userAvatarSrc ? <img src={userAvatarSrc} alt="用户头像" /> : <span>我</span>}
                    </span>
                    <span className="header-avatar-text">{userAvatarSrc ? "点击更换头像" : "点击设置头像"}</span>
                  </button>
                </div>
              </header>

              <section className="chat-body" ref={chatBodyRef} onScroll={handleChatScroll}>
                {isBooting ? <div className="system-tip">正在连接后端...</div> : null}
                {isLoadingMessages && !isBooting ? <div className="system-tip">正在加载会话消息...</div> : null}

                {messages.map((message) => (
                  <ChatMessageRow
                    key={message.id}
                    message={message}
                    aiAvatarSrc={aiAvatarSrc}
                    userAvatarSrc={userAvatarSrc}
                    exportInfo={getMessageExportInfo(message)}
                    activeSessionId={activeSessionId}
                    savingMessageId={savingMessageId}
                    speakingMessageId={speakingMessageId}
                    onSaveExportFile={handleSaveExportFile}
                    onSpeakText={speakText}
                  />
                ))}
                <div ref={messagesEndRef} />
              </section>

              <footer className="chat-composer">
                <form onSubmit={handleSubmit}>
                  <div className="composer-shell">
                    <div className="composer-plus-wrap" ref={attachmentMenuRef}>
                      {isAttachmentMenuOpen ? (
                        <div className="attachment-menu">
                          <button type="button" className="attachment-menu-item" onClick={openAttachmentPicker}>
                            <span className="attachment-menu-icon" aria-hidden="true">＋</span>
                            <span className="attachment-menu-copy">
                              <strong>本地图片/文件</strong>
                              <span>图片和文件都从这里上传</span>
                            </span>
                          </button>
                        </div>
                      ) : null}

                      <button
                        type="button"
                        className="composer-plus"
                        onClick={handleAttachmentMenuToggle}
                        disabled={isSending || isUploading || !activeSessionId}
                      >
                        +
                      </button>
                    </div>

                    <div className="composer-input-wrap">
                      <textarea value={input} onChange={(event) => setInput(event.currentTarget.value)} onKeyDown={handleComposerKeyDown} placeholder="输入消息" rows={1} />
                    </div>

                    <button
                      type="button"
                      className={`composer-voice ${isRecording ? "recording" : ""}`}
                      onClick={() => void toggleVoiceRecording()}
                      disabled={isSending || isUploading || !activeSessionId}
                      aria-label="语音输入"
                    >
                      {isRecording ? "停止" : "语音"}
                    </button>
                  </div>
                  {recordingError ? <div className="composer-hint error">{recordingError}</div> : null}
                </form>

                <input ref={fileInputRef} type="file" className="hidden-file-input" onChange={handleFileChange} />
                <input ref={aiAvatarInputRef} type="file" className="hidden-file-input" accept="image/*" onChange={(event) => void handleAvatarChange(event, "ai")} />
                <input ref={userAvatarInputRef} type="file" className="hidden-file-input" accept="image/*" onChange={(event) => void handleAvatarChange(event, "user")} />
              </footer>
            </div>
          ) : viewMode === "monitor" ? (
            <div className="dashboard-stage">
              <header className="chat-header dashboard-header">
                <div>
                  <h2>运行监控看板</h2>
                  <p>{monitorSummary ? `基于最近 ${monitorSummary.recentRequests.length} 条请求` : "等待监控数据"}</p>
                </div>
                <div className="header-meta">
                  <span>{monitorSummary?.currentChatModel || overview?.ttsModel || "模型未知"}</span>
                  <span>总请求 {formatCount(monitorSummary?.totalRequests)}</span>
                </div>
              </header>

              <section className="dashboard-body">
                {monitorSummary ? (
                  <>
                    <section className="dashboard-section">
                      <div className="dashboard-section-head">
                        <h3>核心指标</h3>
                        <span>桌面端实时摘要</span>
                      </div>
                      <div className="dashboard-card-grid">
                        {monitorCards.map((card) => (
                          <article key={card.label} className="dashboard-card">
                            <span className="dashboard-card-label">{card.label}</span>
                            <strong className="dashboard-card-value">{card.value}</strong>
                            <span className="dashboard-card-hint">{card.hint}</span>
                          </article>
                        ))}
                      </div>
                    </section>

                    <section className="dashboard-two-column">
                      <section className="dashboard-section">
                        <div className="dashboard-section-head">
                          <h3>请求趋势</h3>
                          <span>10 分钟桶聚合</span>
                        </div>
                        <div className="trend-chart-shell">
                          <div className="trend-toolbar">
                            <div className="trend-toolbar-copy">
                              <span className="trend-toolbar-eyebrow">最近窗口</span>
                              <strong>{trendRangeLabel}</strong>
                              <p>左轴展示请求次数，右轴展示 Token，总览最近一段调用密度。</p>
                            </div>
                            <div className="trend-toolbar-actions">
                              <span className="trend-chip active">近 8 段</span>
                              <span className="trend-chip">10 分钟粒度</span>
                              <button
                                type="button"
                                className="trend-refresh-button"
                                onClick={() => void loadMonitorSummary()}
                              >
                                刷新数据
                              </button>
                            </div>
                          </div>

                          {monitorTrend.length ? (
                            <>
                              <div className="trend-snapshot-grid">
                                {trendSnapshotCards.map((card) => (
                                  <article key={card.label} className="trend-snapshot-card">
                                    <span>{card.label}</span>
                                    <strong>{card.value}</strong>
                                    <em>{card.hint}</em>
                                  </article>
                                ))}
                              </div>

                              <div className="trend-legend">
                                <span><i className="legend-swatch count" /> 请求数</span>
                                <span><i className="legend-swatch token" /> Token</span>
                              </div>

                              <div
                                className="trend-chart-wrap"
                                onMouseLeave={() => setHoveredTrendIndex(null)}
                              >
                                <svg className="trend-chart" viewBox={`0 0 ${TREND_CHART_WIDTH} ${TREND_CHART_HEIGHT}`} aria-label="请求趋势图">
                                  {trendAxisTicks.map((tick) => (
                                    <g key={tick.y}>
                                      <line
                                        x1={TREND_CHART_PADDING}
                                        y1={tick.y}
                                        x2={TREND_CHART_WIDTH - TREND_CHART_PADDING}
                                        y2={tick.y}
                                        className="trend-gridline"
                                      />
                                      <text
                                        x={6}
                                        y={tick.y + 4}
                                        className="trend-axis-label left"
                                      >
                                        {tick.countLabel}
                                      </text>
                                      <text
                                        x={TREND_CHART_WIDTH - 6}
                                        y={tick.y + 4}
                                        textAnchor="end"
                                        className="trend-axis-label right"
                                      >
                                        {formatCount(tick.tokenLabel)}
                                      </text>
                                    </g>
                                  ))}
                                  <line
                                    x1={TREND_CHART_PADDING}
                                    y1={TREND_CHART_HEIGHT - TREND_CHART_PADDING}
                                    x2={TREND_CHART_WIDTH - TREND_CHART_PADDING}
                                    y2={TREND_CHART_HEIGHT - TREND_CHART_PADDING}
                                    className="trend-axis"
                                  />
                                  <line
                                    x1={TREND_CHART_PADDING}
                                    y1={TREND_CHART_PADDING}
                                    x2={TREND_CHART_PADDING}
                                    y2={TREND_CHART_HEIGHT - TREND_CHART_PADDING}
                                    className="trend-axis"
                                  />
                                  {trendTokenArea ? <path d={trendTokenArea} className="trend-area token" /> : null}
                                  {trendCountArea ? <path d={trendCountArea} className="trend-area count" /> : null}
                                  {trendTokenPath ? <path d={trendTokenPath} className="trend-line token" /> : null}
                                  {trendCountPath ? <path d={trendCountPath} className="trend-line count" /> : null}
                                  {trendChartPoints.map((item, index) => {
                                    return (
                                      <g key={item.ts}>
                                        <line
                                          x1={item.x}
                                          y1={TREND_CHART_PADDING}
                                          x2={item.x}
                                          y2={TREND_CHART_HEIGHT - TREND_CHART_PADDING}
                                          className={hoveredTrendIndex === index ? "trend-hover-line active" : "trend-hover-line"}
                                        />
                                        <circle cx={item.x} cy={item.countY} r="4" className={hoveredTrendIndex === index ? "trend-point count active" : "trend-point count"} />
                                        <circle cx={item.x} cy={item.tokenY} r="3.5" className={hoveredTrendIndex === index ? "trend-point token active" : "trend-point token"} />
                                        <rect
                                          x={item.hitX}
                                          y={TREND_CHART_PADDING}
                                          width={item.hitWidth}
                                          height={TREND_CHART_HEIGHT - TREND_CHART_PADDING * 2}
                                          className="trend-hover-target"
                                          onMouseEnter={() => setHoveredTrendIndex(index)}
                                        />
                                      </g>
                                    );
                                  })}
                                </svg>
                                <div className="trend-axis-captions">
                                  <span>请求数</span>
                                  <span>Token</span>
                                </div>
                                <div className={hoveredTrendPoint ? "trend-tooltip visible" : "trend-tooltip"}>
                                  {hoveredTrendPoint ? (
                                    <>
                                      <strong>{hoveredTrendPoint.label}</strong>
                                      <span>请求数 {hoveredTrendPoint.count}</span>
                                      <span>Token {formatCount(hoveredTrendPoint.tokens)}</span>
                                      <span>平均耗时 {formatDuration(hoveredTrendPoint.avgDuration)}</span>
                                    </>
                                  ) : (
                                    <>
                                      <strong>悬浮查看明细</strong>
                                      <span>移动到折线图上的时间点</span>
                                    </>
                                  )}
                                </div>
                              </div>

                              <div
                                className="trend-label-row"
                                style={{ gridTemplateColumns: `repeat(${Math.max(monitorTrend.length, 1)}, minmax(72px, 1fr))` }}
                              >
                                {monitorTrend.map((item) => (
                                  <span key={item.ts}>{item.label}</span>
                                ))}
                              </div>
                            </>
                          ) : (
                            <div className="trend-empty-state">最近暂无足够请求数据，产生新请求后会自动生成趋势图。</div>
                          )}
                        </div>
                        <div className="trend-summary-grid">
                          {monitorTrend.map((item) => (
                            <article key={item.ts} className="trend-summary-card">
                              <strong>{item.count} 次</strong>
                              <span>{item.label}</span>
                              <span>{formatCount(item.tokens)} tok · {formatDuration(item.avgDuration)}</span>
                            </article>
                          ))}
                        </div>
                      </section>

                      <section className="dashboard-section model-distribution-section">
                        <div className="dashboard-section-head">
                          <h3>模型分布</h3>
                          <span>按最近请求统计</span>
                        </div>
                        <div className="dashboard-list chart-list model-stats-list">
                          {modelStats.map((item) => (
                            <article key={item.model} className="stats-row-card chart-card">
                              <div className="stats-row-top">
                                <strong>{item.model}</strong>
                                <span>{item.count} 次</span>
                              </div>
                              <div className="distribution-track">
                                <div
                                  className="distribution-fill model"
                                  style={{ width: `${Math.max((item.count / modelStatsMaxCount) * 100, 12)}%` }}
                                />
                              </div>
                              <div className="stats-row-meta">
                                <span>成功率 {item.successRate}</span>
                                <span>总 Token {formatCount(item.tokens)}</span>
                                <span>平均耗时 {formatDuration(item.avgDuration)}</span>
                              </div>
                            </article>
                          ))}
                        </div>
                      </section>
                    </section>

                    <section className="dashboard-two-column">
                      <section className="dashboard-section">
                        <div className="dashboard-section-head">
                          <h3>路由命中</h3>
                          <span>高频路由排序</span>
                        </div>
                        <div className="dashboard-list chart-list">
                          {routeStats.map((item) => (
                            <article key={item.key} className="stats-row-card chart-card">
                              <div className="stats-row-top">
                                <strong>{item.key}</strong>
                                <span>{item.count} 次</span>
                              </div>
                              <div className="distribution-track">
                                <div
                                  className="distribution-fill route"
                                  style={{ width: `${Math.max((item.count / routeStatsMaxCount) * 100, 12)}%` }}
                                />
                              </div>
                              <div className="stats-row-meta">
                                <span>成功率 {item.successRate}</span>
                                <span>工具次数 {item.tools}</span>
                                <span>占比 {formatPercent((item.count / Math.max(monitorSummary.recentRequests.length, 1)) * 100)}</span>
                              </div>
                            </article>
                          ))}
                        </div>
                      </section>

                      <section className="dashboard-section">
                        <div className="dashboard-section-head">
                          <h3>最近请求明细</h3>
                          <span>最近 8 条</span>
                        </div>
                        <div className="dashboard-list">
                          {monitorSummary.recentRequests.slice(0, 8).map((request) => (
                            <article
                              key={request.requestId}
                              className={`monitor-request-item ${request.success ? "success" : "failed"}`}
                            >
                              <div className="monitor-request-top">
                                <span className="monitor-request-route">{formatRouteLabel(request)}</span>
                                <span className="monitor-request-status">{request.success ? "成功" : "失败"}</span>
                              </div>
                              <div className="monitor-request-meta">
                                <span>{request.model || "模型未知"}</span>
                                <span>{formatDuration(request.durationMs)}</span>
                                <span>{formatCount(request.totalTokens)} tok</span>
                              </div>
                              <div className="monitor-request-submeta">
                                <span>{request.stream ? "流式" : "非流式"}</span>
                                <span>工具 {request.toolCallCount}</span>
                                <span>{formatMonitorTime(request.startedAt)}</span>
                              </div>
                              {request.errorMessage ? (
                                <p className="monitor-request-error">{request.errorMessage}</p>
                              ) : null}
                            </article>
                          ))}
                        </div>
                      </section>
                    </section>
                  </>
                ) : (
                  <div className="dashboard-empty">监控数据加载中...</div>
                )}
              </section>
            </div>
          ) : (
            <div className="settings-stage">
              <header className="chat-header dashboard-header"><div><h2>能力设置</h2><p>当前账号：{authUser.displayName || authUser.username}</p></div></header>
              <section className="settings-body">
                <section className="settings-section model-editor-section">
                  <div className="dashboard-section-head"><h3>默认模型</h3><span>API Key 仅保存加密后的值</span></div>
                  <form className="settings-form" onSubmit={saveModelProfile}>
                    <label>服务商<input value={modelForm.provider} onChange={(e) => setModelForm({ ...modelForm, provider: e.target.value })} /></label>
                    <label>兼容接口地址<input value={modelForm.baseUrl} onChange={(e) => setModelForm({ ...modelForm, baseUrl: e.target.value })} /></label>
                    <label>模型名称<input value={modelForm.model} onChange={(e) => setModelForm({ ...modelForm, model: e.target.value })} /></label>
                    <label>API Key<input type="password" value={modelForm.apiKey} onChange={(e) => setModelForm({ ...modelForm, apiKey: e.target.value })} placeholder="输入后覆盖当前密钥" /></label>
                    <div className="settings-form-row"><label>Temperature<input type="number" min="0" max="2" step="0.1" value={modelForm.temperature} onChange={(e) => setModelForm({ ...modelForm, temperature: e.target.value })} /></label><label>Max Tokens<input type="number" min="1" max="128000" value={modelForm.maxTokens} onChange={(e) => setModelForm({ ...modelForm, maxTokens: e.target.value })} /></label></div>
                    <button type="submit" className="auth-submit" disabled={modelSaving}>{modelSaving ? "保存中..." : "保存并设为默认"}</button>
                    {modelMessage ? <p className="settings-message">{modelMessage}</p> : null}
                  </form>
                </section>
                <section className="settings-section model-list-section"><div className="dashboard-section-head"><h3>已配置模型</h3><span>{modelProfiles.length} 个配置</span></div><div className="settings-list">{modelProfiles.map((item) => <article className="settings-item" key={item.id}><strong>{item.model}</strong><span>{item.provider} · {item.baseUrl}</span><em>{item.defaultProfile ? "默认" : "备用"} · {item.maskedApiKey}</em></article>)}{!modelProfiles.length ? <div className="settings-empty-state"><strong>暂无模型配置</strong><span>保存左侧配置后将在这里显示</span></div> : null}</div></section>
                <section className="settings-section skill-settings-section">
                  <div className="dashboard-section-head"><h3>技能能力</h3><span>{skills.filter((skill) => skill.enabled).length}/{skills.length} 已启用</span></div>
                  <div className="skill-settings-list">
                    {skills.map((skill) => (
                      <label className="skill-setting-row" key={skill.name}>
                        <span><strong>{skill.name}</strong><small>{skill.enabled ? "可供模型调用" : "已从模型工具列表隐藏"}</small></span>
                        <input type="checkbox" role="switch" checked={skill.enabled} disabled={!skill.available || skillUpdating === skill.name} onChange={(event) => void setSkillEnabled(skill.name, event.target.checked)} />
                      </label>
                    ))}
                    {!skills.length ? <div className="dashboard-empty">暂无可配置技能</div> : null}
                  </div>
                  {skillMessage ? <p className="settings-message">{skillMessage}</p> : null}
                </section>
                <section className="settings-section mcp-editor-section">
                  <div className="dashboard-section-head"><h3>{editingMcpId == null ? "添加 MCP 服务" : "编辑 MCP 服务"}</h3><span>stdio</span></div>
                  <form className="settings-form" onSubmit={saveMcpServer}>
                    <div className="settings-form-row"><label>服务标识<input required pattern="[a-zA-Z0-9_-]+" value={mcpForm.serverKey} onChange={(e) => setMcpForm({ ...mcpForm, serverKey: e.target.value })} /></label><label>显示名称<input required value={mcpForm.displayName} onChange={(e) => setMcpForm({ ...mcpForm, displayName: e.target.value })} /></label></div>
                    <label>启动命令<input required value={mcpForm.command} onChange={(e) => setMcpForm({ ...mcpForm, command: e.target.value })} /></label>
                    <label>参数（每行一个）<textarea rows={3} value={mcpForm.args} onChange={(e) => setMcpForm({ ...mcpForm, args: e.target.value })} /></label>
                    <label>工作目录<input value={mcpForm.workingDirectory} onChange={(e) => setMcpForm({ ...mcpForm, workingDirectory: e.target.value })} /></label>
                    <label>环境变量（每行 KEY=VALUE）<textarea rows={3} value={mcpForm.environment} onChange={(e) => setMcpForm({ ...mcpForm, environment: e.target.value })} /></label>
                    <div className="settings-form-row"><label>超时（毫秒）<input type="number" min="1000" max="120000" value={mcpForm.timeoutMs} onChange={(e) => setMcpForm({ ...mcpForm, timeoutMs: e.target.value })} /></label><label className="settings-checkbox"><input type="checkbox" checked={mcpForm.enabled} onChange={(e) => setMcpForm({ ...mcpForm, enabled: e.target.checked })} />保存后启用</label></div>
                    <div className="settings-form-row"><label>最大并发请求<input type="number" min="1" max="16" value={mcpForm.maxConcurrentRequests} onChange={(e) => setMcpForm({ ...mcpForm, maxConcurrentRequests: e.target.value })} /></label><label>进程内存上限（MB）<input type="number" min="64" max="4096" value={mcpForm.maxMemoryMb} onChange={(e) => setMcpForm({ ...mcpForm, maxMemoryMb: e.target.value })} /></label></div>
                    <div className="settings-form-row"><label>累计 CPU 秒数<input type="number" min="1" max="86400" value={mcpForm.maxCpuSeconds} onChange={(e) => setMcpForm({ ...mcpForm, maxCpuSeconds: e.target.value })} /></label><label>CPU hard cap（%）<input type="number" min="1" max="100" value={mcpForm.maxCpuPercent} onChange={(e) => setMcpForm({ ...mcpForm, maxCpuPercent: e.target.value })} /></label></div>
                    <label>最大响应（字节）<input type="number" min="65536" max="16777216" value={mcpForm.maxResponseBytes} onChange={(e) => setMcpForm({ ...mcpForm, maxResponseBytes: e.target.value })} /></label>
                    <div className="mcp-form-actions"><button type="submit" className="auth-submit" disabled={mcpBusy}>{mcpBusy ? "处理中..." : editingMcpId == null ? "保存 MCP 服务" : "更新 MCP 服务"}</button>{editingMcpId != null ? <button type="button" onClick={() => { setEditingMcpId(null); setMcpForm(EMPTY_MCP_FORM); }}>取消编辑</button> : null}</div>
                    {mcpMessage ? <p className="settings-message">{mcpMessage}</p> : null}
                  </form>
                </section>
                <section className="settings-section mcp-list-section">
                  <div className="dashboard-section-head"><h3>MCP 服务</h3><span>{mcpServers.filter((item) => item.enabled).length}/{mcpServers.length} 运行可用</span></div>
                  <div className="settings-list">{mcpServers.map((server) => {
                    const status = mcpStatuses[server.id]; const tools = mcpTools[server.id];
                    return <article className="settings-item mcp-settings-item" key={server.id}>
                      <div><strong>{server.displayName}</strong><input type="checkbox" role="switch" checked={server.enabled} disabled={mcpBusy} onChange={(event) => void toggleMcpServer(server, event.target.checked)} /></div>
                      <span>{server.serverKey} · {server.command} {server.args.join(" ")}</span>
                      <em>{server.environmentKeys.length ? `环境变量：${server.environmentKeys.join(", ")}` : "无环境变量"} · 并发 {server.maxConcurrentRequests} · {server.maxMemoryMb} MB · CPU {server.maxCpuPercent}%/{server.maxCpuSeconds}s</em>
                      <div className="mcp-runtime-row"><span className={`mcp-status ${status?.state?.toLowerCase() || "stopped"}`}>{status?.state || "STOPPED"}</span><small>{status?.pid ? `PID ${status.pid}` : "无运行进程"}{status?.pendingRequests ? ` · 等待 ${status.pendingRequests}` : ""}{status?.resourceLimitMode ? ` · ${status.resourceLimitMode}` : ""}</small></div>
                      {status?.stderr?.length ? <details><summary>最近 stderr</summary><pre>{status.stderr.join("\n")}</pre></details> : null}
                      <div className="mcp-item-actions"><button type="button" disabled={!server.enabled || mcpTesting === server.id} onClick={() => void testMcpServer(server)}>{mcpTesting === server.id ? "检测中..." : "检测连接"}</button><button type="button" disabled={!server.enabled || mcpBusy} onClick={() => void controlMcpServer(server, "restart")}>重启</button><button type="button" disabled={mcpBusy} onClick={() => void controlMcpServer(server, "stop")}>关闭</button><button type="button" onClick={() => editMcpServer(server)}>编辑</button><button type="button" className="danger" onClick={() => void deleteMcpServer(server)}>删除</button></div>
                      <small>{mcpTestResults[server.id] || ""}</small>
                      <button type="button" className="mcp-tools-toggle" disabled={!server.enabled} onClick={() => void loadMcpTools(server)}>{tools ? `刷新工具（${tools.length}）` : "查看已发现工具"}</button>
                      {tools ? <div className="mcp-tool-list">{tools.map((tool) => <label key={tool.remoteName}><span><strong>{tool.remoteName}</strong><small>{tool.description}</small></span><input type="checkbox" checked={tool.enabled} onChange={(event) => void toggleMcpTool(server, tool, event.target.checked)} /></label>)}{!tools.length ? <small>该服务未返回工具</small> : null}</div> : null}
                    </article>;
                  })}{!mcpServers.length ? <div className="dashboard-empty">尚未添加 MCP 服务</div> : null}</div>
                </section>
              </section>
            </div>
          )}
        </section>
      </section>
    </main>
  );
}

export default App;
