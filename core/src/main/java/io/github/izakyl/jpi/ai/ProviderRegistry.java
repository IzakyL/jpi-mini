package io.github.izakyl.jpi.ai;

import io.github.izakyl.jpi.ai.internal.Workers;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ProviderRegistry implements AiProvider {

    private final ConcurrentHashMap<String, AiProvider> providers = new ConcurrentHashMap<>();

    public static ProviderRegistry defaults() {
        return defaults(HttpClient.newHttpClient());
    }

    public static ProviderRegistry defaults(HttpClient client) {
        var registry = new ProviderRegistry();
        registry.register("openai-completions", new OpenAiCompletions(client));
        registry.register(AnthropicMessages.API, new AnthropicMessages(client));
        registry.register(GoogleGenerativeAi.API, new GoogleGenerativeAi(client));
        registry.register(OpenAiResponses.API, new OpenAiResponses(client));
        return registry;
    }

    public void register(String api, AiProvider provider) {
        if (providers.putIfAbsent(Objects.requireNonNull(api), Objects.requireNonNull(provider)) != null) {
            throw new IllegalArgumentException("Provider already registered for API: " + api);
        }
    }

    public void replace(String api, AiProvider provider) {
        if (providers.replace(Objects.requireNonNull(api), Objects.requireNonNull(provider)) == null) {
            throw new IllegalArgumentException("No provider registered for API: " + api);
        }
    }

    public void unregister(String api) {
        providers.remove(api);
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        var actual = options == null ? StreamOptions.defaults() : options;
        try {
            var provider = providers.get(model.api());
            if (provider == null) {
                throw new IllegalArgumentException("No provider registered for API: " + model.api());
            }
            var transformed = new Context(context.systemPrompt(),
                    TransformMessages.apply(model, context.messages()), context.tools());
            return Objects.requireNonNull(provider.stream(model, transformed, actual));
        } catch (RuntimeException error) {
            var stream = new AssistantMessageEventStream();
            Workers.executor().execute(
                    () ->
                            stream.complete(
                                    AssistantMessage.failure(
                                            model,
                                            actual.cancellation().isCancelled() ? StopReason.ABORTED : StopReason.ERROR,
                                            Objects.toString(error.getMessage(), "Provider request failed"))));
            return stream;
        }
    }
}
