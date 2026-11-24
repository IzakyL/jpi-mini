package io.github.izakyl.jpi.ai;

import io.github.izakyl.jpi.ai.internal.ListenerStream;
import java.util.Objects;

/**
 * Listeners run serially on library workers, including history replay; never block on result()
 * inside a listener.
 */
public final class AssistantMessageEventStream extends ListenerStream<AssistantMessageEvent, AssistantMessage> {

    AssistantMessageEventStream() {
    }

    void publish(AssistantMessageEvent event) {
        emit(event);
    }

    /** Creates a publishing handle for a custom provider. Return only its stream to consumers. */
    public static Producer producer() {
        return new Producer();
    }

    public static final class Producer {
        private final AssistantMessageEventStream stream = new AssistantMessageEventStream();

        private Producer() {
        }

        public AssistantMessageEventStream stream() {
            return stream;
        }

        public void publish(AssistantMessageEvent event) {
            if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                throw new IllegalArgumentException("Use complete to publish the terminal event");
            }
            stream.publish(Objects.requireNonNull(event));
        }

        public void complete(AssistantMessage message) {
            stream.complete(message);
        }
    }

    void complete(AssistantMessage message) {
        finish(
                message.stopReason().isFailure()
                        ? new AssistantMessageEvent.Error(message)
                        : new AssistantMessageEvent.Done(message),
                message);
    }
}
