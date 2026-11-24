package io.github.izakyl.jpi.ai;

public sealed interface AssistantMessageEvent {

    String type();

    AssistantMessage message();

    enum Kind {

        TEXT("text"),
        THINKING("thinking"),
        TOOLCALL("toolcall");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    record Start(AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return "start";
        }
    }

    record BlockStart(Kind kind, int contentIndex, AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return kind.prefix + "_start";
        }
    }

    record Delta(Kind kind, int contentIndex, String delta, AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return kind.prefix + "_delta";
        }
    }

    record BlockEnd(Kind kind, int contentIndex, AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return kind.prefix + "_end";
        }

        public ContentBlock content() {
            return message.content().get(contentIndex);
        }
    }

    record Done(AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return "done";
        }
    }

    record Error(AssistantMessage message) implements AssistantMessageEvent {

        @Override
        public String type() {
            return "error";
        }
    }
}
