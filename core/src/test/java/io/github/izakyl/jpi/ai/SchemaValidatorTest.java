package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaValidatorTest {

    @Test
    void reportsAndRejectsUnsupportedNestedToolKeywords() {
        var schema = json("{\"properties\":{\"amount\":{\"type\":\"number\",\"minimum\":1}}}").getAsJsonObject();
        String problem = "$.properties.amount.minimum: unsupported schema keyword";
        assertEquals(List.of(problem), SchemaValidator.validate(schema, json("{\"amount\":-5}")));
        var error = assertThrows(IllegalArgumentException.class, () -> new Tool("charge", "Charge", schema));
        assertTrue(error.getMessage().contains(problem));
    }

    @ParameterizedTest
    @CsvSource({
        "string, '\"hello\"', true",
        "string, 1, false",
        "number, 1.5, true",
        "number, '\"1\"', false",
        "integer, 2.0, true",
        "integer, 2.1, false",
        "boolean, true, true",
        "boolean, 1, false",
        "null, null, true",
        "null, false, false",
        "object, {}, true",
        "object, [], false",
        "array, [], true"
    })
    void primitiveTypes(String type, String value, boolean valid) {
        var schema = JsonParser.parseString("{\"type\":\"" + type + "\"}").getAsJsonObject();
        assertEquals(valid, SchemaValidator.validate(schema, JsonParser.parseString(value)).isEmpty());
    }

    @Test
    void nestedRequiredEnumItemsAndAdditionalProperties() {
        var schema =
                json(
                        """
{"type":"object","properties":{
  "kind":{"type":"string","enum":["a","b"]},
  "values":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}}
},"required":["kind","values"],"additionalProperties":false}
""");
        assertTrue(SchemaValidator.validate(schema, json("{\"kind\":\"a\",\"values\":[{\"n\":1}]}")).isEmpty());
        var errors = SchemaValidator.validate(schema, json("{\"kind\":\"z\",\"values\":[{\"n\":1.2},{}],\"extra\":1}"));
        assertEquals(4, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.values[0].n")));
        assertFalse(SchemaValidator.validate(schema, json("{}")).isEmpty());
    }

    @Test
    void unionsAndSchemaAdditionalProperties() {
        var schema = json("{\"type\":[\"string\",\"null\"]}");
        assertTrue(SchemaValidator.validate(schema, JsonNull.INSTANCE).isEmpty());
        assertTrue(SchemaValidator.validate(schema, new JsonPrimitive("hello")).isEmpty());
        assertFalse(SchemaValidator.validate(schema, new JsonPrimitive(1)).isEmpty());
        assertFalse(
                SchemaValidator.validate(json("{\"additionalProperties\":{\"type\":\"boolean\"}}"), json("{\"x\":1}"))
                        .isEmpty());
    }

    @Test
    void toolArgumentsAreCopiedCoercedAndOptionalNullsRemoved() {
        var tool =
                new Tool(
                        "test",
                        "test",
                        json(
                                """
{"type":"object","properties":{"n":{"type":"integer"},"b":{"type":"boolean"},
"optional":{"type":"string"},"nullable":{"type":["string","null"]}},"required":["n","b"]}
"""));
        var call =
                new ContentBlock.ToolCall(
                        "id", "test", json("{\"n\":\"2\",\"b\":\"true\",\"optional\":null,\"nullable\":null}"));
        var args = SchemaValidator.validateToolArguments(tool, call);
        assertEquals(2, args.get("n").getAsInt());
        assertTrue(args.get("b").getAsBoolean());
        assertFalse(args.has("optional"));
        assertTrue(args.get("nullable").isJsonNull());
        assertTrue(call.arguments().get("n").getAsJsonPrimitive().isString());
        args.addProperty("n", 9);
        assertEquals("2", call.arguments().get("n").getAsString());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaValidator.validateToolArguments(
                                tool, new ContentBlock.ToolCall("x", "test", json("{\"n\":\"oops\",\"b\":true}"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaValidator.validateToolArguments(
                                tool, new ContentBlock.ToolCall("x", "test", new JsonObject(), "Malformed JSON")));
    }

    @Test
    void recordsDefensivelyCopySchemasArgumentsDetailsAndLists() {
        var schema = json("{\"type\":\"object\"}");
        var tool = new Tool("test", "test", schema);
        schema.addProperty("type", "string");
        tool.parameters().addProperty("type", "array");
        assertEquals("object", tool.parameters().get("type").getAsString());
        var details = json("{\"x\":1}");
        var message = new ToolResultMessage("id", "test", List.of(new ContentBlock.Text("ok")), details, false, 0);
        details.addProperty("x", 2);
        message.details().getAsJsonObject().addProperty("x", 3);
        assertEquals(1, message.details().getAsJsonObject().get("x").getAsInt());
        assertThrows(UnsupportedOperationException.class, () -> message.content().clear());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
