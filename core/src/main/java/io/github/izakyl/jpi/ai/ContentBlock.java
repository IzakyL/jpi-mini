package io.github.izakyl.jpi.ai;

import com.google.gson.JsonObject;
import java.util.Objects;

public sealed interface ContentBlock {

    record Text(String text, String signature) implements ContentBlock {

        public Text {
            Objects.requireNonNull(text);
        }

        public Text(String text) {
            this(text, null);
        }
    }

    record Thinking(String thinking, String signature) implements ContentBlock {

        public Thinking {
            Objects.requireNonNull(thinking);
        }

        public Thinking(String thinking) {
            this(thinking, null);
        }
    }

    record Image(String data, String mimeType) implements ContentBlock {

        public Image {
            Objects.requireNonNull(data);
            Objects.requireNonNull(mimeType);
        }
    }

    record ToolCall(String id, String name, JsonObject arguments, String argumentsError, String signature)
            implements ContentBlock {

        public ToolCall {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            arguments = Objects.requireNonNull(arguments).deepCopy();
        }

        public ToolCall(String id, String name, JsonObject arguments) {
            this(id, name, arguments, null, null);
        }

        public ToolCall(String id, String name, JsonObject arguments, String argumentsError) {
            this(id, name, arguments, argumentsError, null);
        }

        @Override
        public JsonObject arguments() {
            return arguments.deepCopy();
        }
    }
}
