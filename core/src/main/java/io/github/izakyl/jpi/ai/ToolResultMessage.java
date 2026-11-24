package io.github.izakyl.jpi.ai;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Objects;

/** Construct tool results with {@link #builder(String, String)}. */
public record ToolResultMessage(String toolCallId, String toolName, List<ContentBlock> content, JsonElement details,
        boolean isError, long timestamp) implements Message {

    public ToolResultMessage {
        Objects.requireNonNull(toolCallId);
        Objects.requireNonNull(toolName);
        content = List.copyOf(content);
        details = details == null ? null : details.deepCopy();
        if (content.stream().anyMatch(b -> !(b instanceof ContentBlock.Text || b instanceof ContentBlock.Image))) {
            throw new IllegalArgumentException("Tool results support text and images only");
        }
    }

    @Override
    public JsonElement details() {
        return details == null ? null : details.deepCopy();
    }

    public static Builder builder(String toolCallId, String toolName) {
        return new Builder(toolCallId, toolName);
    }

    public static final class Builder {

        private final String toolCallId;
        private final String toolName;
        private List<ContentBlock> content = List.of();
        private JsonElement details;
        private boolean isError;
        private long timestamp = System.currentTimeMillis();

        private Builder(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }

        public Builder content(List<? extends ContentBlock> value) {
            content = List.copyOf(value);
            return this;
        }

        public Builder details(JsonElement value) {
            details = value == null ? null : value.deepCopy();
            return this;
        }

        public Builder isError(boolean value) {
            isError = value;
            return this;
        }

        public Builder timestamp(long value) {
            timestamp = value;
            return this;
        }

        public ToolResultMessage build() {
            return new ToolResultMessage(toolCallId, toolName, content, details, isError, timestamp);
        }
    }
}
