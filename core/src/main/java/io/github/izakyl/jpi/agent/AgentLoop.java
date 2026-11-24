package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.AssistantMessageEvent;
import io.github.izakyl.jpi.ai.ContentBlock;
import io.github.izakyl.jpi.ai.Context;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.SchemaValidator;
import io.github.izakyl.jpi.ai.StopReason;
import io.github.izakyl.jpi.ai.ToolResultMessage;
import io.github.izakyl.jpi.ai.internal.Workers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Sequential execution strategy; tools and model streams run on library workers. */
public final class AgentLoop {

    private AgentLoop() {
    }

    public static AgentEventStream agentLoop(List<? extends Message> prompts, AgentContext context,
            AgentLoopConfig config) {
        return start(List.copyOf(prompts), context, config);
    }

    public static AgentEventStream agentLoopContinue(AgentContext context, AgentLoopConfig config) {
        if (context.messages().isEmpty()) {
            throw new IllegalArgumentException("Cannot continue: no messages in context");
        }
        if (context.messages().get(context.messages().size() - 1) instanceof AssistantMessage) {
            throw new IllegalArgumentException("Cannot continue from an assistant message");
        }
        return start(List.of(), context, config);
    }

    private static AgentEventStream start(List<Message> prompts, AgentContext context, AgentLoopConfig config) {
        var stream = new AgentEventStream();
        Workers.executor().execute(() -> run(prompts, context, config, stream));
        return stream;
    }

    private static void run(List<Message> prompts, AgentContext context, AgentLoopConfig config,
            AgentEventStream stream) {
        var messages = new ArrayList<>(context.messages());
        var added = new ArrayList<Message>();
        var pending = new ArrayList<>(prompts);
        stream.publish(new AgentEvent.AgentStart());
        try {
            pending.addAll(config.steeringMessages().get());
            for (int turn = 0; ; turn++) {
                stream.publish(new AgentEvent.TurnStart());
                for (var message : pending) {
                    append(message, messages, added, stream);
                }
                pending.clear();
                config.options().cancellation().throwIfCancelled();
                if (turn == config.maxTurns()) {
                    throw new IllegalStateException("Agent turn limit reached (" + config.maxTurns() + ")");
                }
                var response = response(context, messages, config, stream);
                messages.add(response);
                added.add(response);
                var results = executeTools(response, context, config, stream, messages, added);
                stream.publish(new AgentEvent.TurnEnd(response, results));
                if (response.stopReason().isFailure()) {
                    break;
                }
                pending.addAll(config.steeringMessages().get());
                if (!response.toolCalls().isEmpty()
                        || !pending.isEmpty()
                        || config.options().cancellation().isCancelled()) {
                    continue;
                }
                pending.addAll(config.followUpMessages().get());
                if (pending.isEmpty()) {
                    break;
                }
            }
        } catch (Exception error) {
            var message =
                    AssistantMessage.failure(
                            config.model(),
                            config.options().cancellation().isCancelled() ? StopReason.ABORTED : StopReason.ERROR,
                            config.options().cancellation().isCancelled() ? "Request was aborted"
                                    : Objects.toString(error.getMessage(), "Agent operation failed"));
            append(message, messages, added, stream);
            stream.publish(new AgentEvent.TurnEnd(message, List.of()));
        }
        stream.complete(added);
    }

    private static List<ToolResultMessage> executeTools(AssistantMessage response, AgentContext context,
            AgentLoopConfig config, AgentEventStream stream, List<Message> messages, List<Message> added) {
        var results = new ArrayList<ToolResultMessage>();
        if (!response.stopReason().isFailure()) {
            for (var call : response.toolCalls()) {
                var result = execute(call, context, config, stream, response.stopReason() == StopReason.LENGTH);
                results.add(result);
                append(result, messages, added, stream);
            }
        }
        return results;
    }

