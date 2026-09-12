package com.claw.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileParseService {
    private static final Logger log = LoggerFactory.getLogger(FileParseService.class);

    private static final int MAX_PREVIEW_CHARS = 6000;
    private static final int MAX_TEXT_FILE_BYTES = 200_000;
    private static final int MAX_CACHE_SIZE = 50;
    private static final int DEFAULT_CHUNK_SIZE = 4000;
    private static final int MAX_QA_RESULTS = 6;
    private static final int MAX_SEGMENT_LENGTH = 280;

    private static final Set<String> PARSEABLE_EXTENSIONS = Set.of(
            "txt", "md", "csv", "log", "json", "xml", "yaml", "yml", "pdf", "doc", "docx"
    );
    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "什么", "哪些", "哪个", "哪里", "多少", "几个", "请问", "帮我", "看看", "看下", "查询", "查看",
            "告诉", "说说", "总结", "概括", "分析", "内容", "信息", "资料", "文件", "文档", "相关", "部分",
            "这个", "那个", "里面", "其中", "全部", "都有", "还有", "一下", "一下子", "一下吧"
    );

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9._-]+");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?:\\d{1,4}[年/-])?\\d{1,2}[月/-]\\d{1,2}[日号]?");
    private static final Pattern WEEK_MARKER_PATTERN = Pattern.compile("第\\s*([一二三四五六七八九十百零两\\d]+)\\s*周");
    private static final Pattern CALENDAR_DATE_PATTERN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern SIMPLE_DATE_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("周[一二三四五六日天]");
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}\\s*[-—~～]\\s*\\d{1,2}:\\d{2}\\b");
    private static final Pattern LOCATION_LABEL_PATTERN = Pattern.compile("(?:地点|地点与提示|学习地点)\\s*[:：]\\s*([^\\n|；;。]+)");
    private static final Pattern LOCATION_FALLBACK_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9（）()\\-\\s]{2,40}(?:报告厅|教室|会议室|腾讯会议))");
    private static final Pattern SPEAKER_LABEL_PATTERN = Pattern.compile("(?:主讲人(?:信息)?|主讲人员|主讲)\\s*[:：]\\s*([\\p{IsHan}·]{2,20})");
    private static final Pattern SPEAKER_AFTER_TIME_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}\\s*[-—~～]\\s*\\d{1,2}:\\d{2}\\b[^\\n|]*?\\s([\\p{IsHan}·]{2,4})(?=\\s|$)");
    private static final Pattern TITLE_LABEL_PATTERN = Pattern.compile("(?:讲座名称|课程/讲座主题)\\s*[:：]\\s*([^\\n|；;]+)");
    private static final Pattern QUOTED_TITLE_PATTERN = Pattern.compile("[《“\"]([^》”\"]{4,80})[》”\"]");

    private final Map<String, CachedFileContent> contentCache = new ConcurrentHashMap<>();
    private final Map<String, String> activeFileBySession = new ConcurrentHashMap<>();
    private final Map<String, String> latestFileNameBySession = new ConcurrentHashMap<>();

    public String detectFileType(String fileName, byte[] fileBytes) {
        String bySignature = detectBySignature(fileBytes);
        if (bySignature != null) {
            return bySignature;
        }
        String byExtension = detectByExtension(fileName);
        return byExtension != null ? byExtension : "未知文件";
    }

    public static String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isParseable(String fileName) {
        String extension = extractExtension(fileName);
        return !extension.isEmpty() && PARSEABLE_EXTENSIONS.contains(extension);
    }

    public String parseFile(String fileName, byte[] fileData) {
        if (fileData == null || fileData.length == 0) {
            return "文件内容为空。";
        }

        String fileId = buildFileId(fileName, fileData);
        CachedFileContent existing = contentCache.get(fileId);
        if (existing != null) {
            return existing.content();
        }

        if (contentCache.size() >= MAX_CACHE_SIZE) {
            evictOldestEntry();
        }

        String content;
        try {
            content = extractContent(fileName, fileData);
        } catch (Exception e) {
            log.error("解析文件失败, fileName={}, size={}", fileName, fileData.length, e);
            content = "文件解析出错：" + e.getMessage();
        }

        CachedFileContent cached = new CachedFileContent(
                fileId,
                normalizeText(fileName == null || fileName.isBlank() ? "uploaded-file" : fileName.trim()),
                normalizeText(content),
                System.currentTimeMillis()
        );
        contentCache.put(fileId, cached);
        return cached.content();
    }

    public String cacheSessionFile(String sessionId, String fileName, byte[] fileData) {
        String content = parseFile(fileName, fileData);
        String fileId = buildFileId(fileName, fileData);
        activeFileBySession.put(sessionId, fileId);
        latestFileNameBySession.put(sessionId, normalizeText(fileName));
        return content;
    }

    public void rememberSessionFileReference(String sessionId, String fileName) {
        if (sessionId == null || sessionId.isBlank() || fileName == null || fileName.isBlank()) {
            return;
        }
        latestFileNameBySession.put(sessionId, normalizeText(fileName));
    }

    public boolean hasActiveFile(String sessionId) {
        return sessionId != null && activeFileBySession.containsKey(sessionId);
    }

    public void clearSessionContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeFileBySession.remove(sessionId);
        latestFileNameBySession.remove(sessionId);
    }

    public String getActiveFileName(String sessionId) {
        CachedFileContent cached = getActiveCachedFile(sessionId);
        return cached == null ? null : cached.fileName();
    }

    public String getLatestSessionFileName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String latest = latestFileNameBySession.get(sessionId);
        if (latest != null && !latest.isBlank()) {
            return latest;
        }
        return getActiveFileName(sessionId);
    }

    public String getFilePreview(String fileName, byte[] fileData) {
        return buildPreviewText(parseFile(fileName, fileData));
    }

    public String getCachedContent(String fileId) {
        CachedFileContent cached = contentCache.get(fileId);
        return cached == null ? null : cached.content();
    }

    public String findFileIdByName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String target = normalizeSearch(fileName);
        for (Map.Entry<String, CachedFileContent> entry : contentCache.entrySet()) {
            if (normalizeSearch(entry.getValue().fileName()).contains(target)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public String resolveFileId(String sessionId, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            return findFileIdByName(fileName);
        }
        return activeFileBySession.get(sessionId);
    }

    public String getSessionFileInfo(String sessionId, String fileName) {
        CachedFileContent cached = resolveCachedFile(sessionId, fileName);
        if (cached == null) {
            return "未找到当前会话中的文件，请先上传文件。";
        }
        List<String> chunks = splitContent(cached.content(), DEFAULT_CHUNK_SIZE);
        return """
                文件：%s
                内容长度：%d 字符
                分片数量：%d

                预览内容：
                %s
                """.formatted(
                cached.fileName(),
                cached.content().length(),
                chunks.size(),
                buildPreviewText(cached.content())
        ).trim();
    }

    public String getSessionFileFull(String sessionId, String fileName) {
        CachedFileContent cached = resolveCachedFile(sessionId, fileName);
        if (cached == null) {
            return "未找到当前会话中的文件，请先上传文件。";
        }
        if (cached.content().length() <= DEFAULT_CHUNK_SIZE) {
            return cached.content();
        }
        return splitContent(cached.content(), DEFAULT_CHUNK_SIZE).get(0)
                + "\n\n（文件较长，这里仅返回第一片内容）";
    }

    public String getSessionFileChunk(String sessionId, String fileName, Integer chunkIndex) {
        CachedFileContent cached = resolveCachedFile(sessionId, fileName);
        if (cached == null) {
            return "未找到当前会话中的文件，请先上传文件。";
        }
        List<String> chunks = splitContent(cached.content(), DEFAULT_CHUNK_SIZE);
        int index = chunkIndex == null ? 1 : chunkIndex;
        if (index < 1 || index > chunks.size()) {
            return "分片序号超出范围，有效范围为 1 ~ " + chunks.size() + "。";
        }
        return chunks.get(index - 1);
    }

    public String answerQuestion(String sessionId, String fileName, String question) {
        CachedFileContent cached = resolveCachedFile(sessionId, fileName);
        if (cached == null) {
            return "未找到当前会话中的文件，请先上传文件。";
        }
        return answerQuestionForContent(cached.fileName(), cached.content(), question);
    }

    String answerQuestionForContent(String fileName, String content, String question) {
        if (question == null || question.isBlank()) {
            return "未提供问题。";
        }
        if (content == null || content.isBlank()) {
            return "文件内容为空。";
        }

        List<String> segments = splitSegments(content);
        if (segments.isEmpty()) {
            return "文件内容为空。";
        }

        List<String> matched = findSectionMatches(segments, question);
        if (matched.isEmpty()) {
            matched = findRelevantPassages(segments, question);
        }
        if (matched.isEmpty()) {
            return "文件里没有找到和这个问题直接相关的内容，请换个问法或补充更明确的关键词。";
        }

        return buildAnswerText(fileName, question.trim(), content, matched);
    }

    private String buildAnswerText(String fileName, String question, String content, List<String> matched) {
        List<String> displayMatched = refineMatchedFragments(question, matched);
        if (displayMatched.isEmpty()) {
            displayMatched = matched;
        }
        List<ScheduleItem> scheduleItems = extractScheduleItems(content, question, matched);
        if (!scheduleItems.isEmpty()) {
            return buildScheduleAnswer(question, content, matched, scheduleItems);
        }
        return buildGenericAnswer(displayMatched);
    }

    private String buildScheduleAnswer(String question, String content, List<String> matched, List<ScheduleItem> scheduleItems) {
        StringBuilder sb = new StringBuilder();
        String weekMarker = extractWeekMarker(question);
        String arrangementType = extractArrangementType(content, matched);
        String primaryLocation = extractPrimaryLocation(content, matched);

        if (weekMarker != null || arrangementType != null || primaryLocation != null) {
            if (weekMarker != null) {
                sb.append(weekMarker);
            }
            if (arrangementType != null) {
                if (sb.isEmpty()) {
                    sb.append(arrangementType);
                } else {
                    sb.append(arrangementType.startsWith("线") ? arrangementType : "安排");
                }
            }
            if (primaryLocation != null) {
                if (!sb.isEmpty()) {
                    sb.append("，");
                }
                sb.append("地点：").append(primaryLocation);
            }
            if (!sb.isEmpty()) {
                sb.append("。").append('\n').append('\n');
            }
        }

        for (int i = 0; i < scheduleItems.size(); i++) {
            ScheduleItem item = scheduleItems.get(i);
            sb.append(i + 1).append(". ");
            if (!item.date().isBlank()) {
                sb.append(item.date());
            } else {
                sb.append("第").append(i + 1).append("项安排");
            }
            if (!item.weekday().isBlank()) {
                sb.append("（").append(item.weekday()).append("）");
            }
            sb.append('\n');
            sb.append("时间：").append(defaultIfBlank(item.time(), "原文未明确展示")).append('\n');
            if (!item.title().isBlank()) {
                sb.append("讲座名称：").append(item.title()).append('\n');
            }
            sb.append("主讲人：").append(defaultIfBlank(item.speaker(), "原文未明确展示")).append('\n');
            sb.append("地点：").append(defaultIfBlank(item.location(), "原文未明确展示")).append('\n');
            if (i < scheduleItems.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildGenericAnswer(List<String> matched) {
        if (matched.size() == 1) {
            return matched.get(0).trim();
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(Math.min(MAX_QA_RESULTS, matched.size()), 4);
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(". ").append(matched.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    private List<ScheduleItem> extractScheduleItems(String content, String question, List<String> matched) {
        String relevantText = extractRelevantSectionText(content, question);
        if (relevantText.isBlank()) {
            relevantText = String.join("\n", matched);
        }

        List<String> lines = tokenizeSectionText(relevantText);
        if (lines.isEmpty()) {
            return List.of();
        }

        String defaultLocation = extractPrimaryLocation(relevantText, matched);
        String currentDate = "";
        String currentWeekday = "";
        String currentLocation = defaultLocation == null ? "" : defaultLocation;
        String pendingTitle = "";
        List<ScheduleItem> items = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }

            String date = extractDisplayDate(line);
            String weekday = extractWeekday(line);
            String location = extractLocation(line);
            if (!date.isBlank()) {
                currentDate = date;
            }
            if (!weekday.isBlank()) {
                currentWeekday = weekday;
            }
            if (!location.isBlank()) {
                currentLocation = location;
            }

            if (containsExplicitTitle(line)) {
                TitleCapture titleCapture = collectTitle(lines, i);
                pendingTitle = titleCapture.title();
                i = titleCapture.endIndex();
                continue;
            }

            String time = extractTimeRange(line);
            if (time.isBlank()) {
                continue;
            }

            String itemSpeaker = extractSpeaker(line);
            String itemLocation = location.isBlank() ? currentLocation : location;
            int lookAheadIndex = i + 1;
            while (lookAheadIndex < lines.size()) {
                String nextLine = lines.get(lookAheadIndex);
                if (nextLine.isBlank()
                        || !extractTimeRange(nextLine).isBlank()
                        || !extractDisplayDate(nextLine).isBlank()
                        || !extractWeekday(nextLine).isBlank()
                        || containsExplicitTitle(nextLine)) {
                    break;
                }
                if (itemSpeaker.isBlank()) {
                    itemSpeaker = extractSpeaker(nextLine);
                }
                if (itemLocation.isBlank()) {
                    String nextLocation = extractLocation(nextLine);
                    if (!nextLocation.isBlank()) {
                        itemLocation = nextLocation;
                    }
                }
                lookAheadIndex++;
            }

            String itemTitle = pendingTitle;
            if (itemTitle.isBlank()) {
                itemTitle = extractTitle(line);
            }
            if (itemTitle.isBlank()) {
                continue;
            }

            items.add(new ScheduleItem(
                    currentDate,
                    currentWeekday,
                    time,
                    cleanTitleText(itemTitle),
                    itemSpeaker,
                    itemLocation
            ));
            pendingTitle = "";
        }

        return cleanupScheduleItems(deduplicateScheduleItems(items));
    }

    private List<ScheduleItem> deduplicateScheduleItems(List<ScheduleItem> items) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ScheduleItem> result = new ArrayList<>();
        for (ScheduleItem item : items) {
            String key = String.join("|", item.date(), item.weekday(), item.time(), item.title(), item.speaker(), item.location());
            if (seen.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    private String extractRelevantSectionText(String content, String question) {
        String normalizedContent = normalizeText(content);
        if (normalizedContent.isBlank()) {
            return "";
        }
        String weekMarker = extractWeekMarker(question);
        if (weekMarker == null) {
            return normalizedContent;
        }

        String[] lines = normalizedContent.split("\\n");
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.contains(weekMarker)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return normalizedContent;
        }
        for (int i = start + 1; i < lines.length; i++) {
            String nextMarker = extractWeekMarker(lines[i]);
            if (nextMarker != null && !nextMarker.equals(weekMarker)) {
                end = i;
                break;
            }
        }
        return String.join("\n", Arrays.copyOfRange(lines, start, end)).trim();
    }

    private List<String> tokenizeSectionText(String text) {
        List<String> tokens = new ArrayList<>();
        for (String line : normalizeText(text).split("\\n")) {
            for (String part : line.split("\\|")) {
                String token = cleanDisplayText(part);
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private String extractArrangementType(String content, List<String> matched) {
        String combined = normalizeText(content) + "\n" + String.join("\n", matched);
        if (combined.contains("线下讲座")) {
            return "线下讲座";
        }
        if (combined.contains("线上学习")) {
            return "线上学习";
        }
        if (combined.contains("线上课")) {
            return "线上课";
        }
        if (combined.contains("线下学习")) {
            return "线下学习";
        }
        return null;
    }

    private String extractPrimaryLocation(String content, List<String> matched) {
        String location = extractLocation(content);
        if (!location.isBlank()) {
            return location;
        }
        for (String segment : matched) {
            location = extractLocation(segment);
            if (!location.isBlank()) {
                return location;
            }
        }
        return null;
    }

    private String extractDisplayDate(String text) {
        Matcher calendarMatcher = CALENDAR_DATE_PATTERN.matcher(text);
        if (calendarMatcher.find()) {
            return calendarMatcher.group(1) + "月" + calendarMatcher.group(2) + "日";
        }
        if (text.contains(":")) {
            return "";
        }
        Matcher simpleMatcher = SIMPLE_DATE_PATTERN.matcher(text);
        if (simpleMatcher.find()) {
            String month = simpleMatcher.group(1);
            String day = simpleMatcher.group(2);
            if (Integer.parseInt(month) <= 12 && Integer.parseInt(day) <= 31) {
                return month + "月" + day + "日";
            }
        }
        return "";
    }

    private String extractWeekday(String text) {
        Matcher matcher = WEEKDAY_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private String extractTimeRange(String text) {
        Matcher matcher = TIME_RANGE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group().replaceAll("\\s+", "") : "";
    }

    private String extractLocation(String text) {
        Matcher labeledMatcher = LOCATION_LABEL_PATTERN.matcher(text);
        if (labeledMatcher.find()) {
            return cleanLocationText(labeledMatcher.group(1));
        }
        Matcher fallbackMatcher = LOCATION_FALLBACK_PATTERN.matcher(text);
        if (fallbackMatcher.find()) {
            return cleanLocationText(fallbackMatcher.group(1));
        }
        return "";
    }

    private String extractSpeaker(String text) {
        Matcher labeledMatcher = SPEAKER_LABEL_PATTERN.matcher(text);
        if (labeledMatcher.find()) {
            return labeledMatcher.group(1).trim();
        }
        Matcher afterTimeMatcher = SPEAKER_AFTER_TIME_PATTERN.matcher(text);
        if (afterTimeMatcher.find()) {
            return afterTimeMatcher.group(1).trim();
        }
        return "";
    }

    private String extractTitle(String text) {
        Matcher labeledMatcher = TITLE_LABEL_PATTERN.matcher(text);
        if (labeledMatcher.find()) {
            return cleanTitleText(labeledMatcher.group(1));
        }
        Matcher quotedMatcher = QUOTED_TITLE_PATTERN.matcher(text);
        if (quotedMatcher.find()) {
            return cleanTitleText(quotedMatcher.group(1));
        }
        if (text.startsWith("企业专家讲座：")) {
            return cleanTitleText(text.substring("企业专家讲座：".length()));
        }
        return "";
    }

    private boolean containsExplicitTitle(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("讲座名称")
                || text.contains("课程/讲座主题")
                || text.contains("企业专家讲座：")
                || QUOTED_TITLE_PATTERN.matcher(text).find();
    }

    private TitleCapture collectTitle(List<String> lines, int startIndex) {
        StringBuilder title = new StringBuilder(cleanTitleText(extractTitle(lines.get(startIndex))));
        if (title.isEmpty()) {
            title.append(cleanTitleText(lines.get(startIndex)));
        }

        int endIndex = startIndex;
        for (int i = startIndex + 1; i < lines.size(); i++) {
            String nextLine = cleanDisplayText(lines.get(i));
            if (nextLine.isBlank()
                    || !extractTimeRange(nextLine).isBlank()
                    || !extractDisplayDate(nextLine).isBlank()
                    || !extractWeekday(nextLine).isBlank()
                    || containsSpeakerMetadata(nextLine)
                    || containsLocationMetadata(nextLine)
                    || isNonTitleNarration(nextLine)) {
                break;
            }
            if (!looksLikeTitleContinuation(nextLine, title.toString())) {
                break;
            }
            title = new StringBuilder(joinTitleFragments(title.toString(), cleanTitleText(nextLine)));
            endIndex = i;
            if (hasClosedQuote(title.toString()) || title.length() >= 60) {
                break;
            }
        }
        return new TitleCapture(cleanTitleText(title.toString()), endIndex);
    }

    private boolean looksLikeTitleContinuation(String text) {
        return looksLikeTitleContinuation(text, "");
    }

    private boolean looksLikeTitleContinuation(String text, String existingTitle) {
        if (text.isBlank()) {
            return false;
        }
        if (text.length() < 2 || text.length() > 28) {
            return false;
        }
        if (extractDisplayDate(text).length() > 0 || extractWeekday(text).length() > 0 || extractTimeRange(text).length() > 0) {
            return false;
        }
        if (containsSpeakerMetadata(text) || containsLocationMetadata(text) || isNonTitleNarration(text)) {
            return false;
        }
        if (!existingTitle.isBlank() && hasClosedQuote(existingTitle) && !text.contains("》") && !text.contains("”")) {
            return false;
        }
        String[] blockedKeywords = {"主要内容", "主讲人信息", "地点", "提示", "福利", "平台", "评委", "专家库", "导师", "内容", "能力提升", "现场"};
        for (String keyword : blockedKeywords) {
            if (text.contains(keyword)) {
                return false;
            }
        }
        return text.chars().anyMatch(Character::isIdeographic);
    }

    private String cleanDisplayText(String text) {
        return normalizeText(text)
                .replace('　', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:;；、，,.\\-\\s]+", "")
                .replaceAll("[：:;；、\\-\\s]+$", "")
                .trim();
    }

    private String cleanLocationText(String text) {
        return cleanDisplayText(text).replaceAll("\\s+", "");
    }

    private String cleanTitleText(String text) {
        String value = cleanDisplayText(text)
                .replaceAll("^(讲座名称|课程/讲座主题)[:：]?", "")
                .replaceAll("\\s+", " ")
                .trim();
        value = value.replaceAll("([，。、】【；：！？——-])\\s+(?=[\\p{IsHan}《“A-Za-z])", "$1");
        value = value.replaceAll("\\s+([》”])", "$1");
        value = value.replaceAll("([《“])\\s+", "$1");
        return value;
    }

    private String appendDistinct(String existing, String value) {
        String cleanedValue = cleanDisplayText(value);
        if (cleanedValue.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return cleanedValue;
        }
        if (existing.contains(cleanedValue)) {
            return existing;
        }
        if (cleanedValue.contains(existing)) {
            return cleanedValue;
        }
        return existing + " " + cleanedValue;
    }

    private List<String> refineMatchedFragments(String question, List<String> matched) {
        List<String> exactDates = extractDates(question);
        if (exactDates.isEmpty()) {
            return matched;
        }

        List<String> refined = new ArrayList<>();
        for (String segment : matched) {
            for (String part : segment.split("\\|")) {
                String candidate = cleanDisplayText(part);
                if (candidate.isBlank()) {
                    continue;
                }
                String normalizedCandidate = normalizeSearch(candidate);
                boolean keep = false;
                for (String date : exactDates) {
                    if (normalizedCandidate.contains(date)) {
                        keep = true;
                        break;
                    }
                }
                if (keep) {
                    refined.add(candidate);
                }
            }
        }
        return refined;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<ScheduleItem> cleanupScheduleItems(List<ScheduleItem> items) {
        List<ScheduleItem> result = new ArrayList<>();
        for (ScheduleItem item : items) {
            if (item.title().isBlank() || item.time().isBlank()) {
                continue;
            }
            if (isNonTitleNarration(item.title())) {
                continue;
            }
            result.add(new ScheduleItem(
                    item.date(),
                    item.weekday(),
                    item.time(),
                    cleanTitleText(item.title()),
                    item.speaker(),
                    item.location()
            ));
        }
        return result;
    }

    private boolean containsSpeakerMetadata(String text) {
        return text.contains("主讲人") || text.contains("主讲人员");
    }

    private boolean containsLocationMetadata(String text) {
        return text.contains("地点") || text.contains("报告厅") || text.contains("腾讯会议");
    }

    private boolean isNonTitleNarration(String text) {
        String[] narrationKeywords = {
                "主要内容", "福利", "提示", "学习形式", "项目解析", "行业就业指导",
                "人才培养经验", "入场", "现场开通", "席位有限", "帮助学生", "围绕",
                "开展", "引导学生", "成长路径", "发展建议", "实战", "能力提升"
        };
        for (String keyword : narrationKeywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClosedQuote(String text) {
        return (text.contains("《") && text.contains("》")) || (text.contains("“") && text.contains("”"));
    }

    private String joinTitleFragments(String existing, String next) {
        if (existing == null || existing.isBlank()) {
            return next == null ? "" : next;
        }
        if (next == null || next.isBlank()) {
            return existing;
        }
        char last = existing.charAt(existing.length() - 1);
        char first = next.charAt(0);
        if (isAsciiWordChar(last) && isAsciiWordChar(first)) {
            return existing + " " + next;
        }
        return existing + next;
    }

    private boolean isAsciiWordChar(char ch) {
        return ch <= 127 && Character.isLetterOrDigit(ch);
    }

    public List<String> splitContent(String content, int maxChunkSize) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (content.length() <= maxChunkSize) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        int offset = 0;
        int chunkIndex = 1;
        int totalChunks = (content.length() + maxChunkSize - 1) / maxChunkSize;
        while (offset < content.length()) {
            int end = Math.min(offset + maxChunkSize, content.length());
            if (end < content.length()) {
                int lastNewLine = content.lastIndexOf('\n', end);
                if (lastNewLine > offset + maxChunkSize / 2) {
                    end = lastNewLine + 1;
                }
            }
            chunks.add("[第 " + chunkIndex + " 片 / 共 " + totalChunks + " 片]\n" + content.substring(offset, end).trim());
            offset = end;
            chunkIndex++;
        }
        return chunks;
    }

    public void clearCache() {
        contentCache.clear();
        activeFileBySession.clear();
        latestFileNameBySession.clear();
    }

    public String getSessionFileContent(String sessionId, String fileName) {
        CachedFileContent cached = resolveCachedFile(sessionId, fileName);
        return cached == null ? null : cached.content();
    }

    private CachedFileContent resolveCachedFile(String sessionId, String fileName) {
        String fileId = resolveFileId(sessionId, fileName);
        return fileId == null ? null : contentCache.get(fileId);
    }

    private CachedFileContent getActiveCachedFile(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String fileId = activeFileBySession.get(sessionId);
        return fileId == null ? null : contentCache.get(fileId);
    }

    private List<String> findSectionMatches(List<String> segments, String question) {
        String weekMarker = extractWeekMarker(question);
        if (weekMarker == null) {
            return List.of();
        }

        int start = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (normalizeSearch(segments.get(i)).contains(normalizeSearch(weekMarker))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (int i = start; i < segments.size(); i++) {
            String segment = segments.get(i);
            if (i > start) {
                String nextWeekMarker = extractWeekMarker(segment);
                if (nextWeekMarker != null && !normalizeSearch(nextWeekMarker).equals(normalizeSearch(weekMarker))) {
                    break;
                }
            }
            result.add(segment);
            if (result.size() >= MAX_QA_RESULTS) {
                break;
            }
        }
        return result;
    }

    private List<String> findRelevantPassages(List<String> segments, String question) {
        List<String> keywords = buildQuestionKeywords(question);
        List<String> exactDates = extractDates(question);
        String normalizedQuestion = normalizeSearch(question);
        List<ScoredSegment> scored = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            String normalizedSegment = normalizeSearch(segment);
            if (normalizedSegment.isBlank()) {
                continue;
            }

            int score = 0;
            if (!normalizedQuestion.isBlank() && normalizedSegment.contains(normalizedQuestion)) {
                score += 120;
            }

            boolean dateRequired = !exactDates.isEmpty();
            boolean dateMatched = exactDates.isEmpty();
            for (String date : exactDates) {
                if (normalizedSegment.contains(date)) {
                    score += 80;
                    dateMatched = true;
                }
            }
            if (dateRequired && !dateMatched) {
                continue;
            }

            for (String keyword : keywords) {
                if (normalizedSegment.contains(keyword)) {
                    score += keyword.length() >= 4 ? 18 : 10;
                }
            }

            if (score > 0) {
                scored.add(new ScoredSegment(i, segment, score));
            }
        }

        scored.sort(Comparator
                .comparingInt(ScoredSegment::score).reversed()
                .thenComparingInt(ScoredSegment::index));

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ScoredSegment item : scored) {
            result.add(item.segment());
            if (result.size() >= MAX_QA_RESULTS) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> buildQuestionKeywords(String question) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(question == null ? "" : question);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.isBlank()) {
                continue;
            }
            String normalized = normalizeSearch(token);
            if (normalized.isBlank() || QUESTION_STOP_WORDS.contains(normalized)) {
                continue;
            }
            if (normalized.length() >= 2 || normalized.matches("\\d+")) {
                keywords.add(normalized);
            }
        }
        String weekMarker = extractWeekMarker(question);
        if (weekMarker != null) {
            keywords.add(normalizeSearch(weekMarker));
        }
        keywords.addAll(extractDates(question));
        return new ArrayList<>(keywords);
    }

    private List<String> extractDates(String question) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(question == null ? "" : question);
        while (matcher.find()) {
            String value = normalizeSearch(matcher.group());
            if (!value.isBlank()) {
                dates.add(value);
            }
        }
        return dates;
    }

    private String extractWeekMarker(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = WEEK_MARKER_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group().replaceAll("\\s+", "");
        }
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.contains("第一周")) return "第一周";
        if (normalized.contains("第二周")) return "第二周";
        if (normalized.contains("第三周")) return "第三周";
        if (normalized.contains("第四周")) return "第四周";
        if (normalized.contains("第五周")) return "第五周";
        return null;
    }

    private List<String> splitSegments(String content) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : normalizeText(content).split("\\n+")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                flushSegment(current, segments);
                continue;
            }
            if (line.length() > MAX_SEGMENT_LENGTH) {
                flushSegment(current, segments);
                segments.add(line);
                continue;
            }
            if (current.length() + line.length() + 1 > MAX_SEGMENT_LENGTH) {
                flushSegment(current, segments);
            }
            if (!current.isEmpty()) {
                current.append(" | ");
            }
            current.append(line);
        }
        flushSegment(current, segments);
        return segments;
    }

    private void flushSegment(StringBuilder current, List<String> segments) {
        if (current.isEmpty()) {
            return;
        }
        segments.add(current.toString().trim());
        current.setLength(0);
    }

    private String extractContent(String fileName, byte[] fileData) throws Exception {
        String extension = extractExtension(fileName);
        return switch (extension) {
            case "pdf" -> parsePdf(fileData);
            case "docx" -> parseDocx(fileData);
            case "doc" -> "暂不支持解析旧版 Word(.doc)，请另存为 .docx 后重试。";
            case "txt", "md", "csv", "log", "json", "xml", "yaml", "yml" -> parsePlainText(fileData);
            default -> "不支持解析该文件格式（" + extension + "），目前支持 TXT、PDF、Word(.docx) 等格式。";
        };
    }

    private String buildPreviewText(String content) {
        if (content == null || content.isBlank()) {
            return "文件内容为空。";
        }
        if (content.length() <= MAX_PREVIEW_CHARS) {
            return content;
        }
        return content.substring(0, MAX_PREVIEW_CHARS) + "\n\n...（预览已截断）";
    }

    private String parsePdf(byte[] fileData) throws Exception {
        try (PDDocument document = PDDocument.load(fileData)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return "PDF 文件无页面内容。";
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pageCount, 50));
            String text = normalizeText(stripper.getText(document)).trim();
            if (text.isBlank()) {
                return "PDF 文件中未提取到可识别文本，可能是扫描版或图片 PDF。";
            }
            if (pageCount > 50) {
                return text + "\n\n（PDF 共 " + pageCount + " 页，当前仅解析前 50 页）";
            }
            return text;
        }
    }

    private String parseDocx(byte[] fileData) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileData))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                switch (element.getElementType()) {
                    case PARAGRAPH -> appendParagraph(sb, (XWPFParagraph) element);
                    case TABLE -> appendTable(sb, (XWPFTable) element);
                    default -> {
                    }
                }
            }
            String content = normalizeText(sb.toString()).trim();
            return content.isBlank() ? "Word 文档中未提取到文本内容。" : content;
        }
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph paragraph) {
        appendLine(sb, paragraph == null ? null : paragraph.getText());
    }

    private void appendTable(StringBuilder sb, XWPFTable table) {
        if (table == null) {
            return;
        }
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder rowText = new StringBuilder();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (!rowText.isEmpty()) {
                    rowText.append('\t');
                }
                rowText.append(text.trim());
            }
            appendLine(sb, rowText.toString());
        }
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(text.trim());
    }

    private String parsePlainText(byte[] fileData) {
        Charset charset = detectCharset(fileData);
        int length = Math.min(fileData.length, MAX_TEXT_FILE_BYTES);
        String text = new String(fileData, 0, length, charset);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = normalizeText(text).trim();
        if (text.isBlank()) {
            return "文本文件内容为空。";
        }
        if (fileData.length > MAX_TEXT_FILE_BYTES) {
            text += "\n\n（文件较大，当前仅读取前 " + MAX_TEXT_FILE_BYTES + " 字节）";
        }
        return text;
    }

    private Charset detectCharset(byte[] data) {
        return isValidUtf8(data) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
    }

    private boolean isValidUtf8(byte[] data) {
        int limit = Math.min(data.length, 4096);
        int i = 0;
        while (i < limit) {
            int value = data[i] & 0xFF;
            int bytesNeeded;
            if (value <= 0x7F) {
                bytesNeeded = 0;
            } else if (value >= 0xC2 && value <= 0xDF) {
                bytesNeeded = 1;
            } else if (value >= 0xE0 && value <= 0xEF) {
                bytesNeeded = 2;
            } else if (value >= 0xF0 && value <= 0xF4) {
                bytesNeeded = 3;
            } else {
                return false;
            }
            i++;
            for (int j = 0; j < bytesNeeded; j++) {
                if (i >= limit) {
                    return true;
                }
                value = data[i] & 0xFF;
                if (value < 0x80 || value > 0xBF) {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    private static String detectByExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        return switch (extractExtension(fileName)) {
            case "pdf" -> "PDF 文件";
            case "doc", "docx" -> "Word 文件";
            case "xls", "xlsx" -> "Excel 表格";
            case "ppt", "pptx" -> "PPT 演示文稿";
            case "txt", "md", "csv", "log", "json", "xml", "yaml", "yml" -> "文本文件";
            case "zip" -> "ZIP 压缩包";
            case "rar" -> "RAR 压缩包";
            case "7z" -> "7Z 压缩包";
            case "jpg", "jpeg" -> "JPEG 图片";
            case "png" -> "PNG 图片";
            case "gif" -> "GIF 图片";
            case "webp" -> "WEBP 图片";
            case "mp3" -> "MP3 音频";
            case "wav" -> "WAV 音频";
            case "m4a" -> "M4A 音频";
            case "ogg" -> "OGG 音频";
            case "mp4" -> "MP4 视频";
            case "mov" -> "MOV 视频";
            case "avi" -> "AVI 视频";
            default -> null;
        };
    }

    private static String detectBySignature(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 4) {
            return null;
        }
        if (startsWith(fileBytes, new byte[]{0x25, 0x50, 0x44, 0x46})) return "PDF 文件";
        if (startsWith(fileBytes, new byte[]{0x50, 0x4B, 0x03, 0x04})
                || startsWith(fileBytes, new byte[]{0x50, 0x4B, 0x05, 0x06})
                || startsWith(fileBytes, new byte[]{0x50, 0x4B, 0x07, 0x08})) return "ZIP 压缩包";
        if (startsWith(fileBytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})) return "PNG 图片";
        if (startsWith(fileBytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return "JPEG 图片";
        if (startsWithAscii(fileBytes, "GIF8")) return "GIF 图片";
        if (startsWithAscii(fileBytes, "RIFF") && containsAsciiAt(fileBytes, 8, "WAVE")) return "WAV 音频";
        if (startsWithAscii(fileBytes, "ID3")
                || (fileBytes.length > 1 && (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xE0) == 0xE0)) return "MP3 音频";
        if (startsWithAscii(fileBytes, "OggS")) return "OGG 音频";
        if (containsAsciiAt(fileBytes, 4, "ftyp")) return "MP4/MOV 视频";
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || prefix == null || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, String prefix) {
        if (bytes == null || prefix == null || bytes.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((char) bytes[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAsciiAt(byte[] bytes, int offset, String text) {
        if (bytes == null || text == null || offset < 0 || bytes.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((char) bytes[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String buildFileId(String fileName, byte[] fileData) {
        return (fileName == null ? "unknown" : fileName.trim().toLowerCase(Locale.ROOT))
                + "_" + fileData.length + "_" + Arrays.hashCode(fileData);
    }

    private void evictOldestEntry() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CachedFileContent> entry : contentCache.entrySet()) {
            if (entry.getValue().createdAt() < oldestTime) {
                oldestTime = entry.getValue().createdAt();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            contentCache.remove(oldestKey);
        }
    }

    private String normalizeSearch(String text) {
        String raw = normalizeText(text).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.isIdeographic(ch)) {
                sb.append(ch);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static final class ScheduleItemBuilder {
        private String date = "";
        private String weekday = "";
        private String time = "";
        private String title = "";
        private String speaker = "";
        private String location = "";

        private boolean hasStructuredContent() {
            return !time.isBlank() || !title.isBlank() || !speaker.isBlank();
        }

        private ScheduleItem build() {
            return new ScheduleItem(
                    date == null ? "" : date,
                    weekday == null ? "" : weekday,
                    time == null ? "" : time,
                    title == null ? "" : title,
                    speaker == null ? "" : speaker,
                    location == null ? "" : location
            );
        }
    }

    private record CachedFileContent(String fileId, String fileName, String content, long createdAt) {
    }

    private record ScoredSegment(int index, String segment, int score) {
    }

    private record TitleCapture(String title, int endIndex) {
    }

    private record ScheduleItem(String date, String weekday, String time, String title, String speaker,
                                String location) {
    }
}
