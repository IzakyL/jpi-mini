package io.github.izakyl.jpi.ai;

import io.github.izakyl.jpi.auth.CredentialResolver;
import java.util.Map;

/** Construct options with {@link #builder()} or {@link #defaults()}. */
public record StreamOptions(String apiKey, ApiKeyResolver apiKeyResolver, CancellationToken cancellation,
        Double temperature, Integer maxTokens, String reasoningEffort, Map<String, String> headers,
        String toolChoice, CredentialResolver credentialResolver, int maxRetries, long maxRetryDelayMs,
        long requestTimeoutMs, long idleTimeoutMs) {

    public StreamOptions {
        cancellation = cancellation == null ? new CancellationToken() : cancellation;
        headers = Map.copyOf(headers);
        if (maxRetries < 0 || maxRetryDelayMs < 0 || requestTimeoutMs < 0 || idleTimeoutMs < 0) {
            throw new IllegalArgumentException("Retry and timeout values must be nonnegative");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public StreamOptions(String apiKey, ApiKeyResolver apiKeyResolver, CancellationToken cancellation,
            Double temperature, Integer maxTokens, String reasoningEffort, Map<String, String> headers,
            String toolChoice, CredentialResolver credentialResolver) {
        this(apiKey, apiKeyResolver, cancellation, temperature, maxTokens, reasoningEffort, headers, toolChoice,
                credentialResolver, 0, 60000, 0, 0);
    }

    public StreamOptions(String apiKey, ApiKeyResolver apiKeyResolver, CancellationToken cancellation,
            Double temperature, Integer maxTokens, String reasoningEffort, Map<String, String> headers) {
        this(apiKey, apiKeyResolver, cancellation, temperature, maxTokens, reasoningEffort, headers, null, null);
    }

    public StreamOptions(String apiKey, ApiKeyResolver apiKeyResolver, CancellationToken cancellation,
            Double temperature, Integer maxTokens, String reasoningEffort, Map<String, String> headers,
            String toolChoice) {
        this(apiKey, apiKeyResolver, cancellation, temperature, maxTokens, reasoningEffort, headers, toolChoice, null);
    }

    public StreamOptions(String apiKey) {
        this(apiKey, null, null, null, null, null, Map.of());
    }

    @Override
    public String toString() {
        return "StreamOptions[maxRetries=" + maxRetries + ", requestTimeoutMs=" + requestTimeoutMs
                + ", idleTimeoutMs=" + idleTimeoutMs + "]";
    }

    public static StreamOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public StreamOptions withCancellation(CancellationToken token) {
        return new StreamOptions(
                apiKey, apiKeyResolver, token, temperature, maxTokens, reasoningEffort, headers, toolChoice,
                credentialResolver, maxRetries, maxRetryDelayMs, requestTimeoutMs, idleTimeoutMs);
    }

    public static final class Builder {

        private int maxRetries;
        private long maxRetryDelayMs = 60000;
        private long requestTimeoutMs;
        private long idleTimeoutMs;
        private String apiKey;
        private String toolChoice;
        private String reasoningEffort;
        private ApiKeyResolver apiKeyResolver;
        private CredentialResolver credentialResolver;
        private CancellationToken cancellation;
        private Double temperature;
        private Integer maxTokens;
        private Map<String, String> headers = Map.of();

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder apiKeyResolver(ApiKeyResolver value) {
            apiKeyResolver = value;
            return this;
        }

        public Builder credentialResolver(CredentialResolver value) {
            credentialResolver = value;
            return this;
        }

        public Builder cancellation(CancellationToken value) {
            cancellation = value;
            return this;
        }

        public Builder temperature(double value) {
            temperature = value;
            return this;
        }

        public Builder maxTokens(int value) {
            maxTokens = value;
            return this;
        }

        public Builder reasoningEffort(String value) {
            reasoningEffort = value;
            return this;
        }

        /** "auto", "none", "required", or the name of a function to force. */
        public Builder toolChoice(String value) {
            toolChoice = value;
            return this;
        }

        public Builder headers(Map<String, String> value) {
            headers = value;
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        public Builder maxRetryDelayMs(long value) {
            maxRetryDelayMs = value;
            return this;
        }

        public Builder requestTimeoutMs(long value) {
            requestTimeoutMs = value;
            return this;
        }

        public Builder idleTimeoutMs(long value) {
            idleTimeoutMs = value;
            return this;
        }

        public StreamOptions build() {
            return new StreamOptions(
                    apiKey, apiKeyResolver, cancellation, temperature, maxTokens, reasoningEffort, headers, toolChoice,
                    credentialResolver, maxRetries, maxRetryDelayMs, requestTimeoutMs, idleTimeoutMs);
        }
    }
}
