package io.github.izakyl.jpi.ai;

final class UnicodeSanitizer {

    private UnicodeSanitizer() {
    }

    static String sanitize(String text) {
        var result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    result.append(value).append(text.charAt(++i));
                }
            } else if (!Character.isLowSurrogate(value)) {
                result.append(value);
            }
        }
        return result.toString();
    }
}
