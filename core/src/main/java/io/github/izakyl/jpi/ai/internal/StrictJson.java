package io.github.izakyl.jpi.ai.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** Shared by provider and credential parsing. This class is an unsupported implementation detail. */
public final class StrictJson {

    private StrictJson() {
    }

    public static JsonElement parse(String text) {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            var result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonSyntaxException("Trailing JSON data");
            }
            return result;
        } catch (IOException | NumberFormatException error) {
            throw new JsonSyntaxException(error);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 256) {
            throw new JsonSyntaxException("JSON nesting exceeds 256");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    value.add(reader.nextName(), read(reader, depth + 1));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                var value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    value.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield value;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new JsonSyntaxException("Expected JSON value");
        };
    }
}
