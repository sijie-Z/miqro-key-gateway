package com.miqroera.miqrokey.gateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure user-message-text extraction over the three wire protocols the proxy
 * serves (ADR-0014 P1: USER_TEXT_ONLY — only user-role text parts are ever
 * collected; system prompts, tool payloads and model replies are ignored by
 * design). Mirrors the shape knowledge of {@code CacheKeyFactory} without
 * touching the forwarded bytes.
 */
public final class RetentionTextExtractor {

    public enum Protocol {
        ANTHROPIC_MESSAGES, OPENAI_CHAT, OPENAI_RESPONSES
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @return concatenated user text (turns joined by {@code \n---\n}), or empty
     *         string when the body carries no user text.
     */
    public String extract(Protocol protocol, byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> anthropicUserText(root);
                case OPENAI_CHAT -> openaiChatUserText(root);
                case OPENAI_RESPONSES -> openaiResponsesUserText(root);
            };
        } catch (Exception e) {
            // Never fail the hot path over extraction; unparseable bodies are
            // skipped (same posture as every metadata read).
            return "";
        }
    }

    private String anthropicUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                JsonNode content = message.path("content");
                collectTextParts(content, texts);
            }
        }
        return join(texts);
    }

    private String openaiChatUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                collectTextParts(message.path("content"), texts);
            }
        }
        return join(texts);
    }

    private String openaiResponsesUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode input = root.path("input");
        if (input.isArray()) {
            for (JsonNode item : input) {
                if (!"user".equals(item.path("role").asText())) {
                    continue;
                }
                collectTextParts(item.path("content"), texts);
            }
        }
        // Responses also accepts trailing plain strings as user input.
        for (JsonNode item : input) {
            if (item.isTextual() && !item.asText().isBlank()) {
                texts.add(item.asText());
            }
        }
        return join(texts);
    }

    private void collectTextParts(JsonNode content, List<String> texts) {
        if (content == null || content.isMissingNode()) {
            return;
        }
        if (content.isTextual()) {
            if (!content.asText().isBlank()) {
                texts.add(content.asText());
            }
            return;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    if (!part.asText().isBlank()) {
                        texts.add(part.asText());
                    }
                    continue;
                }
                String type = part.path("type").asText("");
                if ("text".equals(type) || "input_text".equals(type)) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                }
            }
        }
    }

    private String join(List<String> texts) {
        if (texts.isEmpty()) {
            return "";
        }
        return String.join("\n---\n", texts);
    }
}
