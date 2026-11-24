package io.github.izakyl.jpi.ai;

import java.util.List;
import java.util.Optional;

public final class Models {

    private Models() {
    }

    public static List<Model> of(String provider) {
        return Providers.all().stream().filter(value -> value.id().equals(provider))
                .findFirst().map(Provider::models).orElse(List.of());
    }

    public static Optional<Model> get(String provider, String id) {
        return of(provider).stream().filter(model -> model.id().equals(id)).findFirst();
    }
}
