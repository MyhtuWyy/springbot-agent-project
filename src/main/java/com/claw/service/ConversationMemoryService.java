package com.claw.service;

import com.claw.entity.ChatMessageEntity;
import com.claw.entity.ChatSessionEntity;
import com.alibaba.fastjson2.JSONObject;
import com.claw.repository.ChatMessageRepository;
import com.claw.repository.ChatSessionRepository;
import com.claw.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ConversationMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    public record ConversationMessage(String role, String content) {}

    public enum EmotionPhase {
        LISTENING,
        ABC_STARTED,
        A_DONE,
        B_DONE,
        C_DONE,
        D_DONE,
        E_COMPLETED
    }

    private static final class StoredMessage {
        private final String role;
        private final String content;
        private final String messageType;

        private StoredMessage(String role, String content, String messageType) {
            this.role = role;
            this.content = content;
            this.messageType = messageType;
        }
    }

    private static final class SessionMemory {
        private final Deque<StoredMessage> messages = new ArrayDeque<>();
        private volatile long lastAccessAt = System.currentTimeMillis();
        private String currentMode = "normal";
        private EmotionPhase emotionPhase = EmotionPhase.LISTENING;
        private String eventA;
        private String beliefB;
        private String emotionC;
        private String disputeD;
        private String newBeliefE;
        private String travelContext;
    }

    public record ResumeSnapshot(boolean found, boolean hasMessages, boolean hasEmotionState, int loadedMessageCount) {
    }

    private final Map<String, SessionMemory> sessionMemories = new ConcurrentHashMap<>();
    private final int maxMessages = ConfigUtil.getConversationMaxMessages();
    private final long expirationMillis = TimeUnit.MINUTES.toMillis(ConfigUtil.getConversationExpireMinutes());
    private final int resumeWindowMinutes = ConfigUtil.getConversationResumeWindowMinutes();

    private final ChatMessageRepository messageRepo;
    private final ChatSessionRepository sessionRepo;

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conversation-memory-cleanup");
        t.setDaemon(true);
        return t;
    });

    public ConversationMemoryService(ChatMessageRepository messageRepo, ChatSessionRepository sessionRepo) {
        this.messageRepo = messageRepo;
        this.sessionRepo = sessionRepo;
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    public List<ConversationMessage> getMessages(String sessionId) {
        return getMessagesInternal(sessionId, false);
    }

    public List<ConversationMessage> getMessagesForceLoad(String sessionId) {
        return getMessagesInternal(sessionId, true);
    }

    public ResumeSnapshot forceResumeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ResumeSnapshot(false, false, false, 0);
        }

        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            reloadSessionState(sessionId, memory);
            List<StoredMessage> loaded = loadStoredMessages(sessionId, true);
            memory.messages.clear();
            memory.messages.addAll(loaded);
            memory.lastAccessAt = System.currentTimeMillis();
            boolean hasMessages = !loaded.isEmpty();
            boolean hasEmotionState = hasEmotionDataInMemory(memory) || "emotion".equalsIgnoreCase(memory.currentMode);
            boolean found = hasMessages || hasEmotionState || sessionRepo.existsById(sessionId);
            return new ResumeSnapshot(found, hasMessages, hasEmotionState, loaded.size());
        }
    }

    @Transactional
    public void addUserMessage(String sessionId, String content) {
        addMessage(sessionId, "user", content, "normal");
    }

    @Transactional
    public void addAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, "assistant", content, "normal");
    }

    @Transactional
    public void addEmotionUserMessage(String sessionId, String content) {
        addMessage(sessionId, "user", content, "emotion");
        setCurrentMode(sessionId, "emotion");
    }

    @Transactional
    public void addEmotionAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, "assistant", content, "emotion");
        setCurrentMode(sessionId, "emotion");
    }

    @Transactional
    public void setCurrentMode(String sessionId, String mode) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.currentMode = normalizeMode(mode);
            persistSession(sessionId, memory, false);
        }
    }

    public String getCurrentMode(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                return memory.currentMode;
            }
        }
        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            return session == null || session.getCurrentMode() == null ? "normal" : session.getCurrentMode();
        } catch (Exception e) {
            log.warn("read current mode failed, sessionId={}", sessionId, e);
            return "normal";
        }
    }

    public EmotionPhase peekEmotionPhase(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                return memory.emotionPhase;
            }
        }
        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            return session == null ? null : parseEmotionPhase(session.getEmotionPhase());
        } catch (Exception e) {
            log.warn("read emotion phase failed, sessionId={}", sessionId, e);
            return null;
        }
    }

    public EmotionPhase getEmotionPhase(String sessionId) {
        EmotionPhase phase = peekEmotionPhase(sessionId);
        return phase == null ? EmotionPhase.LISTENING : phase;
    }

    @Transactional
    public void updateEmotionPhase(String sessionId, EmotionPhase phase) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.emotionPhase = phase == null ? EmotionPhase.LISTENING : phase;
            if (memory.emotionPhase != EmotionPhase.LISTENING) {
                memory.currentMode = "emotion";
            }
            persistSession(sessionId, memory, false);
        }
    }

    @Transactional
    public void updateEmotionAbc(String sessionId, String step, String value) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            switch (step == null ? "" : step.toUpperCase()) {
                case "A" -> {
                    memory.eventA = value;
                    memory.emotionPhase = EmotionPhase.A_DONE;
                }
                case "B" -> {
                    memory.beliefB = value;
                    memory.emotionPhase = EmotionPhase.B_DONE;
                }
                case "C" -> {
                    memory.emotionC = value;
                    memory.emotionPhase = EmotionPhase.C_DONE;
                }
                case "D" -> {
                    memory.disputeD = value;
                    memory.emotionPhase = EmotionPhase.D_DONE;
                }
                case "E" -> {
                    memory.newBeliefE = value;
                    memory.emotionPhase = EmotionPhase.E_COMPLETED;
                }
                default -> {
                    log.warn("unknown ABC step: {}", step);
                    return;
                }
            }
            memory.currentMode = "emotion";
            persistSession(sessionId, memory, false);
        }
    }

    public Map<String, String> getEmotionAbcData(String sessionId) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            Map<String, String> result = new HashMap<>();
            result.put("eventA", safeValue(memory.eventA));
            result.put("beliefB", safeValue(memory.beliefB));
            result.put("emotionC", safeValue(memory.emotionC));
            result.put("disputeD", safeValue(memory.disputeD));
            result.put("newBeliefE", safeValue(memory.newBeliefE));
            return result;
        }
    }

    @Transactional
    public void resetEmotionState(String sessionId) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.currentMode = "normal";
            memory.emotionPhase = EmotionPhase.LISTENING;
            memory.eventA = null;
            memory.beliefB = null;
            memory.emotionC = null;
            memory.disputeD = null;
            memory.newBeliefE = null;
            memory.travelContext = null;
            persistSession(sessionId, memory, false);
        }
        log.info("emotion state reset, sessionId={}", sessionId);
    }

    public JSONObject getTravelContext(String sessionId) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            return parseJson(memory.travelContext);
        }
    }

    public void saveTravelContext(String sessionId, JSONObject travelContext) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.travelContext = travelContext == null || travelContext.isEmpty() ? null : travelContext.toJSONString();
            persistSession(sessionId, memory, false);
        }
    }

    public void clearTravelContext(String sessionId) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.travelContext = null;
            persistSession(sessionId, memory, false);
        }
    }

    public void addEmotionConfession(String sessionId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        addEmotionUserMessage(sessionId, text.trim());
    }

    public boolean hasRecoverableHistory(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                if (!memory.messages.isEmpty()) {
                    return true;
                }
            }
        }
        return !loadStoredMessages(sessionId, false).isEmpty();
    }

    public boolean hasAnySessionData(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                if (!memory.messages.isEmpty() || hasEmotionDataInMemory(memory)) {
                    return true;
                }
            }
        }

        try {
            return sessionRepo.existsById(sessionId);
        } catch (Exception e) {
            log.warn("check session data failed, sessionId={}", sessionId, e);
            return false;
        }
    }

    public boolean hasRecoverableEmotionState(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                return memory.currentMode.equals("emotion")
                        || memory.emotionPhase != EmotionPhase.LISTENING
                        || hasEmotionDataInMemory(memory);
            }
        }

        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            if (session == null) {
                return false;
            }
            return "emotion".equalsIgnoreCase(session.getCurrentMode())
                    || parseEmotionPhase(session.getEmotionPhase()) != EmotionPhase.LISTENING
                    || hasEmotionDataInSession(session);
        } catch (Exception e) {
            log.warn("read emotion recoverable state failed, sessionId={}", sessionId, e);
            return false;
        }
    }

    public String buildEmotionResumeSummary(String sessionId) {
        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            String latest = findLatestEmotionMessage(memory);
            StringBuilder sb = new StringBuilder("Resumed emotion session. ");
            sb.append("Current phase: ").append(describeEmotionPhase(memory.emotionPhase)).append(". ");
            if (latest != null && !latest.isBlank()) {
                sb.append("Last emotion message: ").append(truncate(latest, 40)).append(". ");
            }
            sb.append("You can continue or resume ABC analysis.");
            return sb.toString();
        }
    }

    @Transactional
    public void clearSession(String sessionId) {
        sessionMemories.remove(sessionId);
        try {
            messageRepo.deleteBySessionId(sessionId);
            sessionRepo.deleteById(sessionId);
            log.info("session cleared, sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("clear session data failed, sessionId={}", sessionId, e);
        }
    }

    public int getMessageCount(String sessionId) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            synchronized (memory) {
                memory.lastAccessAt = System.currentTimeMillis();
                return memory.messages.size();
            }
        }

        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            return session == null ? 0 : session.getMessageCount();
        } catch (Exception e) {
            log.warn("read message count failed, sessionId={}", sessionId, e);
            return 0;
        }
    }

    public boolean hasSession(String sessionId) {
        return sessionMemories.containsKey(sessionId);
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
    }

    private List<ConversationMessage> getMessagesInternal(String sessionId, boolean ignoreTimeWindow) {
        SessionMemory memory = sessionMemories.get(sessionId);
        if (memory != null && !memory.messages.isEmpty()) {
            synchronized (memory) {
                memory.lastAccessAt = System.currentTimeMillis();
                return toConversationMessages(new ArrayList<>(memory.messages));
            }
        }

        List<StoredMessage> loaded = loadStoredMessages(sessionId, ignoreTimeWindow);
        if (!loaded.isEmpty()) {
            SessionMemory newMemory = getOrCreateSession(sessionId);
            synchronized (newMemory) {
                newMemory.messages.clear();
                newMemory.messages.addAll(loaded);
                newMemory.lastAccessAt = System.currentTimeMillis();
            }
            log.info("loaded history from DB, sessionId={}, count={}", sessionId, loaded.size());
        }
        return toConversationMessages(loaded);
    }

    private void addMessage(String sessionId, String role, String content, String messageType) {
        if (content == null || content.isBlank()) {
            return;
        }

        SessionMemory memory = getOrCreateSession(sessionId);
        synchronized (memory) {
            memory.lastAccessAt = System.currentTimeMillis();
            String normalizedType = normalizeMessageType(messageType);
            memory.messages.addLast(new StoredMessage(role, content.trim(), normalizedType));
            while (memory.messages.size() > maxMessages) {
                memory.messages.removeFirst();
            }
            persistMessageAndSession(sessionId, memory, role, content.trim(), normalizedType);
        }
    }

    private SessionMemory getOrCreateSession(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, this::loadSession);
    }

    private SessionMemory loadSession(String sessionId) {
        SessionMemory memory = new SessionMemory();
        reloadSessionState(sessionId, memory);
        return memory;
    }

    private void reloadSessionState(String sessionId, SessionMemory memory) {
        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            if (session != null) {
                memory.currentMode = normalizeMode(session.getCurrentMode());
                memory.emotionPhase = parseEmotionPhase(session.getEmotionPhase());
                memory.eventA = session.getEventA();
                memory.beliefB = session.getBeliefB();
                memory.emotionC = session.getEmotionC();
                memory.disputeD = session.getDisputeD();
                memory.newBeliefE = session.getNewBeliefE();
                memory.travelContext = session.getTravelContext();
            }
        } catch (Exception e) {
            log.warn("load session failed, sessionId={}", sessionId, e);
        }
    }

    private void persistMessageAndSession(String sessionId, SessionMemory memory, String role, String content, String messageType) {
        try {
            messageRepo.save(new ChatMessageEntity(sessionId, extractUserId(sessionId), role, content, messageType));
            persistSession(sessionId, memory, true);
        } catch (Exception e) {
            log.warn("save message failed, sessionId={}, role={}", sessionId, role, e);
        }
    }

    private void persistSession(String sessionId, SessionMemory memory, boolean incrementCount) {
        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElseGet(() -> new ChatSessionEntity(sessionId));
            session.setUserId(extractUserId(sessionId));
            session.setChannelType(extractChannelType(sessionId));
            session.setChannelUserId(extractChannelUserId(sessionId));
            session.setLastActive(LocalDateTime.now());
            session.setCurrentMode(memory.currentMode);
            session.setEmotionPhase(memory.emotionPhase.name());
            session.setEventA(memory.eventA);
            session.setBeliefB(memory.beliefB);
            session.setEmotionC(memory.emotionC);
            session.setDisputeD(memory.disputeD);
            session.setNewBeliefE(memory.newBeliefE);
            session.setTravelContext(memory.travelContext);
            if (incrementCount) {
                session.setMessageCount(session.getMessageCount() + 1);
            }
            sessionRepo.save(session);
        } catch (Exception e) {
            log.warn("save session failed, sessionId={}", sessionId, e);
        }
    }

    private List<StoredMessage> loadStoredMessages(String sessionId, boolean ignoreTimeWindow) {
        try {
            ChatSessionEntity session = sessionRepo.findById(sessionId).orElse(null);
            if (session == null) {
                return Collections.emptyList();
            }

            if (!ignoreTimeWindow) {
                long minutesSinceActive = ChronoUnit.MINUTES.between(session.getLastActive(), LocalDateTime.now());
                if (minutesSinceActive > resumeWindowMinutes) {
                    log.info("session {} expired by resume window, minutesSinceActive={}, window={}",
                            sessionId, minutesSinceActive, resumeWindowMinutes);
                    return Collections.emptyList();
                }
            }

            List<ChatMessageEntity> dbMessages = messageRepo.findTop20BySessionIdOrderByCreatedAtDesc(sessionId);
            if (dbMessages.isEmpty()) {
                return Collections.emptyList();
            }

            List<StoredMessage> result = new ArrayList<>(dbMessages.size());
            for (int i = dbMessages.size() - 1; i >= 0; i--) {
                ChatMessageEntity m = dbMessages.get(i);
                result.add(new StoredMessage(m.getRole(), m.getContent(), normalizeMessageType(m.getMessageType())));
            }
            return result;
        } catch (Exception e) {
            log.warn("load message history failed, sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessionMemories.entrySet().removeIf(entry -> now - entry.getValue().lastAccessAt > expirationMillis);
    }

    private List<ConversationMessage> toConversationMessages(List<StoredMessage> messages) {
        List<ConversationMessage> result = new ArrayList<>(messages.size());
        for (StoredMessage message : messages) {
            result.add(new ConversationMessage(message.role, message.content));
        }
        return result;
    }

    private String findLatestEmotionMessage(SessionMemory memory) {
        for (Iterator<StoredMessage> it = memory.messages.descendingIterator(); it.hasNext(); ) {
            StoredMessage message = it.next();
            if ("emotion".equalsIgnoreCase(message.messageType) && "user".equalsIgnoreCase(message.role)) {
                return message.content;
            }
        }
        return null;
    }

    private boolean hasEmotionDataInMemory(SessionMemory memory) {
        return memory.emotionPhase != EmotionPhase.LISTENING
                || hasText(memory.eventA)
                || hasText(memory.beliefB)
                || hasText(memory.emotionC)
                || hasText(memory.disputeD)
                || hasText(memory.newBeliefE);
    }

    private boolean hasEmotionDataInSession(ChatSessionEntity session) {
        return parseEmotionPhase(session.getEmotionPhase()) != EmotionPhase.LISTENING
                || hasText(session.getEventA())
                || hasText(session.getBeliefB())
                || hasText(session.getEmotionC())
                || hasText(session.getDisputeD())
                || hasText(session.getNewBeliefE());
    }

    private String describeEmotionPhase(EmotionPhase phase) {
        return switch (phase) {
            case LISTENING -> "LISTENING";
            case ABC_STARTED -> "ABC_STARTED";
            case A_DONE -> "A_DONE";
            case B_DONE -> "B_DONE";
            case C_DONE -> "C_DONE";
            case D_DONE -> "D_DONE";
            case E_COMPLETED -> "E_COMPLETED";
        };
    }

    private EmotionPhase parseEmotionPhase(String phaseStr) {
        if (phaseStr == null || phaseStr.isBlank()) {
            return EmotionPhase.LISTENING;
        }
        try {
            return EmotionPhase.valueOf(phaseStr);
        } catch (IllegalArgumentException e) {
            return EmotionPhase.LISTENING;
        }
    }

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "normal" : mode.trim().toLowerCase();
    }

    private String normalizeMessageType(String messageType) {
        return messageType == null || messageType.isBlank() ? "normal" : messageType.trim().toLowerCase();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String safeValue(String text) {
        return text == null ? "" : text;
    }

    private JSONObject parseJson(String value) {
        if (value == null || value.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject json = JSONObject.parseObject(value);
            return json == null ? new JSONObject() : json;
        } catch (Exception e) {
            log.warn("parse travel context failed", e);
            return new JSONObject();
        }
    }

    private Long extractUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        int firstColon = sessionId.indexOf(':');
        if (firstColon < 0 || firstColon == sessionId.length() - 1) {
            return null;
        }
        int secondColon = sessionId.indexOf(':', firstColon + 1);
        String userPart = secondColon > firstColon
                ? sessionId.substring(firstColon + 1, secondColon)
                : sessionId.substring(firstColon + 1);
        try {
            return Long.parseLong(userPart.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractChannelType(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        int firstColon = sessionId.indexOf(':');
        if (firstColon <= 0) {
            return null;
        }
        return sessionId.substring(0, firstColon);
    }

    private String extractChannelUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        int secondColon = sessionId.indexOf(':', sessionId.indexOf(':') + 1);
        if (secondColon < 0 || secondColon == sessionId.length() - 1) {
            return null;
        }
        return sessionId.substring(secondColon + 1);
    }
}
