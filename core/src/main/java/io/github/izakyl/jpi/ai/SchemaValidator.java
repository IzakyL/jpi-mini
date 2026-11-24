package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JSON Schema subset: type (including unions), properties, required, enum, items,
 * additionalProperties.
 */
public final class SchemaValidator {

    private static final Set<String> TYPES =
            Set.of("object", "array", "null", "string", "boolean", "number", "integer");

    private SchemaValidator() {
    }

    /** Returns an immutable list of path-qualified schema or value problems, empty for a valid value. */
    public static List<String> validate(JsonObject schema, JsonElement value) {
        var errors = new ArrayList<String>();
        checkSchema(schema, "$", errors);
        if (errors.isEmpty()) {
            check(schema, value == null ? JsonNull.INSTANCE : value, "$", errors);
        }
        return List.copyOf(errors);
    }

    /**
     * Like pi, validates a copy after primitive coercion and removal of non-nullable optional
     * nulls.
     */
    public static JsonObject validateToolArguments(Tool tool, ContentBlock.ToolCall call) {
        if (call.argumentsError() != null) {
            throw new IllegalArgumentException(call.argumentsError());
        }
        var schema = tool.parameters();
        var arguments = normalize(schema, call.arguments()).getAsJsonObject();
        var errors = new ArrayList<String>();
        check(schema, arguments, "$", errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid arguments for tool \"" + tool.name() + "\": " + String.join("; ", errors));
        }
        return arguments;
    }

    static void requireSupported(JsonObject schema) {
        var errors = new ArrayList<String>();
        checkSchema(schema, "$", errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid tool schema: " + String.join("; ", errors));
        }
    }

    private static void checkSchema(JsonElement schema, String path, List<String> errors) {
        if (schema == null || !schema.isJsonObject()) {
            errors.add(path + ": expected a schema object");
            return;
        }
        for (var entry : schema.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            var value = entry.getValue();
            String at = path + "." + key;
            switch (key) {
                case "title", "description" -> {
                    if (!isString(value)) {
                        errors.add(at + ": expected a string");
                    }
                }
                case "type" -> {
                    if (value.isJsonArray() && value.getAsJsonArray().size() > 0) {
                        for (var type : value.getAsJsonArray()) {
                            checkType(type, at, errors);
                        }
                    } else {
                        checkType(value, at, errors);
                    }
                }
                case "required" -> {
                    if (value.isJsonArray()) {
                        for (var name : value.getAsJsonArray()) {
                            if (!isString(name)) {
                                errors.add(at + ": expected property names");
                            }
                        }
                    } else {
                        errors.add(at + ": expected an array of property names");
                    }
                }
                case "enum" -> {
                    if (!value.isJsonArray() || value.getAsJsonArray().size() == 0) {
                        errors.add(at + ": expected a nonempty array");
                    }
                }
                case "properties" -> {
                    if (value.isJsonObject()) {
                        for (var property : value.getAsJsonObject().entrySet()) {
                            checkSchema(property.getValue(), at + "." + property.getKey(), errors);
                        }
                    } else {
                        errors.add(at + ": expected an object");
                    }
                }
                case "items" -> checkSchema(value, at, errors);
                case "additionalProperties" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                        checkSchema(value, at, errors);
                    }
                }
                default -> errors.add(at + ": unsupported schema keyword");
            }
        }
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static void checkType(JsonElement value, String path, List<String> errors) {
        if (!isString(value) || !TYPES.contains(value.getAsString())) {
            errors.add(path + ": expected a supported JSON type");
        }
    }

    private static List<String> types(JsonObject schema) {
        if (!schema.has("type")) {
            return List.of();
        }
        var type = schema.get("type");
        if (type.isJsonPrimitive()) {
            return List.of(type.getAsString());
        }
        var names = new ArrayList<String>();
        for (var name : type.getAsJsonArray()) {
            names.add(name.getAsString());
        }
        return names;
    }

    private static boolean matches(String type, JsonElement value) {
        var primitive = value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "null" -> value.isJsonNull();
            case "string" -> primitive != null && primitive.isString();
            case "boolean" -> primitive != null && primitive.isBoolean();
            case "number" -> primitive != null && primitive.isNumber() && finite(primitive);
            case "integer" ->
                    primitive != null
                            && primitive.isNumber()
                            && finite(primitive)
                            && primitive.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }

    private static boolean finite(JsonPrimitive value) {
        try {
            value.getAsBigDecimal();
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static void check(JsonObject schema, JsonElement value, String path, List<String> errors) {
        var types = types(schema);
        if (!types.isEmpty() && types.stream().noneMatch(type -> matches(type, value))) {
            errors.add(path + ": expected " + String.join(" or ", types));
            return;
        }
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
            errors.add(path + ": not in enum");
        }
        if (value.isJsonObject()) {
            checkObject(schema, value.getAsJsonObject(), path, errors);
        }
        if (value.isJsonArray() && schema.has("items")) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                check(schema.getAsJsonObject("items"), value.getAsJsonArray().get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void checkObject(JsonObject schema, JsonObject object, String path, List<String> errors) {
        if (schema.has("required")) {
            for (var key : schema.getAsJsonArray("required")) {
                if (!object.has(key.getAsString())) {
                    errors.add(path + "." + key.getAsString() + ": required");
                }
            }
        }
        var properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        for (var entry : object.entrySet()) {
            var property = properties.get(entry.getKey());
            if (property != null) {
                check(property.getAsJsonObject(), entry.getValue(), path + "." + entry.getKey(), errors);
            } else if (schema.has("additionalProperties")) {
                var additional = schema.get("additionalProperties");
                if (additional.isJsonObject()) {
                    check(additional.getAsJsonObject(), entry.getValue(), path + "." + entry.getKey(), errors);
                } else if (!additional.getAsBoolean()) {
                    errors.add(path + "." + entry.getKey() + ": additional property");
                }
            }
        }
    }

    private static JsonElement normalize(JsonObject schema, JsonElement value) {
        var types = types(schema);
        var original = value;
        if (types.stream().noneMatch(type -> matches(type, original))) {
            for (var type : types) {
                var candidate = coerce(type, value);
                if (matches(type, candidate)) {
                    value = candidate;
                    break;
                }
            }
        }
        if (value.isJsonObject()) {
            normalizeObject(schema, value.getAsJsonObject());
        } else if (value.isJsonArray() && schema.has("items")) {
            var array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                array.set(i, normalize(schema.getAsJsonObject("items"), array.get(i)));
            }
        }
        return value;
    }

    private static void normalizeObject(JsonObject schema, JsonObject object) {

        var properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        var required = schema.has("required") ? schema.getAsJsonArray("required") : new JsonArray();
        for (var key : new ArrayList<>(object.keySet())) {
            JsonObject propertySchema = properties.has(key) ? properties.getAsJsonObject(key) : null;
            if (propertySchema == null
                    && schema.has("additionalProperties")
                    && schema.get("additionalProperties").isJsonObject()) {
                propertySchema = schema.getAsJsonObject("additionalProperties");
            }
            if (propertySchema == null) {
                continue;
            }
            if (object.get(key).isJsonNull()
                    && properties.has(key)
                    && !required.contains(new JsonPrimitive(key))
                    && !validate(propertySchema, JsonNull.INSTANCE).isEmpty()) {
                object.remove(key);
            } else {
                object.add(key, normalize(propertySchema, object.get(key)));
            }
        }
    }

    private static JsonElement coerce(String type, JsonElement value) {
        if (!value.isJsonNull() && !value.isJsonPrimitive()) {
            return value;
        }
        String text = value.isJsonNull() ? "" : value.getAsString();
        try {
            return switch (type) {
                case "number", "integer" -> {
                    if (value.isJsonNull()) {
                        yield new JsonPrimitive(0);
                    }
                    if (value.getAsJsonPrimitive().isBoolean()) {
                        yield new JsonPrimitive(value.getAsBoolean() ? 1 : 0);
                    }
                    yield text.isBlank() ? value : new JsonPrimitive(new BigDecimal(text.trim()));
                }
                case "string" -> new JsonPrimitive(text);
                case "boolean" -> {
                    if (value.isJsonNull() || text.equals("false") || text.equals("0")) {
                        yield new JsonPrimitive(false);
                    }
                    if (text.equals("true") || text.equals("1")) {
                        yield new JsonPrimitive(true);
                    }
                    yield value;
                }
                case "null" -> text.isEmpty() || text.equals("0") || text.equals("false") ? JsonNull.INSTANCE : value;
                default -> value;
            };
        } catch (NumberFormatException error) {
            return value;
        }
    }
}
