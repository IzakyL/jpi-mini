package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.AiProvider;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.StreamOptions;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public record AgentLoopConfig(Model model, AiProvider provider, StreamOptions options,
        Supplier<List<Message>> steeringMessages, Supplier<List<Message>> followUpMessages,
        UnaryOperator<List<Message>> transformContext, int maxTurns) {

    public static final int DEFAULT_MAX_TURNS = 32;

    public AgentLoopConfig {
        Objects.requireNonNull(model);
        Objects.requireNonNull(provider);
        options = options == null ? StreamOptions.defaults() : options;
        steeringMessages = steeringMessages == null ? List::of : steeringMessages;
        followUpMessages = followUpMessages == null ? List::of : followUpMessages;
        transformContext = transformContext == null ? UnaryOperator.identity() : transformContext;
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
    }

    public AgentLoopConfig(Model model, AiProvider provider, StreamOptions options,
            Supplier<List<Message>> steeringMessages, Supplier<List<Message>> followUpMessages,
            UnaryOperator<List<Message>> transformContext) {
        this(model, provider, options, steeringMessages, followUpMessages, transformContext, DEFAULT_MAX_TURNS);
    }

    public AgentLoopConfig(Model model, AiProvider provider, StreamOptions options,
            Supplier<List<Message>> steeringMessages, Supplier<List<Message>> followUpMessages) {
        this(model, provider, options, steeringMessages, followUpMessages, null);
    }

    public AgentLoopConfig(Model model, AiProvider provider, StreamOptions options) {
        this(model, provider, options, null, null);
    }
}
