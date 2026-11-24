package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.AiProvider;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.ProviderRegistry;
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.ai.Subscription;
import io.github.izakyl.jpi.ai.UserMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Stateful agent. Listener callbacks are serial worker callbacks and must not block on the current
 * run.
 */
public final class Agent {

    private final AiProvider provider;
    private final StreamOptions options;
    private final UnaryOperator<List<Message>> transformContext;
    private final CopyOnWriteArrayList<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Message> steering = new ArrayDeque<>(), followUps = new ArrayDeque<>();
    private final List<Message> messages = new ArrayList<>();

    private String systemPrompt;
    private Model model;
    private List<AgentTool> tools;
    private CancellationToken cancellation;
    private AssistantMessage streamingMessage;
    private boolean streaming;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

    public Agent(Model model, String systemPrompt, List<AgentTool> tools, StreamOptions options) {
        this(model, systemPrompt, tools, options, ProviderRegistry.defaults());
    }

    public Agent(Model model, String systemPrompt, List<AgentTool> tools, StreamOptions options, AiProvider provider) {
        this(model, systemPrompt, tools, options, provider, null);
    }

    /** The context hook receives a snapshot and its return value is used only for the model request. */
    public Agent(Model model, String systemPrompt, List<AgentTool> tools, StreamOptions options, AiProvider provider,
            UnaryOperator<List<Message>> transformContext) {
        this.model = Objects.requireNonNull(model);
        this.systemPrompt = systemPrompt;
        this.tools = new AgentContext(systemPrompt, List.of(), tools).tools();
        this.options = options == null ? StreamOptions.defaults() : options;
        this.provider = Objects.requireNonNull(provider);
        this.transformContext = transformContext;
    }

    public Subscription subscribe(Consumer<AgentEvent> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<List<Message>> prompt(String text) {
        return prompt(new UserMessage(text));
    }

    public synchronized CompletableFuture<List<Message>> prompt(UserMessage message) {
        return start(List.of(message), false);
    }

    public synchronized CompletableFuture<List<Message>> continueRun() {
        return start(List.of(), true);
    }

    public synchronized void abort() {
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    public synchronized CompletableFuture<Void> waitForIdle() {
        return idle.copy();
    }

    public synchronized boolean isStreaming() {
        return streaming;
    }

    public synchronized AgentState state() {
        return new AgentState(systemPrompt, model, tools, messages, streaming, streamingMessage);
    }

    public synchronized void setModel(Model value) {
        requireIdle();
        model = Objects.requireNonNull(value);
    }

    public synchronized void setSystemPrompt(String value) {
        requireIdle();
        systemPrompt = value;
    }

    public synchronized void setTools(List<AgentTool> value) {
        requireIdle();
        tools = new AgentContext(systemPrompt, messages, value).tools();
    }

    public synchronized void replaceMessages(List<Message> value) {
        requireIdle();
        var snapshot = List.copyOf(value);
        messages.clear();
        messages.addAll(snapshot);
    }

    public synchronized void steer(UserMessage message) {
        steering.add(Objects.requireNonNull(message));
    }

    public synchronized void followUp(UserMessage message) {
        followUps.add(Objects.requireNonNull(message));
    }

    public synchronized void clearQueues() {
        steering.clear();
        followUps.clear();
    }

    private CompletableFuture<List<Message>> start(List<Message> prompts, boolean continuing) {
        requireIdle();
        var context = new AgentContext(systemPrompt, messages, tools);
        if (continuing && (messages.isEmpty() || messages.get(messages.size() - 1) instanceof AssistantMessage)) {
            throw new IllegalArgumentException("Continuation requires a final user or tool result message");
        }
        streaming = true;
        cancellation = new CancellationToken();
        var linkedCancellation = options.cancellation().onCancel(cancellation::cancel);
        idle = new CompletableFuture<>();
        var config =
                new AgentLoopConfig(
                        model,
                        provider,
                        options.withCancellation(cancellation),
                        () -> drain(steering),
                        () -> drain(followUps),
                        transformContext);
        var stream =
                continuing
                        ? AgentLoop.agentLoopContinue(context, config)
                        : AgentLoop.agentLoop(prompts, context, config);
        var delivered = new CompletableFuture<Void>();
        var subscription = stream.subscribe(event -> receive(event, delivered));
        var settled = new CompletableFuture<List<Message>>();
        var currentIdle = idle;
        stream.result()
                .thenCombine(delivered, (result, ignored) -> result)
                .whenComplete(
                        (result, error) ->
                                settle(result, error, subscription, linkedCancellation, currentIdle, settled));
        return settled;
    }

    private void receive(AgentEvent event, CompletableFuture<Void> delivered) {
        updateState(event);
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
            }
        }
        if (event instanceof AgentEvent.AgentEnd) {
            delivered.complete(null);
        }
    }

    private synchronized void updateState(AgentEvent event) {
        if (event instanceof AgentEvent.MessageStart start) {
            messages.add(start.message());
            if (start.message() instanceof AssistantMessage assistant) {
                streamingMessage = assistant;
            }
        } else if (event instanceof AgentEvent.MessageUpdate update) {
            messages.set(messages.size() - 1, update.message());
            streamingMessage = update.message();
        } else if (event instanceof AgentEvent.MessageEnd end) {
            messages.set(messages.size() - 1, end.message());
            streamingMessage = null;
        }
    }

    private void settle(List<Message> result, Throwable error, Subscription subscription,
            Subscription linkedCancellation, CompletableFuture<Void> currentIdle,
            CompletableFuture<List<Message>> settled) {
        subscription.close();
        linkedCancellation.close();
        synchronized (this) {
            streaming = false;
            streamingMessage = null;
            cancellation = null;
        }
        currentIdle.complete(null);
        if (error == null) {
            settled.complete(result);
        } else {
            settled.completeExceptionally(error);
        }
    }

    private synchronized List<Message> drain(ArrayDeque<Message> queue) {
        return queue.isEmpty() ? List.of() : List.of(queue.removeFirst());
    }

    private void requireIdle() {
        if (streaming) {
            throw new IllegalStateException("Agent is streaming; use steer() or followUp()");
        }
    }
}
