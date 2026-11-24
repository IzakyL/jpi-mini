package io.github.izakyl.jpi.ai;

import java.util.regex.Pattern;

public final class ContextOverflow {

    private static final Pattern EXCLUDED = Pattern.compile(
            "^(throttling error|service unavailable):|rate limit|too many requests", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERFLOW = Pattern.compile(
            "prompt (?:is )?too long|prompt exceeds max length|request_too_large"
                    + "|input is too long for requested model|exceeds the context window"
                    + "|exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))"
                    + "|input token count.*exceeds the maximum|maximum prompt length is \\d+"
                    + "|reduce the length of the messages|maximum context length is \\d+ tokens"
                    + "|exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?"
                    + "|input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)"
                    + "|exceeds the limit of \\d+|exceeds the available context size|greater than the context length"
                    + "|context window exceeds limit|exceeded model token limit"
                    + "|too large for model with \\d+ maximum context length"
                    + "|prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?"
                    + "|model_context_window_exceeded|prompt too long; exceeded (?:max )?context length"
                    + "|range of input length should be|context[_ ]length[_ ]exceeded|too many tokens"
                    + "|token limit exceeded|^(?:[\\w -]+ API error \\(413\\):"
                    + "|(?:\\w+ )?HTTP 413(?:\\s|:|$)|413(?:\\s|:|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CEREBRAS = Pattern.compile("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)",
            Pattern.CASE_INSENSITIVE);

    private ContextOverflow() {
    }

    public static boolean isContextOverflow(AssistantMessage message, int contextWindow) {
        String error = message.errorMessage();
        if (message.stopReason() == StopReason.ERROR && error != null && !EXCLUDED.matcher(error).find()) {
            if (matchesError(error)
                    || (message.provider().equals("cerebras") && CEREBRAS.matcher(error).find())) {
                return true;
            }
        }
        if (contextWindow <= 0) {
            return false;
        }
        long input = message.usage().input() + message.usage().cacheRead();
        return (message.stopReason() == StopReason.STOP && input > contextWindow)
                || (message.stopReason() == StopReason.LENGTH && message.usage().output() == 0
                        && input >= contextWindow * 0.99);
    }

    static boolean matchesError(String error) {
        return !EXCLUDED.matcher(error).find() && OVERFLOW.matcher(error).find();
    }

    public static boolean isContextOverflow(AssistantMessage message) {
        return isContextOverflow(message, 0);
    }
}
