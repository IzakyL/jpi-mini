package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.internal.ListenerStream;
import java.util.List;

/**
 * result() contains messages produced by this run, including prompts, rather than the prior
 * transcript.
 */
public final class AgentEventStream extends ListenerStream<AgentEvent, List<Message>> {

    AgentEventStream() {
    }

    void publish(AgentEvent event) {
        emitAndWait(event).join();
    }

    void complete(List<Message> messages) {
        var snapshot = List.copyOf(messages);
        finish(new AgentEvent.AgentEnd(snapshot), snapshot);
    }
}
