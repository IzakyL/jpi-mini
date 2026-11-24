package io.github.izakyl.jpi.auth;

import java.util.Objects;

/** Provider credentials. String representations deliberately omit all credential values. */
public sealed interface Credential {

    String apiKey();

    record ApiKey(String apiKey) implements Credential {

        public ApiKey {
            Objects.requireNonNull(apiKey);
        }

        @Override
        public String toString() {
            return "ApiKey[redacted]";
        }
    }
}
