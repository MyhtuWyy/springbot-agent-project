package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeEmbeddingService {
    private static final int DIMENSIONS = 128;

    public double[] embed(String text) {
        double[] vector = new double[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        for (String token : tokenize(text)) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), DIMENSIONS);
            vector[index] += 1.0d;
        }

        normalize(vector);
        return vector;
    }

    public double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public String serialize(double[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    public double[] deserialize(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new double[DIMENSIONS];
        }
        String cleaned = json.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        String[] parts = cleaned.split(",");
        double[] vector = new double[Math.max(DIMENSIONS, parts.length)];
        for (int i = 0; i < parts.length && i < vector.length; i++) {
            try {
                vector[i] = Double.parseDouble(parts[i].trim());
            } catch (Exception ignored) {
                vector[i] = 0.0d;
            }
        }
        return vector;
    }

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        StringBuilder cjkBuffer = new StringBuilder();
        StringBuilder asciiBuffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                asciiBuffer.append(Character.toLowerCase(ch));
                continue;
            }

            flushAscii(tokens, asciiBuffer);
            if (isCjk(ch)) {
                cjkBuffer.append(ch);
            } else {
                flushCjk(tokens, cjkBuffer);
            }
        }
        flushAscii(tokens, asciiBuffer);
        flushCjk(tokens, cjkBuffer);
        return tokens;
    }

    private void normalize(double[] vector) {
        double sumSq = 0.0d;
        for (double v : vector) {
            sumSq += v * v;
        }
        if (sumSq == 0.0d) {
            return;
        }
        double norm = Math.sqrt(sumSq);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    private void flushAscii(List<String> tokens, StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        tokens.add(buffer.toString());
        buffer.setLength(0);
    }

    private void flushCjk(List<String> tokens, StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        String text = buffer.toString();
        for (int i = 0; i < text.length(); i++) {
            tokens.add(String.valueOf(text.charAt(i)));
        }
        if (text.length() >= 2) {
            for (int i = 0; i < text.length() - 1; i++) {
                tokens.add(text.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }
}
