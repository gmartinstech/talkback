package io.gmartinstech.talkback.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A single chat bubble in the TalkBack reviewer conversation.
 *
 * @param id        the unique message identifier
 * @param role      either {@code "user"} or {@code "assistant"}
 * @param content   the message body (may be partial while streaming)
 * @param streaming {@code true} if the message is still being generated
 */
public record ChatMessage(UUID id, String role, String content, boolean streaming) {
    public ChatMessage {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }

    /**
     * Creates a user message.
     */
    public static ChatMessage user(String text) {
        return new ChatMessage(UUID.randomUUID(), "user", text, false);
    }

    /**
     * Creates an assistant message that is still streaming.
     */
    public static ChatMessage streamingAssistant() {
        return new ChatMessage(UUID.randomUUID(), "assistant", "", true);
    }
}
