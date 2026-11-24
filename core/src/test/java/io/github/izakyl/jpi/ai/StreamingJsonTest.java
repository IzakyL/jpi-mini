package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamingJsonTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"city\":\"Paris\",\"days\":2}",
                "{\"a\":[1,true,false,null,{\"x\":\"escaped \\\" quote \\\\ slash \\n newline \\u4e16\\u754c\"}]}",
                "{\"n\":-12.3e-4,\"b\":false,\"c\":null}",
                "{\"nested\":{\"a\":{\"b\":[1,2,3]}},\"after\":\"ok\"}"
            })
    void everyCharacterAndSplitBoundary(String json) {
        for (int split = 0; split <= json.length(); split++) {
            var parser = new StreamingJson();
            var first = parser.append(json.substring(0, split));
            var before = first.deepCopy();
            var complete = parser.append(json.substring(split));
            assertEquals(JsonParser.parseString(json), complete, "split " + split);
            assertEquals(before, first, "old snapshot mutated");
            assertNull(parser.finish().error());
        }
        var parser = new StreamingJson();
        JsonObject last = null;
        for (char c : json.toCharArray()) {
            last = parser.append(String.valueOf(c));
        }
        assertEquals(JsonParser.parseString(json), last);
    }

    @Test
    void partialStringsKeysNumbersAndLiterals() {
        var parser = new StreamingJson();
        assertEquals("{}", parser.append("{\"incomplete").toString());
        assertEquals("{\"incomplete\":\"\"}", parser.append("\":\"").toString());
        assertEquals("a", parser.append("a\\").get("incomplete").getAsString());
        assertEquals("a\n", parser.append("n").get("incomplete").getAsString());
        assertEquals("a\n", parser.append("\\u4e").get("incomplete").getAsString());
        assertEquals("a\n世", parser.append("16").get("incomplete").getAsString());
        assertEquals(1, parser.append("\",\"n\":1e-").get("n").getAsInt());
        assertEquals(0.01, parser.append("2,\"b\":t").get("n").getAsDouble());
        assertTrue(parser.append("").get("b").getAsBoolean());
        assertTrue(parser.append("rue}").get("b").getAsBoolean());
        assertNull(parser.finish().error());
    }

    @Test
    void nestedPendingScalarsStayInTheirOwnContainer() {
        var parser = new StreamingJson();
        assertEquals(JsonParser.parseString("{\"a\":[{\"b\":[1,true]}]}"), parser.append("{\"a\":[{\"b\":[1,t"));
        assertEquals(JsonParser.parseString("{\"a\":[{\"b\":[1,true]}]}"), parser.append(""));
        assertTrue(
                parser.append("rue]}]}")
                        .getAsJsonArray("a")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("b")
                        .get(1)
                        .getAsBoolean());
        assertNull(parser.finish().error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":", "{\"a\":\"x", "[]", "null", "", "{x:1}", "{\"a\":1,}", "{} {}", "{\"a\":NaN}"})
    void finalArgumentsAreStrict(String text) {
        var parser = new StreamingJson();
        parser.append(text);
        assertNotNull(parser.finish().error(), text);
        assertEquals(new JsonObject(), parser.finish().arguments());
    }

    @Test
    void tolerantPreviewsKeepInvalidEscapesAndMissingSeparators() {
        var parser = new StreamingJson();
        assertEquals("a\\d", parser.append("{\"x\":\"a\\d").get("x").getAsString());
        assertNotNull(parser.finish().error());
        assertDoesNotThrow(() -> new StreamingJson().append("{garbage][??"));
    }
}
