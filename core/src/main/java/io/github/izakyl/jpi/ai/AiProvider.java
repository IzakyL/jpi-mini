package io.github.izakyl.jpi.ai;

@FunctionalInterface
public interface AiProvider {

    AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
