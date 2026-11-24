package io.github.izakyl.jpi.auth;

import io.github.izakyl.jpi.ai.ApiKeyResolver;
import io.github.izakyl.jpi.ai.Model;
import java.io.IOException;
import java.util.Objects;

/** Reads the model's provider credential for each request. */
public final class StoreApiKeyResolver implements ApiKeyResolver {

    private final CredentialStore store;

    public StoreApiKeyResolver(CredentialStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public String resolve(Model model) throws IOException {
        return store.get(model.provider()).map(Credential::apiKey).orElse(null);
    }
}
