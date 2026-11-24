package io.github.izakyl.jpi.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional API key lookup from provider-specific environment variables. */
public final class EnvApiKeyResolver implements ApiKeyResolver {

    private static final Map<String, List<String>> VARIABLES = Map.ofEntries(
            Map.entry("openai", List.of("OPENAI_API_KEY")),
            Map.entry("anthropic", List.of("ANTHROPIC_API_KEY")),
            Map.entry("google", List.of("GEMINI_API_KEY", "GOOGLE_API_KEY")),
            Map.entry("deepseek", List.of("DEEPSEEK_API_KEY")),
            Map.entry("openrouter", List.of("OPENROUTER_API_KEY")),
            Map.entry("xai", List.of("XAI_API_KEY")),
            Map.entry("groq", List.of("GROQ_API_KEY")),
            Map.entry("mistral", List.of("MISTRAL_API_KEY")),
            Map.entry("moonshotai", List.of("MOONSHOT_API_KEY")),
            Map.entry("moonshotai-cn", List.of("MOONSHOT_API_KEY")),
            Map.entry("zai", List.of("ZAI_API_KEY")),
            Map.entry("minimax", List.of("MINIMAX_API_KEY")),
            Map.entry("minimax-cn", List.of("MINIMAX_CN_API_KEY")),
            Map.entry("qwen-token-plan", List.of("QWEN_TOKEN_PLAN_API_KEY")),
            Map.entry("qwen-token-plan-cn", List.of("QWEN_TOKEN_PLAN_CN_API_KEY")),
            Map.entry("qwen-token-plan-individual", List.of("QWEN_TOKEN_PLAN_API_KEY")),
            Map.entry("xiaomi", List.of("XIAOMI_API_KEY")),
            Map.entry("xiaomi-token-plan-cn", List.of("XIAOMI_TOKEN_PLAN_CN_API_KEY")),
            Map.entry("xiaomi-token-plan-ams", List.of("XIAOMI_TOKEN_PLAN_AMS_API_KEY")),
            Map.entry("xiaomi-token-plan-sgp", List.of("XIAOMI_TOKEN_PLAN_SGP_API_KEY")),
            Map.entry("together", List.of("TOGETHER_API_KEY")),
            Map.entry("fireworks", List.of("FIREWORKS_API_KEY")),
            Map.entry("cerebras", List.of("CEREBRAS_API_KEY")),
            Map.entry("huggingface", List.of("HF_TOKEN")));

    private final Map<String, String> environment;

    public EnvApiKeyResolver() {
        this(System.getenv());
    }

    public EnvApiKeyResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment));
    }

    @Override
    public String resolve(Model model) {
        for (String name : VARIABLES.getOrDefault(model.provider(), List.of())) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
