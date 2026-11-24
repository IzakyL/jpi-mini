package io.github.izakyl.jpi.ai;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Providers {

    private static final List<Provider> ALL = load();

    private Providers() {
    }

    public static List<Provider> all() {
        return ALL;
    }

    private static List<Provider> load() {
        var resource = Providers.class.getResourceAsStream("models.json");
        if (resource == null) {
            throw new IllegalStateException("Missing model catalog");
        }
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var result = new ArrayList<Provider>();
            for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
                var provider = element.getAsJsonObject();
                String id = provider.get("id").getAsString();
                String api = provider.get("api").getAsString();
                String baseUrl = provider.get("baseUrl").getAsString();
                var models = new ArrayList<Model>();
                for (var item : provider.getAsJsonArray("models")) {
                    var raw = item.getAsJsonObject();
                    var cost = raw.getAsJsonObject("cost");
                    var inputs = new ArrayList<String>();
                    raw.getAsJsonArray("input").forEach(value -> inputs.add(value.getAsString()));
                    Map<String, String> headers = new TreeMap<>();
                    raw.getAsJsonObject("headers").entrySet()
                            .forEach(e -> headers.put(e.getKey(), e.getValue().getAsString()));
                    models.add(Model.builder(raw.get("id").getAsString(), baseUrl).name(raw.get("name").getAsString())
                            .api(api).provider(id).reasoning(raw.get("reasoning").getAsBoolean()).input(inputs)
                            .contextWindow(raw.get("contextWindow").getAsInt())
                            .maxTokens(raw.get("maxTokens").getAsInt())
                            .cost(new Model.Cost(cost.get("input").getAsDouble(), cost.get("output").getAsDouble(),
                                    cost.get("cacheRead").getAsDouble(), cost.get("cacheWrite").getAsDouble()))
                            .headers(headers).build());
                }
                result.add(new Provider(id, provider.get("name").getAsString(), baseUrl, api, models));
            }
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load model catalog", error);
        }
    }
}
