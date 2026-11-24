package io.github.izakyl.jpi.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Construct models with {@link #builder(String, String)} or a provider factory. */
public record Model(String id, String name, String api, String provider, String baseUrl, boolean reasoning,
        int contextWindow, int maxTokens, Cost cost, Map<String, String> headers, OpenAiCompat compat,
        List<String> input) {

    public Model {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(api);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(baseUrl);
        if (contextWindow < 0 || maxTokens <= 0) {
            throw new IllegalArgumentException("Invalid model token limits");
        }
        headers = Map.copyOf(headers);
        input = List.copyOf(input);
        compat = compat == null ? OpenAiCompat.defaults() : compat;
    }

    public Model(String id, String name, String api, String provider, String baseUrl, boolean reasoning,
            int contextWindow, int maxTokens, Cost cost, Map<String, String> headers, OpenAiCompat compat) {
        this(id, name, api, provider, baseUrl, reasoning, contextWindow, maxTokens, cost, headers, compat,
                List.of("text"));
    }

    @Override
    public String toString() {
        return "Model[id=" + id + ", api=" + api + ", provider=" + provider + "]";
    }

    public record Cost(double input, double output, double cacheRead, double cacheWrite) {
    }

    public static Model deepseek(String id) {
        return builder(id, "https://api.deepseek.com").provider("deepseek")
                .reasoning(true).compat(OpenAiCompat.deepseek()).build();
    }

    public static Builder builder(String id, String baseUrl) {
        return new Builder(id, baseUrl);
    }

    public static final class Builder {

        private final String id;
        private final String baseUrl;
        private String name;
        private String api = "openai-completions";
        private String provider = "openai";
        private boolean reasoning;
        private int contextWindow = 128000;
        private int maxTokens = 4096;
        private Cost cost;
        private Map<String, String> headers = Map.of();
        private OpenAiCompat compat;
        private List<String> input = List.of("text");

        private Builder(String id, String baseUrl) {
            this.id = id;
            this.name = id;
            this.baseUrl = baseUrl;
        }

        public Builder name(String value) {
            name = value;
            return this;
        }

        public Builder api(String value) {
            api = value;
            return this;
        }

        public Builder provider(String value) {
            provider = value;
            return this;
        }

        public Builder reasoning(boolean value) {
            reasoning = value;
            return this;
        }

        public Builder contextWindow(int value) {
            contextWindow = value;
            return this;
        }

        public Builder maxTokens(int value) {
            maxTokens = value;
            return this;
        }

        public Builder cost(Cost value) {
            cost = value;
            return this;
        }

        public Builder headers(Map<String, String> value) {
            headers = value;
            return this;
        }

        public Builder input(List<String> value) {
            input = List.copyOf(value);
            return this;
        }

        public Builder compat(OpenAiCompat value) {
            compat = value;
            return this;
        }

        public Model build() {
            return new Model(
                    id, name, api, provider, baseUrl, reasoning, contextWindow, maxTokens, cost, headers,
                    compat == null ? OpenAiCompat.detect(provider, baseUrl, id) : compat, input);
        }
    }
}
