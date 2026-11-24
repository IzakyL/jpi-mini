package io.github.izakyl.jpi.ai;

import java.util.List;

public record Provider(String id, String name, String baseUrl, String api, List<Model> models) {

    public Provider {
        models = List.copyOf(models);
    }
}
