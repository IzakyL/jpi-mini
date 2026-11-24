package io.github.izakyl.jpi.auth;

import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Model;

/** Resolves current API key credentials for each request. */
@FunctionalInterface
public interface CredentialResolver {

    Credential resolve(Model model, CancellationToken cancellation) throws Exception;
}