    private static AssistantMessage response(AgentContext context, List<Message> messages, AgentLoopConfig config,
            AgentEventStream events) {
        var cancellation = config.options().cancellation();
        cancellation.throwIfCancelled();
        var transformed = config.transformContext().apply(List.copyOf(messages));
        cancellation.throwIfCancelled();
        var llmContext = new Context(context.systemPrompt(), transformed,
                context.tools().stream().map(AgentTool::declaration).toList());
        var forwarding = new ResponseForwarding(events);
        var aborted = new CompletableFuture<Void>();
        try (var cancel = cancellation.onCancel(() -> aborted.complete(null))) {
            var stream = Objects.requireNonNull(config.provider().stream(config.model(), llmContext, config.options()));
            try (var subscription = stream.subscribe(forwarding::accept)) {
                var completed = stream.result().thenCombine(forwarding.delivered, (message, ignored) -> message);
                CompletableFuture.anyOf(completed, aborted).join();
                return completed.isDone() ? completed.join() : forwarding.abort(config);
            }
        }
    }

    private static final class ResponseForwarding {
        private final AgentEventStream events;
        private final CompletableFuture<Void> delivered = new CompletableFuture<>();
        private boolean started;
        private AssistantMessage terminal;

        private ResponseForwarding(AgentEventStream events) {
            this.events = events;
        }

        private synchronized void accept(AssistantMessageEvent event) {
            if (delivered.isDone()) {
                return;
            }
            start(event.message());
            if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                terminal = event.message();
                events.publish(new AgentEvent.MessageEnd(terminal));
                delivered.complete(null);
            } else if (!(event instanceof AssistantMessageEvent.Start)) {
                events.publish(new AgentEvent.MessageUpdate(event.message(), event));
            }
        }

        private synchronized AssistantMessage abort(AgentLoopConfig config) {
            if (delivered.isDone()) {
                return terminal;
            }
            var message = AssistantMessage.failure(config.model(), StopReason.ABORTED, "Request was aborted");
            start(message);
            events.publish(new AgentEvent.MessageEnd(message));
            delivered.complete(null);
            return message;
        }

        private void start(AssistantMessage message) {
            if (!started) {
                events.publish(new AgentEvent.MessageStart(message));
                started = true;
            }
        }
    }

    private static ToolResultMessage execute(ContentBlock.ToolCall call, AgentContext context, AgentLoopConfig config,
            AgentEventStream events, boolean truncated) {
        events.publish(new AgentEvent.ToolExecutionStart(call));
        AgentToolResult result;
        var updates = new ToolUpdates(call, events);
        try {
            result = executeTool(call, context, config, updates, truncated);
        } catch (Exception error) {
            result = AgentToolResult.error(Objects.toString(error.getMessage(), "Tool execution failed"));
        } finally {
            updates.close();
        }
        events.publish(new AgentEvent.ToolExecutionEnd(call, result));
        return ToolResultMessage.builder(call.id(), call.name())
                .content(result.content()).details(result.details()).isError(result.isError()).build();
    }

    private static AgentToolResult executeTool(ContentBlock.ToolCall call, AgentContext context,
            AgentLoopConfig config, ToolUpdates updates, boolean truncated) throws Exception {
        config.options().cancellation().throwIfCancelled();
        if (truncated) {
            throw new IllegalArgumentException("Tool call was truncated by the output token limit; please retry");
        }
        var tool =
                context.tools().stream()
                        .filter(candidate -> candidate.name().equals(call.name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Tool \"" + call.name() + "\" not found"));
        var arguments = SchemaValidator.validateToolArguments(tool.declaration(), call);
        var result =
                Objects.requireNonNull(
                        tool.execute(call.id(), arguments, config.options().cancellation(), updates),
                        "Tool returned null");
        config.options().cancellation().throwIfCancelled();
        return result;
    }

    private static final class ToolUpdates implements Consumer<AgentToolResult> {

        private final ContentBlock.ToolCall call;
        private final AgentEventStream events;
        private final Object lock = new Object();
        private boolean active = true;

        private ToolUpdates(ContentBlock.ToolCall call, AgentEventStream events) {
            this.call = call;
            this.events = events;
        }

        @Override
        public void accept(AgentToolResult update) {
            synchronized (lock) {
                if (active) {
                    events.publish(new AgentEvent.ToolExecutionUpdate(call, update));
                }
            }
        }

        private void close() {
            synchronized (lock) {
                active = false;
            }
        }
    }

    private static void append(Message message, List<Message> messages, List<Message> added, AgentEventStream events) {
        messages.add(message);
        added.add(message);
        events.publish(new AgentEvent.MessageStart(message));
        events.publish(new AgentEvent.MessageEnd(message));
    }
}
