package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeChunkingService {
    private static final int MAX_CHARS = 700;
    private static final int MIN_CHARS = 180;

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").trim();
        String[] paragraphs = normalized.split("\\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String piece = paragraph.trim();
            if (piece.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() + 2 > MAX_CHARS) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (piece.length() > MAX_CHARS) {
                flushLongParagraph(chunks, piece);
                continue;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(piece);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        if (chunks.isEmpty() && !normalized.isBlank()) {
            chunks.add(normalized);
        }
        return chunks;
    }

    private void flushLongParagraph(List<String> chunks, String paragraph) {
        String[] sentences = paragraph.split("(?<=[。！？；;])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String piece = sentence.trim();
            if (piece.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() > MAX_CHARS) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
    }
}
