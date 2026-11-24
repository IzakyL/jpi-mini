package io.github.izakyl.jpi.ai;

/** Removes leaked special tokens while retaining incomplete markers across SSE chunks. */
final class DeepSeekText {

    private final StringBuilder pending = new StringBuilder();

    private boolean dsml;

    public String append(String fragment) {
        pending.append(fragment);
        var result = new StringBuilder();
        while (!pending.isEmpty()) {
            if (pending.charAt(0) == '<') {
                String value = pending.toString();
                if (value.equals("<") || value.equals("</")) {
                    break;
                }
                if (value.startsWith("<｜") || value.startsWith("<|")
                        || value.startsWith("</｜") || value.startsWith("</|")) {
                    int end = pending.indexOf(">");
                    if (end < 0) {
                        break;
                    }
                    String tag = pending.substring(0, end + 1);
                    if (tag.contains("DSML") && tag.contains("function_calls")) {
                        dsml = !tag.startsWith("</");
                    }
                    pending.delete(0, end + 1);
                    continue;
                }
            }
            if (!dsml) {
                result.append(pending.charAt(0));
            }
            pending.deleteCharAt(0);
        }
        return result.toString();
    }

    public String finish() {
        String tail = pending.toString();
        pending.setLength(0);
        return dsml || tail.startsWith("<｜") || tail.startsWith("<|") ? "" : tail;
    }
}
