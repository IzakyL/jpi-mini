package io.github.izakyl.jpi.ai;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Construct with {@link #builder(Model)} or {@link #failure(Model, StopReason, String)}.
 * Event partials are snapshots whose stop reason is provisional until a terminal event.
 */
public record AssistantMessage(List<ContentBlock> content, String api, String provider, String model, Usage usage,
        StopReason stopReason, String errorMessage, long timestamp) implements Message {

    public AssistantMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(api);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(model);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(stopReason);
        if (content.stream().anyMatch(b -> b instanceof ContentBlock.Image)) {
            throw new IllegalArgumentException("Assistant messages support text, thinking and tool calls only");
        }
    }

    public String text() {
        return content.stream()
                .filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast)
                .map(ContentBlock.Text::text)
                .collect(Collectors.joining());
    }

    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream()
                .filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast)
                .toList();
    }

    public static Builder builder(Model model) {
        return builder(model.api(), model.provider(), model.id());
    }

    public static Builder builder(String api, String provider, String model) {
        return new Builder(api, provider, model);
    }

    public static AssistantMessage failure(Model model, StopReason reason, String error) {
        return builder(model).stopReason(reason).errorMessage(error).build();
    }

    public static final class Builder {

        private final String api;
        private final String provider;
        private final String model;
        private List<ContentBlock> content = List.of();
        private Usage usage = Usage.ZERO;
        private StopReason stopReason = StopReason.STOP;
        private String errorMessage;
        private long timestamp = System.currentTimeMillis();

        private Builder(String api, String provider, String model) {
            this.api = api;
            this.provider = provider;
            this.model = model;
        }

        public Builder content(List<? extends ContentBlock> value) {
            content = List.copyOf(value);
            return this;
        }

        public Builder usage(Usage value) {
            usage = value;
            return this;
        }

        public Builder stopReason(StopReason value) {
            stopReason = value;
            return this;
        }

        public Builder errorMessage(String value) {
            errorMessage = value;
            return this;
        }

        public Builder timestamp(long value) {
            timestamp = value;
            return this;
        }

        public AssistantMessage build() {
            return new AssistantMessage(content, api, provider, model, usage, stopReason, errorMessage, timestamp);
        }
    }
}
