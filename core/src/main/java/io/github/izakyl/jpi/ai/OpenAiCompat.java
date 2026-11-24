package io.github.izakyl.jpi.ai;

import com.google.gson.JsonObject;
import java.util.List;

/** OpenAI Chat Completions compatibility settings. */
public record OpenAiCompat(boolean supportsStore, boolean supportsDeveloperRole, boolean supportsReasoningEffort,
        boolean supportsUsageInStreaming, boolean supportsFinishReason, String maxTokensField,
        boolean requiresToolResultName, boolean requiresAssistantAfterToolResult, boolean requiresThinkingAsText,
        boolean requiresReasoningContent, List<String> reasoningFields,
        boolean requiresReasoningContentForToolCalls, boolean requiresReasoningContentForAllAssistantTurns,
        boolean requiresAssistantContentForToolCalls, boolean supportsToolChoiceAuto,
        boolean stripDeepseekSpecialTokens, JsonObject extraBody) {

    public OpenAiCompat {
        if (!"max_tokens".equals(maxTokensField) && !"max_completion_tokens".equals(maxTokensField)) {
            throw new IllegalArgumentException("Invalid token limit field: " + maxTokensField);
        }
        reasoningFields = List.copyOf(reasoningFields);
        extraBody = extraBody.deepCopy();
    }

    public OpenAiCompat(boolean supportsStore, boolean supportsDeveloperRole, boolean supportsReasoningEffort,
            boolean supportsUsageInStreaming, boolean supportsFinishReason, String maxTokensField,
            boolean requiresToolResultName, boolean requiresAssistantAfterToolResult, boolean requiresThinkingAsText,
            boolean requiresReasoningContent, List<String> reasoningFields) {
        this(supportsStore, supportsDeveloperRole, supportsReasoningEffort, supportsUsageInStreaming,
                supportsFinishReason, maxTokensField, requiresToolResultName, requiresAssistantAfterToolResult,
                requiresThinkingAsText, requiresReasoningContent, reasoningFields,
                false, false, false, true, false, new JsonObject());
    }

    @Override
    public JsonObject extraBody() {
        return extraBody.deepCopy();
    }

    public static OpenAiCompat deepseek() {
        var thinking = new JsonObject();
        thinking.addProperty("type", "enabled");
        var body = new JsonObject();
        body.add("thinking", thinking);
        return builder().maxTokensField("max_tokens").supportsDeveloperRole(false).supportsStore(false)
                .supportsReasoningEffort(false).supportsToolChoiceAuto(false)
                .requiresReasoningContentForToolCalls(true).requiresReasoningContentForAllAssistantTurns(true)
                .requiresAssistantContentForToolCalls(true).stripDeepseekSpecialTokens(true).extraBody(body).build();
    }

    public static OpenAiCompat detect(String provider, String baseUrl, String modelId) {
        String url = baseUrl.toLowerCase(java.util.Locale.ROOT);
        boolean deepseek = provider.equals("deepseek") || url.contains("deepseek.com");
        if (deepseek) {
            return deepseek();
        }
        boolean zai = provider.equals("zai") || provider.equals("zai-coding-cn")
                || url.contains("api.z.ai") || url.contains("open.bigmodel.cn");
        boolean together = provider.equals("together") || url.contains("api.together.ai")
                || url.contains("api.together.xyz");
        boolean moonshot = provider.startsWith("moonshotai") || url.contains("api.moonshot.");
        boolean router = provider.equals("openrouter") || url.contains("openrouter.ai");
        boolean nvidia = provider.equals("nvidia") || url.contains("integrate.api.nvidia.com");
        boolean ant = provider.equals("ant-ling") || url.contains("api.ant-ling.com");
        boolean gateway = provider.equals("cloudflare-ai-gateway") || url.contains("gateway.ai.cloudflare.com");
        boolean workers = provider.equals("cloudflare-workers-ai") || url.contains("api.cloudflare.com");
        boolean xai = provider.equals("xai") || url.contains("api.x.ai");
        boolean nonstandard = zai || together || moonshot || nvidia || ant || gateway || workers || xai
                || provider.equals("cerebras") || url.contains("cerebras.ai")
                || provider.equals("opencode") || url.contains("opencode.ai") || url.contains("chutes.ai");
        boolean routerDeveloper = router && (modelId.startsWith("anthropic/") || modelId.startsWith("openai/"));
        return builder().supportsStore(!nonstandard)
                .supportsDeveloperRole(routerDeveloper || !nonstandard && !router)
                .supportsReasoningEffort(!(xai || zai || moonshot || together || gateway || nvidia || ant))
                .maxTokensField(zai || together || moonshot || nvidia || ant || gateway || url.contains("chutes.ai")
                        ? "max_tokens" : "max_completion_tokens")
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OpenAiCompat defaults() {
        return builder().build();
    }

    public static final class Builder {

        private boolean supportsStore = true;
        private boolean supportsDeveloperRole = true;
        private boolean supportsReasoningEffort = true;
        private boolean supportsUsageInStreaming = true;
        private boolean supportsFinishReason = true;
        private boolean requiresToolResultName;
        private boolean requiresAssistantAfterToolResult;
        private boolean requiresThinkingAsText;
        private boolean requiresReasoningContent;
        private boolean requiresReasoningContentForToolCalls;
        private boolean requiresReasoningContentForAllAssistantTurns;
        private boolean requiresAssistantContentForToolCalls;
        private boolean supportsToolChoiceAuto = true;
        private boolean stripDeepseekSpecialTokens;
        private JsonObject extraBody = new JsonObject();
        private String maxTokensField = "max_completion_tokens";
        private List<String> reasoningFields = List.of("reasoning_content", "reasoning", "reasoning_text");

        public Builder supportsStore(boolean value) {
            supportsStore = value;
            return this;
        }

        public Builder supportsDeveloperRole(boolean value) {
            supportsDeveloperRole = value;
            return this;
        }

        public Builder supportsReasoningEffort(boolean value) {
            supportsReasoningEffort = value;
            return this;
        }

        public Builder supportsUsageInStreaming(boolean value) {
            supportsUsageInStreaming = value;
            return this;
        }

        public Builder supportsFinishReason(boolean value) {
            supportsFinishReason = value;
            return this;
        }

        public Builder maxTokensField(String value) {
            maxTokensField = value;
            return this;
        }

        public Builder requiresToolResultName(boolean value) {
            requiresToolResultName = value;
            return this;
        }

        public Builder requiresAssistantAfterToolResult(boolean value) {
            requiresAssistantAfterToolResult = value;
            return this;
        }

        public Builder requiresThinkingAsText(boolean value) {
            requiresThinkingAsText = value;
            return this;
        }

        public Builder requiresReasoningContent(boolean value) {
            requiresReasoningContent = value;
            return this;
        }

        public Builder reasoningFields(List<String> value) {
            reasoningFields = value;
            return this;
        }

        public Builder requiresReasoningContentForToolCalls(boolean value) {
            requiresReasoningContentForToolCalls = value;
            return this;
        }

        public Builder requiresReasoningContentForAllAssistantTurns(boolean value) {
            requiresReasoningContentForAllAssistantTurns = value;
            return this;
        }

        public Builder requiresAssistantContentForToolCalls(boolean value) {
            requiresAssistantContentForToolCalls = value;
            return this;
        }

        public Builder supportsToolChoiceAuto(boolean value) {
            supportsToolChoiceAuto = value;
            return this;
        }

        public Builder stripDeepseekSpecialTokens(boolean value) {
            stripDeepseekSpecialTokens = value;
            return this;
        }

        public Builder extraBody(JsonObject value) {
            extraBody = value;
            return this;
        }

        public OpenAiCompat build() {
            return new OpenAiCompat(
                    supportsStore,
                    supportsDeveloperRole,
                    supportsReasoningEffort,
                    supportsUsageInStreaming,
                    supportsFinishReason,
                    maxTokensField,
                    requiresToolResultName,
                    requiresAssistantAfterToolResult,
                    requiresThinkingAsText,
                    requiresReasoningContent,
                    reasoningFields,
                    requiresReasoningContentForToolCalls,
                    requiresReasoningContentForAllAssistantTurns,
                    requiresAssistantContentForToolCalls,
                    supportsToolChoiceAuto,
                    stripDeepseekSpecialTokens,
                    extraBody);
        }
    }
}
