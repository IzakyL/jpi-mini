package io.github.izakyl.jpi.ai;

import com.google.gson.JsonObject;
import java.util.Objects;

public record Tool(String name, String description, JsonObject parameters) {

    public Tool {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        parameters = Objects.requireNonNull(parameters).deepCopy();
        SchemaValidator.requireSupported(parameters);
    }

    @Override
    public JsonObject parameters() {
        return parameters.deepCopy();
    }
}
