package io.github.izakyl.jpi.auth;

import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Model;
import java.io.IOException;
import java.util.Objects;

/** Reads the model's provider credential for each request. */
public final class StoreCredentialResolver implements CredentialResolver {

    private final CredentialStore store;

    public StoreCredentialResolver(CredentialStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Credential resolve(Model model, CancellationToken cancellation) throws IOException {
        cancellation.throwIfCancelled();
        return store.get(model.provider()).orElse(null);
    }
}
